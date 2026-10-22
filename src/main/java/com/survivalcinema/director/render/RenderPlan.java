package com.survivalcinema.director.render;

import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.Shot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * In which order the clips of a film are shot. Opening a replay takes a while, so every session is opened once and
 * all its clips are shot in a row, oldest session first; inside a session the clips go forward in time, so the replay
 * never has to jump back. Each clip keeps its place in the film ({@link Job#number()}) as its file name, which is the
 * order editing puts them back in.
 */
public final class RenderPlan {
	private final List<Job> jobs;

	/**
	 * @param number    position among the film's clips, 1 = first clip of the film; also the file name
	 * @param itemIndex index of the clip in {@link EditDecisionList#items()}
	 */
	public record Job(int number, int itemIndex, EditDecisionList.Item item) {
		public String replayId() {
			return item.replayId();
		}

		public double filmSeconds() {
			return item.filmSeconds();
		}
	}

	private RenderPlan(List<Job> jobs) {
		this.jobs = List.copyOf(jobs);
	}

	public static RenderPlan of(EditDecisionList edl) {
		List<Job> jobs = new ArrayList<>();
		int number = 0;
		for (int i = 0; i < edl.items().size(); i++) {
			EditDecisionList.Item item = edl.items().get(i);
			if (isShot(item) && item.replayId() != null && item.endTick() > item.startTick()) {
				jobs.add(new Job(++number, i, item));
			}
		}
		jobs.sort(Comparator.comparingInt((Job j) -> j.item().sessionIndex())
			.thenComparing(Job::replayId)
			.thenComparingInt(j -> j.item().startTick())
			.thenComparingInt(Job::number));
		return new RenderPlan(jobs);
	}

	/** Items that are shot in a replay: clips and the pictures of the base timelapse. */
	public static boolean isShot(EditDecisionList.Item item) {
		return item.kind() == EditDecisionList.Kind.CLIP || item.kind() == EditDecisionList.Kind.TIMELAPSE;
	}

	/** Every clip, in shooting order. */
	public List<Job> jobs() {
		return jobs;
	}

	public int size() {
		return jobs.size();
	}

	/** The jobs split by replay, in shooting order: one group per replay to open. */
	public List<List<Job>> bySession() {
		List<List<Job>> groups = new ArrayList<>();
		List<Job> current = null;
		for (Job job : jobs) {
			if (current == null || !current.getFirst().replayId().equals(job.replayId())) {
				current = new ArrayList<>();
				groups.add(current);
			}
			current.add(job);
		}
		return groups;
	}

	public double totalFilmSeconds() {
		return jobs.stream().mapToDouble(Job::filmSeconds).sum();
	}

	/**
	 * How long {@code [fromTick, toTick]} lasts in the film when everything from {@code slowFromTick} on plays at
	 * {@link Shot#SLOW_MOTION_SPEED} ({@code slowFromTick < 0}: no slow motion).
	 */
	public static double filmSeconds(int fromTick, int toTick, int slowFromTick) {
		if (toTick <= fromTick) {
			return 0;
		}
		if (slowFromTick < 0 || slowFromTick >= toTick) {
			return (toTick - fromTick) / 20.0;
		}
		int slowStart = Math.max(fromTick, slowFromTick);
		return ((slowStart - fromTick) + (toTick - slowStart) / Shot.SLOW_MOTION_SPEED) / 20.0;
	}

	/**
	 * File name of a clip: {@code 007.mp4}. A clip cut in pieces at a teleport or portal gets one file per piece:
	 * {@code 007.mp4}, {@code 007-2.mp4}...
	 */
	public static String clipFileName(int number, int part) {
		String base = String.format(Locale.ROOT, "%03d", number);
		return (part == 0 ? base : base + "-" + (part + 1)) + ".mp4";
	}
}
