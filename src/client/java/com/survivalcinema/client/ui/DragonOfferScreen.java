package com.survivalcinema.client.ui;

import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.client.render.FilmMaker;
import com.survivalcinema.director.story.FilmLength;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * "The dragon is defeated! Make the film?", shown once per world after the end poem and the credits. The length is
 * chosen right here (five minutes unless changed); "Make the film" starts at once with the last quality and music,
 * "Settings..." opens the full film setup.
 */
public final class DragonOfferScreen extends Screen {
	private static final int BUTTON_WIDTH = 150;
	private static final int GAP = 8;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int YELLOW = 0xFFFFFF55;
	private static final int RED = 0xFFFF5555;

	private final LengthPicker length = new LengthPicker(SurvivalCinemaConfig.get().filmLength(), () -> {
	});
	private List<FormattedCharSequence> body = List.of();
	private int top;
	private Button make;
	private Button settings;
	private boolean busy;
	private @Nullable Component message;
	private boolean problem;

	public DragonOfferScreen() {
		super(Component.translatable("survivalcinema.offer.title"));
	}

	@Override
	protected void init() {
		int center = width / 2;
		body = font.split(Component.translatable("survivalcinema.offer.body"), Math.min(320, width - 40));
		top = Math.max(15, height / 2 - 80);
		int y = top + 16 + body.size() * (font.lineHeight + 2) + 18;
		for (Button button : length.build(center, y, BUTTON_WIDTH * 2 + GAP)) {
			addRenderableWidget(button);
		}
		y += 32;
		make = addRenderableWidget(Button.builder(Component.translatable("survivalcinema.offer.make"), b -> make())
			.bounds(center - BUTTON_WIDTH - GAP / 2, y, BUTTON_WIDTH, 20).build());
		settings = addRenderableWidget(Button.builder(Component.translatable("survivalcinema.offer.settings"),
				b -> minecraft.setScreen(new FilmSetupScreen(this, length.selected())))
			.bounds(center + GAP / 2, y, BUTTON_WIDTH, 20).build());
		y += 24;
		addRenderableWidget(Button.builder(Component.translatable("survivalcinema.offer.later"), b -> onClose())
			.bounds(center - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, 20).build());
		updateActive();
	}

	private void make() {
		FilmLength chosen = length.selected();
		SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
		config.filmMinutes = chosen.minutes();
		config.save();
		busy = true;
		message = Component.translatable("survivalcinema.setup.planning");
		problem = false;
		updateActive();
		if (!FilmMaker.start(chosen, config.renderQuality(), reason -> {
			busy = false;
			message = reason;
			problem = true;
			updateActive();
		})) {
			busy = false;
			updateActive();
		}
	}

	private void updateActive() {
		if (make != null) {
			make.active = !busy;
			settings.active = !busy;
		}
		length.setActive(!busy);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		int center = width / 2;
		graphics.drawCenteredString(font, title, center, top, YELLOW);
		int y = top + 16;
		for (FormattedCharSequence line : body) {
			graphics.drawCenteredString(font, line, center, y, WHITE);
			y += font.lineHeight + 2;
		}
		graphics.drawCenteredString(font, Component.translatable("survivalcinema.setup.length"), center, y + 6, GREY);
		int bottom = make == null ? y + 100 : make.getY() + 52;
		if (message != null) {
			graphics.drawCenteredString(font, message, center, bottom, problem ? RED : WHITE);
		} else {
			graphics.drawCenteredString(font, Component.translatable("survivalcinema.offer.hint"), center, bottom, GREY);
		}
	}
}
