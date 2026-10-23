package com.survivalcinema.director.play;

import java.util.List;

/**
 * Which decoded picture to put on screen. The game draws at its own pace, a frame every 15 to 19 ms or so, and a 60 fps
 * film comes every 16.7: going by the clock alone, a frame drawn a little late found two pictures due and skipped one,
 * the next found none and showed one twice, a stutter every half second. So the oldest picture due is shown, even a
 * little late (the eye cannot tell, nor the voices), and pictures are skipped only to catch up when the film has fallen
 * further behind than that.
 */
public final class FramePicker {
	/** A picture is shown this much before its exact moment, so rounding never holds it back a whole frame. */
	static final double EARLY_SECONDS = 0.004;
	/** How late a picture may still be shown, in film frames (the jitter of the game's frames fits in). */
	static final double LATE_FRAMES = 1.5;
	/** And never later than this, whatever the frame rate: voices out of step with lips show from about 40 ms. */
	static final double MAX_LATE_SECONDS = 0.035;

	private FramePicker() {
	}

	/**
	 * @param times the waiting pictures' film times, in decoding order
	 * @return index of the picture to show now (earlier ones are skipped), or -1 when none is due yet
	 */
	public static int pick(List<Double> times, double now) {
		int due = -1;
		for (int i = 0; i < times.size(); i++) {
			if (times.get(i) <= now + EARLY_SECONDS) {
				due = i;
			} else {
				break;
			}
		}
		if (due <= 0) {
			return due;
		}
		double frame = times.get(1) - times.get(0);
		double late = Math.min(MAX_LATE_SECONDS, frame > 0 ? frame * LATE_FRAMES : MAX_LATE_SECONDS);
		for (int i = 0; i < due; i++) {
			if (times.get(i) >= now - late) {
				return i; // the oldest one not too late: one new picture a frame
			}
		}
		return due; // fallen behind: catch up with the newest one due
	}
}
