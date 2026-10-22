package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.CompanionIO;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailIO;
import com.survivalcinema.director.event.EventLog;
import com.survivalcinema.director.event.GameEvent;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The film of the playthrough bot that plays like a player: it walks everywhere, chops a tree, digs a mine down to
 * diamonds, pillars up and falls, flies with an elytra, walks through the Nether and back, and bridges from the End
 * platform to the island before the dragon lands on the portal. The two sessions in
 * {@code src/test/resources/playthrough-walking}, recorded by the mod itself.
 */
class WalkingPlaythroughFilmTest {
	private static List<SessionData> sessions() throws Exception {
		List<SessionData> sessions = new ArrayList<>();
		for (int i = 1; i <= 2; i++) {
			Path folder = folder("session" + i);
			List<GameEvent> events = EventLog.read(folder.resolve(EventLog.FILE_NAME));
			Trail trail = new Trail(TrailIO.read(folder.resolve("trail.bin")));
			int length = trail.lastTick();
			for (GameEvent event : events) {
				length = Math.max(length, event.tick());
			}
			sessions.add(new SessionData("session" + i, i - 1, length, events, trail, CompanionIO.read(folder.resolve(CompanionIO.FILE_NAME))));
		}
		return sessions;
	}

	private static Path folder(String name) throws URISyntaxException {
		return Path.of(WalkingPlaythroughFilmTest.class.getResource("/playthrough-walking/" + name + "/" + EventLog.FILE_NAME).toURI())
			.getParent();
	}

	/**
	 * The arrival in the End is also where the dragon's boss bar appears: that one scene is a must-have twice over (the
	 * first arrival, the fight before the kill) and was filmed twice, the same ten seconds one after the other.
	 */
	@Test
	void noMomentIsShownTwice() throws Exception {
		EditDecisionList edl = FilmDirector.plan(sessions(), new FilmDirector.Options(300, "Cinema Playtest", "Player"));
		List<EditDecisionList.Item> clips = edl.clips();
		StringBuilder screenplay = new StringBuilder();
		for (EditDecisionList.Item c : clips) {
			screenplay.append(c.sessionIndex()).append(' ').append(c.eventType()).append(' ').append(c.startTick()).append('-')
				.append(c.endTick()).append('\n');
		}
		for (int i = 0; i < clips.size(); i++) {
			for (int j = i + 1; j < clips.size(); j++) {
				EditDecisionList.Item a = clips.get(i);
				EditDecisionList.Item b = clips.get(j);
				boolean overlap = a.sessionIndex() == b.sessionIndex() && a.startTick() < b.endTick() && b.startTick() < a.endTick();
				assertTrue(!overlap, "clips " + i + " and " + j + " show the same moment:\n" + screenplay);
			}
		}
	}
}
