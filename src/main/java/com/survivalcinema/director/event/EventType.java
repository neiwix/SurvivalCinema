package com.survivalcinema.director.event;

/**
 * Everything the mod notices during a survival session. The base weight says how much a moment of this type
 * is worth for the film before any bonuses (first time, loud voices, other players nearby...).
 * <p>
 * Priorities, highest first: voice reactions together with an important moment, voice reactions on their own,
 * important moments, and last the small stuff (a hit, an ordinary mob), which is nearly worthless on its own so
 * hours of combat cannot fill the film.
 */
public enum EventType {
	SESSION_START(0.1, 0x9E9E9E),

	// voice: the heart of the film
	VOICE_PEAK(0.8, 0xFF55FF),
	VOICE_CROWD(0.85, 0xFF55FF),
	/** Friends calmly talking in turns: the "vibe" of the world, what makes it the story of a group of friends. */
	CONVERSATION(0.5, 0x55FFAA),

	// story beats
	DRAGON_KILLED(1.0, 0xFF55FF),
	BOSS_DEFEATED(1.0, 0xAA00AA),
	TOTEM(0.9, 0xFFFF55),
	DEATH(0.75, 0xFF5555),
	BOSS_FIGHT_START(0.7, 0xAA00AA),
	DIMENSION_CHANGE(0.6, 0x5555FF),
	NEAR_DEATH(0.55, 0xFFAA00),
	ELYTRA_FLIGHT(0.5, 0xFFFFFF),
	KILL_STREAK(0.5, 0xAA0000),
	ADVANCEMENT(0.5, 0x55FF55),
	OTHER_DEATH(0.45, 0xFF5555),
	RARE_ITEM(0.4, 0x55FFFF),
	NIGHT_FALL(0.3, 0x5555AA),
	BUILD_BURST(0.3, 0xFFAA00),
	BIG_FALL(0.3, 0xFF8855),
	NEW_BIOME(0.25, 0x55FFFF),
	OTHER_ADVANCEMENT(0.25, 0x55FF55),

	// small stuff: only worth something together with a voice reaction
	KILL(0.25, 0xAA0000),
	BIG_DAMAGE(0.12, 0xFF8855),
	SLEEP(0.08, 0x5555AA),
	NEW_DAY(0.05, 0xFFFF55),

	// made by the director, never recorded: good-looking travel footage to give the film air between big moments
	SCENERY(0.1, 0xFFFFFF),

	// bookkeeping
	BUILD_ACTIVITY(0.0, 0xAA5500);

	private final double baseWeight;
	private final int markerColour;

	EventType(double baseWeight, int markerColour) {
		this.baseWeight = baseWeight;
		this.markerColour = markerColour;
	}

	public double baseWeight() {
		return baseWeight;
	}

	public int markerColour() {
		return markerColour;
	}

	/** Moments that carry a scene by themselves; anything below is small stuff. */
	public boolean isImportant() {
		return baseWeight >= 0.4 && !isVoice();
	}

	public boolean isVoice() {
		return this == VOICE_PEAK || this == VOICE_CROWD;
	}

	/**
	 * Things that happen in bulk during normal play (mob farms, long fights, strip mining, commuting by elytra).
	 * They lose value fast when many happen close together.
	 */
	public boolean isFarmable() {
		return switch (this) {
			case KILL, KILL_STREAK, BIG_DAMAGE, RARE_ITEM, BUILD_BURST, ELYTRA_FLIGHT, BIG_FALL -> true;
			default -> false;
		};
	}

	/** Bookkeeping events that are useful to the director but would only clutter Flashback's timeline. */
	public boolean showAsMarker() {
		return this != BUILD_ACTIVITY && this != NEW_DAY && this != SESSION_START && this != SCENERY;
	}
}
