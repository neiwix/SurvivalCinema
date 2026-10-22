package com.survivalcinema.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** The cinema picture in the normal game GUI. */
final class GuiCanvas implements CinemaCanvas {
	private final GuiGraphics graphics;
	private final Font font;
	private final int width;
	private final int height;

	GuiCanvas(GuiGraphics graphics, Font font, int width, int height) {
		this.graphics = graphics;
		this.font = font;
		this.width = width;
		this.height = height;
	}

	@Override
	public int width() {
		return width;
	}

	@Override
	public int height() {
		return height;
	}

	@Override
	public void fill(int x, int y, int width, int height, int argb) {
		graphics.fill(x, y, x + width, y + height, argb);
	}

	@Override
	public void text(String text, int x, int y, int argb, int scale) {
		if (scale == 1) {
			graphics.drawString(font, text, x, y, argb, true);
			return;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		graphics.drawString(font, text, 0, 0, argb, true);
		graphics.pose().popMatrix();
	}

	@Override
	public int textWidth(String text) {
		return font.width(text);
	}
}
