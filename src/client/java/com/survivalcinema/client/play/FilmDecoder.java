package com.survivalcinema.client.play;

import com.mojang.blaze3d.platform.NativeImage;
import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.director.play.Letterbox;
import org.bytedeco.ffmpeg.global.avutil;
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
 * Reads the film with the FFmpeg inside Flashback on its own thread: pictures (RGBA, already scaled down to the window
 * when the film is bigger) into a few reusable images, sound (48 kHz stereo) into a two-second ring. The player takes
 * them on the render thread.
 * <p>
 * The images are a small fixed pool, which keeps the decoder only a few pictures ahead. Should the sound ever run dry
 * while every image waits for its moment (the file has video far ahead of its sound), pictures are skipped instead of
 * waiting, so the two can never block each other.
 */
final class FilmDecoder implements AutoCloseable {
	static final int RATE = 48_000;
	static final int CHANNELS = 2;
	private static final int IMAGES = 8;
	/** Below this much decoded sound while playing, a picture without a free image is skipped instead of waited for. */
	private static final double SOUND_LOW_SECONDS = 0.25;

	/** One decoded picture; its image goes back to the pool with {@link #recycle}. */
	record Picture(NativeImage image, double time, int generation) {
	}

	private final Path file;
	private final int screenWidth;
	private final int screenHeight;
	private final Thread thread;
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
	private volatile boolean playing;
	private volatile double seekTo = Double.NaN;
	private volatile int endedGeneration = -1;

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
		return endedGeneration == generation.get();
	}

	/** The player plays (skipping a picture is allowed) or waits (never skip). */
	void setPlaying(boolean playing) {
		this.playing = playing;
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

	/** Jumps to a place in the film; everything decoded so far is dropped. On the render thread. */
	void seek(double seconds) {
		int next = generation.incrementAndGet();
		sound.clear(next);
		seekTo = seconds;
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
			grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
			grabber.setSampleRate(RATE);
			grabber.setAudioChannels(CHANNELS);
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
			opened = true;
			decode(grabber);
		} catch (Throwable t) {
			if (!closed) {
				SurvivalCinemaClient.LOGGER.error("Could not play the film {}", file, t);
				problem = String.valueOf(t.getMessage());
			}
		} finally {
			if (grabber != null) {
				try {
					grabber.close();
				} catch (Exception e) {
					SurvivalCinemaClient.LOGGER.warn("Could not close the film {}", file, e);
				}
			}
		}
	}

	private void decode(FFmpegFrameGrabber grabber) throws Exception {
		short[] samples = new short[0];
		int current = generation.get();
		while (!closed) {
			double target = seekTo;
			if (!Double.isNaN(target)) {
				seekTo = Double.NaN;
				current = generation.get();
				grabber.setTimestamp(Math.round(target * 1e6));
			}
			if (endedGeneration == current) {
				Thread.sleep(10); // at the end: wait for a seek (or the end of the film)
				continue;
			}
			Frame frame = grabber.grabFrame(hasSound, true, true, false);
			if (frame == null) {
				endedGeneration = current;
				continue;
			}
			double time = frame.timestamp / 1e6;
			if (frame.image != null && frame.image.length > 0 && frame.image[0] != null) {
				putPicture(frame, time, current);
			} else if (frame.samples != null && frame.samples.length > 0) {
				int count = interleave(frame.samples, samples);
				if (count > samples.length) {
					samples = new short[count];
					interleave(frame.samples, samples);
				}
				int forGeneration = current;
				sound.write(samples, count, time, forGeneration, () -> closed || generation.get() != forGeneration);
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
			if (image == null && playing && hasSound && sound.seconds() < SOUND_LOW_SECONDS) {
				return; // every image waits for the sound, which waits for us: skip this picture
			}
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
		try {
			thread.join(2000);
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
