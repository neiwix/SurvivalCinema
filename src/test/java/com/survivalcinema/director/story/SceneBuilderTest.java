package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.survivalcinema.director.story.Worlds.ev;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneBuilderTest {
	private static List<Scene> scenes(SessionData session) {
		return new SceneBuilder().build(new HighlightScorer().score(List.of(session)));
	}

	@Test
	void aDeathGetsTheLeadUpBeforeIt() {
		SessionData session = Worlds.session(0, 20_000, List.of(ev(1000, EventType.DEATH, 1)));
		List<Scene> scenes = scenes(session);
		assertEquals(1, scenes.size());
		assertEquals(840, scenes.getFirst().startTick());
		assertEquals(1050, scenes.getFirst().endTick());
	}

	@Test
	void momentsCloseTogetherBecomeOneScene() {
		SessionData session = Worlds.session(0, 20_000, List.of(
			ev(1000, EventType.KILL, "minecraft:zombie", 1, 0.2, false),
			ev(1060, EventType.KILL, "minecraft:enderman", 1, 0.9, true)));
		List<Scene> scenes = scenes(session);
		assertEquals(1, scenes.size());
		assertEquals(2, scenes.getFirst().events().size());
		assertEquals("minecraft:enderman", scenes.getFirst().primary().event().subject());
	}

	@Test
	void scenesStayInsideTheSession() {
		SessionData session = Worlds.session(0, 1100, List.of(
			ev(30, EventType.ADVANCEMENT, 1),
			ev(1090, EventType.RARE_ITEM, 1)));
		for (Scene scene : scenes(session)) {
			assertTrue(scene.startTick() >= 0 && scene.endTick() <= 1100);
		}
	}

	@Test
	void aSceneNeverRunsAcrossARespawn() {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= 4000; t += 2) {
			double x = t <= 1020 ? t * 0.1 : 800 + t * 0.1; // respawned far away at tick 1022
			samples.add(new TrailSample(t, x, 64, 0, 0f, 0f, 0));
		}
		List<GameEvent> events = List.of(ev(0, EventType.SESSION_START, 1), ev(1000, EventType.DEATH, 1));
		SessionData session = new SessionData("r", 0, 4000, events, new Trail(samples));
		Scene scene = scenes(session).getFirst();
		assertTrue(scene.endTick() <= 1020, "scene should end at the respawn, ended at " + scene.endTick());
	}

	private static Scene sceneAt(List<Scene> scenes, int tick) {
		return scenes.stream().filter(s -> s.startTick() <= tick && tick <= s.endTick()).findFirst().orElseThrow();
	}

	@Test
	void voicesDuringABigMomentBeatEitherAlone() {
		SessionData session = Worlds.session(0, 40_000, List.of(
			ev(2000, EventType.BOSS_FIGHT_START, "wither", 1, 0.8, false),
			ev(2040, EventType.VOICE_PEAK, "voice", 1, 0.9, false),
			ev(12000, EventType.BOSS_FIGHT_START, "wither", 1, 0.8, false),
			ev(22000, EventType.VOICE_PEAK, "voice", 1, 0.9, false)));
		List<Scene> scenes = scenes(session);
		double combo = sceneAt(scenes, 2000).score();
		double bossAlone = sceneAt(scenes, 12000).score();
		double voiceAlone = sceneAt(scenes, 22000).score();
		assertTrue(combo > 1.5 * bossAlone, "combo " + combo + " vs boss " + bossAlone);
		assertTrue(combo > 1.5 * voiceAlone, "combo " + combo + " vs voice " + voiceAlone);
		assertTrue(voiceAlone > bossAlone, "a loud moment should beat a quiet boss fight start");
	}

	@Test
	void manyKindsAtOnceMakeABetterScene() {
		SessionData session = Worlds.session(0, 40_000, List.of(
			ev(2000, EventType.NEAR_DEATH, "near_death", 1, 0.8, false),
			ev(2050, EventType.KILL_STREAK, "kill_streak", 1, 0.8, false),
			ev(2090, EventType.RARE_ITEM, "minecraft:diamond", 1, 0.6, false),
			ev(12000, EventType.NEAR_DEATH, "near_death", 1, 0.8, false)));
		List<Scene> scenes = scenes(session);
		assertTrue(sceneAt(scenes, 2000).score() > sceneAt(scenes, 12000).score() + 0.3);
	}

	@Test
	void aLaterMomentKeepsItsOwnLeadIn() {
		SessionData session = Worlds.session(0, 20_000, List.of(
			ev(1000, EventType.NEW_BIOME, "minecraft:desert", 1, 0.4, true),
			ev(1010, EventType.VOICE_PEAK, "voice", 1, 0.9, false)));
		Scene scene = sceneAt(scenes(session), 1010);
		assertTrue(scene.startTick() <= 1010 - 140, "the scream lost its lead-up: scene starts at " + scene.startTick());
	}

	@Test
	void aMomentRightInAPortalJumpShowsTheArrival() {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= 4000; t += 2) {
			int dimension = t <= 1000 ? 0 : 1; // stepped through a Nether portal between tick 1000 and 1002
			samples.add(new TrailSample(t, dimension == 0 ? t * 0.1 : t * 0.1 / 8, 64, 0, 0f, 0f, dimension << 8));
		}
		List<GameEvent> events = List.of(ev(0, EventType.SESSION_START, 1),
			ev(1001, EventType.DIMENSION_CHANGE, "minecraft:the_nether", 1, 1.0, true));
		Scene scene = scenes(new SessionData("r", 0, 4000, events, new Trail(samples))).getFirst();
		assertTrue(scene.endTick() >= 1002 + 100, "the arrival is shown: ends at " + scene.endTick());
		// walking up to the portal and standing in its swirl, then the film cuts on the jump
		assertTrue(scene.startTick() <= 1000 - 140, "the way into the portal is shown too: starts at " + scene.startTick());
		assertEquals(1, new ShotPlanner().plan(scene).size(), "one shot: the jump is the cut");
	}

	@Test
	void anArrivalByTeleportAfterADeathKeepsOnlyTheArrival() {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= 4000; t += 2) {
			samples.add(new TrailSample(t, t <= 1000 ? t * 0.1 : 5000, 64, 0, 0f, 0f, t <= 1000 ? 0 : 1 << 8));
		}
		// a death right before is a moment of its own, not the way into anything
		List<GameEvent> events = List.of(ev(0, EventType.SESSION_START, 1),
			ev(1001, EventType.NEW_BIOME, "minecraft:nether_wastes", 1, 1.0, true));
		Scene scene = scenes(new SessionData("r", 0, 4000, events, new Trail(samples))).getFirst();
		assertTrue(scene.startTick() >= 1002, "only an arrival through a portal keeps the way into it: " + scene.startTick());
	}

	@Test
	void busyMomentsDontMakeEndlessScenes() {
		List<GameEvent> events = new ArrayList<>();
		for (int t = 200; t < 6000; t += 50) {
			events.add(ev(t, EventType.KILL, "minecraft:zombie", 1, 0.5, false));
		}
		for (Scene scene : scenes(Worlds.session(0, 7000, events))) {
			assertTrue(scene.durationTicks() <= SceneBuilder.MAX_SCENE_TICKS);
		}
	}
}
