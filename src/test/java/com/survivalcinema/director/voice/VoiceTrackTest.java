package com.survivalcinema.director.voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceTrackTest {
	private static final int PER_TICK = VoiceTrackIndex.RATE / 20;

	@Test
	void indexFindsSamplesBetweenLines() {
		VoiceTrackIndex index = VoiceTrackIndex.parse("100 0\n110 24000\n120 48000\n");
		assertEquals(-1, index.sampleAt(99));
		assertEquals(0, index.sampleAt(100));
		assertEquals(12000, index.sampleAt(105), 1e-6);
		assertEquals(36000, index.sampleAt(115), 1e-6);
		// past the last line it runs on in real time for a while, then the track has no sound
		assertEquals(48000 + 5 * PER_TICK, index.sampleAt(125), 1e-6);
		assertEquals(-1, index.sampleAt(120 + VoiceTrackIndex.TRUST_TICKS + 1));
	}

	@Test
	void pauseKeepsTicksAndSamplesInStep() {
		// paused at tick 200 for a minute: neither the ticks nor the samples moved meanwhile
		VoiceTrackIndex index = VoiceTrackIndex.parse("190 0\n200 24000\n201 26400\n211 50400\n");
		assertEquals(24000, index.sampleAt(200), 1e-6);
		assertEquals(26400 + 5 * PER_TICK, index.sampleAt(206), 1e-6);
	}

	@Test
	void lagStretchesTheTicks() {
		// the game lagged: 10 ticks took a second instead of half a second
		VoiceTrackIndex index = VoiceTrackIndex.parse("0 0\n10 48000\n");
		assertEquals(24000, index.sampleAt(5), 1e-6);
	}

	@Test
	void holeInTheLinesHasNoSound() {
		// the track started again much later (a long gap with no lines)
		VoiceTrackIndex index = VoiceTrackIndex.parse("0 0\n10 24000\n500 30000\n510 54000\n");
		assertTrue(index.sampleAt(20) > 0);
		assertEquals(-1, index.sampleAt(300));
		assertEquals(30000 + 5 * PER_TICK, index.sampleAt(505), 1e-6);
	}

	@Test
	void brokenLinesAreSkipped() {
		VoiceTrackIndex index = VoiceTrackIndex.parse("0 0\ngarbage\n10 24000\n5 100\n20 48000\n30 4");
		assertEquals(List.of(0, 10, 20), index.points().stream().map(VoiceTrackIndex.Point::tick).toList());
		assertTrue(VoiceTrackIndex.parse("").isEmpty());
	}

	@Test
	void clockFillsSilenceOnlyWhenBehind() {
		TrackClock clock = new TrackClock(48_000);
		assertEquals(0, clock.silenceBefore(5_000_000_000L, 0, 0)); // not started
		clock.restart(0, 1000);
		// packets arriving a little late are normal
		assertEquals(0, clock.silenceBefore(100_000_000L, 1000, 0));
		// a program that played nothing for a second
		assertEquals(48_000, clock.silenceBefore(1_000_000_000L, 1000, 0));
		assertEquals(48_000 - 960, clock.silenceBefore(1_000_000_000L, 1000, 960));
		// ahead of the clock: nothing to fill
		assertEquals(0, clock.silenceBefore(1_000_000_000L, 100_000, 0));
		// after a pause it counts from the restart, the paused time is not filled
		clock.restart(60_000_000_000L, 49_000);
		assertEquals(0, clock.silenceBefore(60_050_000_000L, 49_000, 0));
		clock.stop();
		assertFalse(clock.isRunning());
		assertEquals(0, clock.silenceBefore(90_000_000_000L, 49_000, 0));
	}

	@Test
	void findsTheTopProcessOfEachApp() {
		List<CallApps.Proc> processes = List.of(
			new CallApps.Proc(4, 0, "System"),
			new CallApps.Proc(900, 4, "explorer.exe"),
			new CallApps.Proc(1200, 900, "Discord.exe"),
			new CallApps.Proc(1300, 1200, "Discord.exe"),
			new CallApps.Proc(1100, 1300, "Discord.exe"), // a child with a lower pid than its parent
			new CallApps.Proc(2000, 900, "Telegram.exe"),
			new CallApps.Proc(3000, 77, "javaw.exe"));
		List<CallApps.Running> running = CallApps.find(processes, CallApps.ALL);
		assertEquals(2, running.size());
		assertEquals("discord", running.get(0).app().id());
		assertEquals(1200, running.get(0).pid());
		assertEquals("telegram", running.get(1).app().id());
		assertEquals(2000, running.get(1).pid());
		assertEquals(List.of(), CallApps.find(processes, List.of(CallApps.byId("zoom"))));
		assertEquals("Discord.exe", CallApps.exeName("C:\\Users\\me\\AppData\\Local\\Discord\\app-1.0\\Discord.exe"));
	}

	@Test
	void sourceDecidesWhatToRecord() {
		assertEquals(VoiceSource.Tracks.NONE, VoiceSource.AUTO.tracks(false, false, true, true));
		// friends in Discord, no voice chat: Discord and my microphone
		assertEquals(new VoiceSource.Tracks(true, true), VoiceSource.AUTO.tracks(true, false, true, false));
		// voice chat carries my voice: no second copy of it
		assertEquals(new VoiceSource.Tracks(true, false), VoiceSource.AUTO.tracks(true, true, true, false));
		// alone, no call: nothing unless commenting
		assertEquals(new VoiceSource.Tracks(true, false), VoiceSource.AUTO.tracks(true, false, false, false));
		assertEquals(new VoiceSource.Tracks(true, true), VoiceSource.AUTO.tracks(true, false, false, true));
		assertEquals(new VoiceSource.Tracks(false, false), VoiceSource.VOICE_CHAT.tracks(true, false, true, false));
		assertEquals(new VoiceSource.Tracks(true, true), VoiceSource.APPS.tracks(true, true, true, false));
		assertEquals(VoiceSource.Tracks.NONE, VoiceSource.OFF.tracks(true, false, true, true));
		assertEquals(VoiceSource.AUTO, VoiceSource.parse("nonsense"));
		assertEquals(VoiceSource.APPS, VoiceSource.parse("apps"));
	}
}
