package com.survivalcinema.director.play;

import java.util.List;

/**
 * Which decoded picture to put on screen: the newest one whose moment has come. Pictures before it are late and
 * skipped (the game rendered slower than the film's frame rate for a moment), pictures after it wait their turn.
 */
public final class FramePicker {
	/** A picture is shown this much before its exact moment, so rounding never holds it back a whole frame. */
	static final double EARLY_SECONDS = 0.004;

	private FramePicker() {
	}

	/**
	 * @param times the waiting pictures' film times, in decoding order
	 * @return index of the picture to show now (earlier ones are skipped), or -1 when none is due yet
	 */
	public static int pick(List<Double> times, double now) {
		int chosen = -1;
		for (int i = 0; i < times.size(); i++) {
			if (times.get(i) <= now + EARLY_SECONDS) {
				chosen = i;
			} else {
				break;
			}
		}
		return chosen;
	}
}
