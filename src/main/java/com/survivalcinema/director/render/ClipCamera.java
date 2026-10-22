package com.survivalcinema.director.render;

import com.survivalcinema.director.camera.BlockProbe;
import com.survivalcinema.director.camera.CameraKey;
import com.survivalcinema.director.camera.CameraPathBuilder;
import com.survivalcinema.director.camera.OcclusionSolver;
import com.survivalcinema.director.camera.ShotType;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.camera.Vec;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.Shot;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the replay needs to shoot one clip of the screenplay: where it is cut into pieces, and per piece the
 * camera keyframes (kept clear of walls) and the speed keyframes for slow motion. Pure logic; the only things taken
 * from the open replay are the {@link BlockProbe} and where a boss was.
 */
public final class ClipCamera {
	/** Pieces shorter than a second are not worth a cut. */
	public static final int MIN_PIECE_TICKS = 20;
	/** Slow motion eases in over this many ticks instead of switching abruptly. */
	static final int SLOW_MOTION_RAMP_TICKS = 10;
	/** The friend of a two-shot, or the boss of a boss scene, has to be known for at least this share of the piece. */
	static final double PARTNER_COVERAGE = 0.5;
	/** Companion positions are stored once a second; this much around the piece keeps their path smooth at the edges. */
	private static final int PARTNER_MARGIN_TICKS = 40;

	private ClipCamera() {
	}

	/** One continuous stretch of a clip; a teleport, respawn or portal inside a clip becomes a cut between pieces. */
	public record Piece(int index, int fromTick, int toTick) {
		public int durationTicks() {
			return toTick - fromTick;
		}
	}

	/** Playback speed from this replay tick on (1 = normal); interpolated linearly to the next key. */
	public record SpeedKey(int tick, float speed) {
	}

	/** @param shot the shot actually used (a two-shot without its partner becomes a static shot) */
	public record Keys(ShotType shot, List<CameraKey> camera, List<SpeedKey> speed) {
	}

	/** The pieces of a clip, or none when the player's path was not recorded there. */
	public static List<Piece> pieces(EditDecisionList.Item item, @Nullable Trail trail) {
		List<Piece> pieces = new ArrayList<>();
		if (trail == null || trail.isEmpty()) {
			return pieces;
		}
		int from = Math.max(item.startTick(), trail.firstTick());
		int to = Math.min(item.endTick(), trail.lastTick());
		if (item.eventType() == EventType.DEATH && item.eventTick() >= from && item.eventTick() < to) {
			// a death ends with the body: the player standing up again in bed is not part of it
			int respawn = trail.respawnAfter(item.eventTick(), to);
			if (respawn > 0) {
				to = respawn - 1;
			}
		}
		if (to - from < MIN_PIECE_TICKS) {
			return pieces;
		}
		for (int[] range : trail.continuousRanges(from, to, MIN_PIECE_TICKS)) {
			// right after a teleport the replay still draws the player flying over from the old place
			int start = trail.settledStart(range[0]);
			if (range[1] - start >= MIN_PIECE_TICKS) {
				pieces.add(new Piece(pieces.size(), start, range[1]));
			}
		}
		return pieces;
	}

	public static Keys build(EditDecisionList.Item item, Piece piece, Trail trail, @Nullable Trail partner, BlockProbe probe) {
		return build(item, piece, trail, partner, null, probe);
	}

	public static Keys build(EditDecisionList.Item item, Piece piece, Trail trail, @Nullable Trail partner, @Nullable Trail boss,
							 BlockProbe probe) {
		return build(item, piece, trail, partner, boss, probe, null);
	}

	/** The shot before ended at most this many ticks before this one starts: the same moment goes on. */
	static final int CUT_WITHIN_TICKS = 20;
	/**
	 * A shot that goes on from the one before glides from where that camera stood to its own place, round the player:
	 * at least this long, longer the further round it has to go (about 25° a second on average, so it is never a
	 * sharp turn), and over at most two thirds of the shot.
	 */
	static final int MIN_GLIDE_TICKS = 40;
	static final double GLIDE_TICKS_PER_DEGREE = 1.2;

	public static Keys build(EditDecisionList.Item item, Piece piece, Trail trail, @Nullable Trail partner, @Nullable Trail boss,
							 BlockProbe probe, @Nullable CameraKey cutFrom) {
		return build(item, piece, trail, partner, boss, probe, cutFrom, null);
	}

	/**
	 * @param boss    where the boss of a boss scene was during the piece ({@link #isBossScene}), read from the replay;
	 *                the scene is then filmed over the player's shoulder towards it, whatever shot was planned
	 * @param cutFrom the last camera key of the shot just before, or null: when it shows the same moment, this shot
	 *                starts where that camera stood and glides to its own place, so the two make one take, not a cut
	 * @param after   the world as it is at the end of the piece, or null: what the player digs during it is gone there
	 *                (the {@code probe} counts it as rock all along), so a camera following them down a mine checks
	 *                its view against this
	 */
	public static Keys build(EditDecisionList.Item item, Piece piece, Trail trail, @Nullable Trail partner, @Nullable Trail boss,
							 BlockProbe probe, @Nullable CameraKey cutFrom, @Nullable BlockProbe after) {
		int from = piece.fromTick();
		int to = piece.toTick();
		Trail slice = trail.slice(from, to).easeSnaps(); // getting into bed and out of it: the camera glides, not jumps
		ShotType shot = item.shot() == null ? ShotType.ORBIT : item.shot();
		CameraPathBuilder.Params params = params(item, shot);

		List<CameraKey> camera;
		if (isBossScene(item) && covers(boss, from, to)) {
			shot = ShotType.OVER_SHOULDER;
			CameraPathBuilder.Params d = CameraPathBuilder.Params.defaults(shot);
			// the planned distance and height were meant for another shot; the side still alternates
			params = new CameraPathBuilder.Params(d.distance(), d.height(), 0, params.side(), d.step());
			Trail seen = boss.slice(from - PARTNER_MARGIN_TICKS, to + PARTNER_MARGIN_TICKS);
			List<CameraKey> planned = CameraPathBuilder.buildOverShoulder(slice, seen, from, to, params);
			camera = CameraPathBuilder.reframe(OcclusionSolver.solve(slice, planned, probe), slice, seen);
		} else if (shot == ShotType.TWO_SHOT && covers(partner, from, to)) {
			Trail friend = partner.slice(from - PARTNER_MARGIN_TICKS, to + PARTNER_MARGIN_TICKS);
			List<CameraKey> planned = CameraPathBuilder.buildTwoShot(slice, friend, from, to, params);
			camera = OcclusionSolver.solve(midpoints(slice, friend), planned, probe);
		} else {
			if (shot == ShotType.TWO_SHOT) {
				shot = ShotType.STATIC_LOOKAT;
			}
			List<CameraKey> planned = CameraPathBuilder.build(slice, shot, from, to, params, item.eventTick());
			camera = OcclusionSolver.solve(slice, planned, probe, shot == ShotType.STATIC_LOOKAT, trail, after);
			if (continues(cutFrom, trail, from)) {
				List<CameraKey> glided = glideFrom(cutFrom, camera, slice);
				// the glide goes round the player without looking where: through the rock of a Nether cave it showed the
				// caves through the stone. Then the two shots cut instead.
				if (glidesClear(glided, camera, slice, after != null ? after : probe)) {
					camera = glided;
				}
			}
		}
		return new Keys(shot, camera, speedKeys(from, to, item.slowMotionFromTick()));
	}

	/** The shot before showed the same moment: it ended just before this piece, with no teleport in between. */
	static boolean continues(@Nullable CameraKey cutFrom, Trail trail, int from) {
		return cutFrom != null && from >= cutFrom.tick() && from - cutFrom.tick() <= CUT_WITHIN_TICKS
			&& trail.continuousRanges(cutFrom.tick(), from, 0).size() == 1;
	}

	/**
	 * The shot's camera taking over from the one before without a cut: it starts where that one stood and glides round
	 * the player (its angle, distance and height from them blending over) to where this shot wants it.
	 */
	public static List<CameraKey> glideFrom(CameraKey before, List<CameraKey> keys, Trail trail) {
		if (keys.isEmpty()) {
			return keys;
		}
		// while the player is still asleep the camera waits where the last shot left it, and sets off when they wake
		int from = keys.getFirst().tick();
		while (from < keys.getLast().tick() && trail.asleepAt(from - CameraPathBuilder.RENDER_DELAY_TICKS)) {
			from++;
		}
		Vec start = before.position().sub(CameraPathBuilder.aimAt(trail, from)); // so it sets off from exactly there
		int glide = (int) Math.max(MIN_GLIDE_TICKS, cutAngle(trail, from, before, keys.getFirst()) * GLIDE_TICKS_PER_DEGREE);
		glide = Math.min(glide, Math.max(1, (keys.getLast().tick() - from) * 2 / 3));
		double startYaw = Math.atan2(start.z(), start.x());
		double lastTurn = Double.NaN;
		double lastYawTurn = Double.NaN;
		List<CameraKey> out = new ArrayList<>(keys.size());
		for (CameraKey key : keys) {
			if (key.tick() < from) {
				out.add(CameraPathBuilder.lookAt(key.tick(), before.position(), CameraPathBuilder.aimAt(trail, key.tick())));
				continue;
			}
			double t = (key.tick() - from) / (double) glide;
			if (t >= 1) {
				out.add(key);
				continue;
			}
			double e = t * t * (3 - 2 * t);
			Vec aim = CameraPathBuilder.aimAt(trail, key.tick());
			Vec end = key.position().sub(aim);
			double endYaw = Math.atan2(end.z(), end.x());
			double turn = Math.atan2(Math.sin(endYaw - startYaw), Math.cos(endYaw - startYaw));
			// round the player the same way all along: with the new shot's place drifting past straight across, the
			// shorter way would flip to the other side and the camera with it
			if (!Double.isNaN(lastTurn)) {
				turn += 2 * Math.PI * Math.round((lastTurn - turn) / (2 * Math.PI));
			}
			lastTurn = turn;
			double yaw = startYaw + turn * e;
			double distance = start.horizontalLength() + (end.horizontalLength() - start.horizontalLength()) * e;
			double height = start.y() + (end.y() - start.y()) * e;
			Vec at = aim.add(new Vec(Math.cos(yaw) * distance, height, Math.sin(yaw) * distance));
			// the view turns over from where the last shot looked (a side shot in flight looks level, not at the player)
			CameraKey looking = CameraPathBuilder.lookAt(key.tick(), at, aim);
			double yawTurn = looking.yaw() - before.yaw();
			yawTurn -= 360 * Math.round((yawTurn - (Double.isNaN(lastYawTurn) ? 0 : lastYawTurn)) / 360);
			lastYawTurn = yawTurn;
			out.add(new CameraKey(key.tick(), at, (float) (before.yaw() + yawTurn * e),
				(float) (before.pitch() + (looking.pitch() - before.pitch()) * e)));
		}
		return CameraPathBuilder.unwrapYaw(out);
	}

	/** A glide may lose sight of the player behind something for at most this share of it (a trunk going by). */
	static final double GLIDE_MAX_HIDDEN = 0.15;

	/**
	 * Where the glide leaves the shot's own path ({@code shot}), its camera is never inside a block and mostly sees the
	 * player.
	 */
	static boolean glidesClear(List<CameraKey> glided, List<CameraKey> shot, Trail trail, BlockProbe probe) {
		int gliding = 0;
		int hidden = 0;
		for (int i = 0; i < glided.size(); i++) {
			CameraKey key = glided.get(i);
			if (key.position().distance(shot.get(i).position()) < 1e-6) {
				continue;
			}
			gliding++;
			if (OcclusionSolver.insideBlock(key.position(), probe)) {
				return false;
			}
			if (probe.clearFraction(CameraPathBuilder.aimAt(trail, key.tick()), key.position()) < 0.99) {
				hidden++;
			}
		}
		return hidden <= gliding * GLIDE_MAX_HIDDEN;
	}

	/** How far round the player (seen from above) the new camera stands from the last one, in degrees. */
	public static double cutAngle(Trail trail, int tick, CameraKey before, CameraKey after) {
		Vec aim = CameraPathBuilder.aimAt(trail, tick);
		Vec a = before.position().sub(aim).horizontal();
		Vec b = after.position().sub(aim).horizontal();
		if (a.length() < 1e-6 || b.length() < 1e-6) {
			return 180;
		}
		return a.angleTo(b);
	}

	/** The screenplay's camera settings, with defaults for anything missing in an old or hand-edited file. */
	static CameraPathBuilder.Params params(EditDecisionList.Item item, ShotType shot) {
		CameraPathBuilder.Params d = CameraPathBuilder.Params.defaults(shot);
		double distance = item.distance() > 0 ? item.distance() : d.distance();
		int side = item.side() == 0 ? d.side() : Integer.signum(item.side());
		return new CameraPathBuilder.Params(distance, item.height(), item.sweep(), side, d.step());
	}

	/** A boss fight or its end: filmed towards the boss when the replay shows where it is. */
	public static boolean isBossScene(EditDecisionList.Item item) {
		return item.kind() == EditDecisionList.Kind.CLIP && (item.eventType() == EventType.DRAGON_KILLED
			|| item.eventType() == EventType.BOSS_DEFEATED || item.eventType() == EventType.BOSS_FIGHT_START);
	}

	static boolean covers(@Nullable Trail partner, int from, int to) {
		if (partner == null || partner.isEmpty()) {
			return false;
		}
		int overlap = Math.min(to, partner.lastTick()) - Math.max(from, partner.firstTick());
		return overlap >= (to - from) * PARTNER_COVERAGE;
	}

	/** The point between the two friends over time, so obstacles are checked against what the two-shot looks at. */
	static Trail midpoints(Trail a, Trail b) {
		List<TrailSample> samples = new ArrayList<>();
		for (TrailSample s : a.samples()) {
			Vec mid = s.position().lerp(b.positionAt(s.tick()), 0.5);
			samples.add(new TrailSample(s.tick(), mid.x(), mid.y(), mid.z(), s.yaw(), s.pitch(), s.flags()));
		}
		return new Trail(samples);
	}

	/**
	 * Slow motion from {@code slowFromTick} to the end of the piece, eased in over the ticks before it.
	 * Empty when the piece has no slow motion.
	 */
	public static List<SpeedKey> speedKeys(int fromTick, int toTick, int slowFromTick) {
		List<SpeedKey> keys = new ArrayList<>();
		if (slowFromTick < 0 || slowFromTick >= toTick) {
			return keys;
		}
		if (slowFromTick <= fromTick) {
			keys.add(new SpeedKey(fromTick, Shot.SLOW_MOTION_SPEED));
			return keys;
		}
		int rampStart = Math.max(fromTick, slowFromTick - SLOW_MOTION_RAMP_TICKS);
		if (rampStart > fromTick) {
			keys.add(new SpeedKey(fromTick, 1f));
		}
		keys.add(new SpeedKey(rampStart, 1f));
		keys.add(new SpeedKey(slowFromTick, Shot.SLOW_MOTION_SPEED));
		return keys;
	}
}
