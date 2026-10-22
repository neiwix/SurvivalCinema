package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.CompanionIO;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailIO;
import com.survivalcinema.director.event.EventLog;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import com.survivalcinema.director.render.RenderPlan;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The film of a real game: the two sessions the playthrough test's bot played (in {@code src/test/resources/playthrough},
 * recorded by the mod itself). The bot plays fast: it goes through the Nether, back, and to the End and kills the dragon
 * within seconds, the way a player rushing through a portal would.
 */
class PlaythroughFilmTest {
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
		return Path.of(PlaythroughFilmTest.class.getResource("/playthrough/" + name + "/" + EventLog.FILE_NAME).toURI()).getParent();
	}

	@Test
	void everyStoryBeatOfThePlaythroughIsInTheFilm() throws Exception {
		EditDecisionList edl = FilmDirector.plan(sessions(), new FilmDirector.Options(300, "Cinema Playtest", "Player"));
		List<String> chapters = edl.items().stream().filter(i -> i.kind() == EditDecisionList.Kind.CHAPTER)
			.map(i -> String.valueOf(i.dimension())).toList();
		List<EditDecisionList.Item> clips = edl.clips();
		StringBuilder screenplay = new StringBuilder();
		for (EditDecisionList.Item item : edl.items()) {
			screenplay.append(item.kind()).append(' ').append(item.dimension()).append(' ').append(item.eventType()).append(' ')
				.append(item.label()).append(' ').append(item.startTick()).append('-').append(item.endTick()).append('\n');
		}
		assertEquals(List.of("null", "null", "minecraft:the_nether", "survivalcinema:base", "minecraft:the_end"), chapters,
			"day 1, day 2, the first Nether trip, our base, the End:\n" + screenplay);
		assertNotNull(edl.timelapse(), "the house built over two sessions is the base");
		assertTrue(clips.stream().anyMatch(c -> c.eventType() == EventType.DIMENSION_CHANGE && "minecraft:the_nether".equals(c.dimension())),
			"the arrival in the Nether is shown:\n" + screenplay);
		EditDecisionList.Item last = clips.getLast();
		assertEquals("minecraft:the_end", last.dimension(), "the dragon is the finale:\n" + screenplay);
		assertTrue(clips.stream().anyMatch(c -> c.eventType() == EventType.DEATH), "the death is in:\n" + screenplay);
		assertTrue(RenderPlan.of(edl).size() >= 6, "clips and timelapse pictures: " + RenderPlan.of(edl).size());
	}

	/**
	 * The bot hits the dragon at tick 742 as it arrives; the dragon flies back to the portal and its bar disappears at
	 * 1196, so it was dying from 996. The film shows the blow and the flight at normal speed, then cuts to the whole
	 * death animation through to the explosion, in slow motion.
	 */
	@Test
	void theDragonDiesInSlowMotionAfterTheBlow() throws Exception {
		EditDecisionList edl = FilmDirector.plan(sessions(), new FilmDirector.Options(300, "Cinema Playtest", "Player"));
		List<EditDecisionList.Item> finale = edl.clips().stream().filter(c -> c.eventType() == EventType.DRAGON_KILLED).toList();
		String shots = finale.stream().map(c -> c.startTick() + "-" + c.endTick() + " slow " + c.slowMotionFromTick()).toList().toString();
		assertEquals(2, finale.size(), "the blow and the death: " + shots);
		EditDecisionList.Item blow = finale.get(0);
		EditDecisionList.Item death = finale.get(1);
		// the blow came on the tick the bot arrived, so the footage starts with the arrival
		assertTrue(blow.startTick() <= 744 && blow.endTick() < 996, "the blow and the flight: " + shots);
		assertEquals(blow.endTick(), death.startTick(), "nothing of the flight left out: " + shots);
		assertEquals(-1, blow.slowMotionFromTick(), "the blow and the flight at normal speed: " + shots);
		assertEquals(996, death.slowMotionFromTick(), "slow motion from the first beam of light: " + shots);
		assertTrue(death.startTick() < 996 && death.endTick() > 1196, "the whole death animation and the explosion: " + shots);
	}
}
