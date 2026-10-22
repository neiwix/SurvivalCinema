package com.survivalcinema.client.ui;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.Recordings;
import com.survivalcinema.client.capture.SessionStore;
import com.survivalcinema.client.capture.WorldMemory;
import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.director.film.FilmStorage;
import com.survivalcinema.director.film.SessionSummary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Where the disk goes: this world's recordings (choose what to keep, delete them all, or have them deleted after each
 * film) and its films (the clips of older films can go, the films stay). Every deletion asks first.
 */
public final class StorageScreen extends OptionsSubScreen {
	private static final int WIDE = 310;
	/** Below this the film may not fit (a long film in cinema quality takes a few gigabytes while it is made). */
	static final long LOW_SPACE = 3L * 1024 * 1024 * 1024;

	private final WorldMemory world;
	private final State state;

	/** What the screen knows; it is shown anew with it when something changes (an options list is not rebuilt in place). */
	private static final class State {
		@Nullable CompletableFuture<Loaded> loading;
		@Nullable Loaded loaded;
		boolean busy;
		boolean dirty;
	}

	/** What the disk holds, read in the background. */
	private record Loaded(List<SessionSummary> sessions, long recordingBytes, FilmStorage.Usage films, long freeBytes) {
	}

	public StorageScreen(Screen parent, WorldMemory world) {
		this(parent, world, new State());
	}

	private StorageScreen(Screen parent, WorldMemory world, State state) {
		super(parent, Minecraft.getInstance().options, Component.translatable("survivalcinema.storage.title"));
		this.world = world;
		this.state = state;
	}

	@Override
	protected void init() {
		if (state.loading == null) {
			reload();
		}
		super.init();
	}

	private void reload() {
		List<String> ids = List.copyOf(world.sessions);
		UUID live = FlashbackBridge.recordingReplayId();
		int liveTicks = Math.max(0, FlashbackBridge.recordingTick());
		Path films = SessionStore.filmsFolder(world.worldKey);
		Path root = SessionStore.root();
		state.loaded = null;
		state.loading = CompletableFuture.supplyAsync(() -> {
			List<SessionSummary> sessions = Recordings.summaries(ids, live, liveTicks);
			long bytes = sessions.stream().mapToLong(SessionSummary::bytes).sum();
			return new Loaded(sessions, bytes, FilmStorage.usage(films), freeSpace(root));
		}, Util.backgroundExecutor());
	}

	static long freeSpace(Path folder) {
		try {
			Path existing = folder;
			while (existing != null && !Files.exists(existing)) {
				existing = existing.getParent();
			}
			if (existing == null) {
				return -1;
			}
			FileStore store = Files.getFileStore(existing);
			return store.getUsableSpace();
		} catch (IOException | RuntimeException e) {
			return -1;
		}
	}

	@Override
	public void tick() {
		super.tick();
		CompletableFuture<Loaded> pending = state.loading;
		if (state.loaded == null && pending != null && pending.isDone()) {
			try {
				state.loaded = pending.join();
			} catch (RuntimeException e) {
				SurvivalCinemaClient.LOGGER.warn("Could not look at the disk", e);
				state.loaded = new Loaded(List.of(), 0, new FilmStorage.Usage(0, 0, 0), -1);
			}
			state.dirty = true;
		}
		if (state.dirty && minecraft.screen == this) {
			state.dirty = false;
			minecraft.setScreen(new StorageScreen(lastScreen, world, state));
		}
	}

	@Override
	protected void addOptions() {
		SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
		Loaded known = state.loaded;
		list.addHeader(Component.translatable("survivalcinema.storage.recordings", world.displayName));
		if (known == null) {
			list.addHeader(Component.translatable("survivalcinema.setup.disk_counting").withStyle(ChatFormatting.GRAY));
		} else {
			list.addHeader(Component.translatable("survivalcinema.storage.recordings_size", known.sessions().size(),
				FilmSetupScreen.size(known.recordingBytes())).withStyle(ChatFormatting.GRAY));
		}
		Button choose = Button.builder(Component.translatable("survivalcinema.sessions.button_short"),
			b -> minecraft.setScreen(new SessionsScreen(this, world))).width(150).build();
		Button deleteAll = Button.builder(Component.translatable("survivalcinema.storage.delete_all"), b -> askDeleteAll())
			.tooltip(Tooltip.create(Component.translatable("survivalcinema.storage.delete_all.tooltip"))).width(150).build();
		deleteAll.active = !state.busy && known != null && !deletable(known).isEmpty();
		list.addSmall(choose, deleteAll);
		list.addSmall(CycleButton.onOffBuilder(config.deleteRecordingsAfterFilm)
			.withTooltip(v -> Tooltip.create(Component.translatable("survivalcinema.storage.delete_after.tooltip")))
			.create(0, 0, WIDE, 20, Component.translatable("survivalcinema.storage.delete_after"), (b, value) -> {
				config.deleteRecordingsAfterFilm = value;
				config.save();
			}), null);

		list.addHeader(Component.translatable("survivalcinema.storage.films"));
		if (known != null) {
			FilmStorage.Usage films = known.films();
			list.addHeader(Component.translatable("survivalcinema.storage.films_size", FilmSetupScreen.size(films.films()),
				FilmSetupScreen.size(films.clips())).withStyle(ChatFormatting.GRAY));
		}
		Button spare = Button.builder(known == null || known.films().spareClips() == 0
				? Component.translatable("survivalcinema.storage.no_spare")
				: Component.translatable("survivalcinema.storage.delete_spare", FilmSetupScreen.size(known.films().spareClips())),
				b -> askDeleteSpare())
			.tooltip(Tooltip.create(Component.translatable("survivalcinema.storage.delete_spare.tooltip"))).width(150).build();
		spare.active = !state.busy && known != null && known.films().spareClips() > 0;
		list.addSmall(spare, Button.builder(Component.translatable("survivalcinema.cinema.open_folder"), b -> openFilms()).width(150).build());
		if (known != null && known.freeBytes() >= 0) {
			list.addHeader(Component.translatable("survivalcinema.storage.free", FilmSetupScreen.size(known.freeBytes()))
				.withStyle(known.freeBytes() < LOW_SPACE ? ChatFormatting.RED : ChatFormatting.GRAY));
		}
	}

	private List<String> deletable(Loaded known) {
		List<String> ids = new ArrayList<>();
		String recording = String.valueOf(FlashbackBridge.recordingReplayId());
		for (SessionSummary session : known.sessions()) {
			if (!session.id().equals(recording)) {
				ids.add(session.id());
			}
		}
		return ids;
	}

	private void askDeleteAll() {
		Loaded known = state.loaded;
		if (known == null) {
			return;
		}
		List<String> ids = deletable(known);
		minecraft.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				deleteRecordings(ids);
			}
			minecraft.setScreen(this);
		}, Component.translatable("survivalcinema.storage.delete_all.title", world.displayName),
			Component.translatable("survivalcinema.storage.delete_all.body", ids.size(), FilmSetupScreen.size(known.recordingBytes())),
			Component.translatable("survivalcinema.storage.delete"), CommonComponents.GUI_CANCEL));
	}

	private void deleteRecordings(List<String> ids) {
		state.busy = true;
		var recording = FlashbackBridge.recordingReplayId();
		var open = FlashbackBridge.openReplayId();
		String key = world.worldKey;
		String name = world.displayName;
		CompletableFuture.supplyAsync(() -> Recordings.delete(ids, recording, open), Util.backgroundExecutor())
			.thenAcceptAsync(gone -> {
				Recordings.forget(key, name, gone);
				done();
			}, minecraft);
		state.dirty = true;
	}

	private void askDeleteSpare() {
		Loaded known = state.loaded;
		if (known == null) {
			return;
		}
		minecraft.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				state.busy = true;
				String key = world.worldKey;
				CompletableFuture.supplyAsync(() -> Recordings.deleteSpareClips(key), Util.backgroundExecutor())
					.thenAcceptAsync(freed -> done(), minecraft);
			}
			minecraft.setScreen(this);
		}, Component.translatable("survivalcinema.storage.delete_spare.title"),
			Component.translatable("survivalcinema.storage.delete_spare.body", FilmSetupScreen.size(known.films().spareClips())),
			Component.translatable("survivalcinema.storage.delete"), CommonComponents.GUI_CANCEL));
	}

	private void done() {
		state.busy = false;
		reload();
		state.dirty = true;
	}

	private void openFilms() {
		Path films = SessionStore.filmsFolder(world.worldKey);
		try {
			Files.createDirectories(films);
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.warn("Could not make {}", films, e);
		}
		Util.getPlatform().openPath(films);
	}
}
