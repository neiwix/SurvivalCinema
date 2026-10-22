package com.survivalcinema.director.camera;

/**
 * One recorded position of the filmed player.
 *
 * @param tick  replay tick (Flashback's written tick counter)
 * @param yaw   Minecraft yaw in degrees (0 = +Z, 90 = -X)
 * @param pitch Minecraft pitch in degrees (positive = looking down)
 * @param flags bit set of {@link #FLAG_ON_GROUND}, {@link #FLAG_FLYING_ELYTRA}, ...
 */
public record TrailSample(int tick, double x, double y, double z, float yaw, float pitch, int flags) {
	public static final int FLAG_ON_GROUND = 1;
	public static final int FLAG_FLYING_ELYTRA = 1 << 1;
	public static final int FLAG_IN_VEHICLE = 1 << 2;
	public static final int FLAG_SWIMMING = 1 << 3;
	public static final int FLAG_SNEAKING = 1 << 4;
	/** Dead (the death screen is up); recordings made before this flag existed never have it. */
	public static final int FLAG_DEAD = 1 << 5;
	/** Asleep in a bed; recordings made before this flag existed never have it. */
	public static final int FLAG_SLEEPING = 1 << 6;

	public boolean has(int flag) {
		return (flags & flag) != 0;
	}

	public Vec position() {
		return new Vec(x, y, z);
	}
}
