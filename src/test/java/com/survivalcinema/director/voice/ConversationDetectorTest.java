package com.survivalcinema.director.voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationDetectorTest {
	private static final UUID ALEX = UUID.randomUUID();
	private static final UUID STEVE = UUID.randomUUID();

	private final ConversationDetector detector = new ConversationDetector();
	private final List<ConversationDetector.Conversation> found = new ArrayList<>();

	private void speak(UUID who, double db, int windows) {
		for (int i = 0; i < windows; i++) {
			Map<UUID, Double> w = new HashMap<>();
			w.put(who, db + (i % 3 - 1));
			found.addAll(detector.window(w));
		}
	}

	private void silence(int windows) {
		for (int i = 0; i < windows; i++) {
			found.addAll(detector.window(Map.of()));
		}
	}

	/** Alex at -25 dB and Steve at -30 dB taking turns of {@code turnWindows} for {@code seconds}. */
	private void chat(int seconds, int turnWindows, double extraDb) {
		int windows = seconds * 10;
		boolean alex = true;
		for (int done = 0; done < windows; done += turnWindows) {
			speak(alex ? ALEX : STEVE, (alex ? -25 : -30) + extraDb, turnWindows);
			alex = !alex;
		}
	}

	@Test
	void findsACalmChatInTurns() {
		chat(40, 20, 0);
		silence(120);
		assertEquals(1, found.size());
		ConversationDetector.Conversation c = found.getFirst();
		assertTrue(c.speakers().containsAll(List.of(ALEX, STEVE)));
		assertTrue(c.turns() >= 15, "turns " + c.turns());
		assertTrue(c.durationWindows() >= 350 && c.durationWindows() <= 450, "duration " + c.durationWindows());
		assertTrue(c.balance() > 0.8);
	}

	@Test
	void aMonologueWithTheOddYeahIsNotAConversation() {
		for (int i = 0; i < 8; i++) {
			speak(ALEX, -25, 50);
			speak(STEVE, -30, 2); // "yeah"
		}
		silence(120);
		assertTrue(found.isEmpty());
	}

	@Test
	void aShoutingMatchIsNotACalmTalk() {
		chat(10, 20, 0); // learn the normal levels
		silence(120);
		found.clear();
		chat(40, 20, 14);
		silence(120);
		assertTrue(found.isEmpty(), "shouting was taken for a calm talk");
	}

	@Test
	void aQuickExchangeIsTooShort() {
		chat(8, 20, 0);
		silence(120);
		assertTrue(found.isEmpty());
	}

	@Test
	void aLongTalkIsCutIntoMinutePieces() {
		chat(180, 25, 0);
		silence(120);
		assertTrue(found.size() >= 2, "pieces " + found.size());
		for (ConversationDetector.Conversation c : found) {
			assertTrue(c.durationWindows() <= ConversationDetector.MAX_WINDOWS);
		}
	}
}
