package com.survivalcinema.director.edit;

import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.EditDecisionList.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.survivalcinema.director.edit.EditTestData.END;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicPlanTest {
	private static boolean overlaps(MusicPlan.Cue cue, FilmTimeline.Segment segment) {
		return cue.startFrame() < segment.endFrame() && cue.endFrame() > segment.startFrame();
	}

	private static boolean covers(List<MusicPlan.Cue> cues, FilmTimeline.Segment segment) {
		return cues.stream().anyMatch(c -> c.startFrame() <= segment.startFrame() && c.endFrame() >= segment.endFrame());
	}

	@Test
	void musicNeverPlaysOverVoices() {
		FilmTimeline timeline = timeline(edl(List.of(intro(), chapter(1, null),
			clip(EventType.SCENERY, OVERWORLD, false, 0, 12),
			clip(EventType.CONVERSATION, OVERWORLD, true, 300, 10),
			clip(EventType.ELYTRA_FLIGHT, OVERWORLD, false, 600, 12),
			credits())));
		List<MusicPlan.Cue> cues = MusicPlan.plan(timeline);
		FilmTimeline.Segment voice = timeline.clips().get(1);
		assertFalse(cues.isEmpty());
		for (MusicPlan.Cue cue : cues) {
			assertFalse(overlaps(cue, voice), "music over " + cue);
		}
		// the film opens with music, and the credits have their own track
		assertEquals(0, cues.getFirst().startFrame());
		assertEquals(MusicPlan.Mood.CALM, cues.getFirst().mood());
		assertEquals(MusicPlan.Mood.CREDITS, cues.getLast().mood());
		assertEquals(timeline.totalFrames(), cues.getLast().endFrame());
	}

	/** "Music is often missing, even where the game has sounds": ordinary play gets it too, ducked under the game. */
	@Test
	void actionInTheOverworldHasMusicToo() {
		FilmTimeline timeline = timeline(edl(List.of(intro(), chapter(1, null),
			clip(EventType.KILL_STREAK, OVERWORLD, false, 0, 15),
			clip(EventType.DEATH, OVERWORLD, false, 400, 15),
			credits())));
		List<MusicPlan.Cue> cues = MusicPlan.plan(timeline);
		assertEquals(List.of(MusicPlan.Mood.CALM, MusicPlan.Mood.CREDITS), cues.stream().map(MusicPlan.Cue::mood).toList());
		for (FilmTimeline.Segment clip : timeline.clips()) {
			assertTrue(covers(cues, clip), "music under " + clip.item().eventType());
		}
	}

	/** "The intro sits there a long time without music": one tune from the first frame into the first scene. */
	@Test
	void theIntroMusicCarriesIntoTheFirstScene() {
		FilmTimeline timeline = timeline(edl(List.of(intro(), chapter(1, null),
			clip(EventType.SESSION_START, OVERWORLD, false, 0, 8), clip(EventType.KILL, OVERWORLD, false, 200, 8),
			clip(EventType.VOICE_PEAK, OVERWORLD, true, 500, 8),
			credits())));
		MusicPlan.Cue opening = MusicPlan.plan(timeline).getFirst();
		assertEquals(0, opening.startFrame());
		assertEquals(timeline.clips().get(2).startFrame(), opening.endFrame(), "one cue up to the first voice");

		// a film that starts with voices still opens on music, over the intro and the first card
		FilmTimeline talking = timeline(edl(List.of(intro(), chapter(1, null),
			clip(EventType.VOICE_PEAK, OVERWORLD, true, 0, 8), credits())));
		MusicPlan.Cue first = MusicPlan.plan(talking).getFirst();
		assertEquals(0, first.startFrame());
		assertEquals(talking.segments().get(1).endFrame(), first.endFrame());
	}

	/** A voice pauses the music; after it, the same track goes on where it stopped. */
	@Test
	void musicPausesForVoicesAndTheTuneGoesOn() {
		FilmTimeline timeline = timeline(edl(List.of(intro(), chapter(1, null),
			clip(EventType.KILL, OVERWORLD, false, 0, 15),
			clip(EventType.VOICE_PEAK, OVERWORLD, true, 400, 6),
			clip(EventType.SCENERY, OVERWORLD, false, 600, 15),
			credits())));
		List<MusicPlan.Cue> cues = MusicPlan.plan(timeline);
		List<MusicPlan.Cue> calm = cues.stream().filter(c -> c.mood() == MusicPlan.Mood.CALM).toList();
		assertEquals(2, calm.size());
		assertTrue(calm.get(0).thread() >= 0);
		assertEquals(calm.get(0).thread(), calm.get(1).thread(), "the same track, going on");
		assertTrue(cues.getLast().thread() != calm.get(0).thread(), "the credits have their own");
		assertEquals(timeline.clips().get(1).endFrame(), calm.get(1).startFrame());
	}

	@Test
	void theNetherChapterHasNetherMusicFromItsCardAndTheTracksCrossOver() {
		FilmTimeline timeline = timeline(edl(List.of(intro(), chapter(1, null),
			clip(EventType.KILL, OVERWORLD, false, 0, 15),
			chapter(1, NETHER),
			clip(EventType.KILL, NETHER, false, 400, 8), clip(EventType.RARE_ITEM, NETHER, false, 600, 6),
			credits())));
		List<MusicPlan.Cue> cues = MusicPlan.plan(timeline);
		FilmTimeline.Segment card = timeline.segments().get(3);
		MusicPlan.Cue calm = cues.getFirst();
		MusicPlan.Cue nether = cues.stream().filter(c -> c.mood() == MusicPlan.Mood.NETHER).findFirst().orElseThrow();
		assertEquals(card.startFrame(), nether.startFrame());
		assertEquals(card.startFrame() + Math.round(MusicPlan.CROSSFADE_SECONDS * FPS), calm.endFrame(),
			"the old tune plays on under the new one");
		assertTrue(calm.fadeOutFrames() > 0 && nether.fadeInFrames() > 0);
		MusicPlan.Cue credits = cues.getLast();
		assertTrue(nether.endFrame() > credits.startFrame(), "and into the credits");
	}

	@Test
	void theDragonFightGetsTheBossTrack() {
		FilmTimeline timeline = timeline(edl(List.of(intro(), chapter(1, null),
			clip(EventType.KILL, OVERWORLD, false, 0, 15),
			chapter(5, END),
			clip(EventType.BOSS_FIGHT_START, END, false, 400, 6), clip(EventType.DRAGON_KILLED, END, false, 600, 6),
			credits())));
		assertTrue(MusicPlan.plan(timeline).stream().anyMatch(c -> c.mood() == MusicPlan.Mood.BOSS));
	}

	@Test
	void aSnatchOfMusicBetweenVoicesOnlyWhereTheGameIsSilent() {
		FilmTimeline timeline = timeline(edl(List.of(intro(), chapter(1, null),
			clip(EventType.DEATH, OVERWORLD, true, 0, 10),
			clip(EventType.KILL, OVERWORLD, false, 300, 8),
			clip(EventType.DEATH, OVERWORLD, true, 500, 10),
			credits())));
		FilmTimeline.Segment between = timeline.clips().get(1);
		assertTrue(MusicPlan.plan(timeline).stream().noneMatch(c -> overlaps(c, between)), "a few seconds of game sound: no snatch of a tune");
		assertTrue(MusicPlan.plan(timeline, Set.of(between.number())).stream().anyMatch(c -> overlaps(c, between)),
			"a few silent seconds: music");
	}

	@Test
	void aFilmWithManyVoicesKeepsOneTuneGoingThroughThem() {
		List<EditDecisionList.Item> items = new ArrayList<>(List.of(intro(), chapter(1, null)));
		int tick = 0;
		for (int i = 0; i < 6; i++) {
			items.add(clip(EventType.SCENERY, OVERWORLD, false, tick, 15));
			items.add(clip(EventType.CONVERSATION, OVERWORLD, true, tick + 400, 20));
			tick += 1000;
		}
		items.add(chapter(2, NETHER));
		items.add(clip(EventType.KILL, NETHER, false, tick, 12));
		items.add(clip(EventType.CONVERSATION, OVERWORLD, true, tick + 400, 20));
		items.add(credits());
		FilmTimeline timeline = timeline(edl(items));
		List<MusicPlan.Cue> cues = MusicPlan.plan(timeline);
		for (FilmTimeline.Segment clip : timeline.clips()) {
			boolean music = cues.stream().anyMatch(c -> overlaps(c, clip));
			assertEquals(!clip.item().voice(), music, "music under " + clip.item().eventType() + " at " + clip.startFrame());
		}
		assertEquals(3, cues.stream().map(MusicPlan.Cue::thread).distinct().count(), "the calm tune, the Nether's and the credits'");
		for (int i = 1; i < cues.size(); i++) {
			assertTrue(cues.get(i - 1).startFrame() < cues.get(i).startFrame(), "cues in film order");
		}
	}

	@Test
	void cuesFadeInAndOut() {
		MusicPlan.Cue cue = new MusicPlan.Cue(100, 400, MusicPlan.Mood.CALM, 60, 75);
		assertEquals(0, cue.envelope(99));
		assertTrue(cue.envelope(100) < 0.05);
		assertEquals(1, cue.envelope(250), 1e-9);
		assertTrue(cue.envelope(399) < 0.05);
		assertEquals(0, cue.envelope(400));
		assertTrue(cue.envelope(130) > cue.envelope(110));
	}

	@Test
	void cardsSoundLikeTheirChapter() {
		FilmTimeline timeline = timeline(edl(List.of(intro(), chapter(3, null),
			clip(EventType.KILL, NETHER, false, 0, 15), credits())));
		assertEquals(MusicPlan.Mood.NETHER, MusicPlan.mood(timeline.segments(), 1, Set.of()));
		assertEquals(Kind.CHAPTER, timeline.segments().get(1).kind());
		assertEquals(FPS, timeline.fps());
	}
}
