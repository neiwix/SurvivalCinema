package com.survivalcinema.director.story;

import com.survivalcinema.director.timelapse.BaseFinder;
import com.survivalcinema.director.timelapse.TimelapsePlanner;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole "brain" in one call: recorded sessions in, screenplay out.
 */
public final class FilmDirector {
	/** @param baseRules how much building makes a base worth a timelapse */
	public record Options(int targetSeconds, String worldName, String playerName, BaseFinder.Rules baseRules) {
		public Options(int targetSeconds, String worldName, String playerName) {
			this(targetSeconds, worldName, playerName, BaseFinder.Rules.DEFAULT);
		}
	}

	private FilmDirector() {
	}

	public static EditDecisionList plan(List<SessionData> sessions, Options options) {
		TimelapsePlanner.Plan timelapse = TimelapsePlanner.plan(sessions, options.baseRules());
		int storySeconds = options.targetSeconds();
		if (timelapse != null) {
			storySeconds -= (int) Math.ceil(timelapse.seconds() + StoryPlanner.CHAPTER_CARD_SECONDS);
		}

		List<ScoredEvent> scored = new HighlightScorer().score(sessions);
		List<Scene> scenes = new SceneBuilder().build(scored);
		List<Scene> scenery = new SceneryFinder().find(sessions);
		List<Chapter> chapters = new StoryPlanner().plan(sessions, scenes, scenery, storySeconds);

		List<EditDecisionList.Item> items = new ArrayList<>();
		items.add(EditDecisionList.Item.card(EditDecisionList.Kind.INTRO, StoryPlanner.INTRO_SECONDS, 0, 0, null, null));
		ShotPlanner shots = new ShotPlanner();
		int timelapseBefore = timelapse == null ? -1 : timelapseChapter(chapters);
		for (int c = 0; c < chapters.size(); c++) {
			if (c == timelapseBefore) {
				addTimelapse(items, timelapse);
			}
			Chapter chapter = chapters.get(c);
			items.add(EditDecisionList.Item.card(EditDecisionList.Kind.CHAPTER, StoryPlanner.CHAPTER_CARD_SECONDS,
				chapter.dayFrom(), chapter.dayTo(), chapter.dimension(), chapter.subtitle()));
			for (Scene scene : chapter.scenes()) {
				for (Shot shot : shots.plan(scene)) {
					items.add(EditDecisionList.Item.clip(scene, shot));
				}
			}
		}
		if (timelapse != null && timelapseBefore >= chapters.size()) {
			addTimelapse(items, timelapse);
		}
		items.add(EditDecisionList.Item.card(EditDecisionList.Kind.CREDITS, StoryPlanner.CREDITS_SECONDS, 0, 0, null, null));
		return new EditDecisionList(EditDecisionList.VERSION, options.worldName(), options.playerName(),
			options.targetSeconds(), System.currentTimeMillis(), List.copyOf(items), FilmStats.of(sessions, options.playerName()),
			timelapse == null ? null : timelapse.view());
	}

	/**
	 * Where the story of the base goes: near the end, just before the journey to the End (the dragon and the epilogue
	 * stay last), or after the last chapter when the End was never reached.
	 */
	static int timelapseChapter(List<Chapter> chapters) {
		for (int i = 0; i < chapters.size(); i++) {
			if ("minecraft:the_end".equals(chapters.get(i).dimension())) {
				return i;
			}
		}
		return chapters.size();
	}

	/** "Our base, days 3-41", then the base growing. */
	private static void addTimelapse(List<EditDecisionList.Item> items, @Nullable TimelapsePlanner.Plan timelapse) {
		if (timelapse == null) {
			return;
		}
		BaseFinder.Base base = timelapse.base();
		int dayFrom = timelapse.snapshots().getFirst().day();
		int dayTo = Math.max(dayFrom, timelapse.snapshots().getLast().day());
		items.add(EditDecisionList.Item.card(EditDecisionList.Kind.CHAPTER, StoryPlanner.CHAPTER_CARD_SECONDS, dayFrom, dayTo,
			EditDecisionList.BASE_CHAPTER, null));
		for (TimelapsePlanner.Snapshot snapshot : timelapse.snapshots()) {
			items.add(EditDecisionList.Item.timelapse(snapshot, base.dimension()));
		}
	}
}
