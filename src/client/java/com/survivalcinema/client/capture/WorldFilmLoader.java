package com.survivalcinema.client.capture;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.director.camera.CompanionIO;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailIO;
import com.survivalcinema.director.event.EventLog;
import com.survivalcinema.director.event.GameEvent;
import com.survivalcinema.director.event.SafeFiles;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.SessionData;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads everything recorded for a world (every session's events, trail and companions) for the director,
 * and stores the resulting screenplay next to it.
 * <p>
 * Each part is read on its own, so one damaged file costs only that part, not the whole session. Sessions whose
 * Flashback replay no longer exists (cancelled, deleted) are left out: there would be no footage to film.
 */
public final class WorldFilmLoader {
	public static final String EDL_FILE = "edl.json";

	private WorldFilmLoader() {
	}

	/**
	 * @param sessionIds       the world's recordings, oldest first (a copy: this may run on a background thread)
	 * @param availableReplays ids of the replays that exist on disk
	 */
	public static List<SessionData> load(List<String> sessionIds, Set<UUID> availableReplays) {
		List<SessionData> sessions = new ArrayList<>();
		for (int i = 0; i < sessionIds.size(); i++) {
			String replayId = sessionIds.get(i);
			UUID id;
			try {
				id = UUID.fromString(replayId);
			} catch (IllegalArgumentException e) {
				continue;
			}
			if (!availableReplays.contains(id)) {
				continue;
			}
			Path folder = SessionStore.sessionFolder(id);
			List<GameEvent> events = readOrEmpty(() -> EventLog.read(folder.resolve(EventLog.FILE_NAME)), "events", replayId);
			Trail trail = new Trail(readOrEmpty(() -> TrailIO.read(folder.resolve(SessionStore.TRAIL_FILE)), "trail", replayId));
			Map<String, Trail> companions = readOrEmptyMap(() -> CompanionIO.read(folder.resolve(CompanionIO.FILE_NAME)), replayId);
			int length = trail.isEmpty() ? 0 : trail.lastTick();
			for (GameEvent event : events) {
				length = Math.max(length, event.tick());
			}
			if (length > 0) {
				sessions.add(new SessionData(replayId, i, length, events, trail, companions));
			}
		}
		return sessions;
	}

	private interface Reader<T> {
		T read() throws IOException;
	}

	private static <T> List<T> readOrEmpty(Reader<List<T>> reader, String what, String replayId) {
		try {
			return reader.read();
		} catch (IOException | RuntimeException e) {
			SurvivalCinemaClient.LOGGER.warn("Could not read the {} of session {}", what, replayId, e);
			return List.of();
		}
	}

	private static Map<String, Trail> readOrEmptyMap(Reader<Map<String, Trail>> reader, String replayId) {
		try {
			return reader.read();
		} catch (IOException | RuntimeException e) {
			SurvivalCinemaClient.LOGGER.warn("Could not read the companions of session {}", replayId, e);
			return Map.of();
		}
	}

	public static Path saveEdl(WorldMemory world, EditDecisionList edl) throws IOException {
		Path file = WorldMemory.folder(world.worldKey).resolve(EDL_FILE);
		SafeFiles.writeAtomically(file, edl.toJson());
		return file;
	}
}
