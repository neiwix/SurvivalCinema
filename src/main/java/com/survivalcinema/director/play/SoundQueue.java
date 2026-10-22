package com.survivalcinema.director.play;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The pieces of sound handed to the sound card (OpenAL buffers), oldest first, with the film time each starts at.
 * The sound card reports how far into its queue it is (in samples), which this turns into film time: the most
 * precise clock there is, because it is what the player hears.
 */
public final class SoundQueue {
	private record Piece(double start, int frames) {
	}

	private final int rate;
	private final Deque<Piece> pieces = new ArrayDeque<>();
	private double end = Double.NaN;

	/** @param rate sample frames per second */
	public SoundQueue(int rate) {
		this.rate = rate;
	}

	/** A buffer of {@code frames} sample frames starting at {@code start} film seconds went into the queue. */
	public void queued(double start, int frames) {
		pieces.addLast(new Piece(start, frames));
		end = start + frames / (double) rate;
	}

	/** The sound card finished the oldest {@code count} buffers and gave them back. */
	public void processed(int count) {
		for (int i = 0; i < count && !pieces.isEmpty(); i++) {
			pieces.removeFirst();
		}
	}

	/**
	 * Film time being heard now.
	 *
	 * @param sampleOffset the sound card's position in samples, counted from the start of the oldest queued buffer
	 * @return NaN when nothing was ever queued; the end of the last buffer when everything was played
	 */
	public double position(int sampleOffset) {
		Piece first = pieces.peekFirst();
		if (first == null) {
			return end;
		}
		return first.start() + Math.max(0, sampleOffset) / (double) rate;
	}

	/** Seconds of sound waiting in the queue after the given position (how far ahead the sound card is fed). */
	public double secondsAhead(int sampleOffset) {
		if (pieces.isEmpty()) {
			return 0;
		}
		double heard = position(sampleOffset);
		return Math.max(0, end - heard);
	}

	public int size() {
		return pieces.size();
	}

	/** Forgets everything (a seek, or the sound card lost its queue). */
	public void clear() {
		pieces.clear();
		end = Double.NaN;
	}
}
