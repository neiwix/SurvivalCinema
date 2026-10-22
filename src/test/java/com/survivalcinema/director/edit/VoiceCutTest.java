package com.survivalcinema.director.edit;

import com.survivalcinema.director.story.Shot;
import com.survivalcinema.director.voice.VoiceTrackIndex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceCutTest {
	private static final int FPS = 30;
	private static final int PER_FRAME = VoiceTrackIndex.RATE / FPS;
	private static final int PER_TICK = VoiceTrackIndex.RATE / 20;

	/** A track recorded in real time from tick {@code from} to {@code to}. */
	private static VoiceTrackIndex steady(int from, int to, long firstSample) {
		StringBuilder lines = new StringBuilder();
		for (int t = from; t <= to; t += 10) {
			lines.append(VoiceTrackIndex.line(t, firstSample + (long) (t - from) * PER_TICK)).append('\n');
		}
		return VoiceTrackIndex.parse(lines.toString());
	}

	@Test
	void normalClipReadsTheTrackStraight() {
		VoiceTrackIndex index = steady(0, 2000, 0);
		VoiceCut cut = VoiceCut.plan(index, 200, 400, -1, FPS, 300); // 10 s
		assertEquals(300, cut.frames());
		assertEquals(200L * PER_TICK, cut.start(0));
		for (int f = 1; f < 300; f++) {
			assertEquals(cut.start(f - 1) + PER_FRAME, cut.start(f));
			assertEquals(1f, cut.gain(f));
		}
		assertArrayEquals(new long[]{200L * PER_TICK, 400L * PER_TICK}, cut.range());
	}

	@Test
	void lagDriftIsCaughtUpInOneJump() {
		// ticks 0..100 took 5.5 s instead of 5 s (the game lagged a little), then normal
		StringBuilder lines = new StringBuilder();
		for (int t = 0; t <= 200; t += 10) {
			lines.append(VoiceTrackIndex.line(t, t <= 100 ? t * 2640L : 264_000L + (t - 100) * 2400L)).append('\n');
		}
		VoiceTrackIndex index = VoiceTrackIndex.parse(lines.toString());
		VoiceCut cut = VoiceCut.plan(index, 0, 200, -1, FPS, 300);
		int jumps = 0;
		for (int f = 1; f < cut.frames(); f++) {
			long step = cut.start(f) - cut.start(f - 1);
			if (step != PER_FRAME) {
				jumps++;
				assertTrue(step > PER_FRAME, "only ever jumps forward here");
			}
			// never further off than the resync limit (plus a frame)
			double target = index.sampleAt(VoiceCut.frameTicks(0, 200, -1, FPS, 300)[f]);
			assertTrue(Math.abs(cut.start(f) - target) <= VoiceCut.RESYNC_SECONDS * VoiceTrackIndex.RATE + PER_FRAME);
		}
		assertTrue(jumps > 0 && jumps < 10, "a few jumps, not a stutter: " + jumps);
	}

	@Test
	void underSlowMotionTheVoicesKeepTheirOwnSpeed() {
		VoiceTrackIndex index = steady(0, 2000, 0);
		int slowFrom = 300;
		int frames = (int) Math.round(com.survivalcinema.director.render.RenderPlan.filmSeconds(200, 360, slowFrom) * FPS);
		VoiceCut cut = VoiceCut.plan(index, 200, 360, slowFrom, FPS, frames);
		for (int f = 0; f < frames; f++) {
			assertEquals(1f, cut.gain(f), "the voices are heard all the way, slow motion too");
		}
		// from the start of the slow motion on, one frame of picture is one frame of voice: "we killed it!" at normal speed
		int slowFrame = 0;
		double[] frameTicks = VoiceCut.frameTicks(200, 360, slowFrom, FPS, frames);
		while (frameTicks[slowFrame] < slowFrom + 10) {
			slowFrame++;
		}
		for (int f = slowFrame + 1; f < frames; f++) {
			assertEquals(PER_FRAME, cut.start(f) - cut.start(f - 1), "no jumps back to the slowed picture");
		}
		assertTrue(cut.start(frames - 1) > index.sampleAt(360), "the voice runs on past what the slowed picture shows");
		double[] ticks = VoiceCut.frameTicks(200, 360, slowFrom, FPS, frames);
		assertEquals(200, ticks[0], 1e-9);
		assertTrue(ticks[frames - 1] < 360 && ticks[frames - 1] > 355);
		assertEquals(Shot.SLOW_MOTION_SPEED, VoiceCut.speedAt(
			com.survivalcinema.director.render.ClipCamera.speedKeys(200, 360, slowFrom), 340), 1e-6);
	}

	@Test
	void theVoiceChatCopyOnlyPlaysUnderSlowMotion() {
		VoiceTrackIndex index = steady(0, 2000, 0);
		int slowFrom = 300;
		int frames = (int) Math.round(com.survivalcinema.director.render.RenderPlan.filmSeconds(200, 360, slowFrom) * FPS);
		VoiceCut cut = VoiceCut.plan(index, 200, 360, slowFrom, FPS, frames, true);
		assertEquals(0f, cut.gain(0), "at normal speed the clip has Flashback's own voices");
		assertEquals(1f, cut.gain(frames - 1), "under slow motion our copy is heard");
		double[] ticks = VoiceCut.frameTicks(200, 360, slowFrom, FPS, frames);
		int first = 0;
		while (cut.gain(first) == 0) {
			first++;
		}
		assertTrue(ticks[first] >= slowFrom - 10 && ticks[first] <= slowFrom + 10, "from where the slow motion starts: " + ticks[first]);
		assertEquals(index.sampleAt(ticks[first]), cut.start(first), PER_FRAME, "in step with the picture where it starts");
	}

	@Test
	void noTrackThereIsSilence() {
		VoiceTrackIndex index = steady(1000, 2000, 0);
		VoiceCut before = VoiceCut.plan(index, 0, 200, -1, FPS, 300);
		assertTrue(before.isSilent());
		assertNull(before.range());
		// the track starts in the middle of the clip
		VoiceCut half = VoiceCut.plan(index, 900, 1100, -1, FPS, 300);
		assertEquals(-1, half.start(0));
		assertEquals(2 * PER_FRAME, half.start(152), 1);
		assertEquals(0f, half.gain(10));
		assertEquals(1f, half.gain(200));
	}
}
