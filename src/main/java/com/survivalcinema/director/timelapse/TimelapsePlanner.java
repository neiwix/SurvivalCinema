package com.survivalcinema.director.timelapse;

import com.survivalcinema.director.camera.BlockProbe;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.camera.Vec;
import com.survivalcinema.director.story.SessionData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The base timelapse: one fixed camera on the base, and the base growing block by block in a few seconds.
 * <p>
 * The bare land before the first block, then every stretch of time somebody built there, sped up (from all sessions in
 * order, so the days spent mining or far away simply don't show up), then the base at the player's last visit. A
 * stretch only counts while the recording player stood close enough for the base to be in the replay (Flashback only
 * has the world around the player). Each gets film time by how much was built in it, at most a few seconds. Shot at noon
 * in clear weather, so the light doesn't flicker.
 */
public final class TimelapsePlanner {
	/** A still (the bare land, the finished base) is shot as a clip this long; the editor holds it. */
	public static final int SNAPSHOT_TICKS = 10;
	/** At most this many shots, the stills included. */
	static final int MAX_SNAPSHOTS = 12;
	static final int MIN_SNAPSHOTS = 3;
	/** The player must be this close (horizontally) for the base to be loaded in the replay. */
	static final double VIEW = 64;
	/** A moment may move this far to find the player close enough. */
	static final int SEARCH_TICKS = 200;
	/** A building window lasts about this long. */
	static final int WINDOW_TICKS = 20 * 30;
	/** Building windows of a session this close together are one stretch of building. */
	static final int RUN_GAP_TICKS = 20 * 60;
	/** Shorter stretches near the base (passing by) are not worth a shot. */
	static final int MIN_RUN_TICKS = 60;
	/** How long the bare land and the finished base are held. */
	static final double STILL_SECONDS = 0.8;
	static final double MIN_RUN_SECONDS = 0.8;
	static final double MAX_RUN_SECONDS = 3;
	/** Building plays at least this much faster than it happened, and at most this much (a longer stretch keeps its end). */
	static final double MIN_SPEED = 3;
	static final double MAX_SPEED = 400;
	static final double MIN_SECONDS = 4;
	static final double MAX_SECONDS = 14;

	/**
	 * One shot of the base: replay ticks {@code tick} to {@code endTick}, shown for {@code seconds} of the film (sped up
	 * for a stretch of building, held for a still), and how much was built by its end.
	 */
	public record Snapshot(String replayId, int sessionIndex, int tick, int endTick, int day, int blocksSoFar, double seconds) {
		/** A still: a few ticks, held. */
		public Snapshot(String replayId, int sessionIndex, int tick, int day, int blocksSoFar) {
			this(replayId, sessionIndex, tick, tick + SNAPSHOT_TICKS, day, blocksSoFar, STILL_SECONDS);
		}

		public boolean isStill() {
			return endTick - tick <= SNAPSHOT_TICKS;
		}

		/** How many times faster than it happened it plays (1 for a still). */
		public double speed() {
			return isStill() ? 1 : Math.max(1, (endTick - tick) / (seconds * 20));
		}
	}

	/**
	 * @param openX, openZ the side of the base that is open ground (where the player walks around it), a unit vector
	 */
	public record Plan(BaseFinder.Base base, List<Snapshot> snapshots, double openX, double openZ, double seconds) {
		public View view() {
			return new View(base.center().x(), base.center().y(), base.center().z(), base.radius(), base.minY(), base.maxY(),
				openX, openZ);
		}
	}

	/** Where the base is and from which side to look at it: all the camera needs, kept in the screenplay. */
	public record View(double x, double y, double z, double radius, double minY, double maxY, double openX, double openZ) {
		public Vec target() {
			return new Vec(x, minY + (maxY - minY) * 0.35, z);
		}

		/**
		 * The camera: from the open side, high and far enough to see the whole base, turned or raised when the
		 * {@code probe} finds the view blocked (a hill, trees).
		 */
		public Camera camera(BlockProbe probe) {
			double baseYaw = new Vec(openX, 0, openZ).yaw();
			// the base about 60% of the picture's width (a 70 degree view is 2.5 times as wide as it is far)
			double distance = Math.max(18, Math.min(90, radius * 1.6 + 6));
			Vec target = target();
			Camera best = null;
			int bestClear = -1;
			double bestCost = Double.MAX_VALUE;
			// the cheapest view that sees everything: the front, low like a photo of the facade, as close as planned;
			// turning away from the front, going higher (never straight down) or farther costs more
			for (int f = 0; f < DISTANCES.length; f++) {
				for (int e = 0; e < ELEVATIONS.length; e++) {
					for (int t = 0; t < TURNS.length; t++) {
						double cost = DISTANCE_COST[f] + ELEVATION_COST[e] + TURN_COST[t];
						Camera camera = place(target, baseYaw + TURNS[t], ELEVATIONS[e], distance * DISTANCES[f]);
						int clear = clearRays(camera.position(), probe);
						if (clear > bestClear || clear == bestClear && cost < bestCost) {
							bestClear = clear;
							bestCost = cost;
							best = camera;
						}
					}
				}
			}
			return best;
		}

		private static Camera place(Vec target, double yawFromBase, double elevation, double distance) {
			Vec out = Vec.fromYaw(yawFromBase);
			double horizontal = distance * Math.cos(Math.toRadians(elevation));
			Vec position = new Vec(target.x() + out.x() * horizontal, target.y() + distance * Math.sin(Math.toRadians(elevation)),
				target.z() + out.z() * horizontal);
			Vec look = target.sub(position);
			return new Camera(position, (float) look.yaw(), (float) look.pitch());
		}

		/**
		 * Of the base's middle and four points on its edge, how many the camera sees. The base's own walls don't count
		 * (the camera looks at them): only what stands between the camera and the base, a hill or trees.
		 */
		private int clearRays(Vec from, BlockProbe probe) {
			int clear = reaches(from, target(), probe) ? 1 : 0;
			double edge = radius * 0.7;
			double top = minY + (maxY - minY) * 0.6;
			for (Vec point : new Vec[]{new Vec(x + edge, top, z), new Vec(x - edge, top, z), new Vec(x, top, z + edge), new Vec(x, top, z - edge)}) {
				if (reaches(from, point, probe)) {
					clear++;
				}
			}
			return clear;
		}

		/** The line from the camera gets into the base before anything stops it. */
		private boolean reaches(Vec from, Vec point, BlockProbe probe) {
			double length = point.sub(from).length();
			double free = probe.clearFraction(from, point) * length;
			double inside = Math.min(length * 0.5, radius * 0.6 + 1);
			return free >= length - inside;
		}
	}

	public record Camera(Vec position, float yaw, float pitch) {
	}

	/** Camera heights above the base (degrees), from a low facade view to high above as a last resort. */
	static final double[] ELEVATIONS = {16, 24, 34, 46};
	static final double[] ELEVATION_COST = {0, 0.5, 2, 4};
	/** Turns away from the front, degrees. */
	static final double[] TURNS = {0, 30, -30, 60, -60, 90, -90, 135, -135, 180};
	static final double[] TURN_COST = {0, 1, 1, 2, 2, 3, 3, 4.5, 4.5, 6};
	static final double[] DISTANCES = {1, 0.8, 1.3};
	static final double[] DISTANCE_COST = {0, 0.7, 1};

	private TimelapsePlanner() {
	}

	public static @Nullable Plan plan(List<SessionData> sessions, BaseFinder.Rules rules) {
		BaseFinder.Base base = BaseFinder.find(sessions, rules);
		return base == null ? null : plan(sessions, base, rules.minPictures());
	}

	public static @Nullable Plan plan(List<SessionData> sessions, BaseFinder.Base base, int minPictures) {
		List<Snapshot> candidates = new ArrayList<>();
		BaseFinder.Window first = base.windows().getFirst();
		// the land before the first block
		add(candidates, sessions, base, first.sessionIndex(), first.tick() - 40, first.day(), 0, -1);
		int built = 0;
		for (Run run : runs(base)) {
			SessionData session = session(sessions, run.sessionIndex);
			if (session == null || session.trail() == null || session.trail().isEmpty()) {
				built += run.blocks;
				continue;
			}
			List<int[]> stretches = nearStretches(session, base, run.from, run.to);
			int length = Math.max(1, stretches.stream().mapToInt(r -> r[1] - r[0]).sum());
			int before = built;
			for (int[] stretch : stretches) {
				// the run's blocks, shared out by time: each stretch shows its part of the building
				built += (int) Math.round(run.blocks * (stretch[1] - stretch[0]) / (double) length);
				candidates.add(new Snapshot(session.replayId(), session.index(), stretch[0], stretch[1], run.day, built, 0));
			}
			built = before + run.blocks;
		}
		Snapshot last = lastVisit(sessions, base, built);
		if (last != null && (candidates.isEmpty() || isAfter(last, candidates.getLast()))) {
			candidates.add(last);
		}
		List<Snapshot> chosen = timed(choose(dedupe(candidates), built));
		if (chosen.size() < Math.max(2, minPictures)) {
			return null;
		}
		double seconds = chosen.stream().mapToDouble(Snapshot::seconds).sum();
		Vec front = front(sessions, base);
		return new Plan(base, chosen, front.x(), front.z(), seconds);
	}

	/** Building windows of one session close together: one stretch of building. */
	private record Run(int sessionIndex, int from, int to, int day, int blocks) {
	}

	private static List<Run> runs(BaseFinder.Base base) {
		List<Run> runs = new ArrayList<>();
		for (BaseFinder.Window w : base.windows()) {
			Run last = runs.isEmpty() ? null : runs.getLast();
			if (last != null && last.sessionIndex == w.sessionIndex() && w.tick() - last.to <= RUN_GAP_TICKS) {
				runs.set(runs.size() - 1, new Run(last.sessionIndex, last.from, Math.max(last.to, w.tick() + WINDOW_TICKS), last.day,
					last.blocks + w.blocks()));
			} else {
				runs.add(new Run(w.sessionIndex(), w.tick(), w.tick() + WINDOW_TICKS, w.day(), w.blocks()));
			}
		}
		return runs;
	}

	/** The parts of {@code from..to} the player spent near the base (so the base is in the replay all along). */
	static List<int[]> nearStretches(SessionData session, BaseFinder.Base base, int from, int to) {
		int latest = Math.min(session.lengthTicks(), session.trail().lastTick()) - 1;
		List<int[]> stretches = new ArrayList<>();
		int start = -1;
		int end = -1;
		for (TrailSample s : session.trail().samples()) {
			if (s.tick() < from || s.tick() > Math.min(to, latest)) {
				continue;
			}
			boolean near = isNear(s, base);
			if (near && start >= 0 && s.tick() - end <= 40) {
				end = s.tick();
				continue;
			}
			if (start >= 0 && end - start >= MIN_RUN_TICKS) {
				stretches.add(new int[]{start, end});
			}
			start = near ? s.tick() : -1;
			end = start;
		}
		if (start >= 0 && end - start >= MIN_RUN_TICKS) {
			stretches.add(new int[]{start, end});
		}
		return stretches;
	}

	/**
	 * The film time of each shot: stills are held a moment, stretches of building share a few seconds by how much was
	 * built in them, and never play slower than {@link #MIN_SPEED} or faster than {@link #MAX_SPEED} (the end of a very
	 * long stretch is kept).
	 */
	static List<Snapshot> timed(List<Snapshot> shots) {
		long runs = shots.stream().filter(s -> !s.isStill()).count();
		double stills = (shots.size() - runs) * STILL_SECONDS;
		double budget = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, runs * 1.5 + stills)) - stills;
		int total = 0;
		int before = 0;
		for (Snapshot s : shots) {
			if (!s.isStill()) {
				total += Math.max(1, s.blocksSoFar() - before);
			}
			before = s.blocksSoFar();
		}
		List<Snapshot> timed = new ArrayList<>();
		before = 0;
		for (Snapshot s : shots) {
			int blocks = Math.max(1, s.blocksSoFar() - before);
			before = s.blocksSoFar();
			if (s.isStill()) {
				timed.add(new Snapshot(s.replayId(), s.sessionIndex(), s.tick(), s.endTick(), s.day(), s.blocksSoFar(), STILL_SECONDS));
				continue;
			}
			double seconds = Math.max(MIN_RUN_SECONDS, Math.min(MAX_RUN_SECONDS, budget * blocks / Math.max(1, total)));
			int ticks = s.endTick() - s.tick();
			seconds = Math.min(seconds, Math.max(0.4, ticks / (20 * MIN_SPEED)));
			int from = (int) Math.max(s.tick(), s.endTick() - seconds * 20 * MAX_SPEED);
			timed.add(new Snapshot(s.replayId(), s.sessionIndex(), from, s.endTick(), s.day(), s.blocksSoFar(), seconds));
		}
		return timed;
	}

	/** Which way the base faces: towards its doors, or else the side people come and go on. */
	static Vec front(List<SessionData> sessions, BaseFinder.Base base) {
		if (base.entrance() != null) {
			Vec out = base.entrance().sub(base.center()).horizontal();
			if (out.length() > 1) {
				return out.normalize();
			}
		}
		return openSide(sessions, base);
	}

	/**
	 * A moment near {@code tick} where the player stood close enough, if there is one.
	 *
	 * @param notBefore the moment may move back as far as this tick; -1: only {@link #SEARCH_TICKS} back and never later
	 */
	private static void add(List<Snapshot> out, List<SessionData> sessions, BaseFinder.Base base, int sessionIndex,
							int tick, int day, int built, int notBefore) {
		SessionData session = session(sessions, sessionIndex);
		if (session == null || session.trail() == null || session.trail().isEmpty()) {
			return;
		}
		int latest = Math.min(session.lengthTicks(), session.trail().lastTick()) - SNAPSHOT_TICKS;
		int wanted = Math.max(0, Math.min(latest, tick));
		TrailSample near = nearest(session.trail(), base, wanted, notBefore);
		if (near != null) {
			int at = Math.max(0, Math.min(latest, near.tick()));
			out.add(new Snapshot(session.replayId(), session.index(), at, day, built));
		}
	}

	/** The closest sample to the tick (within the search) where the player is near the base. */
	private static @Nullable TrailSample nearest(Trail trail, BaseFinder.Base base, int tick, int notBefore) {
		TrailSample best = null;
		int from = notBefore < 0 ? tick - SEARCH_TICKS : Math.min(notBefore, tick - SEARCH_TICKS);
		int to = notBefore < 0 ? tick : tick + SEARCH_TICKS;
		for (TrailSample s : trail.samples()) {
			if (s.tick() < from || s.tick() > to || !isNear(s, base)) {
				continue;
			}
			if (best == null || Math.abs(s.tick() - tick) < Math.abs(best.tick() - tick)) {
				best = s;
			}
		}
		return best;
	}

	/** The last time anyone could see the finished base: the latest sample near it in any session. */
	private static @Nullable Snapshot lastVisit(List<SessionData> sessions, BaseFinder.Base base, int built) {
		List<SessionData> newestFirst = new ArrayList<>(sessions);
		newestFirst.sort(Comparator.comparingInt(SessionData::index).reversed());
		for (SessionData session : newestFirst) {
			if (session.trail() == null) {
				continue;
			}
			List<TrailSample> samples = session.trail().samples();
			int latest = Math.min(session.lengthTicks(), session.trail().lastTick()) - SNAPSHOT_TICKS;
			for (int i = samples.size() - 1; i >= 0; i--) {
				TrailSample s = samples.get(i);
				if (s.tick() <= latest && isNear(s, base)) {
					return new Snapshot(session.replayId(), session.index(), s.tick(), base.lastDay(), built);
				}
			}
		}
		return null;
	}

	static boolean isNear(TrailSample s, BaseFinder.Base base) {
		return dimension(s).equals(base.dimension()) && BaseFinder.horizontal(s.position(), base.center()) <= VIEW;
	}

	/** Same moment twice (two windows close together) is one picture. */
	private static List<Snapshot> dedupe(List<Snapshot> candidates) {
		List<Snapshot> out = new ArrayList<>();
		for (Snapshot s : candidates) {
			Snapshot last = out.isEmpty() ? null : out.getLast();
			if (last != null && last.sessionIndex() == s.sessionIndex() && Math.abs(last.tick() - s.tick()) < SNAPSHOT_TICKS * 2) {
				out.set(out.size() - 1, s.blocksSoFar() >= last.blocksSoFar() ? s : last);
				continue;
			}
			if (last != null && !isAfter(s, last)) {
				continue;
			}
			out.add(s);
		}
		return out;
	}

	/**
	 * At most {@link #MAX_SNAPSHOTS}, spread by how much was built (equal growth between pictures), keeping the
	 * first and the last.
	 */
	static List<Snapshot> choose(List<Snapshot> candidates, int total) {
		if (candidates.size() <= MAX_SNAPSHOTS) {
			return candidates;
		}
		Set<Snapshot> chosen = new LinkedHashSet<>();
		chosen.add(candidates.getFirst());
		for (int k = 1; k < MAX_SNAPSHOTS - 1; k++) {
			double target = total * k / (double) (MAX_SNAPSHOTS - 1);
			Snapshot best = null;
			for (Snapshot s : candidates) {
				if (!chosen.contains(s) && (best == null || Math.abs(s.blocksSoFar() - target) < Math.abs(best.blocksSoFar() - target))) {
					best = s;
				}
			}
			if (best != null) {
				chosen.add(best);
			}
		}
		chosen.add(candidates.getLast());
		List<Snapshot> ordered = new ArrayList<>(chosen);
		ordered.sort(Comparator.comparingInt(Snapshot::sessionIndex).thenComparingInt(Snapshot::tick));
		return ordered;
	}

	/** Where the player walks around the base: that side is open ground, a good place for the camera. */
	static Vec openSide(List<SessionData> sessions, BaseFinder.Base base) {
		double x = 0, z = 0;
		for (SessionData session : sessions) {
			if (session.trail() == null) {
				continue;
			}
			for (TrailSample s : session.trail().samples()) {
				if (!dimension(s).equals(base.dimension())) {
					continue;
				}
				double dx = s.x() - base.center().x();
				double dz = s.z() - base.center().z();
				double d = Math.hypot(dx, dz);
				if (d > base.radius() + 4 && d < base.radius() + 40) {
					x += dx / d;
					z += dz / d;
				}
			}
		}
		double length = Math.hypot(x, z);
		if (length < 1e-6) {
			return new Vec(Math.sqrt(0.5), 0, Math.sqrt(0.5)); // nothing known: from the south-east
		}
		return new Vec(x / length, 0, z / length);
	}

	private static boolean isAfter(Snapshot a, Snapshot b) {
		return a.sessionIndex() > b.sessionIndex() || a.sessionIndex() == b.sessionIndex() && a.tick() > b.tick();
	}

	private static @Nullable SessionData session(List<SessionData> sessions, int index) {
		for (SessionData s : sessions) {
			if (s.index() == index) {
				return s;
			}
		}
		return null;
	}

	/** The dimension a trail sample was recorded in. */
	/** The vanilla dimension a camera path sample was recorded in, or "other" for one of another mod. */
	public static String dimension(TrailSample s) {
		return switch ((s.flags() >> 8) & 0xFF) {
			case 0 -> "minecraft:overworld";
			case 1 -> "minecraft:the_nether";
			case 2 -> "minecraft:the_end";
			default -> "other";
		};
	}
}
