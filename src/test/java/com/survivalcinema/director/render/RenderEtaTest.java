package com.survivalcinema.director.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderEtaTest {
	@Test
	void guessesBeforeAnythingIsMeasured() {
		RenderEta eta = new RenderEta();
		assertEquals(60 * RenderEta.DEFAULT_SECONDS_PER_FILM_SECOND + 2 * RenderEta.DEFAULT_OPEN_SECONDS,
			eta.secondsLeft(60, 2), 1e-9);
	}

	@Test
	void learnsHowFastThisPcIs() {
		RenderEta eta = new RenderEta();
		eta.pieceDone(50, 10); // 5 s of work per film second
		eta.replayOpened(8);
		eta.replayOpened(12);
		assertEquals(5, eta.secondsPerFilmSecond(), 1e-9);
		assertEquals(20 * 5 + 10, eta.secondsLeft(20, 1), 1e-9);
	}

	@Test
	void aSingleShortClipDoesNotDecideEverything() {
		RenderEta eta = new RenderEta();
		eta.pieceDone(10, 1); // a 1 s clip that took long (first export warms up)
		double blended = eta.secondsPerFilmSecond();
		assertEquals(0.2 * 10 + 0.8 * RenderEta.DEFAULT_SECONDS_PER_FILM_SECOND, blended, 1e-9);
	}

	@Test
	void guessesTheWholeFilmForTheSetupScreen() {
		double draft = RenderEta.guessSeconds(300, RenderQuality.DRAFT, 3);
		double cinema = RenderEta.guessSeconds(300, RenderQuality.CINEMA, 3);
		assertEquals(300 * (RenderEta.DEFAULT_SECONDS_PER_FILM_SECOND + RenderEta.EDIT_SECONDS_PER_FILM_SECOND)
			+ 3 * RenderEta.DEFAULT_OPEN_SECONDS, draft, 1e-9);
		assertTrue(cinema > draft * 2, "cinema quality takes much longer");
		assertTrue(RenderEta.guessSeconds(1200, RenderQuality.DRAFT, 3) > draft, "a longer film takes longer");
		assertEquals(0, RenderEta.guessSeconds(-5, RenderQuality.DRAFT, -1), 1e-9);
	}

	@Test
	void minutesForTheScreen() {
		assertEquals(1, RenderEta.minutes(0));
		assertEquals(1, RenderEta.minutes(59));
		assertEquals(2, RenderEta.minutes(61));
		assertEquals(10, RenderEta.minutes(600));
	}
}
