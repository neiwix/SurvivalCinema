package com.survivalcinema.client.play;

/**
 * Decoded sound waiting for the sound card: interleaved 16-bit stereo samples, with the film time of the first one.
 * The decoder thread writes, the render thread reads. Every seek starts a new generation; samples of an older one
 * (decoded before the seek was noticed) are thrown away.
 */
final class SampleRing {
	/** What one read returned. @param speed film seconds per second of this sound */
	record Read(int count, double time, double speed) {
	}

	private final short[] data;
	private final int rate;
	private final int channels;
	private int start;
	private int size;
	private int generation;
	private double startTime = Double.NaN;
	/** Film seconds per second of the sound in it: sped-up sound covers more film per sample. */
	private double speed = 1;

	SampleRing(int seconds, int rate, int channels) {
		this.data = new short[seconds * rate * channels];
		this.rate = rate;
		this.channels = channels;
	}

	/**
	 * Waits for room and adds the samples. Returns false without adding them when they belong to an older generation
	 * or {@code stop} says to give up.
	 */
	synchronized boolean write(short[] chunk, int count, double time, int forGeneration, java.util.function.BooleanSupplier stop)
		throws InterruptedException {
		count = Math.min(count, data.length);
		while (size + count > data.length) {
			if (forGeneration != generation || stop.getAsBoolean()) {
				return false;
			}
			wait(10);
		}
		if (forGeneration != generation) {
			return false;
		}
		if (size == 0) {
			startTime = time; // the first sound after a seek, or after running dry
		}
		for (int i = 0; i < count; i++) {
			data[(start + size + i) % data.length] = chunk[i];
		}
		size += count;
		return true;
	}

	/** Takes up to {@code max} samples (whole sample frames). */
	synchronized Read read(short[] dst, int max) {
		int count = Math.min(size, max - max % channels);
		count -= count % channels;
		double time = startTime;
		for (int i = 0; i < count; i++) {
			dst[i] = data[(start + i) % data.length];
		}
		start = (start + count) % data.length;
		size -= count;
		startTime += count / (double) channels / rate * speed;
		notifyAll();
		return new Read(count, time, speed);
	}

	synchronized double seconds() {
		return size / (double) channels / rate;
	}

	/** Forgets everything: a seek to a new place (or a new speed) starts the given generation. */
	synchronized void clear(int newGeneration, double newSpeed) {
		start = 0;
		size = 0;
		generation = newGeneration;
		startTime = Double.NaN;
		speed = newSpeed;
		notifyAll();
	}

	/** Wakes a writer waiting for room (closing). */
	synchronized void wake() {
		notifyAll();
	}
}
