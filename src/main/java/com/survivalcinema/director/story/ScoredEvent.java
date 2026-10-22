package com.survivalcinema.director.story;

import com.survivalcinema.director.event.GameEvent;

/**
 * A noticed moment with how much it is worth for the film.
 */
public record ScoredEvent(SessionData session, GameEvent event, double score) {
	public int tick() {
		return event.tick();
	}
}
