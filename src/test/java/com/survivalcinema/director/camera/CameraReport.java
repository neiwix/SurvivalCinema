package com.survivalcinema.director.camera;

import java.util.List;
import java.util.Locale;

/**
 * How a camera path would look on screen: how much of the time the player is hidden or the lens is in leaves, and how
 * hard the camera moves around the player (speed and acceleration of its offset from the player, turning of the view).
 *
 * @param hidden       share of keys where the line to the player is blocked
 * @param bodyHidden   share of keys where the player's head shows but the body below it is behind something (a wall)
 * @param viewerInside share of keys where the replay viewer whose eyes are the camera would stand in a block
 * @param covered      share of keys where any of the five visibility rays is blocked (leaves at the lens)
 * @param clutter      average share of the rays across the picture that hit something right at the lens
 * @param maxSpeed     fastest the camera moves around the player, blocks per second
 * @param maxAccel     hardest change of that movement, blocks per second squared
 * @param maxTurn      fastest the view turns, degrees per second
 * @param maxTurnAccel hardest change of the turning, degrees per second squared
 */
record CameraReport(double hidden, double bodyHidden, double viewerInside, double covered, double clutter, double maxSpeed, double maxAccel, double maxTurn,
					 double maxTurnAccel) {
	private static final double CLEAR = 0.995;

	static CameraReport of(Trail trail, List<CameraKey> keys, BlockProbe probe) {
		int n = keys.size();
		int hidden = 0;
		int bodyHidden = 0;
		int viewerInside = 0;
		int covered = 0;
		int clutter = 0;
		Vec[] offsets = new Vec[n];
		Vec[] views = new Vec[n];
		double[] seconds = new double[n];
		for (int i = 0; i < n; i++) {
			CameraKey key = keys.get(i);
			Vec aim = CameraPathBuilder.aimAt(trail, key.tick());
			offsets[i] = key.position().sub(aim);
			views[i] = direction(key.yaw(), key.pitch());
			seconds[i] = key.tick() / 20.0;
			if (probe.clearFraction(aim, key.position()) < CLEAR) {
				hidden++;
			} else if (probe.clearFraction(OcclusionSolver.body(aim), key.position()) < CLEAR) {
				bodyHidden++;
			}
			if (!OcclusionSolver.viewerFits(key.position(), probe)) {
				viewerInside++;
			}
			clutter += OcclusionSolver.frameClutter(key.position(), offsets[i].scale(-1), probe);
			if (OcclusionSolver.visibility(aim, offsets[i], probe) < CLEAR) {
				covered++;
			}
		}
		double maxSpeed = 0, maxAccel = 0, maxTurn = 0, maxTurnAccel = 0;
		double[] speeds = new double[n];
		Vec[] velocities = new Vec[n];
		for (int i = 1; i < n; i++) {
			double dt = seconds[i] - seconds[i - 1];
			velocities[i] = offsets[i].sub(offsets[i - 1]).scale(1 / dt);
			maxSpeed = Math.max(maxSpeed, velocities[i].length());
			speeds[i] = angle(views[i - 1], views[i]) / dt;
			maxTurn = Math.max(maxTurn, speeds[i]);
			if (i >= 2) {
				double dt2 = (seconds[i] - seconds[i - 2]) / 2;
				maxAccel = Math.max(maxAccel, velocities[i].sub(velocities[i - 1]).length() / dt2);
				maxTurnAccel = Math.max(maxTurnAccel, Math.abs(speeds[i] - speeds[i - 1]) / dt2);
			}
		}
		return new CameraReport(hidden / (double) n, bodyHidden / (double) n, viewerInside / (double) n, covered / (double) n, clutter / (14.0 * n), maxSpeed, maxAccel, maxTurn, maxTurnAccel);
	}

	private static Vec direction(double yaw, double pitch) {
		double y = Math.toRadians(yaw);
		double p = Math.toRadians(pitch);
		return new Vec(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
	}

	private static double angle(Vec a, Vec b) {
		double dot = a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
		return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot / (a.length() * b.length())))));
	}

	@Override
	public String toString() {
		return String.format(Locale.ROOT, "hidden %4.0f%%  body hidden %4.0f%%  viewer in blocks %4.0f%%  covered %4.0f%%  clutter %4.0f%%  speed %5.1f b/s  accel %6.1f b/s2  turn %5.0f deg/s  turn accel %6.0f deg/s2",
			hidden * 100, bodyHidden * 100, viewerInside * 100, covered * 100, clutter * 100, maxSpeed, maxAccel, maxTurn, maxTurnAccel);
	}
}
