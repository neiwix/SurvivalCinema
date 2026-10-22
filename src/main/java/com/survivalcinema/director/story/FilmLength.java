package com.survivalcinema.director.story;

/**
 * Film lengths the player can pick. Five minutes unless they choose otherwise.
 */
public enum FilmLength {
	FIVE(5),
	TEN(10),
	FIFTEEN(15),
	TWENTY(20);

	public static final FilmLength DEFAULT = FIVE;

	private final int minutes;

	FilmLength(int minutes) {
		this.minutes = minutes;
	}

	public int minutes() {
		return minutes;
	}

	public int seconds() {
		return minutes * 60;
	}

	public FilmLength next() {
		return values()[(ordinal() + 1) % values().length];
	}

	public static FilmLength ofMinutes(int minutes) {
		for (FilmLength length : values()) {
			if (length.minutes == minutes) {
				return length;
			}
		}
		return DEFAULT;
	}
}
