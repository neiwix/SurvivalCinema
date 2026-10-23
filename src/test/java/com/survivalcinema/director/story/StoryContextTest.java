package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.camera.Vec;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import com.survivalcinema.director.event.StructureKinds;
import com.survivalcinema.director.timelapse.BaseFinder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.survivalcinema.director.story.Worlds.ev;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a moment's story around it gets into the film: the way there, what comes right after, the fight before it. */
class StoryContextTest {
	private static Scene scene(SessionData session, GameEvent event, int start, int end, double score) {
		ScoredEvent primary = new ScoredEvent(session, event, score);
		return new Scene(session, start, end, primary, List.of(primary), score);
	}

	private static GameEvent at(int tick, EventType type, double x, double z) {
		return new GameEvent(tick, tick * 50L, type, "minecraft:pillager", "Pillager", "Steve", x, 64, z, "minecraft:overworld", 3,
			0.5, false, null);
	}

	/** Sailing east at 6 blocks per second, in a boat or swimming. */
	private static Trail sailing(int lengthTicks, int flags) {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= lengthTicks; t += 2) {
			samples.add(new TrailSample(t, t * 0.3, 62, 0, -90f, 0f, flags));
		}
		return new Trail(samples);
	}

	@Test
	void aLongBoatRideIsOneTake() {
		GameEvent ocean = ev(1000, EventType.NEW_BIOME, "minecraft:deep_ocean", 2, 0.6, true);
		SessionData boat = new SessionData("r", 0, 4000, List.of(ocean), sailing(4000, TrailSample.FLAG_IN_VEHICLE));
		List<Shot> shots = new ShotPlanner().plan(scene(boat, ocean, 960, 1300, 0.5));
		assertEquals(1, shots.size(), "a cut in the middle jumps the camera across the moving water");
		assertEquals(960, shots.getFirst().startTick());
		assertEquals(1300, shots.getFirst().endTick());

		SessionData swimming = new SessionData("r", 0, 4000, List.of(ocean), sailing(4000, TrailSample.FLAG_SWIMMING));
		assertEquals(2, new ShotPlanner().plan(scene(swimming, ocean, 960, 1300, 0.5)).size(), "a long scene on foot still gets two shots");
	}

	@Test
	void theFightBeforeABigKillLeadsUpToIt() {
		SessionData session = Worlds.session(0, 60_000, List.of());
		Scene golem = scene(session, at(50_231, EventType.KILL, -1459, 2378), 50_151, 50_261, 0.27);
		Scene pillager = scene(session, at(48_382, EventType.KILL, -1447, 2377), 48_302, 48_412, 0.24);
		assertTrue(StoryPlanner.leadsUpTo(pillager, golem), "the crossbowmen of the outpost, then its golem");
		assertFalse(StoryPlanner.leadsUpTo(golem, pillager), "what comes after is not the beginning");
		Scene elsewhere = scene(session, at(48_382, EventType.KILL, -1300, 2377), 48_302, 48_412, 0.24);
		assertFalse(StoryPlanner.leadsUpTo(elsewhere, golem), "a fight somewhere else");
		Scene longBefore = scene(session, at(44_000, EventType.KILL, -1447, 2377), 43_920, 44_030, 0.24);
		assertFalse(StoryPlanner.leadsUpTo(longBefore, golem), "five minutes before is another fight");
		Scene biome = scene(session, at(48_382, EventType.NEW_BIOME, -1447, 2377), 48_302, 48_412, 0.24);
		assertFalse(StoryPlanner.leadsUpTo(biome, golem), "only fighting leads up to a fight");
	}

	@Test
	void theOutpostFightIsShownBeforeItsGolem() {
		// one kill allowed for this film, two in the world before: the crossbowmen still come in, as the golem's fight
		List<GameEvent> events = new ArrayList<>();
		events.add(new GameEvent(1000, 50_000L, EventType.KILL, "minecraft:zombie", "Zombie", "Steve", 0, 64, 0,
			"minecraft:overworld", 1, 0.9, true, null));
		events.add(new GameEvent(20_000, 1_000_000L, EventType.KILL, "minecraft:pillager", "Pillager", "Steve", 900, 64, 0,
			"minecraft:overworld", 3, 0.5, true, null));
		events.add(new GameEvent(21_500, 1_075_000L, EventType.KILL, "minecraft:iron_golem", "Iron Golem", "Steve", 905, 64, 0,
			"minecraft:overworld", 3, 1.0, true, null));
		SessionData session = Worlds.session(0, 40_000, events);
		List<Scene> scenes = new SceneBuilder().build(new HighlightScorer().score(List.of(session)));
		List<Scene> film = new StoryPlanner().plan(List.of(session), scenes, 60).stream()
			.flatMap(c -> c.scenes().stream()).toList();
		List<String> kills = film.stream().map(s -> s.primary().event().subject()).filter(s -> s.contains(":")).toList();
		assertTrue(kills.contains("minecraft:iron_golem"), "the golem: " + kills);
		assertTrue(kills.contains("minecraft:pillager"), "and the fight before it: " + kills);
	}

	@Test
	void aShortFilmWithRoomTakesMoreOfTheStory() {
		// a quiet world: an advancement every three minutes and nothing else; six would leave half of a 5-minute film empty
		List<GameEvent> events = new ArrayList<>();
		String[] ids = {"mine_stone", "upgrade_tools", "smelt_iron", "iron_tools", "obtain_armor", "lava_bucket",
			"deflect_arrow", "form_obsidian", "mine_diamond", "enter_the_nether", "shiny_gear", "enchant_item"};
		for (int i = 0; i < ids.length; i++) {
			events.add(ev(1000 + i * 3600, EventType.ADVANCEMENT, "minecraft:story/" + ids[i], 1 + i / 3, 0.6, true));
		}
		SessionData session = Worlds.session(0, 50_000, events);
		List<Scene> scenes = new SceneBuilder().build(new HighlightScorer().score(List.of(session)));
		long advancements = new StoryPlanner().plan(List.of(session), scenes, 300).stream()
			.flatMap(c -> c.scenes().stream())
			.filter(s -> s.primary().event().type() == EventType.ADVANCEMENT)
			.count();
		assertTrue(advancements > StoryPlanner.cap(EventType.ADVANCEMENT, 300), advancements + " advancements");
	}

	/** Standing at spawn, looking up at a trunk from 300 to 600, the crafting table down at 780, then off. */
	private static Trail firstMinutes(int length) {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= length; t += 2) {
			boolean chopping = t >= 300 && t < 600;
			double x = t < 1200 ? 5 : 5 + (t - 1200) * 0.2;
			samples.add(new TrailSample(t, x, 70, 5, 0f, chopping ? -80f : 20f, TrailSample.FLAG_ON_GROUND));
		}
		return new Trail(samples);
	}

	private static GameEvent table(int tick) {
		return new GameEvent(tick, tick * 50L, EventType.BUILD_ACTIVITY, "build", "build", "Steve", 4, 70, 4, "minecraft:overworld", 1,
			0.01, false, "count=1;box=4,70,4,5,71,5;home=1;farm=0;bed=0");
	}

	@Test
	void theFilmOpensOnTheFirstTreeAndTheFirstCraftingTable() {
		SessionData session = new SessionData("r", 0, 20_000, List.of(ev(0, EventType.SESSION_START, 1), table(780)), firstMinutes(20_000));
		List<Scene> steps = StoryBeats.firstSteps(session);
		assertEquals(2, steps.size(), "the tree, then the table (not the standing around in between)");
		assertEquals(EventType.FIRST_STEPS, steps.getFirst().primary().event().type());
		assertTrue(steps.getFirst().startTick() <= 300 && steps.getFirst().endTick() > 400, "the chopping");
		assertTrue(steps.getLast().startTick() < 780 && steps.getLast().endTick() > 780, "putting the table down");

		SessionData quick = new SessionData("r", 0, 20_000, List.of(ev(0, EventType.SESSION_START, 1), table(470)), firstMinutes(20_000));
		assertEquals(1, StoryBeats.firstSteps(quick).size(), "chopped, then crafted on the spot: one scene");

		// recorded logs are taken over guessing from where the player looked
		GameEvent wood = new GameEvent(2000, 100_000L, EventType.WOOD_ACTIVITY, "wood", "wood", "Steve", 300, 70, 5,
			"minecraft:overworld", 1, 0.2, false, "count=6");
		SessionData recorded = new SessionData("r", 0, 20_000, List.of(ev(0, EventType.SESSION_START, 1), wood), firstMinutes(20_000));
		assertTrue(StoryBeats.firstSteps(recorded).stream().anyMatch(s -> s.primary().tick() == 2000));
	}

	@Test
	void theStartEndsAtTheFirstDeath() {
		List<GameEvent> events = List.of(ev(0, EventType.SESSION_START, 1), ev(250, EventType.DEATH, 1), table(780));
		assertTrue(StoryBeats.firstSteps(new SessionData("r", 0, 20_000, events, firstMinutes(20_000))).isEmpty());
	}

	/** Sailing towards a base 400 blocks east, landing 120 blocks short of it at 2000, walking the rest. */
	private static Trail crossing(int length) {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= length; t += 2) {
			boolean boat = t < 2000;
			double x = boat ? t * 0.14 : 280 + Math.min(110, (t - 2000) * 0.2);
			samples.add(new TrailSample(t, x, 63, 0, -90f, 0f, boat ? TrailSample.FLAG_IN_VEHICLE : TrailSample.FLAG_ON_GROUND));
		}
		return new Trail(samples);
	}

	private static BaseFinder.Base baseAt(double x) {
		return new BaseFinder.Base("minecraft:overworld", new Vec(x, 70, 0), 12, 66, 80, 300, List.of(), 3);
	}

	@Test
	void comingAshoreAtTheFutureHomeIsABeat() {
		SessionData session = new SessionData("r", 0, 6000, List.of(ev(0, EventType.SESSION_START, 1),
			ev(1900, EventType.NEW_BIOME, "minecraft:sunflower_plains", 2, 0.4, true)), crossing(6000));
		Scene arrival = StoryBeats.arrival(List.of(session), baseAt(400));
		assertTrue(arrival != null);
		assertEquals(2000, arrival.primary().tick(), "stepping off the boat");
		assertTrue(arrival.startTick() < 1900 && arrival.endTick() > 2080, "the boat reaching the shore, then the first steps");
		assertEquals(2, arrival.day());
		assertEquals(1, new ShotPlanner().plan(arrival).size(), "one take");

		assertTrue(StoryBeats.arrival(List.of(session), baseAt(5)) == null, "a home where the world began: the opening shows it");
		assertTrue(StoryBeats.arrival(List.of(session), null) == null);
	}

	@Test
	void theFirstShipwreckIsAlwaysInButNotEveryChest() {
		List<GameEvent> events = new ArrayList<>();
		// a world full of good moments, and a shipwreck looted three times, then a second one
		for (int i = 0; i < 30; i++) {
			events.add(ev(500 + i * 1500, EventType.ADVANCEMENT, "minecraft:story/a" + i, 1 + i / 4, 0.9, true));
		}
		events.add(new GameEvent(20_300, 0, EventType.STRUCTURE, "shipwreck", "Shipwreck", "Steve", 0, 64, 0, "minecraft:overworld", 4, 0.7, true, null));
		for (int i = 0; i < 3; i++) {
			events.add(new GameEvent(21_100 + i * 900, 0, EventType.LOOT, "shipwreck", "Shipwreck", "Steve", 0, 64, 0, "minecraft:overworld", 4,
				0.7, i == 0, null));
		}
		events.add(new GameEvent(40_100, 0, EventType.LOOT, "shipwreck", "Shipwreck", "Steve", 900, 64, 0, "minecraft:overworld", 7, 0.7, false, null));
		SessionData session = Worlds.session(0, 50_000, events);
		List<Scene> scenes = new SceneBuilder().build(new HighlightScorer().score(List.of(session)));
		List<Scene> film = new StoryPlanner().plan(List.of(session), scenes, 120).stream().flatMap(c -> c.scenes().stream()).toList();
		List<Integer> loot = film.stream().filter(s -> s.primary().event().type() == EventType.LOOT).map(s -> s.primary().tick()).toList();
		assertTrue(loot.contains(21_100), "the first look in its chest: " + loot);
		assertTrue(loot.size() <= StoryPlanner.cap(EventType.LOOT, 120), "not every chest: " + loot);
	}

	@Test
	void chestsAndStructuresAreKindsOfPlaces() {
		assertEquals("shipwreck", StructureKinds.ofLootTable("minecraft:chests/shipwreck_treasure"));
		assertEquals("shipwreck", StructureKinds.ofStructure("minecraft:shipwreck_beached"));
		assertEquals("village", StructureKinds.ofLootTable("minecraft:chests/village/village_weaponsmith"));
		assertEquals("village", StructureKinds.ofStructure("minecraft:village_plains"));
		assertEquals("pillager_outpost", StructureKinds.ofLootTable("minecraft:chests/pillager_outpost"));
		assertEquals("ocean_ruin", StructureKinds.ofLootTable("minecraft:chests/underwater_ruin_small"));
		assertEquals("trial_chambers", StructureKinds.ofLootTable("minecraft:chests/trial_chambers/reward"));
		assertEquals(null, StructureKinds.ofLootTable("minecraft:chests/spawn_bonus_chest"), "the bonus chest is no place");
		assertEquals(null, StructureKinds.ofStructure("minecraft:buried_treasure"), "buried: nothing to see");
		assertEquals(null, StructureKinds.ofLootTable("minecraft:entities/zombie"), "not a chest");
		assertEquals("Pillager outpost", StructureKinds.readable("pillager_outpost"));
	}

	@Test
	void travelFootageIsNeverADayOfItsOwn() {
		// a quiet world: moments on days 1 and 3 only, and plenty of walking all along
		List<GameEvent> events = new ArrayList<>(List.of(
			ev(2000, EventType.ADVANCEMENT, "minecraft:story/mine_stone", 1, 0.6, true),
			ev(30_000, EventType.ADVANCEMENT, "minecraft:story/smelt_iron", 3, 0.6, true)));
		for (int t = 0; t < 40_000; t += 4000) {
			events.add(ev(t + 100, EventType.NEW_DAY, "day", 1 + t / 14_000, 0.1, false));
		}
		SessionData session = Worlds.session(0, 40_000, events);
		List<Scene> scenes = new SceneBuilder().build(new HighlightScorer().score(List.of(session)));
		List<Chapter> chapters = new StoryPlanner().plan(List.of(session), scenes, new SceneryFinder().find(List.of(session)), 300);
		for (Chapter chapter : chapters) {
			assertTrue(chapter.scenes().stream().anyMatch(s -> s.primary().event().type() != EventType.SCENERY),
				"day " + chapter.dayFrom() + " is only travel footage");
		}
	}

	@Test
	void armourIsPutOnAfterItIsMade() {
		int[] armour = SceneBuilder.window(ev(1000, EventType.ADVANCEMENT, "minecraft:story/obtain_armor", 5, 0.6, true));
		int[] other = SceneBuilder.window(ev(1000, EventType.ADVANCEMENT, "minecraft:story/mine_stone", 1, 0.6, true));
		assertTrue(armour[1] >= 200, "putting it on and setting off: " + armour[1]);
		assertTrue(other[1] < armour[1]);
	}
}
