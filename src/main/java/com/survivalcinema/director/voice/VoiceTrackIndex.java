package com.survivalcinema.director.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where in a voice track (a call app's sound or the microphone, recorded by us next to a Flashback replay) each
 * replay tick is: {@code <track>.idx} next to {@code <track>.ogg}, one line {@code "<tick> <sample>"} every few ticks
 * while the recording runs. Nothing is written while the recording is paused, so the replay's ticks and the track's
 * samples stay in step across pauses; lag (fewer ticks per second) is absorbed by the lines in between.
 */
public final class VoiceTrackIndex {
	/** Samples per second of every voice track (mono). */
	public static final int RATE = 48_000;
	public static final String AUDIO_SUFFIX = ".ogg";
	public static final String INDEX_SUFFIX = ".idx";
	/** A line is written at least this often. */
	public static final int EVERY_TICKS = 10;
	/** Past the last line (or across a hole in the lines) the track is trusted this far, then it has no sound. */
	static final int TRUST_TICKS = 40;

	public record Point(int tick, long sample) {
	}

	private final List<Point> points;

	public VoiceTrackIndex(List<Point> points) {
		this.points = List.copyOf(points);
	}

	public static String line(int tick, long sample) {
		return tick + " " + sample;
	}

	/** Reads the lines; broken ones (a crash in the middle of writing) and steps back in time are skipped. */
	public static VoiceTrackIndex parse(String text) {
		List<Point> points = new ArrayList<>();
		for (String raw : text.split("\n")) {
			String[] parts = raw.trim().split(" ");
			if (parts.length != 2) {
				continue;
			}
			try {
				Point point = new Point(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
				Point last = points.isEmpty() ? null : points.getLast();
				if (point.tick() < 0 || point.sample() < 0 || last != null && (point.tick() < last.tick() || point.sample() < last.sample())) {
					continue;
				}
				points.add(point);
			} catch (NumberFormatException ignored) {
			}
		}
		return new VoiceTrackIndex(points);
	}

	public boolean isEmpty() {
		return points.isEmpty();
	}

	public List<Point> points() {
		return points;
	}

	public int firstTick() {
		return points.isEmpty() ? -1 : points.getFirst().tick();
	}

	public int lastTick() {
		return points.isEmpty() ? -1 : points.getLast().tick();
	}

	/**
	 * The track's sample heard at replay tick {@code tick} (fractional), or -1 when the track was not recording then.
	 */
	public double sampleAt(double tick) {
		if (points.isEmpty() || tick < points.getFirst().tick()) {
			return -1;
		}
		int lo = 0;
		int hi = points.size() - 1;
		while (lo < hi) { // last point at or before the tick
			int mid = (lo + hi + 1) >>> 1;
			if (points.get(mid).tick() <= tick) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		Point a = points.get(lo);
		Point b = lo + 1 < points.size() ? points.get(lo + 1) : null;
		if (b != null && b.tick() - a.tick() <= TRUST_TICKS && b.tick() > a.tick()) {
			double t = (tick - a.tick()) / (b.tick() - a.tick());
			return a.sample() + t * (b.sample() - a.sample());
		}
		if (tick - a.tick() > TRUST_TICKS) {
			return -1;
		}
		return a.sample() + (tick - a.tick()) * RATE / 20.0;
	}

	@Override
	public String toString() {
		return String.format(Locale.ROOT, "VoiceTrackIndex[%d points, ticks %d-%d]", points.size(), firstTick(), lastTick());
	}
}
