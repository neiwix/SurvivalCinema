package com.survivalcinema.director.voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finds voice reactions (screams, laughter, everyone shouting at once) in a stream of 100 ms loudness windows.
 * <p>
 * Friends on voice chat talk all the time and every microphone is set up differently, so "loud" means loud
 * <i>for that person</i>: each speaker gets a baseline of their normal talking level, and a reaction is a burst
 * well above it. Several people talking at once is normal too; it only counts when several of them are excited.
 */
public final class VoiceReactionDetector {
	public static final double SPEECH_FLOOR_DB = -45;
	/** How far above their normal level a speaker has to be to count as excited. */
	public static final double EXCITED_ABOVE_BASELINE_DB = 8;
	/** Never counts as excited below this, however quiet the speaker usually is. */
	public static final double EXCITED_MIN_DB = -32;
	/** Windows of speech (100 ms each) used to learn a speaker's normal level before reactions count. */
	static final int CALIBRATION_WINDOWS = 30;
	static final double BASELINE_ALPHA = 0.03;
	static final int BURST_WINDOWS = 5;
	static final int BURST_NEEDED = 3;
	static final int PEAK_COOLDOWN_WINDOWS = 80;
	static final int CROWD_COOLDOWN_WINDOWS = 150;
	static final int CROWD_SPEAKERS = 3;
	static final int CROWD_EXCITED = 2;

	public enum Kind {PEAK, CROWD}

	/**
	 * @param windowsAgo how many windows before the current one the reaction started
	 * @param intensity  0..1
	 */
	public record Reaction(Kind kind, double intensity, List<UUID> speakers, int windowsAgo) {
	}

	private static final class Speaker {
		double baseline;
		int calibration;
		final boolean[] excited = new boolean[BURST_WINDOWS];
		final double[] excess = new double[BURST_WINDOWS];
	}

	private final Map<UUID, Speaker> speakers = new HashMap<>();
	private long window;
	private long peakAllowedAt;
	private long crowdAllowedAt;

	public void reset() {
		speakers.clear();
		window = 0;
		peakAllowedAt = 0;
		crowdAllowedAt = 0;
	}

	/** Baseline of a speaker in dBFS, or NaN while still calibrating. */
	public double baseline(UUID speaker) {
		Speaker s = speakers.get(speaker);
		return s == null || s.calibration < CALIBRATION_WINDOWS ? Double.NaN : s.baseline;
	}

	/**
	 * Feeds one 100 ms window.
	 *
	 * @param peakDbBySpeaker loudest level of each speaker heard in this window (missing = silent)
	 */
	public List<Reaction> window(Map<UUID, Double> peakDbBySpeaker) {
		int slot = (int) (window % BURST_WINDOWS);
		List<UUID> excitedNow = new ArrayList<>();
		int speaking = 0;
		for (Map.Entry<UUID, Speaker> entry : speakers.entrySet()) {
			if (!peakDbBySpeaker.containsKey(entry.getKey())) {
				entry.getValue().excited[slot] = false;
				entry.getValue().excess[slot] = 0;
			}
		}
		for (Map.Entry<UUID, Double> entry : peakDbBySpeaker.entrySet()) {
			double db = entry.getValue();
			Speaker s = speakers.computeIfAbsent(entry.getKey(), id -> new Speaker());
			if (db < SPEECH_FLOOR_DB) {
				s.excited[slot] = false;
				s.excess[slot] = 0;
				continue;
			}
			speaking++;
			if (s.calibration < CALIBRATION_WINDOWS) {
				// The first few seconds of speech only teach us how this person normally sounds.
				s.baseline = s.calibration == 0 ? db : s.baseline + (db - s.baseline) / (s.calibration + 1);
				s.calibration++;
				s.excited[slot] = false;
				s.excess[slot] = 0;
				continue;
			}
			double excess = db - s.baseline;
			boolean excited = excess >= EXCITED_ABOVE_BASELINE_DB && db >= EXCITED_MIN_DB;
			s.excited[slot] = excited;
			s.excess[slot] = excited ? excess : 0;
			if (excited) {
				excitedNow.add(entry.getKey());
			} else {
				// Only normal talk moves the baseline, so a long scream doesn't make itself "normal".
				s.baseline += (db - s.baseline) * BASELINE_ALPHA;
			}
		}

		List<Reaction> reactions = new ArrayList<>();
		if (window >= peakAllowedAt) {
			List<UUID> bursting = new ArrayList<>();
			double strongest = 0;
			for (Map.Entry<UUID, Speaker> entry : speakers.entrySet()) {
				Speaker s = entry.getValue();
				int count = 0;
				double sum = 0;
				for (int i = 0; i < BURST_WINDOWS; i++) {
					if (s.excited[i]) {
						count++;
						sum += s.excess[i];
					}
				}
				if (count >= BURST_NEEDED) {
					bursting.add(entry.getKey());
					strongest = Math.max(strongest, sum / count);
				}
			}
			if (!bursting.isEmpty()) {
				double intensity = clamp(0.5 + (strongest - EXCITED_ABOVE_BASELINE_DB) / 12);
				reactions.add(new Reaction(Kind.PEAK, intensity, bursting, BURST_WINDOWS - 1));
				peakAllowedAt = window + PEAK_COOLDOWN_WINDOWS;
			}
		}
		if (window >= crowdAllowedAt && speaking >= CROWD_SPEAKERS && excitedNow.size() >= CROWD_EXCITED) {
			reactions.add(new Reaction(Kind.CROWD, clamp(excitedNow.size() / 4.0), excitedNow, 0));
			crowdAllowedAt = window + CROWD_COOLDOWN_WINDOWS;
		}
		window++;
		return reactions;
	}

	private static double clamp(double v) {
		return Math.max(0, Math.min(1, v));
	}
}
