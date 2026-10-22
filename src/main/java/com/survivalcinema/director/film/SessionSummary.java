package com.survivalcinema.director.film;

import com.survivalcinema.director.event.GameEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * One recorded session as the player sees it in the list of what goes into the film: when it was played, how long,
 * which in-game days and how much disk it takes.
 *
 * @param startedAt real time the recording started, epoch milliseconds (0 when unknown)
 * @param ticks     how long the recording is, in replay ticks
 * @param firstDay  in-game day at its start (0 when unknown)
 * @param lastDay   in-game day at its end
 * @param bytes     the replay plus our data of it
 * @param hasReplay the Flashback replay still exists (without it there is nothing to film)
 * @param live      it is being recorded right now (its replay is saved when it ends, or when a film is started)
 */
public record SessionSummary(String id, long startedAt, int ticks, int firstDay, int lastDay, long bytes, boolean hasReplay,
							 boolean live) {
	public static SessionSummary of(String id, List<GameEvent> events, int trailLastTick, long bytes, boolean hasReplay) {
		return of(id, events, trailLastTick, bytes, hasReplay, false);
	}

	public static SessionSummary of(String id, List<GameEvent> events, int trailLastTick, long bytes, boolean hasReplay, boolean live) {
		long startedAt = 0;
		int ticks = Math.max(0, trailLastTick);
		int firstDay = 0;
		int lastDay = 0;
		for (GameEvent event : events) {
			if (startedAt == 0 && event.wallTime() > 0) {
				startedAt = event.wallTime() - event.tick() * 50L;
			}
			ticks = Math.max(ticks, event.tick());
			if (event.day() > 0) {
				firstDay = firstDay == 0 ? event.day() : Math.min(firstDay, event.day());
				lastDay = Math.max(lastDay, event.day());
			}
		}
		return new SessionSummary(id, startedAt, ticks, firstDay, lastDay, bytes, hasReplay, live);
	}

	/** Whole minutes of play, at least one for anything recorded. */
	public int minutes() {
		return ticks <= 0 ? 0 : Math.max(1, Math.round(ticks / 1200f));
	}

	/** Can go into a film: the replay is there (or will be) and something was recorded. */
	public boolean usable() {
		return (hasReplay || live) && ticks > 0;
	}

	/** Sessions started after that moment (a film's planning time): "only what was played since the last film". */
	public static List<String> startedAfter(Collection<SessionSummary> sessions, long time) {
		List<String> ids = new ArrayList<>();
		for (SessionSummary session : sessions) {
			if (session.startedAt() > time) {
				ids.add(session.id());
			}
		}
		return ids;
	}
}
