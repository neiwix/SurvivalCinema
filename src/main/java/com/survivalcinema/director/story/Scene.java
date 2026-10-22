package com.survivalcinema.director.story;

import java.util.List;

/**
 * A continuous piece of one session that could go into the film, built around its best moment.
 */
public record Scene(SessionData session, int startTick, int endTick, ScoredEvent primary, List<ScoredEvent> events, double score) {
	public int durationTicks() {
		return endTick - startTick;
	}

	public int day() {
		return Math.max(1, primary.event().day());
	}

	/** Chronological position across the whole world. */
	public long order() {
		return ((long) session.index() << 32) | (startTick & 0xFFFFFFFFL);
	}

	public Scene withRange(int start, int end) {
		return new Scene(session, start, end, primary, events, score);
	}
}
