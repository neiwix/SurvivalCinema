package com.survivalcinema.director.camera;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the player visible: when trees or walls get between the camera and the player, the camera swings around the
 * obstacle, ducks under branches, rises above it or, when nothing else helps, moves in closer (never into a close-up
 * of the face), like a camera operator who knows the whole shot in advance.
 * <p>
 * The whole shot is planned at once, not key by key: every fifth of a second the camera may stand anywhere on a
 * grid of positions around the planned one (turned around the player, lowered or raised, brought closer), and the
 * cheapest way through the shot is found (Viterbi): hiding the player costs a lot, leaves at the lens cost too,
 * standing away from the plan costs a little for as long as it lasts, and every move costs, so the camera moves
 * rarely, gently and only as far as needed. A brief pass behind a thin trunk is cheaper than dodging it. The moves
 * are then smoothed out over about a second, starting before the obstacle arrives, so the camera never jumps.
 * (Choosing the best place key by key, as before, made it jump between both sides of every tree in a forest.)
 * <p>
 * Visibility is tested with a "thick" ray: the centre line plus four lines ending around the lens. A single thin
 * line misses leaves sitting right next to the camera, which can still cover half the picture. Short rays across
 * the whole picture find what is right in front of the lens elsewhere in the frame.
 */
public final class OcclusionSolver {
	private static final double CLEAR = 0.995;
	/**
	 * Closer than this the picture is a close-up of the face, with the name above the head as big as the picture: the
	 * camera never comes closer (unless the shot was planned closer), not even when the player stays hidden there.
	 */
	static final double MIN_DISTANCE = 2.5;
	private static final double PULL_IN_MARGIN = 0.5;
	/** How far around the lens the side rays end, in blocks. */
	static final double LENS_RADIUS = 0.45;

	/** Ticks between the moments the camera decides where to stand; the path in between is smoothed. */
	static final int DECISION_TICKS = 4;
	/**
	 * The grid of places around the planned one: turned by steps of 15 degrees (up to 150), lowered a little (under
	 * branches) or raised, brought closer.
	 */
	private static final double YAW_STEP = 15;
	private static final int YAW_STEPS = 10;
	private static final double[] LIFTS = {-0.75, 0, 1.25, 2.5, 3.75};
	private static final int NO_LIFT = 1;
	private static final double[] SCALES = {1, 0.8, 0.62, 0.46, 0.33};

	/** Cost of the player being hidden for one decision (a fifth of a second). */
	private static final double HIDDEN_COST = 10;
	/**
	 * Cost of the head showing over a wall while the body is behind it. The camera aims at the chest, so a camera just
	 * over a wall "sees" the player while the picture shows planks and a name tag (worst of all for someone lying in
	 * bed by the wall): the body is checked too, at a point this far below the aim.
	 */
	private static final double BODY_HIDDEN_COST = 6;
	static final double BODY_BELOW_AIM = 0.9;
	/**
	 * Flashback films through its replay viewer's eyes, and the viewer's body hangs this far below them. A camera whose
	 * viewer would stand in a block (right over a wall, low over a slope) is pushed out or crouched by the game and
	 * does not film what was planned, so it costs this much per decision.
	 */
	static final double VIEWER_EYE_HEIGHT = 1.62;
	private static final double VIEWER_INSIDE_COST = 6;
	/** How much worse (in costs above) one place for a whole static shot may be than the best moving way. */
	private static final double TRIPOD_STILL_SLACK = 2 * HIDDEN_COST;
	/** Cost of each lens ray in leaves for one decision. */
	private static final double LENS_COST = 2.5;
	/**
	 * The picture is checked for things right in front of the lens: rays from the camera across the frame (Minecraft's
	 * 70 degrees high, so about 100 wide), this long. Each one that hits costs this much per decision.
	 */
	private static final double[] FRAME_YAWS = {-44, -22, 0, 22, 44};
	private static final double[] FRAME_PITCHES = {-26, 0, 26};
	private static final double FRAME_REACH = 1.6;
	private static final double FRAME_COST = 0.7;
	/** Cost per decision of standing one grid step away from the plan. */
	private static final double YAW_AWAY_COST = 0.35;
	private static final double LIFT_AWAY_COST = 0.4;
	private static final double SCALE_AWAY_COST = 0.5;
	/** Cost of moving one grid step between two decisions; at most one step per decision along each axis. */
	private static final double MOVE_COST = 1.5;
	/** How widely the chosen moves are spread out (the Gaussian's sigma), in seconds. */
	static final double SMOOTH_SECONDS = 0.45;
	/** Only a camera blocked for this long moves in front of the obstacle (a cave); a passing trunk is left alone. */
	private static final int PULL_IN_MIN_TICKS = 10;
	/** How early the camera starts moving in before such an obstacle, and how gently. */
	private static final int PULL_IN_EASE_TICKS = 10;

	private OcclusionSolver() {
	}

	/** Where the camera stands relative to its planned place: turned around the player, raised and brought closer. */
	record Deviation(double yaw, double lift, double scale) {
		static final Deviation NONE = new Deviation(0, 0, 1);

		Vec apply(Vec plannedOffset) {
			return rotateHorizontally(plannedOffset, yaw).scale(scale).add(Vec.UP.scale(lift));
		}

		/**
		 * The camera for a planned position: turned round {@code pivot} (the player, or the spot a static shot keeps
		 * looking at), then brought closer to the player and raised.
		 */
		Vec place(Vec aim, Vec pivot, Vec position) {
			Vec turned = pivot.add(rotateHorizontally(position.sub(pivot), yaw));
			return aim.add(turned.sub(aim).scale(scale)).add(Vec.UP.scale(lift));
		}
	}

	public static List<CameraKey> solve(Trail trail, List<CameraKey> keys, BlockProbe probe) {
		return solve(trail, keys, probe, false);
	}

	/**
	 * @param tripod the shot is a static camera: it is turned round one point, the middle of where the player was,
	 *               so it stays where it was put when the player moves a little (gets out of bed), instead of being
	 *               carried along by the turn round the player
	 */
	public static List<CameraKey> solve(Trail trail, List<CameraKey> keys, BlockProbe probe, boolean tripod) {
		return solve(trail, keys, probe, tripod, trail);
	}

	public static List<CameraKey> solve(Trail trail, List<CameraKey> keys, BlockProbe probe, boolean tripod, Trail history) {
		return solve(trail, keys, probe, tripod, history, null);
	}

	/**
	 * @param history the player's whole way, also from before the shot: underground a camera with no air round the
	 *                player follows them along it ({@link #followThroughTunnels})
	 * @param after   the world at the end of the shot, or null: what the player digs during it is air there, while
	 *                {@code probe} may count it as rock all along; the camera following them checks its view in it
	 */
	public static List<CameraKey> solve(Trail trail, List<CameraKey> keys, BlockProbe probe, boolean tripod, Trail history,
										@Nullable BlockProbe after) {
		return solve(trail, keys, probe, tripod, history, after, true);
	}

	/** @param tunnels false for the camera as it was before following through tunnels (the tests compare the two) */
	static List<CameraKey> solve(Trail trail, List<CameraKey> keys, BlockProbe probe, boolean tripod, Trail history,
								 @Nullable BlockProbe after, boolean tunnels) {
		if (keys.isEmpty()) {
			return keys;
		}
		int n = keys.size();
		Vec[] aims = new Vec[n];
		Vec[] planned = new Vec[n];
		Vec[] positions = new Vec[n];
		Vec[] pivots = new Vec[n];
		boolean clear = true;
		for (int i = 0; i < n; i++) {
			aims[i] = CameraPathBuilder.aimAt(trail, keys.get(i).tick());
			planned[i] = keys.get(i).position().sub(aims[i]);
			positions[i] = keys.get(i).position();
			clear &= visibility(aims[i], planned[i], probe) >= CLEAR
				&& probe.clearFraction(body(aims[i]), keys.get(i).position()) >= CLEAR
				&& viewerFits(keys.get(i).position(), probe)
				&& frameClutter(keys.get(i).position(), planned[i].scale(-1), probe) == 0;
		}
		if (clear) {
			return keys; // nothing in the way: the shot as planned
		}

		Vec middle = Vec.ZERO;
		for (Vec aim : aims) {
			middle = middle.add(aim.scale(1.0 / n));
		}
		for (int i = 0; i < n; i++) {
			pivots[i] = tripod ? middle : aims[i];
		}
		List<Integer> decisions = decisionKeys(keys);
		Deviation[] chosen = cheapestWay(decisions, aims, pivots, positions, probe, tripod);
		Deviation[] smoothed = smooth(chosen, keys, decisions);

		Vec[] offsets = new Vec[n];
		for (int i = 0; i < n; i++) {
			offsets[i] = smoothed[i].place(aims[i], pivots[i], positions[i]).sub(aims[i]);
		}
		boolean standsStill = tripod && java.util.Arrays.stream(chosen).distinct().count() == 1;
		if (!standsStill) {
			pullInWhereStillBlocked(keys, aims, offsets, probe); // a camera on a tripod does not creep in and out
		}
		if (tunnels) {
			followThroughTunnels(keys, aims, offsets, probe, history, after == null ? probe : after);
		}

		List<CameraKey> result = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			result.add(CameraPathBuilder.lookAt(keys.get(i).tick(), aims[i].add(offsets[i]), aims[i]));
		}
		return CameraPathBuilder.unwrapYaw(result);
	}

	/** Indices of the keys where the camera decides: one every {@link #DECISION_TICKS}, plus the last key. */
	private static List<Integer> decisionKeys(List<CameraKey> keys) {
		List<Integer> decisions = new ArrayList<>();
		int last = 0;
		for (int i = 0; i < keys.size(); i++) {
			if (i == 0 || i == keys.size() - 1 || keys.get(i).tick() - last >= DECISION_TICKS) {
				decisions.add(i);
				last = keys.get(i).tick();
			}
		}
		return decisions;
	}

	private static int yawSteps() {
		return 2 * YAW_STEPS + 1;
	}

	private static int state(int yaw, int lift, int scale) {
		return (yaw * LIFTS.length + lift) * SCALES.length + scale;
	}

	private static Deviation deviation(int state) {
		int scale = state % SCALES.length;
		int lift = state / SCALES.length % LIFTS.length;
		int yaw = state / SCALES.length / LIFTS.length;
		return new Deviation((yaw - YAW_STEPS) * YAW_STEP, LIFTS[lift], SCALES[scale]);
	}

	/**
	 * The cheapest sequence of places over the decisions (Viterbi over the grid). A static shot keeps one place for the
	 * whole shot when one is nearly as good: a camera on a tripod that drifts looks like a mistake.
	 */
	private static Deviation[] cheapestWay(List<Integer> decisions, Vec[] aims, Vec[] pivots, Vec[] positions, BlockProbe probe,
										   boolean tripod) {
		int states = yawSteps() * LIFTS.length * SCALES.length;
		int m = decisions.size();
		double[] still = new double[states];
		double[] best = new double[states];
		double[] next = new double[states];
		int[][] cameFrom = new int[m][states];
		for (int k = 0; k < m; k++) {
			int key = decisions.get(k);
			for (int yaw = 0; yaw < yawSteps(); yaw++) {
				for (int lift = 0; lift < LIFTS.length; lift++) {
					for (int scale = 0; scale < SCALES.length; scale++) {
						int s = state(yaw, lift, scale);
						Deviation place = new Deviation((yaw - YAW_STEPS) * YAW_STEP, LIFTS[lift], SCALES[scale]);
						double here = placeCost(aims[key], pivots[key], positions[key], place, yaw, lift, scale, probe);
						still[s] += here;
						if (k == 0) {
							next[s] = here;
							continue;
						}
						double cheapest = Double.MAX_VALUE;
						int from = -1;
						for (int dy = -1; dy <= 1; dy++) {
							for (int dl = -1; dl <= 1; dl++) {
								for (int ds = -1; ds <= 1; ds++) {
									int py = yaw + dy, pl = lift + dl, ps = scale + ds;
									if (py < 0 || py >= yawSteps() || pl < 0 || pl >= LIFTS.length || ps < 0 || ps >= SCALES.length) {
										continue;
									}
									double cost = best[state(py, pl, ps)] + MOVE_COST * (Math.abs(dy) + Math.abs(dl) + Math.abs(ds));
									if (cost < cheapest) {
										cheapest = cost;
										from = state(py, pl, ps);
									}
								}
							}
						}
						next[s] = cheapest + here;
						cameFrom[k][s] = from;
					}
				}
			}
			double[] swap = best;
			best = next;
			next = swap;
		}
		int end = 0;
		int stillest = 0;
		for (int s = 1; s < states; s++) {
			if (best[s] < best[end]) {
				end = s;
			}
			if (still[s] < still[stillest]) {
				stillest = s;
			}
		}
		Deviation[] way = new Deviation[m];
		if (tripod && still[stillest] <= best[end] + TRIPOD_STILL_SLACK) {
			java.util.Arrays.fill(way, deviation(stillest));
			return way;
		}
		for (int k = m - 1; k >= 0; k--) {
			way[k] = deviation(end);
			if (k > 0) {
				end = cameFrom[k][end];
			}
		}
		return way;
	}

	/** What standing at one grid place costs for one decision: being away from the plan, and what it cannot see. */
	private static double placeCost(Vec aim, Vec pivot, Vec position, Deviation place, int yaw, int lift, int scale,
									BlockProbe probe) {
		Vec offset = place.place(aim, pivot, position).sub(aim);
		if (offset.length() < Math.min(MIN_DISTANCE, position.sub(aim).length())) {
			return Double.MAX_VALUE / 4; // too close to the player to film them
		}
		double cost = Math.abs(yaw - YAW_STEPS) * YAW_AWAY_COST + Math.abs(lift - NO_LIFT) * LIFT_AWAY_COST
			+ scale * SCALE_AWAY_COST;
		Vec camera = aim.add(offset);
		if (!viewerFits(camera, probe)) {
			cost += VIEWER_INSIDE_COST;
		}
		if (probe.clearFraction(aim, camera) < CLEAR) {
			// with the player hidden, the rest of the picture hardly matters
			return cost + HIDDEN_COST + 4 * LENS_COST + (FRAME_YAWS.length * FRAME_PITCHES.length - 1) * FRAME_COST;
		}
		if (probe.clearFraction(body(aim), camera) < CLEAR) {
			cost += BODY_HIDDEN_COST;
		}
		for (Vec around : lensOffsets(offset)) {
			if (probe.clearFraction(aim, camera.add(around)) < CLEAR) {
				cost += LENS_COST;
			}
		}
		return cost + frameClutter(camera, offset.scale(-1), probe) * FRAME_COST;
	}

	/** The replay viewer whose eyes are the camera has room to stand there: nothing between its eyes and its feet. */
	public static boolean viewerFits(Vec camera, BlockProbe probe) {
		return probe.clearFraction(camera, camera.sub(Vec.UP.scale(VIEWER_EYE_HEIGHT))) >= 1;
	}

	/** A point on the player's body below the aim (the belly of someone standing, the body of someone lying). */
	static Vec body(Vec aim) {
		return aim.sub(Vec.UP.scale(BODY_BELOW_AIM));
	}

	/** How many rays across the picture hit something right in front of the lens (leaves, a wall, the ground). */
	static int frameClutter(Vec camera, Vec look, BlockProbe probe) {
		double yaw = look.yaw();
		double pitch = look.pitch();
		int hits = 0;
		for (double dy : FRAME_YAWS) {
			for (double dp : FRAME_PITCHES) {
				if (dy == 0 && dp == 0) {
					continue; // the line to the player has its own rays
				}
				Vec ray = direction(yaw + dy, pitch + dp).scale(FRAME_REACH);
				if (probe.clearFraction(camera, camera.add(ray)) < 1) {
					hits++;
				}
			}
		}
		return hits;
	}

	/** Unit vector of a Minecraft yaw and pitch (pitch positive = down). */
	private static Vec direction(double yaw, double pitch) {
		double y = Math.toRadians(yaw);
		double p = Math.toRadians(pitch);
		return new Vec(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
	}

	/**
	 * The chosen places for every key (each key takes its nearest decision), smoothed over time: a Gaussian, so moves
	 * start before an obstacle and end after it, with no sudden change of speed.
	 */
	private static Deviation[] smooth(Deviation[] chosen, List<CameraKey> keys, List<Integer> decisions) {
		int n = keys.size();
		List<Integer> ticks = new ArrayList<>(n);
		double[] yaw = new double[n];
		double[] lift = new double[n];
		double[] scale = new double[n];
		int k = 0;
		for (int i = 0; i < n; i++) {
			int tick = keys.get(i).tick();
			while (k < chosen.length - 1
				&& Math.abs(keys.get(decisions.get(k + 1)).tick() - tick) <= Math.abs(keys.get(decisions.get(k)).tick() - tick)) {
				k++;
			}
			ticks.add(tick);
			yaw[i] = chosen[k].yaw();
			lift[i] = chosen[k].lift();
			scale[i] = chosen[k].scale();
		}
		yaw = CameraPathBuilder.smoothOverTime(ticks, yaw, SMOOTH_SECONDS);
		lift = CameraPathBuilder.smoothOverTime(ticks, lift, SMOOTH_SECONDS);
		scale = CameraPathBuilder.smoothOverTime(ticks, scale, SMOOTH_SECONDS);
		Deviation[] perKey = new Deviation[n];
		for (int i = 0; i < n; i++) {
			perKey[i] = new Deviation(yaw[i], lift[i], scale[i]);
		}
		return perKey;
	}

	/**
	 * Where the player stays hidden for a while even so (a cave, a tunnel), the camera moves in front of the
	 * obstacle like the F5 camera, but eased: it starts moving in a little before and comes back out slowly. It stops
	 * at {@link #MIN_DISTANCE}: a face filling the picture is worse than a moment behind a corner.
	 */
	private static void pullInWhereStillBlocked(List<CameraKey> keys, Vec[] aims, Vec[] offsets, BlockProbe probe) {
		int n = keys.size();
		double[] allowed = new double[n];
		boolean[] blocked = new boolean[n];
		for (int i = 0; i < n; i++) {
			double length = offsets[i].length();
			double clear = probe.clearFraction(aims[i], aims[i].add(offsets[i]));
			blocked[i] = clear < CLEAR;
			allowed[i] = blocked[i] ? Math.max(Math.min(MIN_DISTANCE, length), clear * length - PULL_IN_MARGIN) : length;
		}
		// Ignore short blocked stretches: a trunk passing by.
		for (int i = 0; i < n; ) {
			if (!blocked[i]) {
				i++;
				continue;
			}
			int j = i;
			while (j < n && blocked[j]) {
				j++;
			}
			if (keys.get(j - 1).tick() - keys.get(i).tick() < PULL_IN_MIN_TICKS) {
				for (int k = i; k < j; k++) {
					allowed[k] = offsets[k].length();
				}
			}
			i = j;
		}
		// The nearest any key around needs, then an average of that: never further than a key needs, and no jumps.
		double[] nearest = new double[n];
		for (int i = 0; i < n; i++) {
			nearest[i] = allowed[i];
			for (int j = i - 1; j >= 0 && keys.get(i).tick() - keys.get(j).tick() <= PULL_IN_EASE_TICKS; j--) {
				nearest[i] = Math.min(nearest[i], allowed[j]);
			}
			for (int j = i + 1; j < n && keys.get(j).tick() - keys.get(i).tick() <= PULL_IN_EASE_TICKS; j++) {
				nearest[i] = Math.min(nearest[i], allowed[j]);
			}
		}
		for (int i = 0; i < n; i++) {
			double sum = 0;
			int count = 0;
			for (int j = 0; j < n; j++) {
				if (Math.abs(keys.get(j).tick() - keys.get(i).tick()) <= PULL_IN_EASE_TICKS) {
					sum += nearest[j];
					count++;
				}
			}
			double distance = sum / count;
			double length = offsets[i].length();
			if (distance < length - 1e-9) {
				offsets[i] = offsets[i].scale(distance / length);
			}
		}
	}

	/** How far back along the player's way a camera buried in rock looks for where they just were. */
	static final int TUNNEL_LOOK_BACK_TICKS = 120;
	/** Following through a tunnel, the camera stays at least this far from the player (their back, not their face). */
	static final double TUNNEL_MIN_DISTANCE = 1.5;
	private static final int TUNNEL_SMOOTH_TICKS = 8;
	/**
	 * Only a camera buried this long follows: one that touches the top of a wall for a key or two (pulled in to keep
	 * someone walking out of their house in sight) would swing round to their back and straight out again.
	 */
	static final int TUNNEL_MIN_TICKS = 10;
	private static final int TUNNEL_FIRST_DELAY = 6;
	/** A little cost per tick of delay: of two equally smooth ways, the one closer behind the player. */
	private static final double TUNNEL_DELAY_COST = 0.002;

	/**
	 * Underground there may be no air at all round the player at a camera's distance (a one-block tunnel, a staircase
	 * mine): the camera ends up inside the rock and the picture shows the caves through the stone. There it follows the
	 * player along the way they came instead, like someone walking behind them: where their eyes were a moment ago, in
	 * the tunnel's air they have just walked through. Of the delays that work at each key, the chain with the least
	 * movement from key to key is taken (Viterbi), so it moves along their path and does not jump from step to step.
	 * Keys that still see the player are left alone; the move in and out is smoothed.
	 */
	private static void followThroughTunnels(List<CameraKey> keys, Vec[] aims, Vec[] offsets, BlockProbe probe, Trail history,
											 BlockProbe dug) {
		int n = keys.size();
		int delays = (TUNNEL_LOOK_BACK_TICKS - TUNNEL_FIRST_DELAY) / 2 + 1;
		// only a camera inside a block, and for a while: one that merely has a wall between it and the player for a
		// moment (them walking out through a door), or touches it, stays where it is
		boolean[] buried = new boolean[n];
		for (int i = 0; i < n; i++) {
			buried[i] = insideBlock(aims[i].add(offsets[i]), probe);
		}
		for (int i = 0; i < n; ) {
			int j = i;
			while (j < n && buried[j] == buried[i]) {
				j++;
			}
			if (buried[i] && keys.get(j - 1).tick() - keys.get(i).tick() < TUNNEL_MIN_TICKS) {
				java.util.Arrays.fill(buried, i, j, false);
			}
			i = j;
		}
		// for each buried key, the cost of following with each delay that works there (cheapest way to it), else none
		double[][] cost = new double[n][];
		int[][] from = new int[n][];
		boolean any = false;
		for (int i = 0; i < n; i++) {
			if (!buried[i]) {
				continue;
			}
			int tick = keys.get(i).tick();
			double[] here = new double[delays];
			int[] way = new int[delays];
			boolean works = false;
			for (int d = 0; d < delays; d++) {
				here[d] = Double.MAX_VALUE;
				way[d] = -1;
				int back = TUNNEL_FIRST_DELAY + 2 * d;
				if (!followable(tick, back, aims[i], history, dug)) {
					continue;
				}
				works = true;
				double best = 0;
				if (i > 0 && cost[i - 1] != null) {
					best = Double.MAX_VALUE;
					Vec at = following(tick, back, history);
					int lastTick = keys.get(i - 1).tick();
					for (int e = 0; e < delays; e++) {
						if (cost[i - 1][e] < Double.MAX_VALUE) {
							double moved = at.distance(following(lastTick, TUNNEL_FIRST_DELAY + 2 * e, history));
							double total = cost[i - 1][e] + moved * moved;
							if (total < best) {
								best = total;
								way[d] = e;
							}
						}
					}
				}
				here[d] = best + back * TUNNEL_DELAY_COST;
			}
			if (works) {
				cost[i] = here;
				from[i] = way;
				any = true;
			}
		}
		if (!any) {
			return;
		}
		// back from the end of each stretch along the cheapest chain
		Vec[] behind = new Vec[n];
		int next = -1;
		for (int i = n - 1; i >= 0; i--) {
			if (cost[i] == null) {
				next = -1;
				continue;
			}
			int d = next;
			if (d < 0 || cost[i][d] == Double.MAX_VALUE) {
				d = 0;
				for (int e = 1; e < delays; e++) {
					if (cost[i][e] < cost[i][d]) {
						d = e;
					}
				}
			}
			behind[i] = following(keys.get(i).tick(), TUNNEL_FIRST_DELAY + 2 * d, history).sub(aims[i]);
			next = from[i][d];
		}
		// in and out of the tunnel over a few ticks
		Vec[] before = offsets.clone();
		for (int i = 0; i < n; i++) {
			if (behind[i] != null) {
				offsets[i] = behind[i];
				continue;
			}
			Vec sum = Vec.ZERO;
			int count = 0;
			boolean near = false;
			for (int j = 0; j < n; j++) {
				if (Math.abs(keys.get(j).tick() - keys.get(i).tick()) <= TUNNEL_SMOOTH_TICKS) {
					sum = sum.add(behind[j] != null ? behind[j] : before[j]);
					count++;
					near |= behind[j] != null;
				}
			}
			if (near) {
				offsets[i] = sum.scale(1.0 / count);
			}
		}
	}

	/** The point is inside something solid (a camera there shows the caves through the stone). */
	public static boolean insideBlock(Vec point, BlockProbe probe) {
		return probe.clearFraction(point, point.add(Vec.UP.scale(0.05))) < 0.5;
	}

	/** Where the player's eyes were {@code back} ticks ago (a hair higher, so a viewer standing there is not in the floor). */
	private static Vec following(int tick, int back, Trail history) {
		return history.positionAt(tick - back).add(Vec.UP.scale(VIEWER_EYE_HEIGHT + 0.05));
	}

	/** From there, far enough from the player not to film their face, nothing between them and room to stand. */
	private static boolean followable(int tick, int back, Vec aim, Trail history, BlockProbe dug) {
		Vec was = following(tick, back, history);
		return was.distance(aim) >= TUNNEL_MIN_DISTANCE && dug.clearFraction(aim, was) >= CLEAR && viewerFits(was, dug);
	}

	/**
	 * How clear the view from {@code aim + offset} to {@code aim} is: the smallest clear fraction of the five rays.
	 */
	public static double visibility(Vec aim, Vec offset, BlockProbe probe) {
		Vec camera = aim.add(offset);
		double worst = probe.clearFraction(aim, camera);
		if (worst < CLEAR) {
			return worst;
		}
		for (Vec around : lensOffsets(offset)) {
			worst = Math.min(worst, probe.clearFraction(aim, camera.add(around)));
			if (worst < CLEAR) {
				return worst;
			}
		}
		return worst;
	}

	/** The four points around the lens the side rays end at. */
	private static Vec[] lensOffsets(Vec offset) {
		Vec forward = offset.scale(-1).normalize();
		Vec right = new Vec(-forward.z(), 0, forward.x()).normalize();
		if (right.equals(Vec.ZERO)) {
			right = new Vec(1, 0, 0); // looking straight up or down
		}
		Vec up = cross(right, forward).normalize();
		return new Vec[]{right.scale(LENS_RADIUS), right.scale(-LENS_RADIUS), up.scale(LENS_RADIUS), up.scale(-LENS_RADIUS)};
	}

	static Vec rotateHorizontally(Vec v, double degrees) {
		double r = Math.toRadians(degrees);
		double cos = Math.cos(r);
		double sin = Math.sin(r);
		return new Vec(v.x() * cos - v.z() * sin, v.y(), v.x() * sin + v.z() * cos);
	}

	private static Vec cross(Vec a, Vec b) {
		return new Vec(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
	}
}
