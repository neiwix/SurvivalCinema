package com.survivalcinema.director.edit;

import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.render.FilmFolder;
import com.survivalcinema.director.render.RenderPlan;
import com.survivalcinema.director.render.RenderProgress;
import com.survivalcinema.director.render.RenderQuality;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.EditDecisionList.Kind;
import com.survivalcinema.director.timelapse.TimelapsePlanner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.survivalcinema.director.edit.EditTestData.FPS;
import static com.survivalcinema.director.edit.EditTestData.OVERWORLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelapseEditTest {
	private static final double PICTURE_SECONDS = 0.5;

	/** intro, a day chapter with two clips, the base chapter with {@code pictures} pictures, a last chapter, credits. */
	private static EditDecisionList film(int pictures) {
		List<EditDecisionList.Item> items = new ArrayList<>();
		items.add(EditTestData.intro());
		items.add(EditTestData.chapter(1, null));
		items.add(EditTestData.clip(EventType.KILL, OVERWORLD, false, 100, 6));
		items.add(EditTestData.clip(EventType.RARE_ITEM, OVERWORLD, false, 400, 6));
		items.add(EditDecisionList.Item.card(Kind.CHAPTER, 2.5, 1, 9, EditDecisionList.BASE_CHAPTER, null));
		for (int i = 0; i < pictures; i++) {
			items.add(EditDecisionList.Item.timelapse(new TimelapsePlanner.Snapshot("replay", 0, 1000 + i * 700, 1 + i, i * 50),
				OVERWORLD, PICTURE_SECONDS));
		}
		items.add(EditTestData.chapter(10, null));
		items.add(EditTestData.clip(EventType.KILL, OVERWORLD, false, 9000, 6));
		items.add(EditTestData.credits());
		return EditTestData.edl(items);
	}

	/** Every job shot except the pictures listed as missing. */
	private static RenderProgress shot(EditDecisionList edl, int... missingPictures) {
		RenderProgress progress = RenderProgress.fresh(edl, "world", RenderQuality.DRAFT, 0);
		int picture = 0;
		List<RenderPlan.Job> jobs = new ArrayList<>(RenderPlan.of(edl).jobs());
		jobs.sort(java.util.Comparator.comparingInt(RenderPlan.Job::number));
		for (RenderPlan.Job job : jobs) {
			boolean isPicture = job.item().kind() == Kind.TIMELAPSE;
			int index = isPicture ? picture++ : -1;
			if (isPicture && java.util.Arrays.stream(missingPictures).anyMatch(m -> m == index)) {
				progress.skip(job.number(), "test");
				continue;
			}
			progress.pieceDone(job.number(), FilmFolder.clipPath(job.number(), 0));
			progress.clipDone(job.number());
		}
		return progress;
	}

	@Test
	void picturesBecomeOneTimelapse() {
		EditDecisionList edl = film(6);
		FilmTimeline timeline = FilmTimeline.build(edl, shot(edl, 2), FPS, EditTestData.asPlanned(edl), 10);
		List<FilmTimeline.Segment> lapses = timeline.segments().stream().filter(s -> s.kind() == Kind.TIMELAPSE).toList();
		assertEquals(1, lapses.size());
		FilmTimeline.Segment lapse = lapses.getFirst();
		assertEquals(5, lapse.files().size(), "the picture that could not be shot is left out");
		assertEquals((int) Math.round(PICTURE_SECONDS * FPS) * 5, lapse.frames());
		int index = timeline.segments().indexOf(lapse);
		FilmTimeline.Segment card = timeline.segments().get(index - 1);
		assertEquals(Kind.CHAPTER, card.kind());
		assertEquals(EditDecisionList.BASE_CHAPTER, card.item().dimension());
		// the card pulls focus into it, it fades out into the next chapter
		assertEquals(0, lapse.fadeInFrames());
		assertTrue(lapse.fadeOutFrames() > 0);
		assertTrue(timeline.segments().get(index + 1).fadeInFrames() > 0);
		// the clips keep their place around it
		assertEquals(3, timeline.clips().size());
	}

	@Test
	void tooFewPicturesLeaveNoTimelapseAndNoCard() {
		EditDecisionList edl = film(4);
		FilmTimeline timeline = FilmTimeline.build(edl, shot(edl, 0, 1, 2), FPS, EditTestData.asPlanned(edl), 10);
		assertTrue(timeline.segments().stream().noneMatch(s -> s.kind() == Kind.TIMELAPSE));
		assertTrue(timeline.segments().stream().noneMatch(s -> EditDecisionList.BASE_CHAPTER.equals(s.item().dimension())));
		assertEquals(3, timeline.clips().size());
	}

	@Test
	void theTimelapseGetsMusic() {
		EditDecisionList edl = film(10);
		FilmTimeline timeline = FilmTimeline.build(edl, shot(edl), FPS, EditTestData.asPlanned(edl), 10);
		FilmTimeline.Segment lapse = timeline.segments().stream().filter(s -> s.kind() == Kind.TIMELAPSE).findFirst().orElseThrow();
		List<MusicPlan.Cue> cues = MusicPlan.plan(timeline);
		MusicPlan.Cue cue = cues.stream().filter(c -> c.mood() == MusicPlan.Mood.TIMELAPSE).findFirst().orElseThrow();
		assertTrue(cue.startFrame() < lapse.startFrame(), "music starts on its card");
		assertTrue(cue.endFrame() >= lapse.endFrame());
	}
}
