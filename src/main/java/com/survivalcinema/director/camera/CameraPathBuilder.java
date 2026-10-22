package com.survivalcinema.director.camera;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a player's trail into camera keyframes for one shot. Pure math, no Minecraft classes, so it is unit tested.
 */
public final class CameraPathBuilder {
	/** Height above the player's feet the camera aims at (roughly chest/face). */
	private static final double AIM_HEIGHT = 1.3;
	/** How lazily the aim height follows jumps, in ticks (past only). */
	private static final double HEIGHT_LAG_TICKS = 4;
	/** Ticks of past movement that decide which way the player is heading. */
	private static final double HEADING_SMOOTHING = 10;
	/**
	 * Minecraft draws other players (and the replayed player is one) interpolated over 3 steps, which with a
	 * position update every tick leaves the visible body 2 ticks behind its real position. The camera follows
	 * the body you see, not the real position, or it ends up slightly ahead.
	 */
	public static final double RENDER_DELAY_TICKS = 2;
	/** How lazily the camera follows the player's heading, like a human operator (seconds). */
	private static final double HEADING_LAG_SECONDS = 0.5;
	/** The camera never swings around the player faster than this (degrees per second). */
	public static final double MAX_HEADING_DEGREES_PER_SECOND = 50;
	/** Swings around the player start and stop gently over about this long, instead of at full speed at once. */
	private static final double HEADING_EASE_SECONDS = 0.25;

	private CameraPathBuilder() {
	}

	/**
	 * @param distance horizontal distance from the player in blocks
	 * @param height   camera height above the aim point in blocks
	 * @param sweep    degrees an orbit travels over the whole shot (sign = direction)
	 * @param side     +1 or -1: which side SIDE and STATIC_LOOKAT shots film from
	 * @param step     ticks between keyframes
	 */
	public record Params(double distance, double height, double sweep, int side, int step) {
		public static Params defaults(ShotType shot) {
			return switch (shot) {
				case CHASE -> new Params(4.5, 1.2, 0, 1, 2);
				case ORBIT -> new Params(6, 1.5, 180, 1, 2);
				case SIDE -> new Params(5, 0.6, 0, 1, 2);
				case STATIC_LOOKAT -> new Params(8, 2.5, 0, 1, 2);
				case PULLBACK -> new Params(3, 0.8, 0, 1, 2);
				case TWO_SHOT -> new Params(4.5, 0.6, 0, 1, 2);
				case OVER_SHOULDER -> new Params(7, 1.6, 0, 1, 2);
			};
		}
	}

	public static List<CameraKey> build(Trail trail, ShotType shot, int startTick, int endTick, Params params) {
		return build(trail, shot, startTick, endTick, params, -1);
	}

	/**
	 * @param momentTick the tick of the shot's main moment (a death, a shout), or -1: a static camera is set up to
	 *                   film that moment from the planned spot
	 */
	public static List<CameraKey> build(Trail trail, ShotType shot, int startTick, int endTick, Params params, int momentTick) {
		List<CameraKey> keys = new ArrayList<>();
		if (trail.isEmpty() || endTick <= startTick) {
			return keys;
		}
		int step = Math.max(1, params.step());
		List<Integer> ticks = new ArrayList<>();
		for (int tick = startTick; ; tick = Math.min(endTick, tick + step)) {
			ticks.add(tick);
			if (tick >= endTick) {
				break;
			}
		}
		double[] rawHeadings = new double[ticks.size()];
		for (int i = 0; i < ticks.size(); i++) {
			rawHeadings[i] = trail.headingAt(ticks.get(i) - RENDER_DELAY_TICKS, HEADING_SMOOTHING).yaw();
		}
		double[] headings = smoothOverTime(ticks,
			followAngles(ticks, rawHeadings, HEADING_LAG_SECONDS, MAX_HEADING_DEGREES_PER_SECOND), HEADING_EASE_SECONDS);

		double startHeadingYaw = headings[0];
		Vec[] tripod = shot == ShotType.STATIC_LOOKAT || shot == ShotType.TWO_SHOT
			? tripodPath(trail, ticks, headings, params, momentTick) : null;
		double[] moving = awakeProgress(trail, ticks, startTick, endTick);

		for (int i = 0; i < ticks.size(); i++) {
			int tick = ticks.get(i);
			// how far a moving shot has come: it goes on at its pace while the player is awake, and waits while they sleep
			double progress = moving[i];
			Vec aim = aimAt(trail, tick);
			Vec heading = Vec.fromYaw(headings[i]);

			Vec camera = switch (shot) {
				// without a boss to look at, a shot over the shoulder is a chase
				case CHASE, OVER_SHOULDER -> aim.sub(heading.scale(params.distance())).add(Vec.UP.scale(params.height()));
				case ORBIT -> {
					double angle = startHeadingYaw + 180 + params.sweep() * progress;
					yield aim.add(Vec.fromYaw(angle).scale(params.distance())).add(Vec.UP.scale(params.height()));
				}
				case SIDE -> {
					Vec right = new Vec(-heading.z(), 0, heading.x()).scale(params.side());
					yield aim.add(right.scale(params.distance())).add(Vec.UP.scale(params.height()));
				}
				case STATIC_LOOKAT, TWO_SHOT -> tripod[i]; // a two-shot without a partner falls back to a static shot
				case PULLBACK -> {
					double eased = easeInOut(progress);
					double dist = params.distance() * (1 + 2.5 * eased);
					double up = params.height() + 6 * eased;
					yield aim.add(Vec.fromYaw(startHeadingYaw + 160 * params.side()).scale(dist)).add(Vec.UP.scale(up));
				}
			};
			keys.add(lookAt(tick, camera, aim));
		}
		return unwrapYaw(keys);
	}

	/**
	 * For each key, the share of the shot's time the player has been awake so far: the time itself when they never
	 * sleep, and standing still for as long as they lie asleep in bed.
	 */
	static double[] awakeProgress(Trail trail, List<Integer> ticks, int startTick, int endTick) {
		double[] progress = new double[ticks.size()];
		double awake = 0;
		for (int i = 1; i < ticks.size(); i++) {
			int from = ticks.get(i - 1);
			int to = ticks.get(i);
			if (!trail.asleepAt((from + to) / 2.0 - RENDER_DELAY_TICKS)) {
				awake += to - from;
			}
			progress[i] = awake / (endTick - startTick);
		}
		return progress;
	}

	/** A static camera is carried after a player who gets further away than this many times its distance. */
	static final double TRIPOD_LEASH = 2.5;
	/** A static camera keeps its height while the player is within this many blocks of it up or down (jumps, steps). */
	static final double TRIPOD_DEAD_ZONE = 1.5;
	private static final double TRIPOD_SMOOTHING_SECONDS = 0.4;

	/**
	 * Where a static camera stands at each key: on a tripod at the planned spot next to the shot's main moment, which
	 * a crane lowers and lifts with the player when they fall or climb (a camera left at the top of a fall films the
	 * body from far above), and which is carried after them only when they walk out of its reach.
	 */
	static Vec[] tripodPath(Trail trail, List<Integer> ticks, double[] headings, Params params, int momentTick) {
		int n = ticks.size();
		int anchor = 0;
		if (momentTick > ticks.getFirst() && momentTick <= ticks.getLast()) {
			while (anchor + 1 < n && ticks.get(anchor + 1) <= momentTick) {
				anchor++;
			}
		}
		Vec[] aims = new Vec[n];
		for (int i = 0; i < n; i++) {
			aims[i] = aimAt(trail, ticks.get(i));
		}
		Vec spot = aims[anchor].add(Vec.fromYaw(headings[anchor] + 140 * params.side()).scale(params.distance()));
		double[] x = new double[n];
		double[] z = new double[n];
		double leash = params.distance() * TRIPOD_LEASH;
		for (int direction : new int[]{-1, 1}) {
			double cx = spot.x();
			double cz = spot.z();
			for (int i = direction < 0 ? anchor : anchor + 1; i >= 0 && i < n; i += direction) {
				double dx = aims[i].x() - cx;
				double dz = aims[i].z() - cz;
				double d = Math.hypot(dx, dz);
				if (d > leash) {
					cx += dx / d * (d - leash);
					cz += dz / d * (d - leash);
				}
				x[i] = cx;
				z[i] = cz;
			}
		}
		double[] y = new double[n];
		double base = aims[0].y();
		for (int i = 0; i < n; i++) {
			base = Math.max(aims[i].y() - TRIPOD_DEAD_ZONE, Math.min(aims[i].y() + TRIPOD_DEAD_ZONE, base));
			y[i] = base + params.height();
		}
		x = smoothOverTime(ticks, x, TRIPOD_SMOOTHING_SECONDS);
		y = smoothOverTime(ticks, y, TRIPOD_SMOOTHING_SECONDS);
		z = smoothOverTime(ticks, z, TRIPOD_SMOOTHING_SECONDS);
		Vec[] path = new Vec[n];
		for (int i = 0; i < n; i++) {
			path[i] = new Vec(x[i], y[i], z[i]);
		}
		return path;
	}

	/**
	 * Makes an angle lazily follow its target: exponential smoothing with time constant {@code lagSeconds},
	 * plus a hard cap on angular speed. This is what stops the camera from snapping behind the player.
	 */
	static double[] followAngles(List<Integer> ticks, double[] targets, double lagSeconds, double maxDegreesPerSecond) {
		double[] out = new double[targets.length];
		if (targets.length == 0) {
			return out;
		}
		double current = targets[0];
		out[0] = current;
		for (int i = 1; i < targets.length; i++) {
			double dt = (ticks.get(i) - ticks.get(i - 1)) / 20.0;
			double delta = wrapDegrees(targets[i] - current);
			double alpha = 1 - Math.exp(-dt / lagSeconds);
			double change = delta * alpha;
			double maxChange = maxDegreesPerSecond * dt;
			change = Math.max(-maxChange, Math.min(maxChange, change));
			current += change;
			out[i] = current;
		}
		return out;
	}

	/**
	 * A Gaussian blur over time ({@code sigmaSeconds}; at the ends of the shot, over what there is). Smooths the starts
	 * and stops of a movement; never makes it faster.
	 */
	static double[] smoothOverTime(List<Integer> ticks, double[] values, double sigmaSeconds) {
		int n = values.length;
		double[] out = new double[n];
		double sigma = sigmaSeconds * 20;
		double reach = 3 * sigma;
		for (int i = 0; i < n; i++) {
			double sum = 0;
			double weight = 0;
			for (int j = i; j >= 0 && ticks.get(i) - ticks.get(j) <= reach; j--) {
				double w = gaussian(ticks.get(i) - ticks.get(j), sigma);
				sum += values[j] * w;
				weight += w;
			}
			for (int j = i + 1; j < n && ticks.get(j) - ticks.get(i) <= reach; j++) {
				double w = gaussian(ticks.get(j) - ticks.get(i), sigma);
				sum += values[j] * w;
				weight += w;
			}
			out[i] = sum / weight;
		}
		return out;
	}

	private static double gaussian(double distance, double sigma) {
		return Math.exp(-0.5 * distance * distance / (sigma * sigma));
	}

	private static double wrapDegrees(double degrees) {
		double d = degrees % 360;
		if (d > 180) {
			d -= 360;
		} else if (d <= -180) {
			d += 360;
		}
		return d;
	}

	/** Smoothing of the two-shot camera over neighbouring keyframes (each side), so small steps don't shake it. */
	private static final int TWO_SHOT_SMOOTHING_KEYS = 10;

	/**
	 * A dialogue shot: the camera stands to the side of the line between the two speakers, far enough to have both
	 * in frame, and looks at the point between them. Which side is chosen by {@code params.side()}.
	 */
	public static List<CameraKey> buildTwoShot(Trail a, Trail b, int startTick, int endTick, Params params) {
		List<CameraKey> keys = new ArrayList<>();
		if (a.isEmpty() || b.isEmpty() || endTick <= startTick) {
			return keys;
		}
		int step = Math.max(1, params.step());
		List<Integer> ticks = new ArrayList<>();
		for (int tick = startTick; ; tick = Math.min(endTick, tick + step)) {
			ticks.add(tick);
			if (tick >= endTick) {
				break;
			}
		}
		Vec[] mids = new Vec[ticks.size()];
		Vec[] cameras = new Vec[ticks.size()];
		Vec lastNormal = null;
		for (int i = 0; i < ticks.size(); i++) {
			int tick = ticks.get(i);
			Vec aimA = aimAt(a, tick);
			Vec aimB = aimAt(b, tick);
			Vec mid = aimA.lerp(aimB, 0.5);
			Vec axis = aimB.sub(aimA).horizontal();
			Vec normal = axis.length() < 0.5
				? (lastNormal != null ? lastNormal : Vec.fromYaw(a.sampleAt(tick).yaw() + 90))
				: new Vec(-axis.z(), 0, axis.x()).normalize().scale(params.side());
			if (lastNormal != null && normal.x() * lastNormal.x() + normal.z() * lastNormal.z() < 0) {
				normal = normal.scale(-1); // they swapped places: stay on the same side instead of jumping over
			}
			lastNormal = normal;
			double distance = Math.max(params.distance(), axis.length() * 0.9 + 3);
			mids[i] = mid;
			cameras[i] = mid.add(normal.scale(distance)).add(Vec.UP.scale(params.height()));
		}
		for (int i = 0; i < ticks.size(); i++) {
			Vec camera = Vec.ZERO;
			Vec mid = Vec.ZERO;
			int n = 0;
			for (int j = Math.max(0, i - TWO_SHOT_SMOOTHING_KEYS); j <= Math.min(ticks.size() - 1, i + TWO_SHOT_SMOOTHING_KEYS); j++) {
				camera = camera.add(cameras[j]);
				mid = mid.add(mids[j]);
				n++;
			}
			keys.add(lookAt(ticks.get(i), camera.scale(1.0 / n), mid.scale(1.0 / n)));
		}
		return unwrapYaw(keys);
	}

	/** How far to one side of the line from the boss through the player the camera stands, so the player hides less of it. */
	private static final double SHOULDER_DEGREES = 25;
	/**
	 * Seen from the camera, the player and the boss may be at most this far apart, so both fit into the picture
	 * (Minecraft's view is 70 degrees high); when they are further apart, the camera steps back.
	 */
	private static final double FIT_DEGREES = 46;
	private static final double MAX_SHOULDER_DISTANCE = 15;
	/** The player always stays this close to the middle of the picture, even when the boss does not fit. */
	private static final double PLAYER_OFF_CENTRE_DEGREES = 22;
	/** How lazily the camera follows a boss that flies around, and how gently it steps back and forth (seconds). */
	private static final double SUBJECT_LAG_SECONDS = 0.8;
	private static final double SUBJECT_SMOOTHING_SECONDS = 0.5;
	private static final double DISTANCE_EASE_SECONDS = 1;

	/**
	 * A boss shot: the camera stands behind the player as seen from the boss, a little to one side, and looks past the
	 * player at it, so the player is in front and the boss beyond, both in frame. It steps back when the boss is
	 * high above or close by, and turns slowly with a boss flying around. Which side by {@code params.side()}.
	 *
	 * @param subject where the boss was (the middle of its body), sampled every few ticks
	 */
	public static List<CameraKey> buildOverShoulder(Trail trail, Trail subject, int startTick, int endTick, Params params) {
		List<CameraKey> keys = new ArrayList<>();
		if (trail.isEmpty() || subject.isEmpty() || endTick <= startTick) {
			return keys;
		}
		List<Integer> ticks = keyTicks(startTick, endTick, params.step());
		int n = ticks.size();
		Vec[] aims = new Vec[n];
		Vec[] bosses = subjectPath(subject, ticks);
		double[] towards = new double[n];
		for (int i = 0; i < n; i++) {
			aims[i] = aimAt(trail, ticks.get(i));
			Vec toBoss = bosses[i].sub(aims[i]);
			// right under the boss "towards it" means nothing: keep the last direction (or where the player looks)
			towards[i] = toBoss.horizontalLength() >= 1 ? toBoss.yaw()
				: i > 0 ? towards[i - 1] : trail.headingAt(ticks.get(i) - RENDER_DELAY_TICKS, HEADING_SMOOTHING).yaw();
		}
		towards = smoothOverTime(ticks, followAngles(ticks, towards, SUBJECT_LAG_SECONDS, MAX_HEADING_DEGREES_PER_SECOND),
			HEADING_EASE_SECONDS);
		double[] distances = new double[n];
		for (int i = 0; i < n; i++) {
			distances[i] = fittingDistance(aims[i], bosses[i], towards[i], params);
		}
		distances = smoothOverTime(ticks, distances, DISTANCE_EASE_SECONDS);
		for (int i = 0; i < n; i++) {
			keys.add(frameBoth(ticks.get(i), behind(aims[i], towards[i], distances[i], params), aims[i], bosses[i]));
		}
		return unwrapYaw(keys);
	}

	/** Turns keys built for a boss shot (moved around obstacles since) to look at the player and the boss again. */
	public static List<CameraKey> reframe(List<CameraKey> keys, Trail trail, Trail subject) {
		List<Integer> ticks = new ArrayList<>(keys.size());
		for (CameraKey key : keys) {
			ticks.add(key.tick());
		}
		Vec[] bosses = subjectPath(subject, ticks);
		List<CameraKey> framed = new ArrayList<>(keys.size());
		for (int i = 0; i < keys.size(); i++) {
			CameraKey key = keys.get(i);
			framed.add(frameBoth(key.tick(), key.position(), aimAt(trail, key.tick()), bosses[i]));
		}
		return unwrapYaw(framed);
	}

	/** Where the boss is seen at each tick (drawn late like a player), its few samples smoothed into a gentle path. */
	static Vec[] subjectPath(Trail subject, List<Integer> ticks) {
		int n = ticks.size();
		double[] x = new double[n];
		double[] y = new double[n];
		double[] z = new double[n];
		for (int i = 0; i < n; i++) {
			Vec p = subject.positionAt(ticks.get(i) - RENDER_DELAY_TICKS);
			x[i] = p.x();
			y[i] = p.y();
			z[i] = p.z();
		}
		x = smoothOverTime(ticks, x, SUBJECT_SMOOTHING_SECONDS);
		y = smoothOverTime(ticks, y, SUBJECT_SMOOTHING_SECONDS);
		z = smoothOverTime(ticks, z, SUBJECT_SMOOTHING_SECONDS);
		Vec[] path = new Vec[n];
		for (int i = 0; i < n; i++) {
			path[i] = new Vec(x[i], y[i], z[i]);
		}
		return path;
	}

	private static Vec behind(Vec aim, double towardsYaw, double distance, Params params) {
		return aim.add(Vec.fromYaw(towardsYaw + 180 + SHOULDER_DEGREES * params.side()).scale(distance))
			.add(Vec.UP.scale(params.height()));
	}

	/** The nearest distance (from the planned one on) at which the player and the boss both fit into the picture. */
	private static double fittingDistance(Vec aim, Vec boss, double towardsYaw, Params params) {
		for (double distance = params.distance(); distance < MAX_SHOULDER_DISTANCE; distance += 0.5) {
			Vec camera = behind(aim, towardsYaw, distance, params);
			if (aim.sub(camera).angleTo(boss.sub(camera)) <= FIT_DEGREES) {
				return distance;
			}
		}
		return MAX_SHOULDER_DISTANCE;
	}

	/**
	 * A key at {@code camera} that looks between the player and the boss, so both are in the picture; when they are too
	 * far apart for that, the player stays in it and the boss is cut.
	 */
	public static CameraKey frameBoth(int tick, Vec camera, Vec player, Vec boss) {
		Vec toPlayer = player.sub(camera).normalize();
		Vec view = toPlayer.add(boss.sub(camera).normalize()).normalize();
		if (view.equals(Vec.ZERO)) {
			view = toPlayer; // the boss right behind the camera
		}
		double off = view.angleTo(toPlayer);
		if (off > PLAYER_OFF_CENTRE_DEGREES) {
			view = turnTowards(toPlayer, view, PLAYER_OFF_CENTRE_DEGREES);
		}
		return new CameraKey(tick, camera, (float) view.yaw(), (float) view.pitch());
	}

	/** The unit direction {@code degrees} from {@code from} on the way to {@code to} (both unit vectors). */
	private static Vec turnTowards(Vec from, Vec to, double degrees) {
		double angle = Math.toRadians(from.angleTo(to));
		double turn = Math.toRadians(degrees);
		if (angle < 1e-9) {
			return from;
		}
		double sin = Math.sin(angle);
		return from.scale(Math.sin(angle - turn) / sin).add(to.scale(Math.sin(turn) / sin)).normalize();
	}

	private static List<Integer> keyTicks(int startTick, int endTick, int step) {
		List<Integer> ticks = new ArrayList<>();
		for (int tick = startTick; ; tick = Math.min(endTick, tick + Math.max(1, step))) {
			ticks.add(tick);
			if (tick >= endTick) {
				break;
			}
		}
		return ticks;
	}

	public static CameraKey lookAt(int tick, Vec camera, Vec target) {
		Vec dir = target.sub(camera);
		return new CameraKey(tick, camera, (float) dir.yaw(), (float) dir.pitch());
	}

	/**
	 * Shifts yaws by multiples of 360 so neighbouring keyframes never differ by more than 180 degrees;
	 * otherwise interpolation would spin the camera the long way round.
	 */
	public static List<CameraKey> unwrapYaw(List<CameraKey> keys) {
		List<CameraKey> result = new ArrayList<>(keys.size());
		Float previous = null;
		for (CameraKey key : keys) {
			float yaw = key.yaw();
			if (previous != null) {
				while (yaw - previous > 180) {
					yaw -= 360;
				}
				while (yaw - previous < -180) {
					yaw += 360;
				}
			}
			result.add(new CameraKey(key.tick(), key.position(), yaw, key.pitch()));
			previous = yaw;
		}
		return result;
	}

	/**
	 * The point on the player the camera aims at: exactly where they are horizontally, with jumps smoothed out
	 * of the height. Built from the past only, so the camera can never run ahead of the player.
	 */
	public static Vec aimAt(Trail trail, double tick) {
		double visible = tick - RENDER_DELAY_TICKS;
		Vec p = trail.positionAt(visible);
		return new Vec(p.x(), trail.laggedY(visible, HEIGHT_LAG_TICKS) + AIM_HEIGHT, p.z());
	}

	private static double easeInOut(double t) {
		return t * t * (3 - 2 * t);
	}
}
