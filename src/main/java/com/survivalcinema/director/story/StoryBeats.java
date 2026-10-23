package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.camera.Vec;
import com.survivalcinema.director.event.Details;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import com.survivalcinema.director.timelapse.BaseFinder;
import com.survivalcinema.director.timelapse.TimelapsePlanner;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The beats of a survival story that no recorded moment marks by itself, found in what was recorded around them (the
 * path, the building), so recordings made before they were thought of get them too:
 * <ul>
 *     <li>how it began: the first tree chopped, the first crafting table put down;</li>
 *     <li>coming for the first time to the place that became home, after the journey to it: the boat reaching its shore
 *     and the first steps on land, or walking up to it.</li>
 * </ul>
 * Both are always in the film (a few seconds each).
 */
public final class StoryBeats {
	/** The start of a survival: its first minutes, until the first death at the latest. */
	static final int FIRST_STEPS_TICKS = 20 * 60 * 5;
	/** Chopping a tree: standing still, looking up at the trunk at least this steeply (Minecraft's pitch: up is negative). */
	static final float LOOKING_UP_PITCH = -45;
	static final double STILL_SPEED = 0.6;
	static final int MIN_CHOPPING_TICKS = 60;
	static final int TREE_LEAD_TICKS = 20;
	static final int TREE_TICKS = 160;
	/** Before putting the first crafting table down: the last logs, the crafting. */
	static final int TABLE_LEAD_TICKS = 100;
	static final int TABLE_FOLLOW_TICKS = 40;
	/** Closer than this, the place that became home is reached... */
	static final double ARRIVAL_REACH = 160;
	/** ...and this close, the player is there. */
	static final double HOME_REACH = 48;
	/** Getting off the boat this soon after coming within reach is the landing. */
	static final int LANDING_WITHIN_TICKS = 20 * 60;
	/** The boat reaching the shore, then the first steps on land. */
	static final int LANDING_LEAD_TICKS = 180;
	static final int LANDING_FOLLOW_TICKS = 120;
	static final int WALK_IN_LEAD_TICKS = 200;
	static final int WALK_IN_FOLLOW_TICKS = 60;
	/** Within the first minute of the world it is the spawn: the opening shows it already. */
	static final int SPAWN_TICKS = 20 * 60;
	static final double SCORE = 0.9;
	private static final int STEP = 10;

	private StoryBeats() {
	}

	public static List<Scene> find(List<SessionData> sessions, @Nullable BaseFinder.Base base) {
		List<Scene> beats = new ArrayList<>();
		sessions.stream().filter(s -> s.index() == 0).findFirst().ifPresent(first -> beats.addAll(firstSteps(first)));
		Scene arrival = arrival(sessions, base);
		if (arrival != null && beats.stream().noneMatch(b -> overlaps(b, arrival))) {
			beats.add(arrival);
		}
		return beats;
	}

	/** The first tree and the first crafting table, in the first minutes of the world (one scene when close together). */
	static List<Scene> firstSteps(SessionData session) {
		Trail trail = session.trail();
		// the start ends where the first death begins
		int limit = session.events().stream().filter(e -> e.type() == EventType.DEATH).mapToInt(e -> e.tick() - 200)
			.reduce(Math.min(session.lengthTicks(), FIRST_STEPS_TICKS), Math::min);
		int from = StoryPlanner.OPENING_TICKS;
		List<int[]> ranges = new ArrayList<>();
		int tree = firstTree(session, trail, from, limit);
		if (tree >= 0) {
			ranges.add(new int[]{Math.max(from, tree - TREE_LEAD_TICKS), Math.min(limit, tree + TREE_TICKS - TREE_LEAD_TICKS), tree});
		}
		session.events().stream()
			.filter(e -> e.type() == EventType.BUILD_ACTIVITY && Details.getInt(e.detail(), "home", 0) > 0)
			.filter(e -> e.tick() >= from && e.tick() < limit)
			.min(Comparator.comparingInt(GameEvent::tick))
			.ifPresent(table -> ranges.add(new int[]{Math.max(from, table.tick() - TABLE_LEAD_TICKS),
				Math.min(limit, table.tick() + TABLE_FOLLOW_TICKS), table.tick()}));
		ranges.sort(Comparator.comparingInt(r -> r[0]));
		// close together (chopped, then crafted on the spot): one scene
		for (int i = ranges.size() - 1; i > 0; i--) {
			int[] a = ranges.get(i - 1);
			int[] b = ranges.get(i);
			if (b[0] - a[1] <= SceneBuilder.MERGE_GAP_TICKS) {
				ranges.set(i - 1, new int[]{a[0], Math.max(a[1], b[1]), a[2]});
				ranges.remove(i);
			}
		}
		List<Scene> scenes = new ArrayList<>();
		for (int[] r : ranges) {
			Scene scene = scene(session, EventType.FIRST_STEPS, "first_steps", r[0], r[1], r[2]);
			if (scene != null) {
				scenes.add(scene);
			}
		}
		return scenes;
	}

	/**
	 * Where the first tree was chopped: the first logs broken, when recorded; for older recordings, the longest time spent
	 * standing still looking up at something (a trunk) early on.
	 */
	private static int firstTree(SessionData session, @Nullable Trail trail, int from, int limit) {
		for (GameEvent e : session.events()) {
			if (e.type() == EventType.WOOD_ACTIVITY && e.tick() >= from && e.tick() < limit) {
				return e.tick();
			}
		}
		if (trail == null || trail.isEmpty()) {
			return -1;
		}
		int bestStart = -1;
		int bestLength = 0;
		int runStart = -1;
		for (int t = from; t + STEP <= limit; t += STEP) {
			TrailSample s = trail.sampleAt(t);
			boolean chopping = s.pitch() <= LOOKING_UP_PITCH && ShotPlanner.averageSpeed(trail, t, t + STEP) < STILL_SPEED;
			if (chopping && runStart < 0) {
				runStart = t;
			}
			if ((!chopping || t + 2 * STEP > limit) && runStart >= 0) {
				int length = t - runStart;
				if (length > bestLength) {
					bestLength = length;
					bestStart = runStart;
				}
				runStart = -1;
			}
		}
		return bestLength >= MIN_CHOPPING_TICKS ? bestStart : -1;
	}

	/**
	 * Coming to the place that became home for the first time, unless people started there: getting off the boat on its
	 * shore when they came by boat, else walking up to it.
	 */
	static @Nullable Scene arrival(List<SessionData> sessions, @Nullable BaseFinder.Base base) {
		if (base == null) {
			return null;
		}
		List<SessionData> ordered = new ArrayList<>(sessions);
		ordered.sort(Comparator.comparingInt(SessionData::index));
		for (SessionData session : ordered) {
			Trail trail = session.trail();
			if (trail == null || trail.isEmpty()) {
				continue;
			}
			int reached = firstWithin(trail, base, ARRIVAL_REACH, trail.firstTick());
			if (reached < 0) {
				continue;
			}
			if (session.index() == ordered.getFirst().index() && reached - trail.firstTick() < SPAWN_TICKS) {
				return null; // it was where the world began
			}
			int landing = landing(trail, reached);
			if (landing >= 0) {
				return scene(session, EventType.ARRIVAL, "arrival", landing - LANDING_LEAD_TICKS, landing + LANDING_FOLLOW_TICKS, landing);
			}
			int there = firstWithin(trail, base, HOME_REACH, reached);
			int moment = there >= 0 ? there : reached;
			return scene(session, EventType.ARRIVAL, "arrival", moment - WALK_IN_LEAD_TICKS, moment + WALK_IN_FOLLOW_TICKS, moment);
		}
		return null;
	}

	private static int firstWithin(Trail trail, BaseFinder.Base base, double reach, int from) {
		for (TrailSample s : trail.samples()) {
			if (s.tick() >= from && TimelapsePlanner.dimension(s).equals(base.dimension())
				&& Math.hypot(s.x() - base.center().x(), s.z() - base.center().z()) <= reach) {
				return s.tick();
			}
		}
		return -1;
	}

	/** Getting off a boat (or a horse) soon after coming within reach, having ridden up to it; -1 when not. */
	private static int landing(Trail trail, int reached) {
		if (!trail.sampleAt(reached).has(TrailSample.FLAG_IN_VEHICLE)) {
			return -1;
		}
		for (int t = reached; t <= reached + LANDING_WITHIN_TICKS && t <= trail.lastTick(); t += 2) {
			if (!trail.sampleAt(t).has(TrailSample.FLAG_IN_VEHICLE)) {
				return t;
			}
		}
		return -1;
	}

	/** A beat's scene: inside one continuous stretch of the path (never across a respawn or a teleport). */
	private static @Nullable Scene scene(SessionData session, EventType type, String subject, int start, int end, int moment) {
		start = Math.max(0, start);
		end = Math.min(session.lengthTicks(), end);
		Trail trail = session.trail();
		if (trail != null && !trail.isEmpty()) {
			int[] piece = SceneBuilder.pieceFor(trail.continuousRanges(start, end, 1), moment);
			if (piece != null) {
				start = piece[0];
				end = piece[1];
			}
		}
		if (end - start < SceneBuilder.MIN_SCENE_TICKS) {
			return null;
		}
		Vec p = trail == null || trail.isEmpty() ? new Vec(0, 0, 0) : trail.positionAt(moment);
		String dimension = trail == null || trail.isEmpty() ? "minecraft:overworld" : TimelapsePlanner.dimension(trail.sampleAt(moment));
		GameEvent event = new GameEvent(moment, 0, type, subject, "", "", p.x(), p.y(), p.z(), dimension, dayAt(session, moment), 1.0,
			true, null);
		ScoredEvent primary = new ScoredEvent(session, event, SCORE);
		return new Scene(session, start, end, primary, List.of(primary), SCORE);
	}

	/** The in-game day at a moment: the day of the last moment recorded before it. */
	private static int dayAt(SessionData session, int tick) {
		int day = 1;
		int latest = Integer.MIN_VALUE;
		for (GameEvent e : session.events()) {
			if (e.tick() <= tick && e.tick() >= latest && e.day() > 0) {
				latest = e.tick();
				day = e.day();
			}
		}
		return day;
	}

	private static boolean overlaps(Scene a, Scene b) {
		return a.session() == b.session() && a.startTick() < b.endTick() && b.startTick() < a.endTick();
	}
}
