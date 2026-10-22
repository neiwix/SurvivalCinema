package com.survivalcinema.director.story;

import com.survivalcinema.director.event.Details;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decides how good each moment is:
 * <ul>
 *     <li>the type's base weight, scaled by how intense this instance was (a creeper kill vs a warden kill);</li>
 *     <li>a bonus the first time something happens in the world;</li>
 *     <li>voice reactions around it <b>multiply</b> its worth: screaming during a boss fight is the best there is,
 *     while a quiet zombie kill stays nearly worthless;</li>
 *     <li>less each time the same thing happens again (the tenth creeper death is not news);</li>
 *     <li>much less when lots of the same thing happen in one place (a mob farm, a long fight, strip mining):
 *     a real playthrough has thousands of hits and kills, and they must not drown the story;</li>
 *     <li>story advancements (diamonds, the Nether, the End) count more than side quests (planting seeds).</li>
 * </ul>
 */
public final class HighlightScorer {
	/** Voice activity this close to a moment (5 s) counts as a reaction to it. */
	static final int VOICE_WINDOW_TICKS = 100;
	static final double FIRST_BONUS = 0.35;
	/** At full loudness a moment is worth 1 + this many times as much. */
	static final double VOICE_MULTIPLIER = 1.5;
	static final double VOICE_FLAT_BONUS = 0.15;
	static final double REPEAT_FACTOR = 0.8;
	static final double MIN_REPEAT_MULTIPLIER = 0.3;
	static final double MIN_REPEAT_MULTIPLIER_FARMABLE = 0.12;
	/** Same-type events this close in time (3 min) and space (48 blocks) make each other less special. */
	static final int CROWD_TICKS = 20 * 180;
	static final double CROWD_BLOCKS = 48;
	static final double CROWD_PENALTY = 0.3;
	static final double REPEATED_NIGHT_MULTIPLIER = 0.25;
	static final double TOGETHER_MULTIPLIER = 1.3;

	public List<ScoredEvent> score(List<SessionData> sessions) {
		List<SessionData> ordered = new ArrayList<>(sessions);
		ordered.sort(Comparator.comparingInt(SessionData::index));
		Map<String, Integer> seenCounts = new HashMap<>();
		List<ScoredEvent> result = new ArrayList<>();

		for (SessionData session : ordered) {
			List<GameEvent> events = new ArrayList<>(session.events());
			events.sort(Comparator.comparingInt(GameEvent::tick));
			List<GameEvent> voice = events.stream().filter(e -> e.type().isVoice()).toList();
			Map<GameEvent, Integer> crowding = crowding(events);

			for (GameEvent event : events) {
				EventType type = event.type();
				if (type.baseWeight() <= 0 || type == EventType.SESSION_START) {
					continue;
				}
				String key = type + "|" + event.subject();
				int previous = seenCounts.merge(key, 1, Integer::sum) - 1;
				double score = score(event, previous, voice) / (1 + CROWD_PENALTY * crowding.getOrDefault(event, 0));
				result.add(new ScoredEvent(session, event, score));
			}
		}
		return result;
	}

	static double score(GameEvent event, int previousOccurrences, List<GameEvent> voiceEvents) {
		EventType type = event.type();
		double base = type.baseWeight() * categoryMultiplier(event);
		double score = base * (0.3 + 0.7 * clamp(event.intensity()));
		// Every scream and talk is its own moment, not "the same thing again": limits are the planner's job.
		if (!isAlwaysBig(type) && type != EventType.CONVERSATION && !type.isVoice()) {
			double floor = type.isFarmable() ? MIN_REPEAT_MULTIPLIER_FARMABLE : MIN_REPEAT_MULTIPLIER;
			score *= Math.max(floor, Math.pow(REPEAT_FACTOR, previousOccurrences));
		}
		if (type == EventType.NIGHT_FALL && !event.first()) {
			score *= REPEATED_NIGHT_MULTIPLIER; // the first night is a story beat, the hundredth is just dusk
		}
		if (event.first()) {
			score += FIRST_BONUS * base + (base >= 0.25 ? 0.15 : 0.03);
		}
		if (type == EventType.CONVERSATION && !Details.getList(event.detail(), "near").isEmpty()) {
			score *= TOGETHER_MULTIPLIER; // friends talking side by side, not just over voice chat
		}
		if (!type.isVoice() && type != EventType.CONVERSATION) {
			double voice = voiceReaction(event, voiceEvents);
			score = score * (1 + VOICE_MULTIPLIER * voice) + VOICE_FLAT_BONUS * voice;
		}
		return score;
	}

	/** Main-story advancements are the backbone of a survival film; side-quest ones much less so. */
	static double categoryMultiplier(GameEvent event) {
		if (event.type() != EventType.ADVANCEMENT && event.type() != EventType.OTHER_ADVANCEMENT) {
			return 1;
		}
		String id = event.subject();
		if (id.startsWith("minecraft:story/") || id.startsWith("minecraft:end/")) {
			return 1.3;
		}
		if (id.startsWith("minecraft:nether/")) {
			return 1.1;
		}
		if (id.startsWith("minecraft:husbandry/")) {
			return 0.6;
		}
		return id.startsWith("minecraft:adventure/") ? 1.0 : 0.9;
	}

	/** For farmable events: how many of the same type happened nearby in time and space. */
	static Map<GameEvent, Integer> crowding(List<GameEvent> sortedByTick) {
		Map<GameEvent, Integer> counts = new IdentityHashMap<>();
		Map<EventType, List<GameEvent>> byType = new HashMap<>();
		for (GameEvent e : sortedByTick) {
			if (e.type().isFarmable()) {
				byType.computeIfAbsent(e.type(), t -> new ArrayList<>()).add(e);
			}
		}
		double maxDistanceSq = CROWD_BLOCKS * CROWD_BLOCKS;
		for (List<GameEvent> list : byType.values()) {
			int lo = 0;
			for (int i = 0; i < list.size(); i++) {
				GameEvent e = list.get(i);
				while (list.get(lo).tick() < e.tick() - CROWD_TICKS) {
					lo++;
				}
				int count = 0;
				for (int j = lo; j < list.size() && list.get(j).tick() <= e.tick() + CROWD_TICKS; j++) {
					GameEvent o = list.get(j);
					if (o == e || !Objects.equals(o.dimension(), e.dimension())) {
						continue;
					}
					double dx = o.x() - e.x(), dy = o.y() - e.y(), dz = o.z() - e.z();
					if (dx * dx + dy * dy + dz * dz <= maxDistanceSq) {
						count++;
					}
				}
				counts.put(e, count);
			}
		}
		return counts;
	}

	/** 0..1: how loud people were around this moment (everyone excited at once counts as full). */
	static double voiceReaction(GameEvent event, List<GameEvent> voiceEvents) {
		double loudest = 0;
		for (GameEvent voice : voiceEvents) {
			if (Math.abs(voice.tick() - event.tick()) <= VOICE_WINDOW_TICKS) {
				loudest = Math.max(loudest, voice.type() == EventType.VOICE_CROWD ? 1 : clamp(voice.intensity()));
			}
		}
		return loudest;
	}

	/** The big story beats never get boring. */
	private static boolean isAlwaysBig(EventType type) {
		return type == EventType.DRAGON_KILLED || type == EventType.BOSS_DEFEATED || type == EventType.TOTEM;
	}

	private static double clamp(double value) {
		return Math.max(0, Math.min(1, value));
	}
}
