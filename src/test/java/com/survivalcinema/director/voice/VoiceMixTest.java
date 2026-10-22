package com.survivalcinema.director.voice;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceMixTest {
	private static final int PACKET = 960; // 20 ms, as voice chat sends them
	private static final UUID ALEX = UUID.randomUUID();
	private static final UUID STEVE = UUID.randomUUID();

	private static short[] packet(int value) {
		short[] pcm = new short[PACKET];
		Arrays.fill(pcm, (short) value);
		return pcm;
	}

	@Test
	void aSpeakerComesOutInTimeAndSilenceFillsTheRest() {
		VoiceMix mix = new VoiceMix();
		long now = 1_000_000;
		assertEquals(0, mix.take(now + VoiceMix.LATENCY).length, "the first look only starts the clock");
		mix.add(ALEX, packet(1000), now);
		mix.add(ALEX, packet(1000), now + PACKET);
		short[] out = mix.take(now + VoiceMix.LATENCY + 3 * PACKET);
		assertEquals(3 * PACKET, out.length, "everything since the last look, in real time");
		assertEquals(1000, out[0]);
		assertEquals(1000, out[2 * PACKET - 1]);
		assertEquals(0, out[2 * PACKET], "then silence");
		assertEquals(0, mix.take(now + VoiceMix.LATENCY + 3 * PACKET).length, "nothing new yet");
	}

	@Test
	void latePacketsStillLandRightAfterTheirSpeakersLastOne() {
		VoiceMix mix = new VoiceMix();
		long now = 5_000_000;
		mix.take(now + VoiceMix.LATENCY);
		mix.add(ALEX, packet(500), now);
		// the next packet comes 30 ms late (network jitter): it still follows the first without a gap
		mix.add(ALEX, packet(700), now + PACKET + 1440);
		short[] out = mix.take(now + VoiceMix.LATENCY + 2 * PACKET);
		assertEquals(500, out[PACKET - 1]);
		assertEquals(700, out[PACKET], "no hole between the two packets");
	}

	@Test
	void twoPeopleAtOnceAreMixed() {
		VoiceMix mix = new VoiceMix();
		long now = 0;
		mix.take(now + VoiceMix.LATENCY);
		mix.add(ALEX, packet(1000), now);
		mix.add(STEVE, packet(-300), now);
		mix.add(STEVE, packet(32000), now + PACKET);
		mix.add(ALEX, packet(32000), now + PACKET);
		short[] out = mix.take(now + VoiceMix.LATENCY + 2 * PACKET);
		assertEquals(700, out[10]);
		assertEquals(Short.MAX_VALUE, out[PACKET + 10], "shouting together is clipped, not wrapped around");
	}

	@Test
	void aTrackNotReadForLongDoesNotGrowWithoutEnd() {
		VoiceMix mix = new VoiceMix();
		mix.take(VoiceMix.LATENCY);
		for (int i = 0; i < 1000; i++) {
			mix.add(ALEX, packet(100), 0); // a burst far longer than the track holds
		}
		short[] out = mix.take(VoiceMix.LATENCY + 10 * VoiceMix.RATE);
		assertTrue(out.length <= 4 * VoiceMix.RATE, "at most what the track holds: " + out.length);
		mix.reset();
		assertEquals(0, mix.take(1).length);
	}
}
