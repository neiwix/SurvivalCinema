package com.survivalcinema.director.play;

/**
 * Where the film goes on a screen of another shape: as big as fits, centered, black bars around (like a cinema).
 */
public record Letterbox(int x, int y, int width, int height) {
	public static Letterbox fit(int videoWidth, int videoHeight, int screenWidth, int screenHeight) {
		if (videoWidth <= 0 || videoHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
			return new Letterbox(0, 0, Math.max(0, screenWidth), Math.max(0, screenHeight));
		}
		double scale = Math.min(screenWidth / (double) videoWidth, screenHeight / (double) videoHeight);
		int width = Math.min(screenWidth, (int) Math.round(videoWidth * scale));
		int height = Math.min(screenHeight, (int) Math.round(videoHeight * scale));
		return new Letterbox((screenWidth - width) / 2, (screenHeight - height) / 2, width, height);
	}

	/**
	 * The size to decode the film at: never bigger than the film itself or than the screen it is shown on (a 1080p
	 * film in a small window costs no more than the window), with the film's shape and even sides (video scalers
	 * like them).
	 *
	 * @return {width, height}
	 */
	public static int[] decodeSize(int videoWidth, int videoHeight, int screenWidth, int screenHeight) {
		if (videoWidth <= 0 || videoHeight <= 0) {
			return new int[]{2, 2};
		}
		Letterbox box = fit(videoWidth, videoHeight, Math.max(2, screenWidth), Math.max(2, screenHeight));
		boolean smaller = box.width() < videoWidth;
		int width = smaller ? box.width() : videoWidth;
		int height = smaller ? box.height() : videoHeight;
		return new int[]{Math.max(2, width & ~1), Math.max(2, height & ~1)};
	}
}
