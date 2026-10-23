package com.survivalcinema.client.play;

import com.mojang.blaze3d.platform.NativeImage;
import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.director.play.Letterbox;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads the film with the FFmpeg inside Flashback: pictures (RGBA, already scaled down to the window when the film is
 * bigger) into a few reusable images, sound (48 kHz stereo) into a two-second ring. The player takes them on the
 * render thread.
 * <p>
 * Pictures and sound are read by two readers of the file, each on its own thread. With one reader the sound could only
 * be decoded as far ahead as there were free images for the pictures in between: at 60 fps the few images cover a
 * tenth of a second, the sound card wants a third of one queued, and most pictures had to be thrown away to keep the
 * sound going (a 1080p 60 fps film stuttered at 18 fps). Now the sound reads ahead on its own and the pictures wait
 * for free images, the decoder staying a few pictures ahead.
 */
final class FilmDecoder implements AutoCloseable {
	static final int RATE = 48_000;
	static final int CHANNELS = 2;
	private static final int IMAGES = 8;
	/** Threads decoding the picture: a few, never all of the processor (the game runs beside it). */
	private static final int DECODE_THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

	/** One decoded picture; its image goes back to the pool with {@link #recycle}. */
	record Picture(NativeImage image, double time, int generation) {
	}

	private final Path file;
	private final int screenWidth;
	private final int screenHeight;
	private final Thread thread;
	private volatile @Nullable Thread soundThread;
	private final AtomicInteger generation = new AtomicInteger();
	private final BlockingQueue<Picture> pictures = new ArrayBlockingQueue<>(IMAGES);
	private final BlockingQueue<NativeImage> free = new ArrayBlockingQueue<>(IMAGES);
	private final List<NativeImage> allImages = new ArrayList<>();
	private final SampleRing sound = new SampleRing(2, RATE, CHANNELS);

	private volatile boolean closed;
	private volatile boolean opened;
	private volatile @Nullable String problem;
	private volatile int width;
	private volatile int height;
	private volatile double duration;
	private volatile boolean hasSound;
	/** Where the last seek goes; set before {@link #generation} moves on, so a reader seeing the new one finds it. */
	private volatile double seekTarget;
	private volatile int picturesEndedGeneration = -1;
	private volatile int soundEndedGeneration = -1;
	private volatile double speed = 1;
	/** The sound reader's scratch samples. */
	private short[] soundSamples = new short[0];

	/** @param screenWidth the window size in pixels: there is no point decoding more */
	FilmDecoder(Path file, int screenWidth, int screenHeight) {
		this.file = file;
		this.screenWidth = screenWidth;
		this.screenHeight = screenHeight;
		this.thread = new Thread(this::run, "SurvivalCinema-Player");
		this.thread.setDaemon(true);
	}

	void start() {
		thread.start();
	}

	boolean isOpen() {
		return opened;
	}

	@Nullable String problem() {
		return problem;
	}

	int width() {
		return width;
	}

	int height() {
		return height;
	}

	double duration() {
		return duration;
	}

	boolean hasSound() {
		return hasSound;
	}

	SampleRing sound() {
		return sound;
	}

	int generation() {
		return generation.get();
	}

	/** Everything up to the end of the file was decoded (since the last seek). */
	boolean ended() {
		int now = generation.get();
		return picturesEndedGeneration == now && (!hasSound || soundEndedGeneration == now);
	}

	/** The next decoded picture, or null. Pictures from before the last seek are recycled on the way. */
	@Nullable Picture poll() {
		while (true) {
			Picture picture = pictures.poll();
			if (picture == null || picture.generation() == generation.get()) {
				return picture;
			}
			recycle(picture.image());
		}
	}

	void recycle(NativeImage image) {
		free.offer(image);
	}

	/** Film seconds per second from the next {@link #seek} on (the sound is decoded for it). */
	void setSpeed(double speed) {
		this.speed = speed;
	}

	/** Jumps to a place in the film; everything decoded so far is dropped. On the render thread. */
	void seek(double seconds) {
		seekTarget = seconds;
		int next = generation.incrementAndGet();
		sound.clear(next, speed);
		Picture stale;
		while ((stale = pictures.poll()) != null) {
			recycle(stale.image());
		}
	}

	private void run() {
		FFmpegFrameGrabber grabber = null;
		try {
			// a first look at the file for its size, so the real reader can scale pictures from the start
			int videoWidth;
			int videoHeight;
			try (FFmpegFrameGrabber probe = new FFmpegFrameGrabber(file.toFile())) {
				probe.start();
				videoWidth = probe.getImageWidth();
				videoHeight = probe.getImageHeight();
				duration = Math.max(0, probe.getLengthInTime() / 1e6);
				hasSound = probe.hasAudio() && probe.getAudioChannels() > 0;
				probe.stop();
			}
			int[] size = Letterbox.decodeSize(videoWidth, videoHeight, screenWidth, screenHeight);
			grabber = new FFmpegFrameGrabber(file.toFile());
			grabber.setPixelFormat(avutil.AV_PIX_FMT_RGBA);
			grabber.setImageWidth(size[0]);
			grabber.setImageHeight(size[1]);
			// one thread kept up with a 1080p 60 fps film only while the game left the processor alone
			grabber.setVideoOption("threads", String.valueOf(DECODE_THREADS));
			grabber.start();
			width = size[0];
			height = size[1];
			for (int i = 0; i < IMAGES; i++) {
				NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
				allImages.add(image);
				free.add(image);
			}
			SurvivalCinemaClient.LOGGER.info("Playing {} ({}x{} shown at {}x{}, {} s, sound: {})", file, videoWidth, videoHeight,
				width, height, Math.round(duration), hasSound);
			if (hasSound) {
				Thread reader = new Thread(this::runSound, "SurvivalCinema-Player-Sound");
				reader.setDaemon(true);
				soundThread = reader;
				reader.start();
			}
			opened = true;
			decodePictures(grabber);
		} catch (Throwable t) {
			fail(t);
		} finally {
			closeQuietly(grabber);
		}
	}

	private void runSound() {
		FFmpegFrameGrabber grabber = null;
		try {
			grabber = new FFmpegFrameGrabber(file.toFile());
			grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
			grabber.setSampleRate(RATE);
			grabber.setAudioChannels(CHANNELS);
			grabber.start();
			decodeSound(grabber);
		} catch (Throwable t) {
			fail(t);
		} finally {
			closeQuietly(grabber);
		}
	}

	private void fail(Throwable t) {
		if (!closed) {
			SurvivalCinemaClient.LOGGER.error("Could not play the film {}", file, t);
			problem = String.valueOf(t.getMessage());
		}
	}

	private void closeQuietly(@Nullable FFmpegFrameGrabber grabber) {
		if (grabber != null) {
			try {
				grabber.close();
			} catch (Exception e) {
				SurvivalCinemaClient.LOGGER.warn("Could not close the film {}", file, e);
			}
		}
	}

	private void decodePictures(FFmpegFrameGrabber grabber) throws Exception {
		int current = generation.get();
		while (!closed) {
			if (generation.get() != current) {
				current = generation.get();
				grabber.setTimestamp(Math.round(seekTarget * 1e6));
			}
			if (picturesEndedGeneration == current) {
				Thread.sleep(10); // at the end: wait for a seek (or the end of the film)
				continue;
			}
			Frame frame = grabber.grabFrame(false, true, true, false);
			if (frame == null) {
				picturesEndedGeneration = current;
				continue;
			}
			if (frame.image != null && frame.image.length > 0 && frame.image[0] != null) {
				putPicture(frame, frame.timestamp / 1e6, current);
			}
		}
	}

	/**
	 * Sound at another speed goes through FFmpeg's {@code atempo}: faster or slower, at the same pitch (voices stay
	 * voices). What comes out covers the film from where the filter started, {@code speed} film seconds per second.
	 */
	private void decodeSound(FFmpegFrameGrabber grabber) throws Exception {
		int current = generation.get();
		FFmpegFrameFilter tempo = null;
		double tempoStart = Double.NaN;
		long tempoFrames = 0;
		try {
			while (!closed) {
				if (generation.get() != current) {
					current = generation.get();
					grabber.setTimestamp(Math.round(seekTarget * 1e6));
					closeQuietly(tempo);
					tempo = null;
				}
				if (soundEndedGeneration == current) {
					Thread.sleep(10);
					continue;
				}
				Frame frame = grabber.grabFrame(true, false, true, false);
				if (frame == null) {
					soundEndedGeneration = current;
					continue;
				}
				if (frame.samples == null || frame.samples.length == 0) {
					continue;
				}
				int forGeneration = current;
				double speed = this.speed;
				if (speed == 1) {
					write(frame, frame.timestamp / 1e6, forGeneration);
					continue;
				}
				if (tempo == null) {
					tempo = new FFmpegFrameFilter(String.format(java.util.Locale.ROOT, "atempo=%.4f", speed), CHANNELS);
					tempo.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
					tempo.setSampleRate(RATE);
					tempo.start();
					tempoStart = frame.timestamp / 1e6;
					tempoFrames = 0;
				}
				tempo.push(frame);
				Frame out;
				while ((out = tempo.pull()) != null) {
					if (out.samples == null || out.samples.length == 0) {
						continue;
					}
					tempoFrames += write(out, tempoStart + tempoFrames / (double) RATE * speed, forGeneration) / CHANNELS;
					if (closed || generation.get() != forGeneration) {
						break;
					}
				}
			}
		} finally {
			closeQuietly(tempo);
		}
	}

	/** Puts a sound frame's samples in the ring, starting at {@code time} in the film. @return how many samples */
	private int write(Frame frame, double time, int forGeneration) throws InterruptedException {
		int count = interleave(frame.samples, soundSamples);
		if (count > soundSamples.length) {
			soundSamples = new short[count];
			interleave(frame.samples, soundSamples);
		}
		sound.write(soundSamples, count, time, forGeneration, () -> closed || generation.get() != forGeneration);
		return count;
	}

	private void closeQuietly(@Nullable FFmpegFrameFilter filter) {
		if (filter != null) {
			try {
				filter.close();
			} catch (Exception e) {
				SurvivalCinemaClient.LOGGER.warn("Could not close the film's sound filter", e);
			}
		}
	}

	private void putPicture(Frame frame, double time, int forGeneration) throws InterruptedException {
		NativeImage image = null;
		while (image == null) {
			if (closed || generation.get() != forGeneration) {
				return;
			}
			image = free.poll(5, TimeUnit.MILLISECONDS);
		}
		if (frame.imageWidth != width || frame.imageHeight != height) {
			recycle(image);
			return;
		}
		ByteBuffer pixels = (ByteBuffer) frame.image[0];
		long from = MemoryUtil.memAddress0(pixels);
		long to = image.getPointer();
		int row = width * 4;
		int stride = frame.imageStride > 0 ? frame.imageStride : row;
		if (stride == row) {
			MemoryUtil.memCopy(from, to, (long) row * height);
		} else {
			for (int y = 0; y < height; y++) {
				MemoryUtil.memCopy(from + (long) y * stride, to + (long) y * row, row);
			}
		}
		if (generation.get() != forGeneration || !pictures.offer(new Picture(image, time, forGeneration))) {
			recycle(image);
		}
	}

	/**
	 * Interleaved 16-bit samples of a sound frame into {@code out}.
	 *
	 * @return how many samples there are (more than {@code out} holds means nothing was copied)
	 */
	private static int interleave(Buffer[] planes, short[] out) {
		if (planes.length == 1) {
			Buffer buffer = planes[0];
			int count = buffer.limit();
			if (count > out.length) {
				return count;
			}
			if (buffer instanceof ShortBuffer shorts) {
				for (int i = 0; i < count; i++) {
					out[i] = shorts.get(i);
				}
			} else if (buffer instanceof FloatBuffer floats) {
				for (int i = 0; i < count; i++) {
					out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(floats.get(i) * 32767)));
				}
			} else {
				return 0;
			}
			return count;
		}
		// planar: one buffer per channel
		int frames = planes[0].limit();
		int count = frames * CHANNELS;
		if (count > out.length) {
			return count;
		}
		for (int c = 0; c < CHANNELS; c++) {
			Buffer plane = planes[Math.min(c, planes.length - 1)];
			for (int i = 0; i < frames; i++) {
				out[i * CHANNELS + c] = plane instanceof ShortBuffer s ? s.get(i)
					: plane instanceof FloatBuffer f ? (short) Math.max(-32768, Math.min(32767, Math.round(f.get(i) * 32767))) : 0;
			}
		}
		return count;
	}

	/** Stops decoding and frees the images. On the render thread, after the last picture was shown. */
	@Override
	public void close() {
		closed = true;
		sound.wake();
		Thread reader = soundThread;
		try {
			thread.join(2000);
			if (reader != null) {
				reader.join(2000);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (thread.isAlive()) {
			// stuck inside FFmpeg: freeing the images under it could crash the game, leave them
			SurvivalCinemaClient.LOGGER.warn("The film reader did not stop in time");
			return;
		}
		pictures.clear();
		free.clear();
		for (NativeImage image : allImages) {
			image.close();
		}
		allImages.clear();
	}
}
