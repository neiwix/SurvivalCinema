package com.survivalcinema.director.event;

import org.jetbrains.annotations.Nullable;

/**
 * One noticed moment.
 *
 * @param tick      replay tick (Flashback's written tick counter) the moment starts at
 * @param wallTime  real time, epoch milliseconds
 * @param subject   machine id of what happened, e.g. {@code minecraft:story/mine_diamond} or {@code minecraft:creeper}
 * @param label     human-readable text in the player's language, e.g. "Diamonds!"
 * @param actor     player the moment belongs to (us, or another player for OTHER_* events)
 * @param day       in-game day number, starting at 1
 * @param intensity 0..1, how strong this instance is (big damage vs small, loud vs quiet...)
 * @param first     true when this is the first time in the whole world (first diamond, first Nether visit...)
 * @param detail    optional extra info, e.g. elytra flight duration or death message
 */
public record GameEvent(int tick, long wallTime, EventType type, String subject, String label, String actor,
						double x, double y, double z, String dimension, int day, double intensity, boolean first,
						@Nullable String detail) {
}
