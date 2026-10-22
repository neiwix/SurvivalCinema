package com.survivalcinema.director.edit;

import com.survivalcinema.director.event.EventType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.survivalcinema.director.edit.EditTestData.FPS;
import static com.survivalcinema.director.edit.EditTestData.NETHER;
import static com.survivalcinema.director.edit.EditTestData.OVERWORLD;
import static com.survivalcinema.director.edit.EditTestData.chapter;
import static com.survivalcinema.director.edit.EditTestData.clip;
import static com.survivalcinema.director.edit.EditTestData.credits;
import static com.survivalcinema.director.edit.EditTestData.edl;
import static com.survivalcinema.director.edit.EditTestData.intro;
import static com.survivalcinema.director.edit.EditTestData.timeline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundAndCreditsTest {
	@Test
	void soundEffectsLandOnTheirMoments() {
		FilmTimeline timeline = timeline(edl(List.of(intro(), chapter(1, null),
			clip(EventType.ADVANCEMENT, OVERWORLD, false, 0, 6),
			clip(EventType.KILL, OVERWORLD, false, 300, 6),
			chapter(1, NETHER), clip(EventType.DIMENSION_CHANGE, NETHER, false, 600, 6),
			credits())));
		List<SoundCues.Cue> cues = SoundCues.plan(timeline);
		assertEquals(List.of(SoundCues.Effect.LEVEL_UP, SoundCues.Effect.PORTAL), cues.stream().map(SoundCues.Cue::effect).toList());
		FilmTimeline.Segment advancement = timeline.clips().getFirst();
		// the event is in the middle of the clip
		assertEquals(advancement.startFrame() + advancement.frames() / 2, cues.getFirst().frame(), 1);
		assertEquals(timeline.segments().get(4).startFrame(), cues.get(1).frame());
	}

	@Test
	void musicDucksFastUnderLoudGameSoundAndComesBackSlowly() {
		Ducker ducker = new Ducker(FPS);
		for (int i = 0; i < FPS; i++) {
			assertEquals(1, ducker.next(0.01), 1e-9);
		}
		double gain = 1;
		for (int i = 0; i < FPS / 2; i++) {
			gain = ducker.next(0.5);
		}
		assertEquals(Ducker.DUCKED, gain, 0.05);
		for (int i = 0; i < FPS / 2; i++) {
			gain = ducker.next(0.0);
		}
		assertTrue(gain < 0.8, "still coming back after half a second: " + gain);
		for (int i = 0; i < FPS * 6; i++) {
			gain = ducker.next(0.0);
		}
		assertEquals(1, gain, 0.02);
	}

	@Test
	void loudnessOfSamples() {
		short[] silence = new short[100];
		assertEquals(0, Ducker.rms(silence, 100), 1e-9);
		short[] full = new short[100];
		java.util.Arrays.fill(full, Short.MAX_VALUE);
		assertEquals(1, Ducker.rms(full, 100), 1e-3);
		assertEquals(0, Ducker.rms(full, 0), 1e-9);
	}

	@Test
	void creditsRollUpUntilTheLastLineIsInTheMiddle() {
		List<CreditsRoll.Line> lines = new ArrayList<>();
		lines.add(new CreditsRoll.Line(CreditsRoll.Style.TITLE, "Мир"));
		for (int i = 0; i < 10; i++) {
			lines.add(new CreditsRoll.Line(CreditsRoll.Style.TEXT, "line " + i));
		}
		CreditsRoll roll = new CreditsRoll(lines, 20);
		assertEquals(20, roll.topAt(0), 1e-9);
		double seconds = roll.seconds(10);
		assertTrue(seconds >= 10);
		double end = roll.topAt(seconds);
		assertEquals(end, roll.topAt(seconds + 100), 1e-9);
		// the last line's middle sits in the middle of the picture
		double lastMiddle = end + roll.heightRows() - CreditsRoll.Style.TEXT.rows / 2;
		assertEquals(10, lastMiddle, 1e-9);
		assertTrue(roll.topAt(1) < roll.topAt(0));

		List<CreditsRoll.Line> longer = new ArrayList<>(lines);
		for (int i = 0; i < 40; i++) {
			longer.add(new CreditsRoll.Line(CreditsRoll.Style.TEXT, "more " + i));
		}
		assertTrue(new CreditsRoll(longer, 20).seconds(10) > seconds);
		assertEquals(10, new CreditsRoll(List.of(), 20).seconds(10), 1e-9);
	}
}
