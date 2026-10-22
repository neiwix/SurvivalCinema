package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.camera.Vec;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds good-looking travel footage between the big moments: the player running across a plain, riding a boat,
 * gliding on an elytra. Every Minecraft montage has these shots; they give the film air and fill it when a world has
 * few real highlights. They only have a small score, so they never push out a real moment.
 */
public final class SceneryFinder {
	static final int SHOT_TICKS = 160;
	static final int STEP_TICKS = 100;
	/** At most one scenery candidate per this much play (10 minutes). */
	static final int PER_TICKS = 20 * 60 * 10;
	/** Keep away from real moments, so scenery never steals the lead-in of one. */
	static final int EVENT_CLEARANCE_TICKS = 200;
	static final double MIN_SPEED = 3;
	static final double MAX_SPEED = 45;

	public List<Scene> find(List<SessionData> sessions) {
		List<Scene> scenes = new ArrayList<>();
		for (SessionData session : sessions) {
			Trail trail = session.trail();
			if (trail == null || trail.isEmpty()) {
				continue;
			}
			for (int bucketStart = 0; bucketStart < session.lengthTicks(); bucketStart += PER_TICKS) {
				Scene best = null;
				for (int[] range : trail.continuousRanges(bucketStart, Math.min(session.lengthTicks(), bucketStart + PER_TICKS), SHOT_TICKS)) {
					for (int start = range[0]; start + SHOT_TICKS <= range[1]; start += STEP_TICKS) {
						int end = start + SHOT_TICKS;
						double speed = ShotPlanner.averageSpeed(trail, start, end);
						if (speed < MIN_SPEED || speed > MAX_SPEED || nearEvent(session, start, end)) {
							continue;
						}
						Scene candidate = scene(session, trail, start, end, speed);
						if (best == null || candidate.score() > best.score()) {
							best = candidate;
						}
					}
				}
				if (best != null) {
					scenes.add(best);
				}
			}
		}
		return scenes;
	}

	private static boolean nearEvent(SessionData session, int start, int end) {
		for (GameEvent e : session.events()) {
			if (e.type().baseWeight() <= 0 || e.type() == EventType.NEW_DAY) {
				continue;
			}
			if (e.tick() >= start - EVENT_CLEARANCE_TICKS && e.tick() <= end + EVENT_CLEARANCE_TICKS) {
				return true;
			}
		}
		return false;
	}

	private static Scene scene(SessionData session, Trail trail, int start, int end, double speed) {
		int middle = (start + end) / 2;
		TrailSample sample = trail.sampleAt(middle);
		Vec p = sample.position();
		GameEvent nearest = null;
		for (GameEvent e : session.events()) {
			if (nearest == null || Math.abs(e.tick() - middle) < Math.abs(nearest.tick() - middle)) {
				nearest = e;
			}
		}
		int day = nearest == null ? 1 : Math.max(1, nearest.day());
		// Faster travel (sprinting, boats, elytra) looks better, up to a point.
		double intensity = Math.min(1, speed / 15);
		GameEvent event = new GameEvent(start, 0, EventType.SCENERY, "scenery", "", "", p.x(), p.y(), p.z(),
			dimension(sample.flags() >> 8), day, intensity, false, String.valueOf(Math.round(speed)));
		ScoredEvent primary = new ScoredEvent(session, event, 0.05 + 0.05 * intensity);
		return new Scene(session, start, end, primary, List.of(primary), primary.score());
	}

	private static String dimension(int index) {
		return switch (index) {
			case 1 -> "minecraft:the_nether";
			case 2 -> "minecraft:the_end";
			default -> "minecraft:overworld";
		};
	}
}
