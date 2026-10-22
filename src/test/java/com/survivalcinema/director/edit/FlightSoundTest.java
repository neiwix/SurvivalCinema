package com.survivalcinema.director.edit;

import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.event.EventType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.survivalcinema.director.edit.EditTestData.FPS;
import static com.survivalcinema.director.edit.EditTestData.OVERWORLD;
import static com.survivalcinema.director.edit.EditTestData.chapter;
import static com.survivalcinema.director.edit.EditTestData.clip;
import static com.survivalcinema.director.edit.EditTestData.credits;
import static com.survivalcinema.director.edit.EditTestData.edl;
import static com.survivalcinema.director.edit.EditTestData.intro;
import static com.survivalcinema.director.edit.EditTestData.timeline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wind of an elytra flight, which the replay of someone else's flight does not have. */
class FlightSoundTest {
	/** Walks until tick 100, flies at 1.5 blocks a tick until 300, then walks again. */
	private static Trail flight() {
		List<TrailSample> samples = new ArrayList<>();
		double x = 0;
		for (int t = 0; t <= 400; t += 2) {
			boolean flying = t >= 100 && t < 300;
			samples.add(new TrailSample(t, x, 100, 0, -90f, 0f, flying ? TrailSample.FLAG_FLYING_ELYTRA : TrailSample.FLAG_ON_GROUND));
			x += flying ? 3 : 0.4;
		}
		return new Trail(samples);
	}

	@Test
	void theWindFollowsTheFlightLikeInTheGame() {
		Trail trail = flight();
		int frames = (320 - 80) * FPS / 20;
		double[] wind = FlightSound.volumes(trail, 80, 320, -1, FPS, frames);
		assertEquals(frames, wind.length);
		assertEquals(0, wind[frameAt(90)], 1e-9, "walking: no wind");
		assertEquals(0, wind[frameAt(110)], 1e-9, "the first second of a flight is silent");
		assertTrue(wind[frameAt(130)] > 0 && wind[frameAt(130)] < 0.5, "growing: " + wind[frameAt(130)]);
		assertEquals(1.5 * 1.5 / 4, wind[frameAt(200)], 0.02, "louder the faster they fly");
		assertEquals(0, wind[frameAt(310)], 1e-9, "landed");
	}

	private static int frameAt(int tick) {
		return (tick - 80) * FPS / 20;
	}

	@Test
	void slowMotionStretchesTheWind() {
		Trail trail = flight();
		// from 200 on at half speed: twice the frames for the same ticks
		int frames = (200 - 150) * FPS / 20 + (250 - 200) * FPS / 10;
		double[] wind = FlightSound.volumes(trail, 150, 250, 200, FPS, frames);
		assertEquals(1.5 * 1.5 / 4, wind[frames - 1], 0.02);
		assertEquals(1.5 * 1.5 / 4, wind[0], 0.02);
	}

	@Test
	void aClipMostlyFlownIsAFlight() {
		Trail trail = flight();
		assertTrue(FlightSound.isFlight(trail, 90, 290));
		assertFalse(FlightSound.isFlight(trail, 0, 120));
	}

	@Test
	void aFlightGetsMusicEvenWhenItsMomentIsSomethingElse() {
		// the flight was filmed for the advancement it brought: the music that plays on before it carries on over it
		FilmTimeline timeline = timeline(edl(List.of(intro(), chapter(1, null),
			clip(EventType.KILL_STREAK, OVERWORLD, false, 0, 15),
			clip(EventType.ADVANCEMENT, OVERWORLD, false, 400, 9),
			credits())));
		FilmTimeline.Segment flight = timeline.clips().get(1);
		List<MusicPlan.Cue> cues = MusicPlan.plan(timeline, Set.of(), Set.of(flight.number()));
		MusicPlan.Cue music = cues.getFirst();
		assertTrue(music.startFrame() <= flight.startFrame() && music.endFrame() >= flight.endFrame(), "over the whole flight: " + music);

		// between two voices, even a short flight gets a soaring tune of its own
		FilmTimeline alone = timeline(edl(List.of(intro(), chapter(1, null),
			clip(EventType.VOICE_PEAK, OVERWORLD, true, 0, 10),
			clip(EventType.ADVANCEMENT, OVERWORLD, false, 400, 6),
			clip(EventType.VOICE_PEAK, OVERWORLD, true, 600, 10),
			credits())));
		FilmTimeline.Segment short_ = alone.clips().get(1);
		assertTrue(MusicPlan.plan(alone, Set.of(), Set.of(short_.number())).stream()
			.anyMatch(c -> c.mood() == MusicPlan.Mood.FLIGHT && c.startFrame() < short_.endFrame() && c.endFrame() > short_.startFrame()));
	}
}
