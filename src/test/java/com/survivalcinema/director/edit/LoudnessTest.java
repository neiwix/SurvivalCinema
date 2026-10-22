package com.survivalcinema.director.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoudnessTest {
	@Test
	void silenceDoesNotCountTowardsTheLevel() {
		double[] mostlySilent = {0, 0, 0.001, 0.05, 0.05, 0, 0};
		assertEquals(0.05, Loudness.gatedRms(mostlySilent), 1e-9);
		assertEquals(0, Loudness.gatedRms(new double[]{0, 0.001}), 1e-9);
	}

	@Test
	void quietRecordingsAreBroughtUpWithinLimits() {
		// shot at 20% master volume: five times quieter than the target
		assertEquals(5, Loudness.gain(Loudness.GAME_TARGET / 5, Loudness.GAME_TARGET), 1e-9);
		assertEquals(Loudness.MAX_GAIN, Loudness.gain(0.001, Loudness.GAME_TARGET), 1e-9);
		assertEquals(Loudness.MIN_GAIN, Loudness.gain(0.9, Loudness.GAME_TARGET), 1e-9);
		assertEquals(1, Loudness.gain(0, Loudness.GAME_TARGET), 1e-9);
	}

	@Test
	void aClipIsQuietWhenMostOfItHasNothingToHear() {
		double[] calmThenFight = new double[100];
		for (int i = 80; i < 100; i++) {
			calmThenFight[i] = 0.15;
		}
		assertTrue(Loudness.isQuiet(calmThenFight, 1));
		double[] fight = new double[100];
		java.util.Arrays.fill(fight, 0.1);
		assertFalse(Loudness.isQuiet(fight, 1));
		// the gain decides what "quiet" means: a whisper brought up is no longer quiet
		double[] whisper = new double[100];
		java.util.Arrays.fill(whisper, 0.01);
		assertTrue(Loudness.isQuiet(whisper, 1));
		assertFalse(Loudness.isQuiet(whisper, 5));
		assertTrue(Loudness.isQuiet(new double[0], 1));
	}

	@Test
	void theLimiterOnlyBendsTheLoudestPeaks() {
		assertEquals(1000, Loudness.limit(1000));
		assertEquals(-20000, Loudness.limit(-20000));
		assertTrue(Loudness.limit(60000) <= Short.MAX_VALUE);
		assertTrue(Loudness.limit(60000) > Loudness.limit(30000));
		assertTrue(Loudness.limit(-60000) >= Short.MIN_VALUE + 1);
	}
}
