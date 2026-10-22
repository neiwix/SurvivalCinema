package com.survivalcinema.director.render;

/**
 * Guesses how long the rest of the shooting takes, from how fast this PC shot the clips so far.
 * Until something is measured it assumes a middle-of-the-road PC.
 */
public final class RenderEta {
	/** Seconds of work per second of film (seeking, camera, rendering, encoding), before anything is measured. */
	static final double DEFAULT_SECONDS_PER_FILM_SECOND = 3.0;
	/** Seconds to open one replay, before anything is measured. */
	static final double DEFAULT_OPEN_SECONDS = 15;
	/** Measurements shorter than this are too noisy to trust on their own. */
	private static final double MIN_MEASURED_FILM_SECONDS = 5;

	private double workSeconds;
	private double filmSeconds;
	private double openSeconds;
	private int opens;

	/** A piece of {@code film} seconds took {@code work} seconds from seeking to the finished file. */
	public void pieceDone(double work, double film) {
		if (work > 0 && film > 0) {
			workSeconds += work;
			filmSeconds += film;
		}
	}

	public void replayOpened(double seconds) {
		if (seconds > 0) {
			openSeconds += seconds;
			opens++;
		}
	}

	public double secondsPerFilmSecond() {
		if (filmSeconds <= 0) {
			return DEFAULT_SECONDS_PER_FILM_SECOND;
		}
		double measured = workSeconds / filmSeconds;
		if (filmSeconds >= MIN_MEASURED_FILM_SECONDS) {
			return measured;
		}
		// blend towards the measurement while there is little of it
		double weight = filmSeconds / MIN_MEASURED_FILM_SECONDS;
		return measured * weight + DEFAULT_SECONDS_PER_FILM_SECOND * (1 - weight);
	}

	public double secondsLeft(double filmSecondsLeft, int replaysToOpen) {
		double open = opens == 0 ? DEFAULT_OPEN_SECONDS : openSeconds / opens;
		return Math.max(0, filmSecondsLeft) * secondsPerFilmSecond() + Math.max(0, replaysToOpen) * open;
	}

	/**
	 * A first guess for the film setup screen, before anything is shot: shooting plus editing of a film this long.
	 * A cinema-quality clip has twice the frames and more than twice the pixels of a draft.
	 *
	 * @param replays how many recordings the film is shot from (each one is opened once)
	 */
	public static double guessSeconds(double filmSeconds, RenderQuality quality, int replays) {
		double perFilmSecond = DEFAULT_SECONDS_PER_FILM_SECOND * (quality == RenderQuality.CINEMA ? 2.5 : 1);
		return Math.max(0, filmSeconds) * (perFilmSecond + EDIT_SECONDS_PER_FILM_SECOND) + Math.max(0, replays) * DEFAULT_OPEN_SECONDS;
	}

	/** Editing time per second of film, measured with the film test. */
	static final double EDIT_SECONDS_PER_FILM_SECOND = 0.4;

	/** Whole minutes for the screen, rounded up, at least 1. */
	public static int minutes(double seconds) {
		return Math.max(1, (int) Math.ceil(seconds / 60.0));
	}
}
