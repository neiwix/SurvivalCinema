package com.survivalcinema.director.story;

import com.survivalcinema.director.event.EventType;
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
		List<Scene> scenes = new ArrayList<>(new SceneBuilder().build(scored));
		BaseFinder.Base base = timelapse != null ? timelapse.base() : BaseFinder.find(sessions, options.baseRules());
		scenes.addAll(StoryBeats.find(sessions, base));
		List<Scene> scenery = new SceneryFinder().find(sessions);
		List<Chapter> chapters = new StoryPlanner().plan(sessions, scenes, scenery, storySeconds);

		List<EditDecisionList.Item> items = new ArrayList<>();
		items.add(EditDecisionList.Item.card(EditDecisionList.Kind.INTRO, StoryPlanner.INTRO_SECONDS, 0, 0, null, null));
		ShotPlanner shots = new ShotPlanner();
		int timelapseBefore = timelapse == null ? -1 : timelapseChapter(chapters);
		if (timelapseBefore > 0 && timelapseBefore < chapters.size()) {
			int before = chapters.size();
			chapters = withLeadIn(chapters, timelapseBefore);
			timelapseBefore -= before - chapters.size();
		}
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

	/** Scenes at most this far apart lead straight into the next one (setting the eyes of ender, then the jump in). */
	static final int LEAD_IN_GAP_TICKS = 20 * 30;
	/** The way to the End taken into its chapter is at most this long. */
	static final int LEAD_IN_MAX_TICKS = 20 * 40;

	/**
	 * The scenes at the end of the chapter before {@code chapter} that lead straight into it (walking up to the End
	 * portal and setting the eyes in, a minute before jumping in) go over to it: the base's timelapse goes in between
	 * the two chapters and would cut the way to the End in half. Not across a change of dimension (coming back from the
	 * Nether is the Nether's chapter's end). A chapter left with nothing goes.
	 */
	static List<Chapter> withLeadIn(List<Chapter> chapters, int chapter) {
		Chapter before = chapters.get(chapter - 1);
		Chapter into = chapters.get(chapter);
		if (into.scenes().isEmpty()) {
			return chapters;
		}
		Scene next = into.scenes().getFirst();
		int moved = 0;
		int ticks = 0;
		for (int i = before.scenes().size() - 1; i >= 0; i--) {
			Scene scene = before.scenes().get(i);
			boolean leads = scene.session().index() == next.session().index() && next.startTick() - scene.endTick() <= LEAD_IN_GAP_TICKS
				&& next.startTick() >= scene.endTick() && scene.primary().event().type() != EventType.DIMENSION_CHANGE
				&& ticks + scene.durationTicks() <= LEAD_IN_MAX_TICKS;
			if (!leads) {
				break;
			}
			ticks += scene.durationTicks();
			moved++;
			next = scene;
		}
		if (moved == 0) {
			return chapters;
		}
		List<Scene> stays = before.scenes().subList(0, before.scenes().size() - moved);
		List<Scene> leadIn = new ArrayList<>(before.scenes().subList(before.scenes().size() - moved, before.scenes().size()));
		leadIn.addAll(into.scenes());
		List<Chapter> result = new ArrayList<>(chapters);
		result.set(chapter, new Chapter(Math.min(into.dayFrom(), leadIn.getFirst().day()), into.dayTo(), into.dimension(),
			into.subtitle(), List.copyOf(leadIn)));
		if (stays.isEmpty()) {
			result.remove(chapter - 1);
		} else {
			result.set(chapter - 1, new Chapter(before.dayFrom(), before.dayTo(), before.dimension(), before.subtitle(), List.copyOf(stays)));
		}
		return result;
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
