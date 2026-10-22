package com.survivalcinema.client.ui;

import com.survivalcinema.client.capture.RecordingController;
import com.survivalcinema.client.config.SurvivalCinemaConfig;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Small "● REC" in the top right corner while we record, "❚❚ AFK" while paused for inactivity.
 */
public final class RecordingIndicator implements HudElement {
	private final RecordingController controller;

	public RecordingIndicator(RecordingController controller) {
		this.controller = controller;
	}

	@Override
	public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (controller.session() == null || mc.options.hideGui || !SurvivalCinemaConfig.get().showRecordingIndicator) {
			return;
		}
		boolean afk = controller.isAfkPaused();
		Component text = afk
			? Component.translatable("survivalcinema.hud.afk")
			: Component.translatable("survivalcinema.hud.rec");
		boolean dotVisible = afk || (System.currentTimeMillis() / 700) % 2 == 0;
		String dot = afk ? "❚❚ " : "● ";
		int textWidth = mc.font.width(dot) + mc.font.width(text);
		int x = graphics.guiWidth() - textWidth - 6;
		int y = 6;
		if (dotVisible) {
			graphics.drawString(mc.font, dot, x, y, afk ? 0xFFFFFF55 : 0xFFFF5555, true);
		}
		graphics.drawString(mc.font, text, x + mc.font.width(dot), y, 0xFFFFFFFF, true);
	}
}
