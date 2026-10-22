package com.survivalcinema.director.camera;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The recorded path of a player, with interpolation and smoothing so cameras built on it don't jitter.
 */
public final class Trail {
	/**
	 * Between these speeds (blocks per second) the heading blends from "where they face" to "where they move".
	 * A hard threshold made the camera snap behind the player whenever they stopped.
	 */
	private static final double FACING_ONLY_SPEED = 0.3;
	private static final double MOVEMENT_ONLY_SPEED = 2.0;

	private final List<TrailSample> samples;

	public Trail(List<TrailSample> samples) {
		List<TrailSample> sorted = new ArrayList<>(samples);
		sorted.sort(Comparator.comparingInt(TrailSample::tick));
		this.samples = List.copyOf(sorted);
	}

	public boolean isEmpty() {
		return samples.isEmpty();
	}

	public int firstTick() {
		return samples.isEmpty() ? 0 : samples.getFirst().tick();
	}

	public int lastTick() {
		return samples.isEmpty() ? 0 : samples.getLast().tick();
	}

	public List<TrailSample> samples() {
		return samples;
	}

	/**
	 * Moving faster than this (5 blocks per tick = 100 blocks/s, beyond even an elytra dive) means a teleport,
	 * respawn or portal. Short jumps under {@link #TELEPORT_MIN_BLOCKS} never count, whatever the sample spacing.
	 */
	private static final double TELEPORT_BLOCKS_PER_TICK = 5;
	private static final double TELEPORT_MIN_BLOCKS = 8;

	/**
	 * After a teleport the replay still draws the player on the way from the old place to the new one for a few ticks
	 * (other players are interpolated), so footage starting right on it opens on an empty picture and the player pops
	 * in. Footage starts this much later ({@link #settledStart}).
	 */
	public static final int SETTLE_TICKS = 5;
	/**
	 * Recordings made before {@link TrailSample#FLAG_DEAD}: a dead body lies perfectly still, and the respawn moves the
	 * player at once. A body still for this long that then moves more than {@link #RESPAWN_MIN_BLOCKS} in one sample
	 * has respawned.
	 */
	private static final int STILL_BODY_TICKS = 10;
	private static final double RESPAWN_MIN_BLOCKS = 1.5;

	/**
	 * Splits {@code [startTick, endTick]} at teleports, respawns and dimension changes, where the camera must cut
	 * instead of flying across the map. Pieces shorter than {@code minTicks} are dropped.
	 */
	public List<int[]> continuousRanges(int startTick, int endTick, int minTicks) {
		List<int[]> ranges = new ArrayList<>();
		int rangeStart = startTick;
		TrailSample previous = null;
		for (TrailSample sample : samples) {
			if (sample.tick() < startTick) {
				previous = sample;
				continue;
			}
			if (sample.tick() > endTick) {
				break;
			}
			if (previous != null && isJump(previous, sample)) {
				addRange(ranges, rangeStart, previous.tick(), minTicks);
				rangeStart = sample.tick();
			}
			previous = sample;
		}
		addRange(ranges, rangeStart, endTick, minTicks);
		return ranges;
	}

	/**
	 * The first tick from {@code tick} on where the replay shows the player where they are: {@link #SETTLE_TICKS}
	 * after a jump that happened just before (or at) {@code tick}, otherwise {@code tick} itself.
	 */
	public int settledStart(int tick) {
		TrailSample previous = null;
		int settled = tick;
		for (TrailSample sample : samples) {
			if (sample.tick() > tick) {
				break;
			}
			if (previous != null && sample.tick() > tick - SETTLE_TICKS && isJump(previous, sample)) {
				settled = sample.tick() + SETTLE_TICKS;
			}
			previous = sample;
		}
		return Math.max(tick, settled);
	}

	private static boolean isJump(TrailSample a, TrailSample b) {
		boolean dimensionChanged = (a.flags() >> 8) != (b.flags() >> 8);
		boolean respawned = a.has(TrailSample.FLAG_DEAD) && !b.has(TrailSample.FLAG_DEAD);
		int ticks = Math.max(1, b.tick() - a.tick());
		double limit = Math.max(TELEPORT_MIN_BLOCKS, TELEPORT_BLOCKS_PER_TICK * ticks);
		return dimensionChanged || respawned || a.position().distance(b.position()) > limit;
	}

	/**
	 * The tick the player respawned after dying at {@code deathTick} (the first sample alive again), or -1 when they
	 * did not respawn before {@code untilTick}. Recordings that mark the dead know it; for older ones the body lying
	 * still and then moving at once gives it away.
	 */
	public int respawnAfter(int deathTick, int untilTick) {
		boolean marked = false;
		for (TrailSample sample : samples) {
			if (sample.tick() >= deathTick && sample.tick() <= untilTick && sample.has(TrailSample.FLAG_DEAD)) {
				marked = true;
				break;
			}
		}
		TrailSample previous = null;
		int stillSince = Integer.MAX_VALUE;
		for (TrailSample sample : samples) {
			if (sample.tick() > untilTick) {
				break;
			}
			if (sample.tick() < deathTick || previous == null) {
				previous = sample;
				continue;
			}
			if (marked) {
				if (previous.has(TrailSample.FLAG_DEAD) && !sample.has(TrailSample.FLAG_DEAD)) {
					return sample.tick();
				}
			} else {
				double moved = previous.position().distance(sample.position());
				if (moved < 1e-3) {
					stillSince = Math.min(stillSince, previous.tick());
				} else {
					if (moved > RESPAWN_MIN_BLOCKS && previous.tick() - stillSince >= STILL_BODY_TICKS) {
						return sample.tick();
					}
					stillSince = Integer.MAX_VALUE;
				}
			}
			previous = sample;
		}
		return -1;
	}

	private static void addRange(List<int[]> ranges, int start, int end, int minTicks) {
		if (end - start >= minTicks) {
			ranges.add(new int[]{start, end});
		}
	}

	/** A player standing (or lying) still who is then this far away one sample later was snapped there by the game. */
	private static final double SNAP_MIN_BLOCKS = 0.6;
	private static final double STILL_BLOCKS = 0.15;
	/** Over how many ticks a camera glides after such a snap, instead of jumping with it. */
	static final int SNAP_EASE_TICKS = 12;

	/**
	 * The trail with the game's small snaps eased out: lying down in a bed and getting up again move the player a block
	 * or more at once (a short ender pearl too). The camera follows that glide, so it does not zoom or swing in a
	 * single frame; the player still snaps in the picture, as they did. Real teleports are cuts and stay as they are.
	 */
	public Trail easeSnaps() {
		List<TrailSample> eased = new ArrayList<>(samples);
		for (int i = 2; i < samples.size(); i++) {
			TrailSample before = samples.get(i - 2);
			TrailSample previous = samples.get(i - 1);
			TrailSample snap = samples.get(i);
			double moved = previous.position().distance(snap.position());
			if (moved < SNAP_MIN_BLOCKS || isJump(previous, snap) || snap.tick() - previous.tick() > 4
				|| before.position().distance(previous.position()) > STILL_BLOCKS) {
				continue;
			}
			Vec back = previous.position().sub(snap.position());
			int span = SNAP_EASE_TICKS + snap.tick() - previous.tick();
			for (int j = i; j < samples.size() && samples.get(j).tick() < previous.tick() + span; j++) {
				double t = (samples.get(j).tick() - previous.tick()) / (double) span;
				Vec shift = back.scale(1 - smoothstep(0, 1, t));
				TrailSample s = eased.get(j);
				eased.set(j, new TrailSample(s.tick(), s.x() + shift.x(), s.y() + shift.y(), s.z() + shift.z(), s.yaw(), s.pitch(), s.flags()));
			}
		}
		return new Trail(eased);
	}

	/** The part of the trail inside {@code [fromTick, toTick]}, so smoothing never blends across a cut. */
	public Trail slice(int fromTick, int toTick) {
		List<TrailSample> part = new ArrayList<>();
		for (TrailSample sample : samples) {
			if (sample.tick() >= fromTick && sample.tick() <= toTick) {
				part.add(sample);
			}
		}
		return new Trail(part);
	}

	/** Linear interpolation between recorded samples, clamped at both ends. */
	public Vec positionAt(double tick) {
		if (samples.isEmpty()) {
			return Vec.ZERO;
		}
		int i = indexAtOrBefore(tick);
		if (i < 0) {
			return samples.getFirst().position();
		}
		TrailSample a = samples.get(i);
		if (i + 1 >= samples.size()) {
			return a.position();
		}
		TrailSample b = samples.get(i + 1);
		double span = b.tick() - a.tick();
		double t = span <= 0 ? 0 : (tick - a.tick()) / span;
		return a.position().lerp(b.position(), Math.max(0, Math.min(1, t)));
	}

	public TrailSample sampleAt(double tick) {
		int i = indexAtOrBefore(tick);
		return samples.get(Math.max(0, i));
	}

	/** In bed, asleep (never, in recordings made before this was written down). */
	public boolean asleepAt(double tick) {
		return !samples.isEmpty() && sampleAt(tick).has(TrailSample.FLAG_SLEEPING);
	}

	/** Gaussian-weighted average position over +-window ticks. */
	public Vec smoothedPositionAt(double tick, double window) {
		if (window <= 0) {
			return positionAt(tick);
		}
		double sigma = window / 2;
		double sumW = 0;
		double x = 0, y = 0, z = 0;
		for (double d = -window; d <= window; d += 1) {
			double w = Math.exp(-(d * d) / (2 * sigma * sigma));
			Vec p = positionAt(tick + d);
			x += p.x() * w;
			y += p.y() * w;
			z += p.z() * w;
			sumW += w;
		}
		return new Vec(x / sumW, y / sumW, z / sumW);
	}

	/**
	 * Height smoothed over the recent past only, so jumping doesn't bob the camera. Never looks at future samples:
	 * a camera that knows where the player is going arrives there before them.
	 */
	public double laggedY(double tick, double lagTicks) {
		if (lagTicks <= 0) {
			return positionAt(tick).y();
		}
		double alpha = 1 - Math.exp(-1 / lagTicks);
		double from = tick - Math.ceil(lagTicks * 6);
		double y = positionAt(from).y();
		for (double t = from + 1; t <= tick; t += 1) {
			y += (positionAt(t).y() - y) * alpha;
		}
		return y;
	}

	/**
	 * Horizontal unit direction the player is heading: where they have been moving over the last {@code window}
	 * ticks, or where they face when standing still. Only uses the past, never future samples.
	 */
	public Vec headingAt(double tick, double window) {
		Vec before = positionAt(tick - window);
		Vec now = positionAt(tick);
		Vec movement = now.sub(before).horizontal();
		double blocksPerSecond = movement.length() / (window / 20.0);
		double w = smoothstep(FACING_ONLY_SPEED, MOVEMENT_ONLY_SPEED, blocksPerSecond);
		Vec facing = smoothedFacingAt(tick, window);
		Vec blended = movement.normalize().scale(w).add(facing.scale(1 - w)).normalize();
		// Walking straight backwards cancels out; fall back to the movement direction then.
		return blended.equals(Vec.ZERO) ? movement.normalize() : blended;
	}

	private static double smoothstep(double edge0, double edge1, double x) {
		double t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
		return t * t * (3 - 2 * t);
	}

	/**
	 * Average of the recent facing directions (averaging unit vectors avoids the 359 -> 0 degree jump).
	 * Past only, like {@link #headingAt}.
	 */
	public Vec smoothedFacingAt(double tick, double window) {
		Vec sum = Vec.ZERO;
		for (double d = -window; d <= 0; d += 2) {
			sum = sum.add(Vec.fromYaw(sampleAt(tick + d).yaw()));
		}
		Vec dir = sum.normalize();
		return dir.equals(Vec.ZERO) ? Vec.fromYaw(sampleAt(tick).yaw()) : dir;
	}

	private int indexAtOrBefore(double tick) {
		int lo = 0;
		int hi = samples.size() - 1;
		int result = -1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (samples.get(mid).tick() <= tick) {
				result = mid;
				lo = mid + 1;
			} else {
				hi = mid - 1;
			}
		}
		return result;
	}
}
