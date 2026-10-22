package com.survivalcinema.client.capture;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.director.event.EventLog;
import com.survivalcinema.director.event.GameEvent;
import com.survivalcinema.director.film.FilmStorage;
import com.survivalcinema.director.film.SessionSummary;
import com.survivalcinema.director.render.FilmFolder;
import com.survivalcinema.director.render.RenderProgress;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A world's recordings on disk: what each one is (for the list of what goes into the film) and deleting them to free
 * space. A recording is two things: Flashback's replay zip and our folder {@code sessions/<replay id>/} (events,
 * camera path, call voices). Reading and deleting run on a background thread; the world's memory is changed by the
 * caller on the client thread.
 */
public final class Recordings {
	private Recordings() {
	}

	/**
	 * What the world's sessions are, oldest first. Reads files: never on the render thread.
	 *
	 * @param live      the recording running now (no replay file yet), or null
	 * @param liveTicks how long it is so far
	 */
	public static List<SessionSummary> summaries(List<String> sessionIds, @Nullable UUID live, int liveTicks) {
		Map<UUID, FlashbackBridge.ReplayFile> replays = FlashbackBridge.replayFiles();
		List<SessionSummary> summaries = new ArrayList<>();
		for (String id : sessionIds) {
			UUID uuid = parse(id);
			if (uuid == null) {
				continue;
			}
			Path folder = SessionStore.sessionFolder(uuid);
			List<GameEvent> events;
			try {
				events = EventLog.read(folder.resolve(EventLog.FILE_NAME));
			} catch (IOException | RuntimeException e) {
				events = List.of();
			}
			FlashbackBridge.ReplayFile replay = replays.get(uuid);
			long bytes = FilmStorage.size(folder) + (replay == null ? 0 : FilmStorage.size(replay.zip()));
			boolean recording = uuid.equals(live);
			summaries.add(SessionSummary.of(id, events, recording ? liveTicks : replay == null ? 0 : replay.ticks(), bytes, replay != null,
				recording));
		}
		return summaries;
	}

	/** When the newest edited film of the world was planned (epoch ms), or 0 when there is none. */
	public static long lastFilmPlannedAt(String worldKey) {
		for (Path film : FilmStorage.filmFolders(SessionStore.filmsFolder(worldKey))) {
			RenderProgress progress = RenderProgress.read(FilmFolder.progressFile(film));
			if (progress != null && progress.film != null && !FilmFolder.needsWork(film, progress)) {
				return progress.edlCreatedAt;
			}
		}
		return 0;
	}

	/**
	 * Deletes these recordings: their replays and our data. The recording running right now and the replay open right
	 * now are never touched. Call on a background thread.
	 *
	 * @return the ids that are gone (the caller removes them from the world's memory)
	 */
	public static List<String> delete(Collection<String> sessionIds, @Nullable UUID recordingNow, @Nullable UUID openNow) {
		Map<UUID, Path> replays = FlashbackBridge.replaysById();
		Path replayRoot = FlashbackBridge.replayFolder();
		Path sessionRoot = SessionStore.root().resolve("sessions");
		List<String> gone = new ArrayList<>();
		long freed = 0;
		for (String id : sessionIds) {
			UUID uuid = parse(id);
			if (uuid == null || uuid.equals(recordingNow) || uuid.equals(openNow)) {
				continue;
			}
			try {
				Path replay = replays.get(uuid);
				if (replay != null) {
					freed += FilmStorage.delete(replay, replayRoot);
				}
				freed += FilmStorage.delete(SessionStore.sessionFolder(uuid), sessionRoot);
				gone.add(id);
			} catch (IOException | RuntimeException e) {
				SurvivalCinemaClient.LOGGER.warn("Could not delete the recording {}", id, e);
			}
		}
		SurvivalCinemaClient.LOGGER.info("Deleted {} recordings, {} MB freed", gone.size(), freed / (1024 * 1024));
		return gone;
	}

	/** Deletes the clips of the world's older edited films (their films stay). @return bytes freed */
	public static long deleteSpareClips(String worldKey) {
		Path films = SessionStore.filmsFolder(worldKey);
		long freed = 0;
		for (Path clips : FilmStorage.spareClipFolders(films)) {
			try {
				freed += FilmStorage.delete(clips, films);
			} catch (IOException | RuntimeException e) {
				SurvivalCinemaClient.LOGGER.warn("Could not delete the clips {}", clips, e);
			}
		}
		return freed;
	}

	/**
	 * Forgets deleted recordings in the world's memory: the one in use when the player is in that world (it is saved
	 * again later and must not bring them back), else the file. Client thread.
	 */
	public static void forget(String worldKey, String worldName, Collection<String> gone) {
		if (gone.isEmpty()) {
			return;
		}
		WorldMemory live = SurvivalCinemaClient.controller().world();
		WorldMemory world = live != null && live.worldKey.equals(worldKey) ? live : WorldMemory.load(worldKey, worldName);
		world.sessions.removeAll(gone);
		world.skippedSessions.removeAll(gone);
		world.save();
	}

	private static @Nullable UUID parse(String id) {
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException | NullPointerException e) {
			return null;
		}
	}
}
