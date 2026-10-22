package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.ShotType;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.survivalcinema.director.story.Worlds.ev;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilmDirectorTest {
	private static EditDecisionList plan(List<SessionData> sessions, int seconds) {
		return FilmDirector.plan(sessions, new FilmDirector.Options(seconds, "Test World", "Steve"));
	}

	@Test
	void fillsTheRequestedLength() {
		// busyWorld is only 100 minutes of random fights, so longer films run out of good material on purpose;
		// RealisticFilmTest checks full lengths on a real-sized playthrough.
		for (int seconds : new int[]{120, 300}) {
			EditDecisionList edl = plan(Worlds.busyWorld(), seconds);
			double total = edl.totalSeconds();
			assertTrue(total >= seconds * 0.8 && total <= seconds * 1.06, seconds + " s film came out " + total + " s");
		}
	}

	@Test
	void runsOutOfGoodMaterialRatherThanPadding() {
		EditDecisionList edl = plan(Worlds.busyWorld(), 1200);
		assertTrue(edl.totalSeconds() < 1200 * 0.8, "a 100 minute world of random fights should not fill 20 minutes");
		assertTrue(edl.totalSeconds() > 180, "but it should still make a proper film");
	}

	@Test
	void startsWithIntroEndsWithCredits() {
		List<EditDecisionList.Item> items = plan(Worlds.busyWorld(), 300).items();
		assertEquals(EditDecisionList.Kind.INTRO, items.getFirst().kind());
		assertEquals(EditDecisionList.Kind.CHAPTER, items.get(1).kind());
		assertEquals(EditDecisionList.Kind.CREDITS, items.getLast().kind());
	}

	@Test
	void clipsAreInChronologicalOrder() {
		List<EditDecisionList.Item> clips = plan(Worlds.busyWorld(), 600).clips();
		for (int i = 1; i < clips.size(); i++) {
			EditDecisionList.Item a = clips.get(i - 1);
			EditDecisionList.Item b = clips.get(i);
			assertTrue(a.sessionIndex() < b.sessionIndex() || (a.sessionIndex() == b.sessionIndex() && a.startTick() <= b.startTick()),
				"clip " + i + " goes back in time");
		}
	}

	@Test
	void keepsTheOpeningTheFirstNightAndTheDragonEvenInAShortFilm() {
		List<EditDecisionList.Item> clips = plan(Worlds.busyWorld(), 60).clips();
		Set<EventType> types = clips.stream().map(EditDecisionList.Item::eventType).collect(Collectors.toSet());
		assertTrue(types.contains(EventType.SESSION_START), "opening missing");
		assertTrue(types.contains(EventType.NIGHT_FALL), "first night missing");
		assertTrue(types.contains(EventType.DRAGON_KILLED), "dragon missing");
		assertEquals(EventType.SESSION_START, clips.getFirst().eventType());
	}

	@Test
	void keepsVarietyWhenOneThingHappensALot() {
		List<GameEvent> events = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			events.add(ev(600 + i * 400, EventType.DEATH, "death", 1 + i / 10, 1.0, i == 0));
		}
		events.add(ev(3_100, EventType.ADVANCEMENT, "a", 1, 0.5, false));
		events.add(ev(7_100, EventType.RARE_ITEM, "b", 2, 0.5, false));
		events.add(ev(11_100, EventType.KILL, "c", 3, 0.5, false));
		events.add(ev(15_100, EventType.NEW_BIOME, "d", 4, 0.5, true));
		events.add(ev(19_100, EventType.ELYTRA_FLIGHT, "e", 5, 0.5, true));
		events.sort(java.util.Comparator.comparingInt(GameEvent::tick));
		List<EditDecisionList.Item> clips = plan(List.of(Worlds.session(0, 30_000, events)), 180).clips();

		Map<EventType, Long> counts = clips.stream().collect(Collectors.groupingBy(EditDecisionList.Item::eventType, Collectors.counting()));
		assertTrue(counts.keySet().size() >= 5, "too little variety: " + counts);
		assertTrue(counts.getOrDefault(EventType.DEATH, 0L) <= clips.size() * 0.7, "too many deaths: " + counts);
	}

	@Test
	void hoursOfFightingDontCrowdOutTheGoodMoments() {
		List<GameEvent> events = new ArrayList<>();
		for (int t = 300; t < 70_000; t += 120) {
			events.add(ev(t, EventType.BIG_DAMAGE, "damage", 1 + t / 24_000, 0.7, false));
			if (t % 600 == 300) {
				events.add(ev(t + 40, EventType.KILL, "minecraft:zombie", 1 + t / 24_000, 0.2, false));
			}
		}
		int[] voiceTicks = {5_000, 15_000, 25_000, 35_000, 45_000, 55_000, 65_000};
		for (int t : voiceTicks) {
			events.add(ev(t + 50, EventType.VOICE_PEAK, "voice", 1 + t / 24_000, 0.9, false));
		}
		events.add(ev(30_000, EventType.ADVANCEMENT, "minecraft:story/mine_diamond", 2, 0.7, true));
		events.add(ev(50_000, EventType.DIMENSION_CHANGE, "minecraft:the_nether", 3, 1.0, true));
		events.sort(java.util.Comparator.comparingInt(GameEvent::tick));
		EditDecisionList edl = plan(List.of(Worlds.session(0, 72_000, events)), FilmLength.DEFAULT.seconds());

		List<EditDecisionList.Item> clips = edl.clips();
		for (int t : voiceTicks) {
			assertTrue(clips.stream().anyMatch(c -> c.startTick() <= t + 50 && t + 50 <= c.endTick()), "voice moment at " + t + " left out");
		}
		assertTrue(clips.stream().anyMatch(c -> c.eventType() == EventType.ADVANCEMENT), "first diamonds left out");
		assertTrue(clips.stream().anyMatch(c -> c.eventType() == EventType.DIMENSION_CHANGE), "Nether left out");
		long damage = clips.stream().filter(c -> c.eventType() == EventType.BIG_DAMAGE).count();
		assertTrue(damage <= clips.size() / 2, damage + " of " + clips.size() + " clips are just damage");
	}

	@Test
	void defaultLengthIsFiveMinutes() {
		assertEquals(300, FilmLength.DEFAULT.seconds());
		assertEquals(FilmLength.TEN, FilmLength.FIVE.next());
		assertEquals(FilmLength.FIVE, FilmLength.TWENTY.next());
		assertEquals(FilmLength.FIVE, FilmLength.ofMinutes(7));
	}

	@Test
	void chaptersFollowTheDaysAndTheNetherGetsItsOwn() {
		EditDecisionList edl = plan(Worlds.busyWorld(), 600);
		List<EditDecisionList.Item> chapters = edl.items().stream().filter(i -> i.kind() == EditDecisionList.Kind.CHAPTER).toList();
		assertTrue(chapters.size() >= 2 && chapters.size() <= StoryPlanner.maxChapters(600), "chapters: " + chapters.size());
		for (int i = 1; i < chapters.size(); i++) {
			assertTrue(chapters.get(i).dayFrom() >= chapters.get(i - 1).dayTo(), "chapters go back in days");
		}
		assertTrue(chapters.stream().anyMatch(c -> "minecraft:the_nether".equals(c.dimension())), "no Nether chapter");
		assertNotNull(chapters.getFirst().subtitle());
	}

	/** A scene of one moment in a dimension (the playthrough test's dragon: the arrival in the End is not in the film). */
	private static Scene scene(SessionData session, int tick, EventType type, String dimension, int day) {
		String subject = type == EventType.DIMENSION_CHANGE ? dimension : type.name();
		GameEvent event = new GameEvent(tick, tick * 50L, type, subject, type.name(), "Steve", 0, 64, 0, dimension, day, 0.8, true, null);
		ScoredEvent scored = new ScoredEvent(session, event, 1);
		return new Scene(session, tick - 100, tick + 100, scored, List.of(scored), 1);
	}

	@Test
	void theDragonFightOpensTheEndChapterEvenWithoutTheArrival() {
		SessionData session = new SessionData("r", 0, 30_000, List.of(), Worlds.standing(30_000));
		List<Scene> scenes = List.of(
			scene(session, 1000, EventType.KILL_STREAK, "minecraft:overworld", 1),
			scene(session, 9000, EventType.DIMENSION_CHANGE, "minecraft:the_nether", 2),
			scene(session, 12000, EventType.KILL, "minecraft:the_nether", 2),
			scene(session, 20000, EventType.DRAGON_KILLED, "minecraft:the_end", 2));
		List<Chapter> chapters = StoryPlanner.chapters(scenes, 2);
		assertEquals(Arrays.asList(null, "minecraft:the_nether", "minecraft:the_end"), chapters.stream().map(Chapter::dimension).toList(),
			"a first look at the Nether and at the End keep their cards even in a short film");
		assertEquals(List.of(scenes.getLast()), chapters.getLast().scenes(), "the dragon is in the End chapter, not in the Nether one");
		assertEquals(2, FilmDirector.timelapseChapter(chapters), "the base's story comes before the End, the dragon stays the finale");
	}

	/**
	 * Setting the eyes of ender in the frame at the base, then jumping in: the base's timelapse goes before the End
	 * chapter and cut that in half (the walk to the portal stood before the timelapse, the End after it). The walk goes
	 * over to the End chapter; coming back from the Nether before it stays where it was.
	 */
	@Test
	void theWayIntoTheEndIsNotCutOffByTheTimelapse() {
		SessionData session = new SessionData("r", 0, 30_000, List.of(), Worlds.standing(30_000));
		Scene back = scene(session, 14_000, EventType.DIMENSION_CHANGE, "minecraft:overworld", 2);
		Scene eyes = scene(session, 15_000, EventType.RARE_ITEM, "minecraft:overworld", 2);
		Scene dragon = scene(session, 15_500, EventType.DRAGON_KILLED, "minecraft:the_end", 2);
		List<Scene> scenes = List.of(
			scene(session, 1000, EventType.KILL_STREAK, "minecraft:overworld", 1),
			scene(session, 9000, EventType.DIMENSION_CHANGE, "minecraft:the_nether", 2),
			scene(session, 12000, EventType.KILL, "minecraft:the_nether", 2),
			back, eyes, dragon);
		List<Chapter> chapters = StoryPlanner.chapters(scenes, 2);
		int end = FilmDirector.timelapseChapter(chapters);
		assertTrue(chapters.get(end - 1).scenes().contains(eyes), "test setup: the eyes end the chapter before the End");

		List<Chapter> moved = FilmDirector.withLeadIn(chapters, end);
		Chapter endChapter = moved.get(FilmDirector.timelapseChapter(moved));
		assertEquals(List.of(eyes, dragon), endChapter.scenes(), "the way to the portal opens the End chapter");
		assertTrue(moved.stream().anyMatch(c -> c.scenes().contains(back)), "coming back from the Nether is still in");
		assertFalse(moved.get(FilmDirector.timelapseChapter(moved) - 1).scenes().contains(eyes));
	}

	@Test
	void neverUsesTheSameShotTwiceInARow() {
		List<EditDecisionList.Item> clips = plan(Worlds.busyWorld(), 600).clips();
		Set<ShotType> used = new HashSet<>();
		for (int i = 1; i < clips.size(); i++) {
			assertNotEquals(clips.get(i - 1).shot(), clips.get(i).shot(), "same shot twice at clip " + i);
			used.add(clips.get(i).shot());
		}
		assertTrue(used.size() >= 4, "only used " + used);
	}

	@Test
	void bigMomentsGetSlowMotion() {
		List<EditDecisionList.Item> clips = plan(Worlds.busyWorld(), 300).clips();
		EditDecisionList.Item dragon = clips.stream().filter(c -> c.eventType() == EventType.DRAGON_KILLED && c.slowMotionFromTick() >= 0)
			.findFirst().orElseThrow();
		assertTrue(dragon.filmSeconds() > (dragon.endTick() - dragon.startTick()) / 20.0, "slow motion should make the clip longer");
	}

	@Test
	void doesNotChaseSomeoneStandingStill() {
		List<GameEvent> events = List.of(ev(0, EventType.SESSION_START, 1), ev(1000, EventType.KILL, "minecraft:zombie", 1, 0.5, false));
		SessionData session = new SessionData("r", 1, 3000, events, Worlds.standing(3000));
		Scene scene = new SceneBuilder().build(new HighlightScorer().score(List.of(session))).getFirst();
		for (Shot shot : new ShotPlanner().plan(scene)) {
			assertFalse(shot.type() == ShotType.CHASE || shot.type() == ShotType.SIDE, "chased a player standing still");
		}
	}

	@Test
	void longScenesGetTwoShotsAndTheClimaxIsNotCut() {
		List<GameEvent> events = List.of(ev(0, EventType.SESSION_START, 1),
			ev(1000, EventType.BOSS_FIGHT_START, "wither", 1, 0.8, true),
			ev(1200, EventType.KILL, "minecraft:wither_skeleton", 1, 0.9, true),
			ev(1290, EventType.KILL, "minecraft:wither_skeleton", 1, 0.9, false));
		Scene scene = new SceneBuilder().build(new HighlightScorer().score(List.of(Worlds.session(1, 5000, events)))).stream()
			.filter(s -> s.startTick() > 0).findFirst().orElseThrow();
		assertTrue(scene.durationTicks() > ShotPlanner.SPLIT_ABOVE_TICKS, "test setup: scene should be long");
		List<Shot> shots = new ShotPlanner().plan(scene);
		assertEquals(2, shots.size());
		int cut = shots.getFirst().endTick();
		assertTrue(Math.abs(cut - scene.primary().tick()) >= 40, "cut right on the main moment");
	}

	@Test
	void screenplayRoundTripsThroughJson() {
		EditDecisionList edl = plan(Worlds.busyWorld(), 300);
		EditDecisionList back = EditDecisionList.fromJson(edl.toJson());
		assertEquals(edl, back);
	}

	@Test
	void anEmptyWorldStillMakesAValidFilm() {
		EditDecisionList edl = plan(List.of(Worlds.session(0, 2000, List.of())), 300);
		assertEquals(EditDecisionList.Kind.INTRO, edl.items().getFirst().kind());
		assertEquals(EditDecisionList.Kind.CREDITS, edl.items().getLast().kind());
	}
}
