package com.survivalcinema.client.ui;

import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.client.render.CinemaMode;
import com.survivalcinema.client.render.RenderPipeline;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * "Shooting the film...": covers the game while the clips are rendered, so the player never sees Flashback's editor.
 * Esc asks whether to stop (finished clips are kept). Plain vanilla widgets on a black, cinema-like background.
 */
public final class CinemaScreen extends Screen {
	private static final int BUTTON_WIDTH = 150;
	private static final int GAP = 8;
	/** "The film is ready!" stays this long before the film starts playing. */
	private static final int AUTO_PLAY_TICKS = 40;

	private final RenderPipeline pipeline;
	private RenderPipeline.Phase builtFor;
	private int doneTicks;

	public CinemaScreen(RenderPipeline pipeline) {
		super(Component.translatable("survivalcinema.cinema.title"));
		this.pipeline = pipeline;
	}

	@Override
	protected void init() {
		builtFor = pipeline.phase();
		int center = width / 2;
		int y = height / 2 + CinemaPainter.BUTTONS_Y;
		int left = center - BUTTON_WIDTH - GAP / 2;
		int right = center + GAP / 2;
		switch (builtFor) {
			case CONFIRM_STOP -> {
				addRenderableWidget(Button.builder(Component.translatable("survivalcinema.cinema.confirm.continue"),
					b -> pipeline.continueShooting()).bounds(left, y, BUTTON_WIDTH, 20).build());
				addRenderableWidget(Button.builder(Component.translatable("survivalcinema.cinema.confirm.stop"),
					b -> pipeline.stop()).bounds(right, y, BUTTON_WIDTH, 20).build());
			}
			case DONE, STOPPED, FAILED -> {
				Path filmFile = pipeline.filmFile();
				if (filmFile != null) {
					addRenderableWidget(Button.builder(Component.translatable("survivalcinema.cinema.watch"),
						b -> watch(filmFile)).bounds(left, y, BUTTON_WIDTH * 2 + GAP, 20).build());
					y += 24;
				}
				if (pipeline.hasShotClips()) {
					addRenderableWidget(Button.builder(Component.translatable("survivalcinema.cinema.open_folder"),
						b -> openFolder()).bounds(left, y, BUTTON_WIDTH, 20).build());
					addRenderableWidget(Button.builder(Component.translatable("survivalcinema.cinema.close"),
						b -> pipeline.dismiss()).bounds(right, y, BUTTON_WIDTH, 20).build());
				} else {
					addRenderableWidget(Button.builder(Component.translatable("survivalcinema.cinema.close"),
						b -> pipeline.dismiss()).bounds(center - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, 20).build());
				}
			}
			default -> {
			}
		}
	}

	@Override
	public void tick() {
		if (pipeline.phase() != builtFor) {
			rebuildWidgets();
		}
		if (pipeline.phase() == RenderPipeline.Phase.DONE && ++doneTicks >= AUTO_PLAY_TICKS && pipeline.consumeAutoPlay()) {
			watch(pipeline.filmFile()); // the film starts by itself after "The film is ready!", like in a cinema
		}
	}

	/** Opens the in-game cinema; closing it comes back here. */
	private void watch(@Nullable Path filmFile) {
		if (filmFile != null) {
			minecraft.setScreen(new FilmPlayerScreen(this, filmFile, pipeline.worldName()));
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if (pipeline.phase() != builtFor) {
			rebuildWidgets(); // the buttons belong to the phase on screen, from its very first frame
		}
		CinemaMode.rememberGuiScale(minecraft.getWindow().getGuiScale());
		CinemaPainter.paint(new GuiCanvas(graphics, font, width, height), pipeline.status(-1), 0, false);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// the painter draws the black background itself (no blur over the replay world)
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			// Also arrives in the middle of a clip being rendered: the pipeline then asks once the clip is done.
			switch (pipeline.phase()) {
				case CONFIRM_STOP -> pipeline.continueShooting();
				case DONE, STOPPED, FAILED -> pipeline.dismiss();
				default -> pipeline.requestStop();
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		// a replay runs on an integrated server, which must not pause; the player's own world waits for us
		return !FlashbackBridge.isInReplay();
	}

	private void openFolder() {
		Path clips = pipeline.film().resolve("clips");
		Util.getPlatform().openPath(pipeline.filmFile() != null || !Files.isDirectory(clips) ? pipeline.film() : clips);
	}
}
