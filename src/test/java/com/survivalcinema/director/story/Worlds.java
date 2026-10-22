package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Builders for synthetic survival worlds used by the director tests. */
final class Worlds {
	private Worlds() {
	}

	static GameEvent ev(int tick, EventType type, String subject, int day, double intensity, boolean first) {
		return new GameEvent(tick, tick * 50L, type, subject, type + " " + subject, "Steve", 0, 64, 0,
			"minecraft:overworld", day, intensity, first, null);
	}

	static GameEvent ev(int tick, EventType type, int day) {
		return ev(tick, type, type.name().toLowerCase(), day, 0.6, false);
	}

	/** Walking east at 4 blocks per second the whole session. */
	static Trail walking(int lengthTicks) {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= lengthTicks; t += 2) {
			samples.add(new TrailSample(t, t * 0.2, 64, 0, -90f, 0f, TrailSample.FLAG_ON_GROUND));
		}
		return new Trail(samples);
	}

	static Trail standing(int lengthTicks) {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= lengthTicks; t += 2) {
			samples.add(new TrailSample(t, 5, 64, 5, 0f, 0f, TrailSample.FLAG_ON_GROUND));
		}
		return new Trail(samples);
	}

	static SessionData session(int index, int lengthTicks, List<GameEvent> events) {
		List<GameEvent> all = new ArrayList<>();
		all.add(ev(0, EventType.SESSION_START, "session", 1, 0.1, index == 0));
		all.addAll(events);
		return new SessionData("replay-" + index, index, lengthTicks, all, walking(lengthTicks));
	}

	/**
	 * A busy world: 5 sessions of 20 minutes, a day every 2.5 minutes, lots of repeated deaths and kills,
	 * a few real story beats, some loud voice moments, the Nether in session 2 and the dragon at the very end.
	 */
	static List<SessionData> busyWorld() {
		Random random = new Random(42);
		List<SessionData> sessions = new ArrayList<>();
		int length = 20 * 60 * 20;
		boolean firstNight = true;
		for (int s = 0; s < 5; s++) {
			List<GameEvent> events = new ArrayList<>();
			int dayBase = s * 8 + 1;
			for (int t = 600; t < length - 600; t += 300 + random.nextInt(400)) {
				int day = dayBase + t / 3000;
				EventType type = switch (random.nextInt(6)) {
					case 0, 1 -> EventType.KILL;
					case 2 -> EventType.DEATH;
					case 3 -> EventType.BIG_DAMAGE;
					case 4 -> EventType.RARE_ITEM;
					default -> EventType.BUILD_BURST;
				};
				events.add(ev(t, type, type == EventType.KILL ? "minecraft:zombie" : "x", day, random.nextDouble(), false));
				if (random.nextInt(8) == 0) {
					events.add(ev(t + 30, EventType.VOICE_PEAK, "voice", day, 0.9, false));
				}
			}
			if (firstNight) {
				events.add(ev(2500, EventType.NIGHT_FALL, "night", dayBase, 0.8, true));
				firstNight = false;
			}
			events.add(ev(5000, EventType.ADVANCEMENT, "minecraft:story/mine_diamond", dayBase + 1, 0.7, s == 0));
			if (s == 2) {
				events.add(ev(8000, EventType.DIMENSION_CHANGE, "minecraft:the_nether", dayBase + 2, 1.0, true));
			}
			if (s == 4) {
				events.add(ev(length - 400, EventType.BOSS_FIGHT_START, "entity.minecraft.ender_dragon", dayBase + 7, 0.8, true));
				events.add(ev(length - 200, EventType.DRAGON_KILLED, "minecraft:ender_dragon", dayBase + 7, 1.0, true));
			}
			events.sort(java.util.Comparator.comparingInt(GameEvent::tick));
			sessions.add(session(s, length, events));
		}
		return sessions;
	}
}
