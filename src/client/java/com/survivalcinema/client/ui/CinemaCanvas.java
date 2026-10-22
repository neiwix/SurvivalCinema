package com.survivalcinema.client.ui;

/**
 * Where the cinema picture is painted, in GUI units: the normal game GUI, or the window while Flashback renders a
 * clip (where the normal GUI does not run). Both paint the same picture, so the switch between them is invisible.
 */
interface CinemaCanvas {
	int width();

	int height();

	/** An opaque rectangle. */
	void fill(int x, int y, int width, int height, int argb);

	/** Text in the Minecraft font with its shadow; {@code scale} 2 for titles. */
	void text(String text, int x, int y, int argb, int scale);

	int textWidth(String text);
}
