package com.survivalcinema.director.play;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackTest {
	private static final long SECOND = 1_000_000_000L;

	@Test
	void theClockRunsOnlyWhilePlaying() {
		PlaybackClock clock = new PlaybackClock();
		assertEquals(0, clock.now(5 * SECOND), 1e-9);
		clock.start(10 * SECOND);
		assertTrue(clock.isRunning());
		assertEquals(2.5, clock.now(12 * SECOND + SECOND / 2), 1e-9);
		clock.stop(13 * SECOND);
		assertFalse(clock.isRunning());
		assertEquals(3, clock.now(60 * SECOND), 1e-9, "paused: the film waits");
		clock.start(100 * SECOND);
		assertEquals(4, clock.now(101 * SECOND), 1e-9, "goes on where it paused");
	}

	@Test
	void seekingMovesTheClockRunningOrNot() {
		PlaybackClock clock = new PlaybackClock();
		clock.set(120, 0);
		assertEquals(120, clock.now(10 * SECOND), 1e-9);
		clock.start(10 * SECOND);
		clock.set(30, 11 * SECOND);
		assertEquals(31, clock.now(12 * SECOND), 1e-9);
	}

	@Test
	void theClockGlidesTowardsTheSoundAndJumpsWhenFarOff() {
		PlaybackClock clock = new PlaybackClock();
		clock.start(0);
		// the sound is 50 ms behind: closed a little, not at once (no visible jump)
		clock.follow(0.95, SECOND);
		double glided = clock.now(SECOND);
		assertTrue(glided < 1 && glided > 0.95, "glides: " + glided);
		for (int i = 0; i < 100; i++) {
			clock.follow(0.95, SECOND);
		}
		assertEquals(0.95, clock.now(SECOND), 1e-3, "and catches up over a few frames");
		// the game froze for a second while the sound went on
		clock.follow(2.5, SECOND);
		assertEquals(2.5, clock.now(SECOND), 1e-9);
	}

	@Test
	void atAnotherSpeedTheFilmMovesFasterOrSlower() {
		PlaybackClock clock = new PlaybackClock();
		clock.start(0);
		clock.setSpeed(2, 10 * SECOND);
		assertEquals(10, clock.now(10 * SECOND), 1e-9, "no jump when the speed changes");
		assertEquals(14, clock.now(12 * SECOND), 1e-9, "then two seconds of film a second");
		clock.setSpeed(0.5, 12 * SECOND);
		assertEquals(15, clock.now(14 * SECOND), 1e-9);

		// sped-up sound keeps its pitch, so a second of samples covers 1.5 s of the film
		SoundQueue queue = new SoundQueue(48_000);
		queue.queued(20.0, 48_000, 1.5);
		queue.queued(21.5, 48_000, 1.5);
		assertEquals(20.75, queue.position(24_000), 1e-9);
		assertEquals(1.5 * 2 - 0.75, queue.secondsAhead(24_000), 1e-9);
		queue.processed(1);
		assertEquals(21.5, queue.position(0), 1e-9);
	}

	@Test
	void theSoundQueueSaysWhatIsHeardNow() {
		SoundQueue queue = new SoundQueue(48_000);
		assertTrue(Double.isNaN(queue.position(0)), "nothing played yet");
		queue.queued(10.0, 4800);
		queue.queued(10.1, 4800);
		queue.queued(10.2, 4800);
		assertEquals(3, queue.size());
		assertEquals(10.05, queue.position(2400), 1e-9);
		assertEquals(10.15, queue.position(7200), 1e-9, "offsets count from the oldest queued buffer");
		assertEquals(0.15, queue.secondsAhead(7200), 1e-9);
		queue.processed(1);
		assertEquals(10.15, queue.position(2400), 1e-9, "after giving a buffer back the offset restarts at the next one");
		queue.processed(2);
		assertEquals(10.3, queue.position(0), 1e-9, "all played: the end of the last buffer");
		assertEquals(0, queue.secondsAhead(0), 1e-9);
		queue.clear();
		assertTrue(Double.isNaN(queue.position(0)));
	}

	@Test
	void picksTheOldestPictureDueUnlessFarBehind() {
		List<Double> times = List.of(1.0, 1.033, 1.066, 1.1);
		assertEquals(-1, FramePicker.pick(times, 0.9), "too early for any");
		assertEquals(0, FramePicker.pick(times, 1.0));
		assertEquals(0, FramePicker.pick(times, 1.02));
		assertEquals(0, FramePicker.pick(times, 1.035), "a little late (under a frame and a half): still shown, not skipped");
		assertEquals(2, FramePicker.pick(times, 1.07), "further behind: the late ones are skipped");
		assertEquals(3, FramePicker.pick(times, 5));
		assertEquals(-1, FramePicker.pick(List.of(), 5));
	}

	/**
	 * A 60 fps film in a game drawing 60 frames a second, give or take a few milliseconds a frame: each frame shows the
	 * next picture, none is skipped (going by the clock alone, one in seven was skipped and another shown twice).
	 */
	@Test
	void aJitteryGameAtTheFilmsRateShowsEveryPicture() {
		java.util.ArrayDeque<Double> waiting = new java.util.ArrayDeque<>();
		int next = 0;
		int shown = 0;
		int skipped = 0;
		java.util.Random random = new java.util.Random(7);
		for (int frame = 0; frame < 3600; frame++) {
			// the game's frame limiter keeps to the clock; each frame lands up to 6 ms off it. The game's frames fall
			// right on the film's, where any jitter crosses from one picture to the next (the old rule skipped 500).
			double now = frame / 60.0 + (random.nextDouble() - 0.5) * 0.012;
			while (waiting.size() < 8) {
				waiting.add(next++ / 60.0);
			}
			int chosen = FramePicker.pick(List.copyOf(waiting), now);
			for (int i = 0; i < chosen; i++) {
				waiting.poll();
				skipped++;
			}
			if (chosen >= 0) {
				waiting.poll();
				shown++;
			}
		}
		assertEquals(0, skipped, "pictures skipped");
		assertTrue(shown > 3550, "a picture nearly every frame: " + shown);
	}

	@Test
	void theFilmFitsTheScreenWithBlackBars() {
		assertEquals(new Letterbox(0, 0, 1920, 1080), Letterbox.fit(1280, 720, 1920, 1080));
		assertEquals(new Letterbox(0, 60, 800, 450), Letterbox.fit(1280, 720, 800, 570), "bars above and below");
		assertEquals(new Letterbox(240, 0, 1440, 1080), Letterbox.fit(1280, 960, 1920, 1080), "bars on the sides");
		assertEquals(new Letterbox(0, 0, 0, 0), Letterbox.fit(0, 0, 0, 0));
	}

	@Test
	void decodesNoBiggerThanTheFilmOrTheScreen() {
		assertArrayEquals(new int[]{1280, 720}, Letterbox.decodeSize(1280, 720, 2560, 1440), "never upscaled");
		assertArrayEquals(new int[]{1920, 1080}, Letterbox.decodeSize(1920, 1080, 1920, 1080));
		assertArrayEquals(new int[]{854, 480}, Letterbox.decodeSize(1920, 1080, 854, 600), "a small window: even sides");
		assertArrayEquals(new int[]{2, 2}, Letterbox.decodeSize(0, 0, 100, 100));
	}

	@Test
	void timesReadLikeAPlayer() {
		assertEquals("0:00", PlayTime.format(0));
		assertEquals("0:07", PlayTime.format(7.9));
		assertEquals("4:32", PlayTime.format(272));
		assertEquals("20:00", PlayTime.format(1200));
		assertEquals("1:02:03", PlayTime.format(3723));
		assertEquals("0:00", PlayTime.format(-3));
		assertEquals("0:00", PlayTime.format(Double.NaN));
	}
}
