package com.survivalcinema.client.edit;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Path;

/**
 * Sound decoded with the FFmpeg inside Flashback, always as the film's format: 48 kHz, stereo, 16-bit, interleaved.
 * Reads a music track, a game sound or the sound of a shot clip piece by piece.
 */
final class AudioStream implements AutoCloseable {
	static final int RATE = 48_000;
	static final int CHANNELS = 2;

	private final FFmpegFrameGrabber grabber;
	private short[] fifo = new short[RATE * CHANNELS];
	private int start;
	private int size;
	private boolean ended;

	private AudioStream(FFmpegFrameGrabber grabber) throws IOException {
		this.grabber = grabber;
		grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
		grabber.setSampleRate(RATE);
		grabber.setAudioChannels(CHANNELS);
		grabber.start();
		if (!grabber.hasAudio()) {
			ended = true; // a file without sound: reads as silence
		}
	}

	static AudioStream open(Path file) throws IOException {
		return new AudioStream(new FFmpegFrameGrabber(file.toFile()));
	}

	static AudioStream open(byte[] data) throws IOException {
		FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(new ByteArrayInputStream(data), 0);
		grabber.setCloseInputStream(true);
		return new AudioStream(grabber);
	}

	/** The whole sound at once (for short sound effects). */
	static short[] decodeAll(byte[] data) throws IOException {
		try (AudioStream stream = open(data)) {
			short[] all = new short[0];
			short[] chunk = new short[RATE * CHANNELS];
			int total = 0;
			while (true) {
				int n = stream.read(chunk, chunk.length);
				if (n <= 0) {
					break;
				}
				if (total + n > all.length) {
					all = java.util.Arrays.copyOf(all, Math.max(all.length * 2, total + n));
				}
				System.arraycopy(chunk, 0, all, total, n);
				total += n;
				if (n < chunk.length) {
					break;
				}
			}
			return java.util.Arrays.copyOf(all, total);
		}
	}

	boolean isEnded() {
		return ended && size == 0;
	}

	/**
	 * Fills {@code dst} with the next {@code count} interleaved samples.
	 *
	 * @return how many were real sound; the rest is silence
	 */
	int read(short[] dst, int count) throws IOException {
		while (size < count && !ended) {
			Frame frame = grabber.grabSamples();
			if (frame == null || frame.samples == null) {
				ended = true;
				break;
			}
			push(frame.samples);
		}
		int n = Math.min(count, size);
		for (int i = 0; i < n; i++) {
			dst[i] = fifo[(start + i) % fifo.length];
		}
		start = (start + n) % fifo.length;
		size -= n;
		java.util.Arrays.fill(dst, n, count, (short) 0);
		return n;
	}

	private void push(Buffer[] planes) {
		if (planes.length == 0) {
			return;
		}
		if (planes.length == 1) {
			Buffer buffer = planes[0];
			if (buffer instanceof ShortBuffer shorts) {
				ShortBuffer view = shorts.duplicate();
				view.rewind();
				short[] chunk = new short[view.remaining()];
				view.get(chunk);
				append(chunk, chunk.length);
			} else if (buffer instanceof FloatBuffer floats) {
				FloatBuffer view = floats.duplicate();
				view.rewind();
				short[] chunk = new short[view.remaining()];
				for (int i = 0; i < chunk.length; i++) {
					chunk[i] = toShort(view.get());
				}
				append(chunk, chunk.length);
			}
			return;
		}
		// planar: one buffer per channel, interleave them
		int frames = planes[0].limit();
		short[] chunk = new short[frames * CHANNELS];
		for (int c = 0; c < CHANNELS; c++) {
			Buffer plane = planes[Math.min(c, planes.length - 1)];
			for (int i = 0; i < frames; i++) {
				short value = plane instanceof ShortBuffer s ? s.get(i) : plane instanceof FloatBuffer f ? toShort(f.get(i)) : 0;
				chunk[i * CHANNELS + c] = value;
			}
		}
		append(chunk, chunk.length);
	}

	private void append(short[] chunk, int count) {
		if (size + count > fifo.length) {
			short[] bigger = new short[Math.max(fifo.length * 2, size + count)];
			for (int i = 0; i < size; i++) {
				bigger[i] = fifo[(start + i) % fifo.length];
			}
			fifo = bigger;
			start = 0;
		}
		for (int i = 0; i < count; i++) {
			fifo[(start + size + i) % fifo.length] = chunk[i];
		}
		size += count;
	}

	private static short toShort(float sample) {
		return (short) Math.max(-32768, Math.min(32767, Math.round(sample * 32767)));
	}

	@Override
	public void close() throws IOException {
		grabber.close();
	}
}
