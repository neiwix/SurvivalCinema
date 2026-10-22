package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import com.survivalcinema.director.timelapse.TimelapsePlanner;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Everything recorded in one play session (= one Flashback replay).
 *
 * @param index       position in the world's history, 0 = first session ever
 * @param lengthTicks how long the replay is
 * @param trail       the player's path, if it was recorded
 * @param companions  paths of the other players who were near us, by name
 */
public record SessionData(String replayId, int index, int lengthTicks, List<GameEvent> events, @Nullable Trail trail,
						  Map<String, Trail> companions) {
	public SessionData(String replayId, int index, int lengthTicks, List<GameEvent> events, @Nullable Trail trail) {
		this(replayId, index, lengthTicks, events, trail, Map.of());
	}

	/**
	 * Where a moment happened. Building is reported when its half-minute window closes, which is often right after
	 * stepping through a portal (older recordings even carry the new dimension): the camera path knows where it was.
	 */
	public String dimensionOf(GameEvent event) {
		boolean building = event.type() == EventType.BUILD_ACTIVITY || event.type() == EventType.BUILD_BURST;
		if (building && trail != null && !trail.isEmpty() && event.tick() >= trail.firstTick() && event.tick() <= trail.lastTick()) {
			String recorded = TimelapsePlanner.dimension(trail.sampleAt(event.tick()));
			if (!recorded.equals("other")) {
				return recorded;
			}
		}
		return event.dimension();
	}

	/** Records compare by value, which would compare whole event lists; sessions are only ever the same object. */
	@Override
	public boolean equals(Object o) {
		return this == o;
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(this);
	}
}
