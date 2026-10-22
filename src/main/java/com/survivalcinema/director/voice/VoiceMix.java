package com.survivalcinema.director.voice;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everyone in voice chat mixed into one track as they are heard. Voice chat hands over each speaker's sound in small
 * packets (20 ms) from its own threads; each packet goes on the track where its speaker's last packet ended, or "now"
 * when they were quiet, and the track is taken in real time a little behind, so packets that arrive late still land
 * in place. Times are counted in samples (48 kHz) by the caller's clock.
 */
public final class VoiceMix {
	public static final int RATE = 48_000;
	/** How far behind "now" the track is taken: room for packets that arrive late. */
	public static final int LATENCY = RATE * 3 / 25;
	private static final int CAPACITY = RATE * 4;

	private final int[] ring = new int[CAPACITY];
	private final Map<UUID, Long> ends = new HashMap<>();
	private long taken = -1;

	/** A packet of {@code speaker}'s voice, heard at sample {@code now}. Any thread. */
	public synchronized void add(UUID speaker, short[] pcm, long now) {
		if (pcm == null || pcm.length == 0) {
			return;
		}
		if (taken < 0) {
			taken = now - LATENCY;
		}
		Long end = ends.get(speaker);
		// a packet a little late goes right after the speaker's last one (no hole in a word); after a pause, at "now"
		boolean goesOn = end != null && end >= taken && now - end < LATENCY;
		long at = goesOn ? end : Math.max(now, taken);
		if (at + pcm.length > taken + CAPACITY) {
			return; // far ahead of what is taken (the track was not read for seconds): dropped
		}
		for (int i = 0; i < pcm.length; i++) {
			ring[(int) Math.floorMod(at + i, (long) CAPACITY)] += pcm[i];
		}
		ends.put(speaker, at + pcm.length);
	}

	/**
	 * The mixed track up to sample {@code now - LATENCY}: everything since the last call, silence where nobody spoke.
	 * Returns an empty array when nothing new is due.
	 */
	public synchronized short[] take(long now) {
		long until = now - LATENCY;
		if (taken < 0) {
			taken = until;
			return new short[0];
		}
		if (until <= taken) {
			return new short[0];
		}
		long from = Math.max(taken, until - CAPACITY);
		short[] out = new short[(int) (until - from)];
		for (int i = 0; i < out.length; i++) {
			int slot = (int) Math.floorMod(from + i, (long) CAPACITY);
			out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, ring[slot]));
			ring[slot] = 0;
		}
		taken = until;
		return out;
	}

	/** Forgets everyone (a new recording). */
	public synchronized void reset() {
		java.util.Arrays.fill(ring, 0);
		ends.clear();
		taken = -1;
	}
}
