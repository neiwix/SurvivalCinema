package com.survivalcinema.director.edit;

import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;

/**
 * The wind of an elytra flight. The game plays it only for the player at the keyboard; in a replay the filmed player is
 * someone else, so their flights are silent. The editor lays it under the clip the way the game would play it: louder
 * the faster they fly (full at 2 blocks a tick), growing over the first two seconds of a flight. Slow motion only
 * stretches it, like the picture.
 */
public final class FlightSound {
	/** The game's wind is silent for the first second of a flight and grows over the next. */
	private static final int SILENT_TICKS = 20;
	private static final int GROW_TICKS = 20;
	/** A clip flown for at least this share of its time is a flight (it gets the music of one). */
	static final double FLIGHT_SHARE = 0.4;
	/** How many frames the wind's volume is averaged over: the path is only known every tick or two. */
	private static final int SMOOTH_FRAMES = 5;

	private FlightSound() {
	}

	/**
	 * The wind's volume (0..1, 0 when not flying) for each frame of a shot file of {@code frames} frames that shows
	 * replay ticks {@code fromTick} to {@code toTick}.
	 */
	public static double[] volumes(Trail trail, int fromTick, int toTick, int slowFromTick, int fps, int frames) {
		double[] volumes = new double[Math.max(0, frames)];
		if (trail.isEmpty() || frames <= 0) {
			return volumes;
		}
		double[] ticks = VoiceCut.frameTicks(fromTick, toTick, slowFromTick, fps, frames);
		// a flight that began long before the clip is at full wind already: the path before that does not matter
		Trail near = trail.slice(fromTick - 10 * (SILENT_TICKS + GROW_TICKS), toTick + 2);
		double[] raw = new double[frames];
		for (int f = 0; f < frames; f++) {
			raw[f] = near.isEmpty() ? 0 : volumeAt(near, ticks[f]);
		}
		for (int f = 0; f < frames; f++) {
			double sum = 0;
			int n = 0;
			for (int g = Math.max(0, f - SMOOTH_FRAMES / 2); g <= Math.min(frames - 1, f + SMOOTH_FRAMES / 2); g++) {
				sum += raw[g];
				n++;
			}
			volumes[f] = raw[f] == 0 ? 0 : sum / n;
		}
		return volumes;
	}

	/** The game's volume of the wind at a replay tick. */
	static double volumeAt(Trail trail, double tick) {
		TrailSample sample = trail.sampleAt(tick);
		if (!sample.has(TrailSample.FLAG_FLYING_ELYTRA) || tick < trail.firstTick()) {
			return 0;
		}
		double speed = trail.positionAt(tick).distance(trail.positionAt(tick - 1)); // blocks per tick
		double volume = Math.min(1, speed * speed / 4);
		double flying = tick - flightStart(trail, tick);
		if (flying < SILENT_TICKS) {
			return 0;
		}
		return flying < SILENT_TICKS + GROW_TICKS ? volume * (flying - SILENT_TICKS) / GROW_TICKS : volume;
	}

	/** The tick the flight going on at {@code tick} began. */
	private static int flightStart(Trail trail, double tick) {
		int start = trail.sampleAt(tick).tick();
		for (int i = trail.samples().size() - 1; i >= 0; i--) {
			TrailSample s = trail.samples().get(i);
			if (s.tick() > tick) {
				continue;
			}
			if (!s.has(TrailSample.FLAG_FLYING_ELYTRA)) {
				break;
			}
			start = s.tick();
		}
		return start;
	}

	/** Whether a clip is mostly an elytra flight. */
	public static boolean isFlight(Trail trail, int fromTick, int toTick) {
		int flying = 0;
		int all = 0;
		for (TrailSample s : trail.samples()) {
			if (s.tick() >= fromTick && s.tick() <= toTick) {
				all++;
				if (s.has(TrailSample.FLAG_FLYING_ELYTRA)) {
					flying++;
				}
			}
		}
		return all > 0 && flying >= all * FLIGHT_SHARE;
	}
}
