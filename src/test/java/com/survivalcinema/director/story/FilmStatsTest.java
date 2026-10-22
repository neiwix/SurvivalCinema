package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilmStatsTest {
	private static GameEvent event(int tick, long wallTime, EventType type, String subject, String dimension, int day, String detail) {
		return new GameEvent(tick, wallTime, type, subject, subject, "Steve", 0, 64, 0, dimension, day, 0.5, false, detail);
	}

	@Test
	void countsTheStoryOfTheWorldFromTheRecordings() {
		List<GameEvent> first = List.of(
			event(0, 1_000, EventType.SESSION_START, "session", "minecraft:overworld", 1, null),
			event(100, 2_000, EventType.KILL, "minecraft:zombie", "minecraft:overworld", 1, null),
			event(200, 3_000, EventType.KILL, "minecraft:skeleton", "minecraft:overworld", 1, null),
			event(300, 4_000, EventType.BUILD_ACTIVITY, "build", "minecraft:overworld", 1, "count=40"),
			event(400, 5_000, EventType.RARE_ITEM, "minecraft:diamond", "minecraft:overworld", 2, "3"),
			event(500, 6_000, EventType.OTHER_DEATH, "death.attack.lava", "minecraft:overworld", 2, "Alex"),
			event(600, 7_000, EventType.DEATH, "lava", "minecraft:overworld", 2, null));
		List<GameEvent> second = List.of(
			event(0, 50_000, EventType.SESSION_START, "session", "minecraft:overworld", 3, null),
			event(50, 51_000, EventType.DIMENSION_CHANGE, "minecraft:the_nether", "minecraft:the_nether", 3, null),
			event(60, 52_000, EventType.ADVANCEMENT, "minecraft:story/enter_the_nether", "minecraft:the_nether", 3, null),
			event(70, 53_000, EventType.BUILD_ACTIVITY, "build", "minecraft:the_nether", 3, "count=2"),
			event(80, 54_000, EventType.OTHER_DEATH, "death.fell", "minecraft:the_nether", 3, "Alex"),
			event(90, 55_000, EventType.DRAGON_KILLED, "minecraft:ender_dragon", "minecraft:the_end", 4, null));

		// 10 blocks of walking, then a 500 block jump through a portal that is not travel
		List<TrailSample> path = new ArrayList<>();
		for (int t = 0; t <= 50; t += 5) {
			path.add(new TrailSample(t, t * 0.2, 64, 0, 0, 0, 1));
		}
		path.add(new TrailSample(55, 510, 64, 0, 0, 0, 1));
		SessionData a = new SessionData("a", 0, 12_000, first, new Trail(path), Map.of("Alex", new Trail(List.of()), "Steve", new Trail(List.of())));
		SessionData b = new SessionData("b", 1, 6_000, second, null, Map.of("Bob", new Trail(List.of())));

		FilmStats stats = FilmStats.of(List.of(a, b), "Steve");
		assertEquals(1_000, stats.firstPlayed());
		assertEquals(55_000, stats.lastPlayed());
		assertEquals(2, stats.sessions());
		assertEquals(18_000, stats.playTicks());
		assertEquals(4, stats.days());
		assertEquals(1, stats.deaths());
		assertEquals(2, stats.kills());
		assertEquals(42, stats.blocksPlaced());
		assertEquals(3, stats.diamonds());
		assertEquals(1, stats.advancements());
		assertEquals(10, stats.distanceBlocks());
		assertEquals(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"), stats.dimensions());
		assertTrue(stats.visited("minecraft:the_nether"));
		assertTrue(stats.dragonKilled());
		// friends: who died near us and who was around; never ourselves
		assertEquals(List.of(new FilmStats.Friend("Alex", 2), new FilmStats.Friend("Bob", 0)), stats.friends());
	}

	@Test
	void anEmptyWorldHasNothingToBrag() {
		FilmStats stats = FilmStats.of(List.of(), "Steve");
		assertEquals(0, stats.firstPlayed());
		assertEquals(1, stats.days());
		assertFalse(stats.dragonKilled());
		assertTrue(stats.friends().isEmpty());
	}

	@Test
	void theScreenplayCarriesTheStatsAndOldScreenplaysStillLoad() {
		FilmStats stats = FilmStats.of(List.of(), "Steve");
		EditDecisionList edl = new EditDecisionList(EditDecisionList.VERSION, "Мир", "Steve", 300, 5,
			List.of(EditDecisionList.Item.card(EditDecisionList.Kind.INTRO, 4, 0, 0, null, null)), stats);
		EditDecisionList loaded = EditDecisionList.fromJson(edl.toJson());
		assertNotNull(loaded.stats());
		assertEquals(stats, loaded.stats());

		String old = "{\"version\":1,\"worldName\":\"w\",\"playerName\":\"p\",\"targetSeconds\":300,\"createdAt\":1,"
			+ "\"items\":[{\"kind\":\"CLIP\",\"cardSeconds\":0,\"dayFrom\":1,\"dayTo\":1,\"replayId\":\"r\",\"sessionIndex\":0,"
			+ "\"startTick\":10,\"endTick\":50,\"shot\":\"ORBIT\",\"slowMotionFromTick\":-1,\"eventType\":\"KILL\",\"score\":1}]}";
		EditDecisionList legacy = EditDecisionList.fromJson(old);
		assertEquals(null, legacy.stats());
		assertFalse(legacy.items().getFirst().voice());
		assertEquals(50, legacy.items().getFirst().endTick());
	}
}
