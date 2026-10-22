package com.survivalcinema.director.render;

import com.survivalcinema.director.camera.ShotType;
import com.survivalcinema.director.story.EditDecisionList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.survivalcinema.director.render.RenderTestData.card;
import static com.survivalcinema.director.render.RenderTestData.clip;
import static com.survivalcinema.director.render.RenderTestData.edl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderPlanTest {
	private static EditDecisionList film() {
		return edl(1, List.of(
			card(EditDecisionList.Kind.INTRO),
			card(EditDecisionList.Kind.CHAPTER),
			clip("b", 1, 500, 600),  // film clip 1
			clip("a", 0, 300, 400),  // film clip 2
			clip("b", 1, 100, 200),  // film clip 3
			card(EditDecisionList.Kind.CHAPTER),
			clip("a", 0, 100, 200),  // film clip 4
			card(EditDecisionList.Kind.CREDITS)));
	}

	@Test
	void clipsAreShotSessionBySessionAndForwardInTime() {
		RenderPlan plan = RenderPlan.of(film());
		assertEquals(List.of(4, 2, 3, 1), plan.jobs().stream().map(RenderPlan.Job::number).toList());
		assertEquals(List.of("a", "a", "b", "b"), plan.jobs().stream().map(RenderPlan.Job::replayId).toList());
	}

	@Test
	void everySessionIsOpenedOnce() {
		List<List<RenderPlan.Job>> sessions = RenderPlan.of(film()).bySession();
		assertEquals(2, sessions.size());
		assertEquals(List.of(4, 2), sessions.get(0).stream().map(RenderPlan.Job::number).toList());
		assertEquals(List.of(3, 1), sessions.get(1).stream().map(RenderPlan.Job::number).toList());
	}

	@Test
	void numbersFollowTheFilmAndPointBackToTheScreenplay() {
		EditDecisionList edl = film();
		for (RenderPlan.Job job : RenderPlan.of(edl).jobs()) {
			assertTrue(edl.items().get(job.itemIndex()) == job.item());
		}
		RenderPlan.Job first = RenderPlan.of(edl).jobs().stream().filter(j -> j.number() == 1).findFirst().orElseThrow();
		assertEquals(500, first.item().startTick());
	}

	@Test
	void cardsAndBrokenClipsAreNotShot() {
		EditDecisionList edl = edl(1, List.of(
			card(EditDecisionList.Kind.INTRO),
			clip(null, 0, 0, 100),
			clip("a", 0, 100, 100),
			clip("a", 0, 100, 140)));
		RenderPlan plan = RenderPlan.of(edl);
		assertEquals(1, plan.size());
		assertEquals(1, plan.jobs().getFirst().number());
		assertEquals(2.0, plan.totalFilmSeconds(), 1e-9);
	}

	@Test
	void slowMotionMakesTheFilmLonger() {
		assertEquals(5.0, RenderPlan.filmSeconds(0, 100, -1), 1e-9);
		assertEquals(5.0, RenderPlan.filmSeconds(0, 100, 100), 1e-9);
		assertEquals(10.0, RenderPlan.filmSeconds(0, 100, 0), 1e-9);
		assertEquals(7.5, RenderPlan.filmSeconds(0, 100, 50), 1e-9);
		// a piece that starts after slow motion began is slow all the way
		assertEquals(10.0, RenderPlan.filmSeconds(200, 300, 150), 1e-9);
		EditDecisionList.Item item = clip("a", 0, 0, 100, ShotType.ORBIT, 50, null);
		assertEquals(item.filmSeconds(), RenderPlan.filmSeconds(0, 100, 50), 1e-9);
	}

	@Test
	void fileNamesSortInFilmOrder() {
		assertEquals("007.mp4", RenderPlan.clipFileName(7, 0));
		assertEquals("007-2.mp4", RenderPlan.clipFileName(7, 1));
		assertEquals("123.mp4", RenderPlan.clipFileName(123, 0));
		assertEquals("clips/012-3.mp4", FilmFolder.clipPath(12, 2));
	}
}
