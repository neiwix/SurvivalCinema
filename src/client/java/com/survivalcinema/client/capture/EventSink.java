package com.survivalcinema.client.capture;

import com.survivalcinema.director.event.EventType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Where watchers report noticed moments. The session fills in tick, time, day and dimension.
 */
public interface EventSink {
	/**
	 * @param tickOverride replay tick the moment started at, or -1 for "now"
	 * @param dimension    where it happened when that is not where the player is now (building reported after a
	 *                     portal), or null
	 */
	void emit(EventType type, String subject, String label, @Nullable Vec3 position, double intensity,
			  boolean first, @Nullable String detail, int tickOverride, @Nullable String dimension);

	/**
	 * @param tickOverride replay tick the moment started at, or -1 for "now"
	 */
	default void emit(EventType type, String subject, String label, @Nullable Vec3 position, double intensity,
					  boolean first, @Nullable String detail, int tickOverride) {
		emit(type, subject, label, position, intensity, first, detail, tickOverride, null);
	}

	default void emit(EventType type, String subject, String label, @Nullable Vec3 position, double intensity,
					  boolean first, @Nullable String detail) {
		emit(type, subject, label, position, intensity, first, detail, -1);
	}

	/** Remembers something for this world; true when it is the first time. */
	boolean markSeen(String category, String id);

	/** The Ender Dragon died. Several watchers can notice it; the session reports it only once. */
	void dragonKilled(@Nullable Vec3 position);

	int currentTick();
}
