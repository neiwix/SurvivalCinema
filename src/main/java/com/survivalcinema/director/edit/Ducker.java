package com.survivalcinema.director.edit;

/**
 * Turns the music down while the game itself is loud (a voice the screenplay did not know about, an explosion, a
 * fight), and back up when it calms down. Fed once per video frame with how loud the clip's own sound is.
 * Goes down fast and comes back slowly, so the music does not pump on every footstep.
 */
public final class Ducker {
	/** Clip loudness (RMS, 0..1) where ducking starts, and where it is full. */
	static final double QUIET = 0.04;
	static final double LOUD = 0.2;
	/** Music level while the game is loud. */
	static final double DUCKED = 0.35;
	static final double ATTACK_SECONDS = 0.15;
	static final double RELEASE_SECONDS = 1.2;

	private final double attack;
	private final double release;
	private double gain = 1;

	public Ducker(int fps) {
		this.attack = 1 - Math.exp(-1.0 / (ATTACK_SECONDS * fps));
		this.release = 1 - Math.exp(-1.0 / (RELEASE_SECONDS * fps));
	}

	/**
	 * @param clipLevel RMS of the clip's sound during this frame, 0..1
	 * @return the music's gain for this frame, {@link #DUCKED}..1
	 */
	public double next(double clipLevel) {
		double t = Math.max(0, Math.min(1, (clipLevel - QUIET) / (LOUD - QUIET)));
		double target = 1 - (1 - DUCKED) * t * t * (3 - 2 * t);
		gain += (target - gain) * (target < gain ? attack : release);
		return gain;
	}

	/** RMS of interleaved 16-bit samples, 0..1. */
	public static double rms(short[] samples, int count) {
		if (count <= 0) {
			return 0;
		}
		double sum = 0;
		for (int i = 0; i < count; i++) {
			double v = samples[i] / 32768.0;
			sum += v * v;
		}
		return Math.sqrt(sum / count);
	}
}
