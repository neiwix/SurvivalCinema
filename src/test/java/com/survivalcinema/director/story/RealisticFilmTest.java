package com.survivalcinema.director.story;

import com.survivalcinema.director.event.EventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The film of a simulated 30 hour survival playthrough should feel like a film: the right length, the story told
 * from start to finish, the funny moments in, and the thousands of ordinary hits and kills out.
 */
class RealisticFilmTest {
	private static final Set<EventType> SMALL_STUFF = EnumSet.of(EventType.BIG_DAMAGE, EventType.KILL, EventType.KILL_STREAK,
		EventType.BUILD_BURST, EventType.SLEEP, EventType.NEW_DAY);

	private static EditDecisionList film(RealisticWorld world, FilmLength length) {
		return FilmDirector.plan(world.sessions, new FilmDirector.Options(length.seconds(), "SMP", "Steve"));
	}

	private static double clipSeconds(List<EditDecisionList.Item> clips, java.util.function.Predicate<EditDecisionList.Item> filter) {
		return clips.stream().filter(filter).mapToDouble(EditDecisionList.Item::filmSeconds).sum();
	}

	@ParameterizedTest
	@EnumSource(FilmLength.class)
	void aLongPlaythroughMakesAFullFilmOfTheRightLength(FilmLength length) {
		for (long seed = 1; seed <= 3; seed++) {
			EditDecisionList edl = film(RealisticWorld.playthrough(seed), length);
			double total = edl.totalSeconds();
			assertTrue(total >= length.seconds() * 0.85 && total <= length.seconds() * 1.06,
				length + " seed " + seed + ": film is " + total + " s");
		}
	}

	@ParameterizedTest
	@EnumSource(FilmLength.class)
	void smallStuffStaysSmall(FilmLength length) {
		EditDecisionList edl = film(RealisticWorld.playthrough(7), length);
		List<EditDecisionList.Item> clips = edl.clips();
		double small = clipSeconds(clips, c -> SMALL_STUFF.contains(c.eventType()));
		double all = clipSeconds(clips, c -> true);
		assertTrue(small <= all * 0.15, length + ": " + Math.round(small) + " of " + Math.round(all) + " s are hits and kills");
	}

	@ParameterizedTest
	@EnumSource(FilmLength.class)
	void tellsTheWholeStoryFromStartToFinish(FilmLength length) {
		RealisticWorld world = RealisticWorld.playthrough(11);
		List<EditDecisionList.Item> clips = film(world, length).clips();
		int sessions = world.sessions.size();
		// Every quarter of the playthrough is in the film.
		for (int quarter = 0; quarter < 4; quarter++) {
			int from = quarter * sessions / 4;
			int to = (quarter + 1) * sessions / 4;
			assertTrue(clips.stream().anyMatch(c -> c.sessionIndex() >= from && c.sessionIndex() < to),
				length + ": nothing from sessions " + from + "-" + (to - 1));
		}
		assertTrue(clips.getFirst().eventType() == EventType.SESSION_START, "should open on the very beginning");
		EditDecisionList.Item last = clips.getLast();
		assertTrue(last.sessionIndex() == sessions - 1, "should end at the dragon");
	}

	@ParameterizedTest
	@EnumSource(FilmLength.class)
	void theFinaleIsBig(FilmLength length) {
		List<EditDecisionList.Item> clips = film(RealisticWorld.playthrough(5), length).clips();
		double dragon = clipSeconds(clips, c -> c.eventType() == EventType.DRAGON_KILLED);
		assertTrue(dragon >= 18, length + ": dragon kill gets only " + dragon + " s");
		assertTrue(clips.stream().anyMatch(c -> c.eventType() == EventType.BOSS_FIGHT_START || c.label().contains("ender_dragon")
			|| (c.sessionIndex() == clips.getLast().sessionIndex() && c.eventType() != EventType.DRAGON_KILLED)), length + ": no dragon fight before the kill");
	}

	@ParameterizedTest
	@EnumSource(FilmLength.class)
	void theStoryBeatsAreIn(FilmLength length) {
		List<EditDecisionList.Item> clips = film(RealisticWorld.playthrough(3), length).clips();
		boolean diamonds = clips.stream().anyMatch(c -> c.label() != null && c.label().contains("diamond"));
		boolean nether = clips.stream().anyMatch(c -> c.eventType() == EventType.DIMENSION_CHANGE && c.label().contains("the_nether"));
		boolean end = clips.stream().anyMatch(c -> c.eventType() == EventType.DIMENSION_CHANGE && c.label().contains("the_end"));
		int beats = (diamonds ? 1 : 0) + (nether ? 1 : 0) + (end ? 1 : 0);
		int needed = length == FilmLength.FIVE ? 2 : 3;
		assertTrue(beats >= needed, length + ": diamonds=" + diamonds + " nether=" + nether + " end=" + end);
	}

	@ParameterizedTest
	@EnumSource(FilmLength.class)
	void voicesLeadButDontTakeOver(FilmLength length) {
		EditDecisionList edl = film(RealisticWorld.playthrough(13), length);
		List<EditDecisionList.Item> clips = edl.clips();
		double all = clipSeconds(clips, c -> true);
		// The friends' voices: reactions (screams, laughter) plus calm talks.
		double voices = clipSeconds(clips, c -> c.eventType().isVoice() || c.eventType() == EventType.CONVERSATION);
		assertTrue(voices >= all * 0.15, length + ": too little of the friends, " + Math.round(voices) + " of " + Math.round(all) + " s");
		assertTrue(voices <= all * 0.6, length + ": voices take over, " + Math.round(voices) + " of " + Math.round(all) + " s");
		long reactions = clips.stream().filter(c -> c.eventType().isVoice()).map(c -> c.sessionIndex() + ":" + c.score()).distinct().count();
		assertTrue(reactions >= Math.max(1, length.minutes() / 5), length + ": only " + reactions + " screams/laughs");
	}

	@ParameterizedTest
	@EnumSource(FilmLength.class)
	void hasVarietyAndRespectsTheLimits(FilmLength length) {
		List<EditDecisionList.Item> clips = film(RealisticWorld.playthrough(17), length).clips();
		Map<EventType, Long> scenesByType = clips.stream()
			.collect(Collectors.groupingBy(EditDecisionList.Item::eventType,
				Collectors.collectingAndThen(Collectors.mapping(c -> c.sessionIndex() + ":" + c.label() + ":" + c.score(), Collectors.toSet()), s -> (long) s.size())));
		assertTrue(scenesByType.size() >= 6, length + ": only " + scenesByType.keySet());
		for (Map.Entry<EventType, Long> entry : scenesByType.entrySet()) {
			int cap = StoryPlanner.cap(entry.getKey(), length.seconds());
			assertTrue(entry.getValue() <= cap, length + ": " + entry.getValue() + " " + entry.getKey() + " scenes, limit " + cap);
		}
	}

	@ParameterizedTest
	@EnumSource(FilmLength.class)
	void itIsAlsoAFilmAboutFriends(FilmLength length) {
		List<EditDecisionList.Item> clips = film(RealisticWorld.playthrough(23), length).clips();
		long talks = clips.stream().filter(c -> c.eventType() == EventType.CONVERSATION)
			.map(c -> c.sessionIndex() + ":" + c.score()).distinct().count();
		int wanted = Math.max(1, length.minutes() / 5);
		assertTrue(talks >= wanted, length + ": only " + talks + " calm talks");
		assertTrue(talks <= StoryPlanner.cap(EventType.CONVERSATION, length.seconds()), length + ": " + talks + " talks is too many");
	}

	@ParameterizedTest
	@EnumSource(FilmLength.class)
	void endsWithTheirTalkAfterTheDragon(FilmLength length) {
		List<EditDecisionList.Item> clips = film(RealisticWorld.playthrough(29), length).clips();
		int dragon = -1;
		for (int i = 0; i < clips.size(); i++) {
			if (clips.get(i).eventType() == EventType.DRAGON_KILLED) {
				dragon = i;
			}
		}
		assertTrue(dragon >= 0, "no dragon");
		assertTrue(dragon < clips.size() - 1 && clips.getLast().eventType() == EventType.CONVERSATION,
			length + ": the film should close on the friends talking after the dragon");
	}

	@Test
	void friendsTalkingTogetherAreFilmedTogether() {
		List<EditDecisionList.Item> clips = film(RealisticWorld.playthrough(31), FilmLength.TEN).clips();
		assertTrue(clips.stream().anyMatch(c -> c.shot() == com.survivalcinema.director.camera.ShotType.TWO_SHOT && "Alex".equals(c.partner())),
			"no two-shot of a conversation");
		assertTrue(clips.stream().filter(c -> c.shot() == com.survivalcinema.director.camera.ShotType.TWO_SHOT)
			.allMatch(c -> c.eventType() == EventType.CONVERSATION && c.partner() != null), "two-shot used for something else");
	}

	@Test
	void clipsNeverOverlap() {
		List<EditDecisionList.Item> clips = film(RealisticWorld.playthrough(21), FilmLength.TWENTY).clips();
		for (int i = 0; i < clips.size(); i++) {
			for (int j = i + 1; j < clips.size(); j++) {
				EditDecisionList.Item a = clips.get(i), b = clips.get(j);
				boolean overlap = a.sessionIndex() == b.sessionIndex() && a.startTick() < b.endTick() && b.startTick() < a.endTick();
				assertTrue(!overlap, "clips " + i + " and " + j + " show the same moment twice");
			}
		}
	}

	@Test
	void aQuietEveningStillMakesAWatchableShortFilm() {
		EditDecisionList edl = film(RealisticWorld.quietEvening(2), FilmLength.FIVE);
		double total = edl.totalSeconds();
		assertTrue(total >= 60, "only " + total + " s from a 25 minute session");
		assertTrue(total <= FilmLength.FIVE.seconds() * 1.06);
		assertTrue(edl.clips().stream().anyMatch(c -> c.eventType() == EventType.SCENERY), "no travel shots to give it air");
	}

	@Test
	void planningAThirtyHourWorldIsFast() {
		RealisticWorld world = RealisticWorld.playthrough(99);
		long start = System.nanoTime();
		film(world, FilmLength.TWENTY);
		double seconds = (System.nanoTime() - start) / 1e9;
		assertTrue(seconds < 10, "planning took " + seconds + " s");
	}
}
