package com.survivalcinema.director.play;

import java.util.Locale;

/** Film times the way players read them: "0:07", "4:32", "1:02:03". */
public final class PlayTime {
	private PlayTime() {
	}

	public static String format(double seconds) {
		long whole = Double.isFinite(seconds) ? (long) Math.floor(Math.max(0, seconds)) : 0;
		long hours = whole / 3600;
		long minutes = whole / 60 % 60;
		long secs = whole % 60;
		return hours > 0
			? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
			: String.format(Locale.ROOT, "%d:%02d", minutes, secs);
	}
}
