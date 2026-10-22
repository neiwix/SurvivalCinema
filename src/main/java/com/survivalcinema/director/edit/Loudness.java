package com.survivalcinema.director.edit;

/**
 * Evens out how loud the film is. The clips come out as loud as the player's volume settings were while shooting
 * (often far below full), and the game's music is mastered quietly, so both are measured and brought to fixed
 * levels: game sound first, music a step below it.
 * <p>
 * Measured on short windows (a tenth of a second), ignoring near-silent ones, so a clip that is mostly quiet with
 * one loud moment is judged by what can actually be heard.
 */
public final class Loudness {
	/** Windows quieter than this (RMS) are silence and do not count. */
	public static final double GATE = 0.003;
	/** Where the game's sound is brought to (RMS of the audible windows). */
	public static final double GAME_TARGET = 0.10;
	/** Where a music track is brought to, before its cue level. */
	public static final double MUSIC_TARGET = 0.09;
	/** Where our own voice tracks (call apps, the microphone) are brought to: a little above the game. */
	public static final double VOICE_TARGET = 0.11;
	/** Quieter than this a voice track is room noise or silence: not counted for its level, and gated down in the mix. */
	public static final double VOICE_GATE = 0.01;
	/** Gains never go beyond these: no blasting a nearly silent recording, no flattening a loud one. */
	public static final double MIN_GAIN = 0.5;
	public static final double MAX_GAIN = 6;
	/** A window quieter than this (after the gain) has nothing to listen to. */
	public static final double QUIET_WINDOW = 0.02;
	/** A clip that is quiet for this share of its time has room for music. */
	public static final double QUIET_SHARE = 0.6;

	private Loudness() {
	}

	/** RMS over the audible windows, or 0 when everything is silent. */
	public static double gatedRms(double[] windows) {
		double sum = 0;
		int count = 0;
		for (double window : windows) {
			if (window > GATE) {
				sum += window * window;
				count++;
			}
		}
		return count == 0 ? 0 : Math.sqrt(sum / count);
	}

	/** The gain that brings a level to its target (1 for silence). */
	public static double gain(double gatedRms, double target) {
		if (gatedRms <= 0) {
			return 1;
		}
		return Math.max(MIN_GAIN, Math.min(MAX_GAIN, target / gatedRms));
	}

	/** Whether a clip (its windows, before the gain) is mostly quiet once brought to level. */
	public static boolean isQuiet(double[] windows, double gain) {
		if (windows.length == 0) {
			return true;
		}
		int quiet = 0;
		for (double window : windows) {
			if (window * gain < QUIET_WINDOW) {
				quiet++;
			}
		}
		return quiet >= QUIET_SHARE * windows.length;
	}

	/** Keeps the mix from clipping harshly: straight up to 80% of full scale, then a gentle curve to full. */
	public static short limit(double sample) {
		double knee = 0.8 * 32767;
		double magnitude = Math.abs(sample);
		if (magnitude > knee) {
			double over = (magnitude - knee) / (32767 - knee);
			magnitude = knee + (32767 - knee) * over / (1 + over);
		}
		return (short) Math.round(Math.copySign(Math.min(32767, magnitude), sample));
	}
}
