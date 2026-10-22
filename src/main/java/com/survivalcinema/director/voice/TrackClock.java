package com.survivalcinema.director.voice;

/**
 * Keeps a voice track as long as the time it covers. A program that plays nothing (or quits, or a microphone that
 * stalls) may deliver no sound at all for a while; the missing time is filled with silence, otherwise everything
 * after it would be heard too early. Counted from the last {@link #restart} (the recording starting or coming back
 * from a pause), so the paused time itself is never filled.
 */
public final class TrackClock {
	/** Being this far behind is normal (sound comes in packets) and not filled. */
	static final double TOLERANCE_SECONDS = 0.12;

	private final int rate;
	private long startNanos;
	private long startSamples;
	private boolean running;

	public TrackClock(int rate) {
		this.rate = rate;
	}

	/** The track goes on from {@code writtenSamples} at {@code nowNanos}. */
	public void restart(long nowNanos, long writtenSamples) {
		startNanos = nowNanos;
		startSamples = writtenSamples;
		running = true;
	}

	public void stop() {
		running = false;
	}

	public boolean isRunning() {
		return running;
	}

	/**
	 * How many samples of silence to write before {@code incoming} new ones, so the track does not fall behind.
	 */
	public long silenceBefore(long nowNanos, long writtenSamples, long incoming) {
		if (!running) {
			return 0;
		}
		double expected = startSamples + (nowNanos - startNanos) / 1e9 * rate;
		double behind = expected - (writtenSamples + incoming);
		return behind > TOLERANCE_SECONDS * rate ? Math.round(behind) : 0;
	}
}
