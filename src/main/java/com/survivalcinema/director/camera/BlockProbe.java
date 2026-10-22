package com.survivalcinema.director.camera;

/**
 * Asks the world whether the line between two points is blocked. Implemented with a real raycast in the replay
 * world, and with simple boxes in tests.
 */
@FunctionalInterface
public interface BlockProbe {
	/** How far along {@code from -> to} the line stays clear: 1 when nothing is in the way, 0.3 when blocked at 30%. */
	double clearFraction(Vec from, Vec to);

	BlockProbe EMPTY = (from, to) -> 1;

	/** Blocked where either world is: the replay at the start of a clip and at its end (walls built during it). */
	static BlockProbe both(BlockProbe a, BlockProbe b) {
		return (from, to) -> Math.min(a.clearFraction(from, to), b.clearFraction(from, to));
	}
}
