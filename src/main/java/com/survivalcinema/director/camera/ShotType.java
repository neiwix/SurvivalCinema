package com.survivalcinema.director.camera;

public enum ShotType {
	/** Behind the player, following the direction they move in. */
	CHASE,
	/** Circles around the player while they move. */
	ORBIT,
	/** Travels alongside the player, filming them from the side. */
	SIDE,
	/** Stays in one place and turns to keep the player in frame. */
	STATIC_LOOKAT,
	/** Starts close and slowly pulls back and up, revealing the surroundings. */
	PULLBACK,
	/** Two friends talking, filmed from the side so both are in frame, like a dialogue scene in a film. */
	TWO_SHOT,
	/**
	 * From behind the player, a little to one side, looking past them at a boss, so both are in frame. Never planned
	 * ahead: a boss scene is filmed like this once the replay shows where the boss is.
	 */
	OVER_SHOULDER
}
