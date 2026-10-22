package com.survivalcinema.director.camera;

/**
 * Tiny immutable 3D vector, so the director code does not depend on Minecraft classes.
 */
public record Vec(double x, double y, double z) {
	public static final Vec ZERO = new Vec(0, 0, 0);
	public static final Vec UP = new Vec(0, 1, 0);

	public Vec add(Vec o) {
		return new Vec(x + o.x, y + o.y, z + o.z);
	}

	public Vec sub(Vec o) {
		return new Vec(x - o.x, y - o.y, z - o.z);
	}

	public Vec scale(double s) {
		return new Vec(x * s, y * s, z * s);
	}

	public double length() {
		return Math.sqrt(x * x + y * y + z * z);
	}

	public double horizontalLength() {
		return Math.sqrt(x * x + z * z);
	}

	public Vec horizontal() {
		return new Vec(x, 0, z);
	}

	public Vec normalize() {
		double len = length();
		return len < 1e-9 ? ZERO : scale(1 / len);
	}

	public Vec lerp(Vec o, double t) {
		return new Vec(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
	}

	public double distance(Vec o) {
		return sub(o).length();
	}

	public double dot(Vec o) {
		return x * o.x + y * o.y + z * o.z;
	}

	/** Angle between two directions, in degrees. */
	public double angleTo(Vec o) {
		double lengths = length() * o.length();
		return lengths < 1e-12 ? 0 : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(o) / lengths))));
	}

	/** Horizontal unit vector a Minecraft entity faces with this yaw. */
	public static Vec fromYaw(double yawDegrees) {
		double r = Math.toRadians(yawDegrees);
		return new Vec(-Math.sin(r), 0, Math.cos(r));
	}

	/** Minecraft yaw of a horizontal direction (inverse of {@link #fromYaw}). */
	public double yaw() {
		return Math.toDegrees(Math.atan2(-x, z));
	}

	/** Minecraft pitch of this direction (positive = down). */
	public double pitch() {
		return -Math.toDegrees(Math.atan2(y, horizontalLength()));
	}
}
