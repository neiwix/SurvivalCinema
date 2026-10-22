package com.survivalcinema.client.ui;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.flashback.FlashbackBridge;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * "Make a film" in the pause menu: a square icon button (a clapperboard) to the right of "Back to Game", in the same
 * column as Flashback's recording buttons; its name shows when the mouse is over it.
 */
public final class PauseMenuButton {
	private static final Identifier ICON = Identifier.fromNamespaceAndPath(SurvivalCinemaClient.MOD_ID, "make_film");
	private static final int SIZE = 20;
	private static final int GAP = 4;

	private PauseMenuButton() {
	}

	public static void install() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof PauseScreen pause && pause.showsPauseMenu()) {
				add(client, pause);
			}
		});
	}

	private static void add(Minecraft mc, PauseScreen screen) {
		if (FlashbackBridge.isInReplay() || SurvivalCinemaClient.controller().world() == null) {
			return;
		}
		List<AbstractWidget> buttons = Screens.getButtons(screen);
		AbstractWidget back = find(buttons, "menu.returnToGame");
		if (back == null) {
			return;
		}
		int right = back.getX() + back.getWidth();
		int x = right + GAP;
		// line up with Flashback's icon buttons when they are there
		for (AbstractWidget widget : buttons) {
			if (widget.getWidth() == SIZE && widget.getHeight() == SIZE && widget.getX() > right) {
				x = widget.getX();
				break;
			}
		}
		SpriteIconButton button = SpriteIconButton.builder(Component.translatable("survivalcinema.pause.make_film"),
				b -> mc.setScreen(new FilmSetupScreen(screen)), true)
			.sprite(ICON, 16, 16)
			.size(SIZE, SIZE)
			.withTootip()
			.build();
		button.setPosition(x, back.getY());
		buttons.add(button);
	}

	private static @Nullable AbstractWidget find(List<AbstractWidget> buttons, String key) {
		for (AbstractWidget widget : buttons) {
			if (widget.getMessage().getContents() instanceof TranslatableContents translatable && key.equals(translatable.getKey())) {
				return widget;
			}
		}
		return null;
	}
}
