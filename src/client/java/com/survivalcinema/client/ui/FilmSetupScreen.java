package com.survivalcinema.client.ui;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.SessionStore;
import com.survivalcinema.client.capture.WorldMemory;
import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.client.edit.MusicLibrary;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.client.flashback.FlashbackCheck;
import com.survivalcinema.client.render.FilmMaker;
import com.survivalcinema.director.film.DiskSize;
import com.survivalcinema.director.film.FilmStorage;
import com.survivalcinema.director.render.FilmFolder;
import com.survivalcinema.director.render.RenderEta;
import com.survivalcinema.director.render.RenderQuality;
import com.survivalcinema.director.story.FilmLength;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * "Make a film" (from the pause menu, or "Settings..." in the offer after the dragon): the film's length (always
 * chosen, five minutes unless changed), quality, music, how much disk the recordings take and roughly how long the
 * shooting takes. "Start shooting" plans the film and shoots it under the cinema screen. When a film of this world
 * was stopped half-way it offers to finish it, and the last finished film can be watched from here.
 */
public final class FilmSetupScreen extends Screen {
	private static final int COLUMN = 310;
	private static final int HALF = 150;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int RED = 0xFFFF5555;
	private static final int YELLOW = 0xFFFFFF55;

	/** What the screen learns from the disk in the background. */
	private record Info(long recordingBytes, int savedSessions, long filmBytes, @Nullable FilmMaker.Unfinished unfinished,
						@Nullable Path lastFilm, long freeBytes, long chosenTicks) {
	}

	private final Screen parent;
	private final @Nullable WorldMemory world;
	private final LengthPicker length;
	private RenderQuality quality;
	private MusicLibrary.Source music;
	private @Nullable CompletableFuture<Info> loading;
	private @Nullable Info info;
	private boolean busy;
	private @Nullable Component message;
	private boolean messageIsProblem;
	private final List<AbstractWidget> inputs = new ArrayList<>();
	private int infoY;

	public FilmSetupScreen(Screen parent) {
		this(parent, SurvivalCinemaConfig.get().filmLength());
	}

	public FilmSetupScreen(Screen parent, FilmLength initialLength) {
		super(Component.translatable("survivalcinema.setup.title"));
		this.parent = parent;
		this.world = SurvivalCinemaClient.controller().world();
		SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
		this.length = new LengthPicker(initialLength, () -> {
		});
		this.quality = config.renderQuality();
		this.music = MusicLibrary.Source.parse(config.music);
	}

	@Override
	protected void init() {
		if (loading == null && world != null) {
			WorldMemory w = world;
			List<String> sessions = List.copyOf(w.sessions);
			loading = CompletableFuture.supplyAsync(() -> load(w, sessions), Util.backgroundExecutor());
		}
		inputs.clear();
		int center = width / 2;
		int left = center - COLUMN / 2;
		int right = center + COLUMN / 2 - HALF;
		int y = 52;
		for (Button button : length.build(center, y, COLUMN)) {
			inputs.add(addRenderableWidget(button));
		}
		y += 28;
		inputs.add(addRenderableWidget(CycleButton.<RenderQuality>builder(q -> Component.translatable(
				"survivalcinema.setup.quality." + q.name().toLowerCase(Locale.ROOT)), quality)
			.withValues(RenderQuality.values())
			.withTooltip(q -> Tooltip.create(Component.translatable("survivalcinema.setup.quality." + q.name().toLowerCase(Locale.ROOT) + ".tooltip")))
			.create(left, y, HALF, 20, Component.translatable("survivalcinema.setup.quality"), (button, value) -> quality = value)));
		inputs.add(addRenderableWidget(CycleButton.<MusicLibrary.Source>builder(m -> Component.translatable(
				"survivalcinema.setup.music." + m.name().toLowerCase(Locale.ROOT)), music)
			.withValues(MusicLibrary.Source.values())
			.withTooltip(m -> Tooltip.create(Component.translatable("survivalcinema.setup.music." + m.name().toLowerCase(Locale.ROOT) + ".tooltip")))
			.create(right, y, HALF, 20, Component.translatable("survivalcinema.setup.music"), (button, value) -> music = value)));
		y += 24;
		if (world != null) {
			WorldMemory w = world;
			inputs.add(addRenderableWidget(Button.builder(Component.translatable("survivalcinema.sessions.button", SessionsScreen.chosen(w), w.sessions.size()),
				b -> openForgetting(new SessionsScreen(this, w))).bounds(left, y, HALF, 20).build()));
		}
		addRenderableWidget(Button.builder(Component.translatable("survivalcinema.voice.button"), b -> minecraft.setScreen(new VoiceSettingsScreen(this)))
			.bounds(right, y, HALF, 20).build());
		y += 24;
		if (world != null) {
			WorldMemory w = world;
			addRenderableWidget(Button.builder(Component.translatable("survivalcinema.storage.button"), b -> openForgetting(new StorageScreen(this, w)))
				.bounds(left, y, HALF, 20).build());
		}
		addRenderableWidget(Button.builder(Component.translatable("survivalcinema.settings.button"), b -> minecraft.setScreen(new SettingsScreen(this)))
			.bounds(right, y, HALF, 20).build());
		y += 30;
		infoY = y;
		y += infoLines() * 11 + 4;
		Info known = info;
		if (known != null && known.unfinished() != null) {
			FilmMaker.Unfinished unfinished = known.unfinished();
			Component label = unfinished.clipsLeft() == 0
				? Component.translatable("survivalcinema.setup.resume_edit")
				: Component.translatable("survivalcinema.setup.resume", unfinished.clipCount() - unfinished.clipsLeft(), unfinished.clipCount());
			inputs.add(addRenderableWidget(Button.builder(label, b -> resume()).bounds(left, y, COLUMN, 20).build()));
			y += 24;
		}
		if (known != null && known.lastFilm() != null) {
			Path lastFilm = known.lastFilm();
			inputs.add(addRenderableWidget(Button.builder(Component.translatable("survivalcinema.setup.watch_last"),
				b -> minecraft.setScreen(new FilmPlayerScreen(this, lastFilm, world == null ? "" : world.displayName)))
				.bounds(left, y, COLUMN, 20).build()));
			y += 24;
		}

		int bottom = Math.max(y + 10, height - 28);
		inputs.add(addRenderableWidget(Button.builder(Component.translatable("survivalcinema.setup.start"), b -> start())
			.bounds(left, bottom, HALF, 20).build()));
		addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(right, bottom, HALF, 20).build());
		updateActive();
	}

	@Override
	public void tick() {
		CompletableFuture<Info> pending = loading;
		if (info == null && pending != null && pending.isDone()) {
			try {
				info = pending.join();
			} catch (RuntimeException e) {
				SurvivalCinemaClient.LOGGER.warn("Could not look at the recordings on disk", e);
				info = new Info(0, 0, 0, null, null, -1, 0);
			}
			rebuildWidgets(); // "finish the last film" and "watch the last film" appear once known
		}
		if (busy && !FilmMaker.isPlanning() && minecraft.screen == this) {
			busy = false; // planning ended without starting (the listener says why)
			updateActive();
		}
	}

	/** Opens a screen that may delete recordings: what the disk holds is looked at again on the way back. */
	private void openForgetting(Screen screen) {
		loading = null;
		info = null;
		minecraft.setScreen(screen);
	}

	private void updateActive() {
		boolean can = !busy && world != null;
		for (AbstractWidget widget : inputs) {
			widget.active = can;
		}
	}

	private void start() {
		SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
		FilmLength chosen = length.selected();
		config.filmMinutes = chosen.minutes();
		config.renderQuality = quality.name();
		config.music = music.name();
		config.save();
		busy = true;
		showMessage(Component.translatable("survivalcinema.setup.planning"), false);
		updateActive();
		if (!FilmMaker.start(chosen, quality, this::failed)) {
			busy = false;
			updateActive();
		}
	}

	private void resume() {
		SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
		config.music = music.name();
		config.save();
		FilmMaker.resume(this::failed);
	}

	private void failed(Component reason) {
		busy = false;
		showMessage(reason, true);
		updateActive();
	}

	private void showMessage(Component text, boolean problem) {
		message = text;
		messageIsProblem = problem;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		int center = width / 2;
		graphics.drawCenteredString(font, title, center, 15, WHITE);
		if (world == null) {
			graphics.drawCenteredString(font, Component.translatable("survivalcinema.film.error.no_world"), center, 28, RED);
		} else {
			graphics.drawCenteredString(font, Component.translatable("survivalcinema.setup.world", world.displayName,
				world.sessions.size()), center, 28, GREY);
		}
		graphics.drawCenteredString(font, Component.translatable("survivalcinema.setup.length"), center, 40, WHITE);

		int y = infoY;
		for (Line line : infoLines(info)) {
			graphics.drawCenteredString(font, line.text(), center, y, line.colour());
			y += 11;
		}

		if (message != null) {
			int bottom = height - 28;
			graphics.drawCenteredString(font, message, center, Math.min(bottom - 12, height - 40), messageIsProblem ? RED : WHITE);
		}
	}

/** One line of the text under the buttons. */
	private record Line(Component text, int colour) {
	}

	private int infoLines() {
		return infoLines(info).size();
	}

	/** What the disk holds, how long the shooting takes, and anything to know before starting. */
	private List<Line> infoLines(@Nullable Info known) {
		List<Line> lines = new ArrayList<>();
		Component flashback = FlashbackCheck.problem();
		if (flashback != null) {
			lines.add(new Line(flashback, RED));
		}
		if (known == null) {
			if (world != null) {
				lines.add(new Line(Component.translatable("survivalcinema.setup.disk_counting"), GREY));
			}
		} else {
			lines.add(new Line(Component.translatable("survivalcinema.setup.disk", size(known.recordingBytes())), GREY));
			if (known.filmBytes() > 0) {
				lines.add(new Line(Component.translatable("survivalcinema.setup.disk_films", size(known.filmBytes())), GREY));
			}
		}
		int replays = Math.max(1, Math.min(world == null ? 1 : SessionsScreen.chosen(world), length.selected().minutes() * 2));
		// a film is never longer than what was played (plus the recording running now)
		int filmSeconds = length.selected().seconds();
		if (known != null) {
			long played = known.chosenTicks() + Math.max(0, FlashbackBridge.recordingTick());
			if (played > 0) {
				filmSeconds = (int) Math.min(filmSeconds, played / 20 + 20);
			}
		}
		int minutes = RenderEta.minutes(RenderEta.guessSeconds(filmSeconds, quality, replays));
		lines.add(new Line(Component.translatable("survivalcinema.setup.estimate", minutes), GREY));
		if (SurvivalCinemaConfig.get().deleteRecordingsAfterFilm) {
			lines.add(new Line(Component.translatable("survivalcinema.setup.delete_after"), YELLOW));
		}
		if (known != null && known.freeBytes() >= 0 && known.freeBytes() < StorageScreen.LOW_SPACE) {
			lines.add(new Line(Component.translatable("survivalcinema.setup.low_space", size(known.freeBytes())), RED));
		}
		return lines;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	/** "1,2 GB" in the player's language. */
	static Component size(long bytes) {
		String decimal = Language.getInstance().getOrDefault("survivalcinema.size.decimal", ".");
		DiskSize size = DiskSize.of(bytes, decimal.isEmpty() ? '.' : decimal.charAt(0));
		return Component.translatable("survivalcinema.size." + size.unit().name().toLowerCase(Locale.ROOT), size.number());
	}

	private static Info load(WorldMemory world, List<String> sessionIds) {
		long recordings = 0;
		int saved = 0;
		long chosenTicks = 0;
		Map<UUID, FlashbackBridge.ReplayFile> replays = FlashbackBridge.replayFiles();
		for (String id : sessionIds) {
			UUID uuid;
			try {
				uuid = UUID.fromString(id);
			} catch (IllegalArgumentException e) {
				continue;
			}
			FlashbackBridge.ReplayFile replay = replays.get(uuid);
			if (replay != null) {
				recordings += FilmStorage.size(replay.zip());
				saved++;
				if (!world.skippedSessions.contains(id)) {
					chosenTicks += replay.ticks();
				}
			}
			recordings += FilmStorage.size(SessionStore.sessionFolder(uuid));
		}
		Path films = SessionStore.filmsFolder(world.worldKey);
		return new Info(recordings, saved, FilmStorage.size(films), FilmMaker.unfinished(world), FilmFolder.latestEdited(films),
			StorageScreen.freeSpace(films), chosenTicks);
	}
}
