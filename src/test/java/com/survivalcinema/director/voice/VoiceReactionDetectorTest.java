package com.survivalcinema.director.voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceReactionDetectorTest {
	private static final UUID LOUD_MIC = UUID.randomUUID();
	private static final UUID QUIET_MIC = UUID.randomUUID();
	private static final UUID THIRD = UUID.randomUUID();
	private final Random random = new Random(1);

	/** Normal chatting: talking 70% of the time around the speaker's level, +-3 dB. */
	private Double talk(double level) {
		return random.nextDouble() < 0.7 ? level + random.nextGaussian() * 1.5 : null;
	}

	private List<VoiceReactionDetector.Reaction> run(VoiceReactionDetector detector, int windows,
													 java.util.function.IntFunction<Map<UUID, Double>> source) {
		List<VoiceReactionDetector.Reaction> all = new ArrayList<>();
		for (int w = 0; w < windows; w++) {
			all.addAll(detector.window(source.apply(w)));
		}
		return all;
	}

	private Map<UUID, Double> chat(double loudMic, double quietMic, double third) {
		Map<UUID, Double> m = new HashMap<>();
		Double a = talk(loudMic), b = talk(quietMic), c = talk(third);
		if (a != null) m.put(LOUD_MIC, a);
		if (b != null) m.put(QUIET_MIC, b);
		if (c != null) m.put(THIRD, c);
		return m;
	}

	@Test
	void normalChattingIsNotAReactionEvenWithALoudMicrophone() {
		VoiceReactionDetector detector = new VoiceReactionDetector();
		// The loud mic sits at -10 dBFS all the time: that is just how this person sounds.
		run(detector, 200, w -> chat(-10, -30, -24));
		List<VoiceReactionDetector.Reaction> later = run(detector, 3000, w -> chat(-10, -30, -24));
		assertTrue(later.isEmpty(), "5 minutes of normal chat produced " + later.size() + " reactions");
	}

	@Test
	void aQuietPersonScreamingIsCaught() {
		VoiceReactionDetector detector = new VoiceReactionDetector();
		run(detector, 300, w -> chat(-10, -30, -24));
		List<VoiceReactionDetector.Reaction> scream = run(detector, 8, w -> {
			Map<UUID, Double> m = chat(-10, -30, -24);
			m.put(QUIET_MIC, -17.0); // 13 dB above their normal level, still quieter than the loud mic
			return m;
		});
		assertEquals(1, scream.stream().filter(r -> r.kind() == VoiceReactionDetector.Kind.PEAK).count());
		assertTrue(scream.getFirst().speakers().contains(QUIET_MIC));
		assertFalse(scream.getFirst().speakers().contains(LOUD_MIC));
	}

	@Test
	void everyoneTalkingAtOnceIsNotACrowdUnlessTheyAreExcited() {
		VoiceReactionDetector detector = new VoiceReactionDetector();
		Map<UUID, Double> allTalking = Map.of(LOUD_MIC, -10.0, QUIET_MIC, -30.0, THIRD, -24.0);
		run(detector, 400, w -> new HashMap<>(allTalking));
		List<VoiceReactionDetector.Reaction> calm = run(detector, 600, w -> new HashMap<>(allTalking));
		assertTrue(calm.stream().noneMatch(r -> r.kind() == VoiceReactionDetector.Kind.CROWD));

		List<VoiceReactionDetector.Reaction> chaos = run(detector, 10, w -> new HashMap<>(Map.of(LOUD_MIC, -1.0, QUIET_MIC, -20.0, THIRD, -12.0)));
		assertTrue(chaos.stream().anyMatch(r -> r.kind() == VoiceReactionDetector.Kind.CROWD));
	}

	@Test
	void aLongScreamDoesNotBecomeTheNewNormal() {
		VoiceReactionDetector detector = new VoiceReactionDetector();
		run(detector, 300, w -> chat(-10, -30, -24));
		double before = detector.baseline(QUIET_MIC);
		run(detector, 100, w -> Map.of(QUIET_MIC, -15.0));
		assertEquals(before, detector.baseline(QUIET_MIC), 1.0);
	}

	@Test
	void reactionsHaveACooldown() {
		VoiceReactionDetector detector = new VoiceReactionDetector();
		run(detector, 300, w -> chat(-10, -30, -24));
		List<VoiceReactionDetector.Reaction> screaming = run(detector, 60, w -> Map.of(QUIET_MIC, -12.0));
		assertEquals(1, screaming.stream().filter(r -> r.kind() == VoiceReactionDetector.Kind.PEAK).count());
	}
}
