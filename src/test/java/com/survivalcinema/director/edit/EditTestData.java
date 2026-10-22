package com.survivalcinema.director.edit;

import com.survivalcinema.director.camera.ShotType;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.render.FilmFolder;
import com.survivalcinema.director.render.RenderPlan;
import com.survivalcinema.director.render.RenderProgress;
import com.survivalcinema.director.render.RenderQuality;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.EditDecisionList.Kind;

import java.util.List;

/** Screenplays and shooting progress for the editing tests. All clips are 30 fps, one file each. */
final class EditTestData {
	static final int FPS = 30;
	static final String OVERWORLD = "minecraft:overworld";
	static final String NETHER = "minecraft:the_nether";
	static final String END = "minecraft:the_end";

	private EditTestData() {
	}

	static EditDecisionList.Item intro() {
		return EditDecisionList.Item.card(Kind.INTRO, 4, 0, 0, null, null);
	}

	static EditDecisionList.Item chapter(int day, String dimension) {
		return EditDecisionList.Item.card(Kind.CHAPTER, 2.5, day, day, dimension, "subtitle");
	}

	static EditDecisionList.Item credits() {
		return EditDecisionList.Item.card(Kind.CREDITS, 10, 0, 0, null, null);
	}

	/** A clip of {@code seconds} of replay time starting at {@code startTick}. */
	static EditDecisionList.Item clip(EventType type, String dimension, boolean voice, int startTick, double seconds) {
		int end = startTick + (int) Math.round(seconds * 20);
		return new EditDecisionList.Item(Kind.CLIP, 0, 1, 1, dimension, null, "replay", 0, startTick, end,
			ShotType.ORBIT, 6, 1.5, 90, 1, -1, type, type.name(), 1, null, startTick + (end - startTick) / 2, voice);
	}

	static EditDecisionList.Item slowClip(EventType type, int startTick, int endTick, int slowFrom, int eventTick) {
		return new EditDecisionList.Item(Kind.CLIP, 0, 1, 1, OVERWORLD, null, "replay", 0, startTick, endTick,
			ShotType.ORBIT, 6, 1.5, 90, 1, slowFrom, type, type.name(), 1, null, eventTick, false);
	}

	static EditDecisionList edl(List<EditDecisionList.Item> items) {
		return new EditDecisionList(EditDecisionList.VERSION, "Мир", "Steve", 300, 7, items);
	}

	/** Every clip shot, one file each. */
	static RenderProgress allShot(EditDecisionList edl) {
		RenderProgress progress = RenderProgress.fresh(edl, "world", RenderQuality.DRAFT, 0);
		for (RenderPlan.Job job : RenderPlan.of(edl).jobs()) {
			progress.pieceDone(job.number(), FilmFolder.clipPath(job.number(), 0));
			progress.clipDone(job.number());
		}
		progress.finished = true;
		return progress;
	}

	/** Files exactly as long as planned. */
	static FilmTimeline.FrameCounter asPlanned(EditDecisionList edl) {
		return file -> {
			for (RenderPlan.Job job : RenderPlan.of(edl).jobs()) {
				if (file.equals(FilmFolder.clipPath(job.number(), 0))) {
					return (int) Math.round(job.filmSeconds() * FPS);
				}
			}
			return -1;
		};
	}

	static FilmTimeline timeline(EditDecisionList edl) {
		return FilmTimeline.build(edl, allShot(edl), FPS, asPlanned(edl), 10);
	}
}
