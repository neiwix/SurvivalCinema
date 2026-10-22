package com.survivalcinema.director.story;

import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.survivalcinema.director.story.Worlds.ev;
import static com.survivalcinema.director.story.Worlds.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighlightScorerTest {
	private static double scoreOf(List<ScoredEvent> scored, int tick) {
		return scored.stream().filter(s -> s.tick() == tick).findFirst().orElseThrow().score();
	}

	@Test
	void theFirstTimeBeatsTheTenthTime() {
		List<GameEvent> events = new ArrayList<>();
		events.add(ev(100, EventType.RARE_ITEM, "minecraft:diamond", 1, 0.6, true));
		for (int i = 1; i < 10; i++) {
			events.add(ev(100 + i * 1000, EventType.RARE_ITEM, "minecraft:diamond", 1, 0.6, false));
		}
		List<ScoredEvent> scored = new HighlightScorer().score(List.of(session(0, 20_000, events)));
		double previous = Double.MAX_VALUE;
		for (int i = 0; i < 10; i++) {
			double score = scoreOf(scored, 100 + i * 1000);
			assertTrue(score <= previous, "repeat " + i + " should not score higher than the one before");
			assertTrue(score > 0, "even boring repeats keep some value");
			previous = score;
		}
		assertTrue(scoreOf(scored, 100) > 2 * scoreOf(scored, 9100));
	}

	@Test
	void loudVoicesMakeAMomentBetter() {
		List<GameEvent> events = List.of(
			ev(1000, EventType.KILL, "minecraft:creeper", 1, 0.5, false),
			ev(1040, EventType.VOICE_PEAK, "voice", 1, 1.0, false),
			ev(9000, EventType.KILL, "minecraft:skeleton", 1, 0.5, false));
		List<ScoredEvent> scored = new HighlightScorer().score(List.of(session(0, 20_000, events)));
		assertTrue(scoreOf(scored, 1000) > scoreOf(scored, 9000) + 0.2);
	}

	@Test
	void theDragonBeatsEveryMomentWithoutVoices() {
		List<ScoredEvent> scored = new HighlightScorer().score(Worlds.busyWorld());
		List<GameEvent> voice = scored.stream().map(ScoredEvent::event).filter(e -> e.type().isVoice()).toList();
		ScoredEvent best = scored.stream()
			.filter(s -> !s.event().type().isVoice() && HighlightScorer.voiceReaction(s.event(), voice) == 0)
			.max(java.util.Comparator.comparingDouble(ScoredEvent::score)).orElseThrow();
		assertEquals(EventType.DRAGON_KILLED, best.event().type());
	}

	@Test
	void aLoudMomentBeatsSmallStuff() {
		List<GameEvent> events = List.of(
			ev(1000, EventType.VOICE_PEAK, "voice", 1, 0.8, false),
			ev(5000, EventType.BIG_DAMAGE, "damage", 1, 0.8, false),
			ev(9000, EventType.KILL, "minecraft:zombie", 1, 0.2, false));
		List<ScoredEvent> scored = new HighlightScorer().score(List.of(session(0, 20_000, events)));
		assertTrue(scoreOf(scored, 1000) > 3 * scoreOf(scored, 5000));
		assertTrue(scoreOf(scored, 1000) > 3 * scoreOf(scored, 9000));
	}

	@Test
	void screamsLateInTheWorldCountAsMuchAsEarlyOnes() {
		List<GameEvent> events = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			events.add(ev(1000 + i * 5000, EventType.VOICE_PEAK, "voice", 1 + i, 0.8, false));
		}
		List<ScoredEvent> scored = new HighlightScorer().score(List.of(session(0, 70_000, events)));
		assertEquals(scoreOf(scored, 1000), scoreOf(scored, 1000 + 11 * 5000), 1e-9);
	}

	@Test
	void bookkeepingEventsAreNotHighlights() {
		List<GameEvent> events = List.of(ev(500, EventType.BUILD_ACTIVITY, 1));
		List<ScoredEvent> scored = new HighlightScorer().score(List.of(session(0, 2000, events)));
		assertTrue(scored.stream().noneMatch(s -> s.event().type() == EventType.BUILD_ACTIVITY || s.event().type() == EventType.SESSION_START));
	}
}
