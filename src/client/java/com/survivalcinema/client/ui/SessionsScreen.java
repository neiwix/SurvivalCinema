package com.survivalcinema.client.ui;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.Recordings;
import com.survivalcinema.client.capture.WorldMemory;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.director.film.SessionSummary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * What goes into the film: every recording of the world with its date, length, in-game days and size, ticked when it
 * is used. "Only new" keeps what was played since the last film. Recordings left out can be deleted from here to free
 * the disk. The choice is kept in the world's memory (new recordings are always in).
 */
public final class SessionsScreen extends OptionsSubScreen {
	private static final int WIDE = 310;

	private final WorldMemory world;
	private final State state;
	private final double scrolled;

	/**
	 * What the screen knows. When something changes the screen is shown anew with it (keeping where the list was
	 * scrolled to): a vanilla options list cannot be rebuilt in place.
	 */
	private static final class State {
		@Nullable CompletableFuture<Loaded> loading;
		@Nullable Loaded loaded;
		boolean deleting;
		boolean dirty;
	}

	private record Loaded(List<SessionSummary> sessions, long lastFilmAt) {
	}

	public SessionsScreen(Screen parent, WorldMemory world) {
		this(parent, world, new State(), 0);
	}

	private SessionsScreen(Screen parent, WorldMemory world, State state, double scrolled) {
		super(parent, net.minecraft.client.Minecraft.getInstance().options, Component.translatable("survivalcinema.sessions.title"));
		this.world = world;
		this.state = state;
		this.scrolled = scrolled;
	}

	/** "5 of 7" for the film setup's button: the recordings that go into the film. */
	public static int chosen(WorldMemory world) {
		int chosen = 0;
		for (String id : world.sessions) {
			if (!world.skippedSessions.contains(id)) {
				chosen++;
			}
		}
		return chosen;
	}

	@Override
	protected void init() {
		if (state.loading == null) {
			reload();
		}
		super.init();
		list.setScrollAmount(scrolled);
	}

	private void reload() {
		List<String> ids = List.copyOf(world.sessions);
		UUID live = FlashbackBridge.recordingReplayId();
		int liveTicks = Math.max(0, FlashbackBridge.recordingTick());
		String key = world.worldKey;
		state.loaded = null;
		state.loading = CompletableFuture.supplyAsync(() -> new Loaded(Recordings.summaries(ids, live, liveTicks), Recordings.lastFilmPlannedAt(key)),
			Util.backgroundExecutor());
	}

	@Override
	public void tick() {
		super.tick();
		CompletableFuture<Loaded> pending = state.loading;
		if (state.loaded == null && pending != null && pending.isDone()) {
			try {
				state.loaded = pending.join();
			} catch (RuntimeException e) {
				SurvivalCinemaClient.LOGGER.warn("Could not read the recordings of {}", world.displayName, e);
				state.loaded = new Loaded(List.of(), 0);
			}
			state.dirty = true;
		}
		if (state.dirty && minecraft.screen == this) {
			state.dirty = false;
			minecraft.setScreen(new SessionsScreen(lastScreen, world, state, list == null ? 0 : list.scrollAmount()));
		}
	}

	@Override
	protected void addOptions() {
		Loaded known = state.loaded;
		if (known == null) {
			list.addHeader(Component.translatable("survivalcinema.setup.disk_counting").withStyle(ChatFormatting.GRAY));
			return;
		}
		List<SessionSummary> sessions = known.sessions();
		if (sessions.isEmpty()) {
			list.addHeader(Component.translatable("survivalcinema.film.error.nothing_recorded").withStyle(ChatFormatting.GRAY));
			return;
		}
		list.addHeader(summary(sessions));
		Button all = Button.builder(Component.translatable("survivalcinema.sessions.all"), b -> choose(sessions, null)).width(150).build();
		Button fresh = Button.builder(Component.translatable("survivalcinema.sessions.new"), b -> choose(sessions, known.lastFilmAt()))
			.tooltip(Tooltip.create(Component.translatable("survivalcinema.sessions.new.tooltip"))).width(150).build();
		fresh.active = known.lastFilmAt() > 0 && !SessionSummary.startedAfter(sessions, known.lastFilmAt()).isEmpty();
		list.addSmall(all, fresh);
		for (int i = sessions.size() - 1; i >= 0; i--) { // newest first
			SessionSummary session = sessions.get(i);
			Checkbox box = Checkbox.builder(line(session), font)
				.selected(session.usable() && !world.skippedSessions.contains(session.id()))
				.onValueChange((checkbox, on) -> {
					if (on) {
						world.skippedSessions.remove(session.id());
					} else {
						world.skippedSessions.add(session.id());
					}
					world.save();
					state.dirty = true;
				})
				.maxWidth(WIDE)
				.build();
			box.active = session.usable() && !state.deleting;
			list.addSmall(box, null);
		}
	}

	@Override
	protected void addFooter() {
		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		Button delete = footer.addChild(Button.builder(Component.translatable("survivalcinema.sessions.delete_unchosen"), b -> askDelete())
			.width(150).build());
		delete.active = !state.deleting && state.loaded != null && !unchosen().isEmpty();
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(150).build());
	}

	private Component summary(List<SessionSummary> sessions) {
		int chosen = 0;
		int minutes = 0;
		int usable = 0;
		for (SessionSummary session : sessions) {
			if (session.usable()) {
				usable++;
				if (!world.skippedSessions.contains(session.id())) {
					chosen++;
					minutes += session.minutes();
				}
			}
		}
		return Component.translatable("survivalcinema.sessions.summary", chosen, usable, duration(minutes));
	}

	/** "19 Oct, 01:56 · 1 h 12 min · days 1–3 · 35 MB" */
	private Component line(SessionSummary session) {
		String language = minecraft.options.languageCode.replace('_', '-');
		DateTimeFormatter format = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag(language));
		Component when = session.startedAt() > 0
			? Component.literal(format.format(Instant.ofEpochMilli(session.startedAt()).atZone(ZoneId.systemDefault())))
			: Component.translatable("survivalcinema.sessions.unknown_date");
		Component line = Component.empty().append(when).append(" · ").append(duration(session.minutes()));
		if (session.firstDay() > 0) {
			line = line.copy().append(" · ").append(session.firstDay() == session.lastDay()
				? Component.translatable("survivalcinema.sessions.day", session.firstDay())
				: Component.translatable("survivalcinema.sessions.days", session.firstDay(), session.lastDay()));
		}
		line = line.copy().append(" · ").append(session.live() ? Component.translatable("survivalcinema.sessions.live")
			: FilmSetupScreen.size(session.bytes()));
		if (!session.hasReplay() && !session.live()) {
			line = line.copy().append(" · ").append(Component.translatable("survivalcinema.sessions.lost")).withStyle(ChatFormatting.GRAY);
		}
		return line;
	}

	static Component duration(int minutes) {
		return minutes >= 60
			? Component.translatable("survivalcinema.film.credits.hours", minutes / 60, minutes % 60)
			: Component.translatable("survivalcinema.film.credits.minutes", minutes);
	}

	/** All recordings, or only those started after the last film was planned. */
	private void choose(List<SessionSummary> sessions, @Nullable Long after) {
		Set<String> fresh = after == null ? null : new HashSet<>(SessionSummary.startedAfter(sessions, after));
		world.skippedSessions.clear();
		for (SessionSummary session : sessions) {
			if (fresh != null && !fresh.contains(session.id())) {
				world.skippedSessions.add(session.id());
			}
		}
		world.save();
		state.dirty = true;
	}

	/** Recordings that are not ticked (or whose replay is gone), except the one being recorded now. */
	private List<String> unchosen() {
		Loaded known = state.loaded;
		List<String> ids = new ArrayList<>();
		if (known == null) {
			return ids;
		}
		String recording = String.valueOf(FlashbackBridge.recordingReplayId());
		for (SessionSummary session : known.sessions()) {
			boolean out = !session.usable() || world.skippedSessions.contains(session.id());
			if (out && !session.id().equals(recording)) {
				ids.add(session.id());
			}
		}
		return ids;
	}

	private void askDelete() {
		List<String> ids = unchosen();
		long bytes = 0;
		Loaded known = state.loaded;
		if (known != null) {
			for (SessionSummary session : known.sessions()) {
				if (ids.contains(session.id())) {
					bytes += session.bytes();
				}
			}
		}
		minecraft.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				delete(ids);
			}
			minecraft.setScreen(this);
		}, Component.translatable("survivalcinema.sessions.delete.title", ids.size()),
			Component.translatable("survivalcinema.sessions.delete.body", FilmSetupScreen.size(bytes)),
			Component.translatable("survivalcinema.storage.delete"), CommonComponents.GUI_CANCEL));
	}

	private void delete(List<String> ids) {
		state.deleting = true;
		var recording = FlashbackBridge.recordingReplayId();
		var open = FlashbackBridge.openReplayId();
		String key = world.worldKey;
		String name = world.displayName;
		State shared = state;
		CompletableFuture.supplyAsync(() -> Recordings.delete(ids, recording, open), Util.backgroundExecutor())
			.thenAcceptAsync(gone -> {
				Recordings.forget(key, name, gone);
				shared.deleting = false;
				reload();
				shared.dirty = true;
			}, minecraft);
		state.dirty = true;
	}
}
