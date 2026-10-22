package com.survivalcinema.director.edit;

import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.render.RenderProgress;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.EditDecisionList.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.survivalcinema.director.edit.EditTestData.FPS;
import static com.survivalcinema.director.edit.EditTestData.NETHER;
import static com.survivalcinema.director.edit.EditTestData.OVERWORLD;
import static com.survivalcinema.director.edit.EditTestData.END;
import static com.survivalcinema.director.edit.EditTestData.allShot;
import static com.survivalcinema.director.edit.EditTestData.asPlanned;
import static com.survivalcinema.director.edit.EditTestData.chapter;
import static com.survivalcinema.director.edit.EditTestData.clip;
import static com.survivalcinema.director.edit.EditTestData.credits;
import static com.survivalcinema.director.edit.EditTestData.edl;
import static com.survivalcinema.director.edit.EditTestData.intro;
import static com.survivalcinema.director.edit.EditTestData.slowClip;
import static com.survivalcinema.director.edit.EditTestData.timeline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilmTimelineTest {
	@Test
	void cardsAndClipsFollowTheScreenplayWithFadesAtChapterBreaks() {
		EditDecisionList edl = edl(List.of(intro(), chapter(1, null),
			clip(EventType.SCENERY, OVERWORLD, false, 0, 6), clip(EventType.KILL, OVERWORLD, false, 120, 5),
			chapter(2, NETHER), clip(EventType.DIMENSION_CHANGE, NETHER, false, 400, 8), credits()));
		FilmTimeline timeline = timeline(edl);
		List<FilmTimeline.Segment> s = timeline.segments();
		assertEquals(List.of(Kind.INTRO, Kind.CHAPTER, Kind.CLIP, Kind.CLIP, Kind.CHAPTER, Kind.CLIP, Kind.CREDITS),
			s.stream().map(FilmTimeline.Segment::kind).toList());
		assertEquals(List.of(120, 75, 180, 150, 75, 240, 300), s.stream().map(FilmTimeline.Segment::frames).toList());
		for (int i = 1; i < s.size(); i++) {
			assertEquals(s.get(i - 1).endFrame(), s.get(i).startFrame(), "segments follow each other without gaps");
		}
		// fades: the film fades in, intro and the first card share the picture, a cut inside the chapter,
		// a dip to black before the next chapter, a long fade into the credits and out of the film
		assertEquals(30, s.get(0).fadeInFrames());
		assertEquals(0, s.get(0).fadeOutFrames());
		assertEquals(0, s.get(1).fadeInFrames());
		assertEquals(0, s.get(2).fadeOutFrames());
		assertEquals(15, s.get(3).fadeOutFrames());
		assertEquals(15, s.get(4).fadeInFrames());
		assertEquals(45, s.get(5).fadeOutFrames());
		assertEquals(30, s.get(6).fadeInFrames());
		assertEquals(60, s.get(6).fadeOutFrames());
		assertEquals(1140, timeline.totalFrames());
		assertEquals(List.of(1, 2, 3), timeline.clips().stream().map(FilmTimeline.Segment::number).toList());
	}

	/**
	 * Building the house, then flying with an elytra: a hard cut made the flight just appear. Clips of different
	 * moments dissolve into each other; two shots of one moment still cut.
	 */
	@Test
	void differentMomentsDissolveIntoEachOther() {
		EditDecisionList edl = edl(List.of(intro(), chapter(1, null),
			clip(EventType.BUILD_BURST, OVERWORLD, false, 0, 6), clip(EventType.ELYTRA_FLIGHT, OVERWORLD, false, 400, 8),
			clip(EventType.ELYTRA_FLIGHT, OVERWORLD, false, 560, 5), credits()));
		List<FilmTimeline.Segment> s = timeline(edl).segments();
		FilmTimeline.Segment building = s.get(2);
		FilmTimeline.Segment flight = s.get(3);
		FilmTimeline.Segment sameFlight = s.get(4);
		int dissolve = (int) Math.round(FilmTimeline.DISSOLVE_SECONDS * FPS);
		assertEquals(dissolve, flight.dissolveFrames());
		assertEquals(building.endFrame() - dissolve, flight.startFrame(), "both play at once while one dissolves into the other");
		assertEquals(0, building.fadeOutFrames());
		assertEquals(0, flight.fadeInFrames());
		assertEquals(0, sameFlight.dissolveFrames(), "the same flight from another angle is a cut");
		assertEquals(flight.endFrame(), sameFlight.startFrame());
		assertEquals(0, s.get(2).dissolveFrames(), "no dissolve out of a chapter card");
		assertEquals(120 + 75 + 180 + 240 + 150 + 300 - dissolve, timeline(edl).totalFrames());

		// another recording, or another world, is always another moment; a very short clip dissolves for a third of it
		EditDecisionList.Item next = clip(EventType.KILL, NETHER, false, 125, 1);
		assertTrue(FilmTimeline.dissolves(clip(EventType.KILL, OVERWORLD, false, 0, 6), next));
		List<FilmTimeline.Segment> short_ = timeline(edl(List.of(intro(), chapter(1, null),
			clip(EventType.KILL, OVERWORLD, false, 0, 6), next, credits()))).segments();
		assertEquals(10, short_.get(3).dissolveFrames());
	}

	@Test
	void clipsAreAsLongAsTheirFilesNotAsPlanned() {
		EditDecisionList edl = edl(List.of(intro(), chapter(1, null), clip(EventType.SCENERY, OVERWORLD, false, 0, 6), credits()));
		FilmTimeline timeline = FilmTimeline.build(edl, allShot(edl), FPS, file -> 197, 10);
		assertEquals(197, timeline.clips().getFirst().frames());
	}

	@Test
	void unshotClipsAndChaptersLeftWithoutClipsAreLeftOut() {
		EditDecisionList edl = edl(List.of(intro(), chapter(1, null), clip(EventType.SCENERY, OVERWORLD, false, 0, 6),
			chapter(2, null), clip(EventType.KILL, OVERWORLD, false, 300, 5),
			chapter(3, null), clip(EventType.DEATH, OVERWORLD, false, 600, 5), credits()));
		RenderProgress progress = allShot(edl);
		progress.skip(2, "no replay");
		FilmTimeline.FrameCounter counter = file -> file.contains("003") ? -1 : asPlanned(edl).frames(file);
		FilmTimeline timeline = FilmTimeline.build(edl, progress, FPS, counter, 10);
		assertEquals(List.of(Kind.INTRO, Kind.CHAPTER, Kind.CLIP, Kind.CREDITS),
			timeline.segments().stream().map(FilmTimeline.Segment::kind).toList());
		assertEquals(1, timeline.clips().getFirst().number());
		// the last clip now fades into the credits
		assertEquals(45, timeline.clips().getFirst().fadeOutFrames());
	}

	@Test
	void noShotClipsMeansNoFilm() {
		EditDecisionList edl = edl(List.of(intro(), chapter(1, null), clip(EventType.SCENERY, OVERWORLD, false, 0, 6), credits()));
		RenderProgress progress = allShot(edl);
		progress.skip(1, "gone");
		assertTrue(FilmTimeline.build(edl, progress, FPS, asPlanned(edl), 10).isEmpty());
	}

	@Test
	void theFinaleGetsALongerDiveIntoBlack() {
		EditDecisionList edl = edl(List.of(intro(), chapter(1, null), clip(EventType.SCENERY, OVERWORLD, false, 0, 6),
			chapter(2, END), clip(EventType.BOSS_FIGHT_START, END, false, 300, 6), clip(EventType.DRAGON_KILLED, END, true, 500, 6),
			credits()));
		List<FilmTimeline.Segment> s = timeline(edl).segments();
		assertEquals(36, s.get(2).fadeOutFrames());
		assertEquals(24, s.get(3).fadeInFrames());
		assertTrue(s.get(3).finale() && s.get(4).finale() && s.get(5).finale());
		assertTrue(!s.get(2).finale());
	}

	@Test
	void creditsRollLongerWhenTheyHaveMoreToTell() {
		EditDecisionList edl = edl(List.of(intro(), chapter(1, null), clip(EventType.SCENERY, OVERWORLD, false, 0, 6), credits()));
		FilmTimeline timeline = FilmTimeline.build(edl, allShot(edl), FPS, asPlanned(edl), 17);
		assertEquals(17 * FPS, timeline.segments().getLast().frames());
	}

	@Test
	void ticksMapToFilmFramesThroughSlowMotion() {
		// 50 ticks at normal speed (2.5 s), then 50 ticks at half speed (5 s)
		EditDecisionList edl = edl(List.of(intro(), chapter(1, null), slowClip(EventType.DEATH, 0, 100, 50, 75), credits()));
		FilmTimeline.Segment clip = timeline(edl).clips().getFirst();
		assertEquals(225, clip.frames());
		assertEquals(clip.startFrame(), FilmTimeline.frameOf(clip, 0));
		assertEquals(clip.startFrame() + 75, FilmTimeline.frameOf(clip, 50));
		assertEquals(clip.startFrame() + 150, FilmTimeline.frameOf(clip, 75));
		assertEquals(-1, FilmTimeline.frameOf(clip, 100));
		assertEquals(-1, FilmTimeline.frameOf(clip, -5));
	}

	@Test
	void brightnessFollowsTheFades() {
		FilmTimeline.Segment credits = timeline(edl(List.of(intro(), chapter(1, null),
			clip(EventType.SCENERY, OVERWORLD, false, 0, 6), credits()))).segments().getLast();
		assertTrue(credits.brightness(0) < 0.05);
		assertEquals(1, credits.brightness(credits.frames() / 2), 1e-9);
		assertTrue(credits.brightness(credits.frames() - 1) < 0.05);
	}
}
