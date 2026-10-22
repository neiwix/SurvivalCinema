package com.survivalcinema.director.voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finds calm conversations: two or more friends talking in turns for a while, nobody screaming. These are the "vibe"
 * moments of a survival world (planning the next trip, joking around at the base, talking about life while mining)
 * that make a film feel like the story of a group of friends and not just of a game.
 * <p>
 * Works on the same 100 ms loudness windows as {@link VoiceReactionDetector}.
 */
public final class ConversationDetector {
	public static final double SPEECH_FLOOR_DB = -45;
	/** Talking well above one's own normal level counts as excited, which is not a calm talk. */
	static final double EXCITED_ABOVE_DB = 8;
	static final double BASELINE_ALPHA = 0.03;
	/** A conversation ends after this long without anyone taking a turn (8 s). */
	static final int END_AFTER_NO_TURN_WINDOWS = 80;
	/** Long conversations are cut into pieces of at most a minute. */
	static final int MAX_WINDOWS = 600;
	static final int MIN_WINDOWS = 150;
	static final int MIN_TURNS = 6;
	static final double MAX_EXCITED_SHARE = 0.15;
	/** A turn only counts when the new speaker talks for at least this long (0.5 s), not for a single "yeah". */
	static final int TURN_MIN_WINDOWS = 5;

	/**
	 * @param windowsAgo      how many windows before the current one it started
	 * @param durationWindows how long it lasted
	 * @param speakers        who took part, most talkative first
	 * @param turns           how many times the word passed to someone else
	 * @param balance         0..1, how evenly the talking was shared (1 = perfectly even)
	 */
	public record Conversation(int windowsAgo, int durationWindows, List<UUID> speakers, int turns, double balance,
							   double intensity) {
	}

	private final Map<UUID, Double> baselines = new HashMap<>();
	private final Map<UUID, Integer> baselineSamples = new HashMap<>();

	private long window;
	private boolean active;
	private long startWindow;
	private long lastTurnWindow;
	private UUID currentSpeaker;
	private UUID candidateSpeaker;
	private int candidateRun;
	private int turns;
	private int talkWindows;
	private int excitedWindows;
	private final Map<UUID, Integer> talkBySpeaker = new LinkedHashMap<>();

	public void reset() {
		baselines.clear();
		baselineSamples.clear();
		window = 0;
		active = false;
		currentSpeaker = null;
	}

	public List<Conversation> window(Map<UUID, Double> peakDbBySpeaker) {
		List<Conversation> found = new ArrayList<>();
		UUID loudest = null;
		double loudestDb = -1000;
		boolean excited = false;
		for (Map.Entry<UUID, Double> entry : peakDbBySpeaker.entrySet()) {
			double db = entry.getValue();
			if (db < SPEECH_FLOOR_DB) {
				continue;
			}
			UUID id = entry.getKey();
			int samples = baselineSamples.getOrDefault(id, 0);
			double baseline = baselines.getOrDefault(id, db);
			if (samples >= 30 && db - baseline >= EXCITED_ABOVE_DB) {
				excited = true;
			} else {
				baselines.put(id, samples == 0 ? db : baseline + (db - baseline) * (samples < 30 ? 1.0 / (samples + 1) : BASELINE_ALPHA));
				baselineSamples.put(id, samples + 1);
			}
			if (db > loudestDb) {
				loudestDb = db;
				loudest = id;
			}
		}

		if (loudest != null) {
			// The word passes to someone else once they have been the main speaker for a moment.
			if (loudest.equals(candidateSpeaker)) {
				candidateRun++;
			} else {
				candidateSpeaker = loudest;
				candidateRun = 1;
			}
			if (candidateRun >= TURN_MIN_WINDOWS && !candidateSpeaker.equals(currentSpeaker)) {
				if (currentSpeaker != null) {
					if (!active) {
						active = true;
						startWindow = Math.max(0, window - TURN_MIN_WINDOWS * 4L);
						turns = 0;
						talkWindows = 0;
						excitedWindows = 0;
						talkBySpeaker.clear();
					}
					turns++;
					lastTurnWindow = window;
				}
				currentSpeaker = candidateSpeaker;
			}
		}

		if (active) {
			if (loudest != null) {
				talkWindows++;
				talkBySpeaker.merge(loudest, 1, Integer::sum);
				if (excited) {
					excitedWindows++;
				}
			}
			long length = window - startWindow;
			if (window - lastTurnWindow > END_AFTER_NO_TURN_WINDOWS || length >= MAX_WINDOWS) {
				int end = (int) Math.min(length, lastTurnWindow - startWindow + 30);
				Conversation c = finish(end);
				if (c != null) {
					found.add(c);
				}
				active = false;
				if (length >= MAX_WINDOWS && window - lastTurnWindow <= END_AFTER_NO_TURN_WINDOWS) {
					// Still going: start the next piece right away.
					active = true;
					startWindow = window;
					turns = 0;
					talkWindows = 0;
					excitedWindows = 0;
					talkBySpeaker.clear();
				}
			}
		}
		window++;
		return found;
	}

	private Conversation finish(int durationWindows) {
		if (durationWindows < MIN_WINDOWS || turns < MIN_TURNS || talkBySpeaker.size() < 2 || talkWindows == 0) {
			return null;
		}
		if (excitedWindows > talkWindows * MAX_EXCITED_SHARE) {
			return null; // a shouting match is a reaction, not a calm talk
		}
		List<Map.Entry<UUID, Integer>> ranked = new ArrayList<>(talkBySpeaker.entrySet());
		ranked.sort(Map.Entry.<UUID, Integer>comparingByValue().reversed());
		double balance = ranked.get(1).getValue() / (double) ranked.get(0).getValue();
		double turnRate = turns / (durationWindows / 100.0); // turns per 10 seconds
		double intensity = Math.min(1, 0.3 + 0.35 * balance + 0.1 * Math.min(3.5, turnRate));
		List<UUID> speakers = ranked.stream().map(Map.Entry::getKey).toList();
		int windowsAgo = (int) (window - startWindow);
		return new Conversation(windowsAgo, durationWindows, speakers, turns, balance, intensity);
	}
}
