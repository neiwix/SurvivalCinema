package com.survivalcinema.client.ui;

import com.survivalcinema.director.story.FilmLength;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * "5 min · 10 min · 15 min · 20 min": the film length as a row of vanilla buttons, the chosen one in yellow. Shared by
 * the film setup screen and the offer after the dragon (a length is always chosen, five minutes unless changed).
 */
final class LengthPicker {
	private static final int GAP = 4;

	private FilmLength selected;
	private final List<Button> buttons = new ArrayList<>();
	private final Runnable onChange;

	LengthPicker(FilmLength selected, Runnable onChange) {
		this.selected = selected;
		this.onChange = onChange;
	}

	FilmLength selected() {
		return selected;
	}

	/** New buttons for the row, centered on {@code centerX}; the screen adds them. */
	List<Button> build(int centerX, int y, int totalWidth) {
		buttons.clear();
		FilmLength[] lengths = FilmLength.values();
		int width = (totalWidth - GAP * (lengths.length - 1)) / lengths.length;
		int x = centerX - (width * lengths.length + GAP * (lengths.length - 1)) / 2;
		for (FilmLength length : lengths) {
			buttons.add(Button.builder(label(length), b -> choose(length)).bounds(x, y, width, 20).build());
			x += width + GAP;
		}
		return buttons;
	}

	void setActive(boolean active) {
		for (Button button : buttons) {
			button.active = active;
		}
	}

	private void choose(FilmLength length) {
		selected = length;
		FilmLength[] lengths = FilmLength.values();
		for (int i = 0; i < buttons.size() && i < lengths.length; i++) {
			buttons.get(i).setMessage(label(lengths[i]));
		}
		onChange.run();
	}

	private Component label(FilmLength length) {
		Component text = Component.translatable("survivalcinema.setup.minutes", length.minutes());
		return length == selected
			? Component.literal("[ ").append(text).append(" ]").withStyle(ChatFormatting.YELLOW)
			: text;
	}
}
