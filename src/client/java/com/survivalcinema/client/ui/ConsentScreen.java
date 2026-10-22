package com.survivalcinema.client.ui;

import com.survivalcinema.client.capture.RecordingController;
import com.survivalcinema.client.capture.WorldMemory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Asked when joining a world: should this session be recorded for the film? Plain vanilla widgets.
 */
public final class ConsentScreen extends Screen {
	private static final int BUTTON_WIDTH = 150;
	private static final int GAP = 8;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int YELLOW = 0xFFFFFF55;

	private final RecordingController controller;
	private final WorldMemory world;
	private Checkbox voiceChat;
	private List<FormattedCharSequence> body = List.of();
	private int bodyTop;
	private boolean answered;

	public ConsentScreen(RecordingController controller, WorldMemory world) {
		super(Component.translatable("survivalcinema.consent.title"));
		this.controller = controller;
		this.world = world;
	}

	@Override
	protected void init() {
		int center = width / 2;
		body = font.split(Component.translatable("survivalcinema.consent.body", world.displayName), Math.min(320, width - 40));
		bodyTop = Math.max(12, height / 2 - 104);
		int y = bodyTop + 16 + body.size() * (font.lineHeight + 2) + 10;

		voiceChat = Checkbox.builder(Component.translatable("survivalcinema.consent.voice"), font)
			.selected(voiceChat != null ? voiceChat.selected() : world.recordVoiceChat) // kept when back from the voice settings
			.build();
		voiceChat.setPosition(center - voiceChat.getWidth() / 2, y);
		addRenderableWidget(voiceChat);
		y += 20 + font.lineHeight + 14;
		addRenderableWidget(Button.builder(Component.translatable("survivalcinema.consent.voice_settings"), b -> minecraft.setScreen(new VoiceSettingsScreen(this)))
			.bounds(center - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, 20).build());
		y += 28;

		int left = center - BUTTON_WIDTH - GAP / 2;
		int right = center + GAP / 2;
		addRenderableWidget(Button.builder(Component.translatable("survivalcinema.consent.record_once"), b -> answer(true, false))
			.bounds(left, y, BUTTON_WIDTH, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("survivalcinema.consent.record_always"), b -> answer(true, true))
			.bounds(right, y, BUTTON_WIDTH, 20).build());
		y += 24;
		addRenderableWidget(Button.builder(Component.translatable("survivalcinema.consent.not_now"), b -> answer(false, false))
			.bounds(left, y, BUTTON_WIDTH, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("survivalcinema.consent.never"), b -> answer(false, true))
			.bounds(right, y, BUTTON_WIDTH, 20).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		int center = width / 2;
		graphics.drawCenteredString(font, title, center, bodyTop, YELLOW);
		int y = bodyTop + 16;
		for (FormattedCharSequence line : body) {
			graphics.drawCenteredString(font, line, center, y, WHITE);
			y += font.lineHeight + 2;
		}
		if (voiceChat != null && voiceChat.selected()) {
			graphics.drawCenteredString(font, Component.translatable("survivalcinema.consent.voice_note"),
				center, voiceChat.getY() + 24, GREY);
		}
	}

	private void answer(boolean record, boolean remember) {
		answered = true;
		controller.onConsent(record, remember, voiceChat != null && voiceChat.selected());
		onClose();
	}

	@Override
	public void onClose() {
		if (!answered) {
			answered = true;
			controller.onConsent(false, false, voiceChat != null && voiceChat.selected());
		}
		super.onClose();
	}
}
