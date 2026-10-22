package com.survivalcinema.director.edit;

import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.render.RenderPlan;
import com.survivalcinema.director.render.RenderProgress;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.EditDecisionList.Kind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The finished film frame by frame: which title cards and shot clips follow each other, how long each one lasts
 * (a clip as long as its files really are: slow motion ramps make them a little longer than planned) and where the
 * picture fades through black.
 * <p>
 * Transitions, like a film editor would cut it: the film fades in on the intro, a chapter card fades in from black
 * and then pulls focus into its first clip, clips of one chapter follow each other on a cut, the last clip of a
 * chapter fades to black, the finale gets a longer "dive into black", and the credits fade out at the very end.
 * Clips that could not be shot are left out, and so is a chapter left without clips.
 */
public final class FilmTimeline {
	public static final double FILM_FADE_IN_SECONDS = 1.0;
	public static final double CHAPTER_FADE_SECONDS = 0.5;
	public static final double FINALE_FADE_OUT_SECONDS = 1.2;
	public static final double FINALE_FADE_IN_SECONDS = 0.8;
	public static final double CREDITS_FADE_IN_SECONDS = 1.0;
	public static final double LAST_CLIP_FADE_OUT_SECONDS = 1.5;
	public static final double FILM_FADE_OUT_SECONDS = 2.0;
	/** How long two clips of different moments dissolve into each other (both play at once). */
	public static final double DISSOLVE_SECONDS = 0.8;
	/** Clips of one recording at most this many ticks apart are one moment: they cut, not dissolve. */
	static final int CONTINUOUS_GAP_TICKS = 20;
	/** A timelapse needs at least this many pictures of the base ("before" and "after"), or it is left out. */
	public static final int MIN_TIMELAPSE_PICTURES = 2;

	/**
	 * One piece of the film.
	 *
	 * @param number        the clip's number (its file name), 0 for cards
	 * @param files         the clip's files, relative to the film folder, in order (empty for cards); for the base
	 *                      timelapse its pictures, one file each, each shown {@link #frames()} / pictures frames
	 * @param fadeInFrames  frames at the start that come up from black
	 * @param fadeOutFrames frames at the end that go down to black
	 * @param chapter       index of the chapter, -1 before the first one (intro) and for the credits
	 * @param finale        part of the chapter with the dragon fight
	 * @param dissolveFrames frames at the start that dissolve from the end of the clip before (both play at once: this
	 *                       segment starts that many frames before the previous one ends); 0 for a cut
	 */
	public record Segment(Kind kind, EditDecisionList.Item item, int number, List<String> files, int startFrame,
						  int frames, int fadeInFrames, int fadeOutFrames, int chapter, boolean finale, int dissolveFrames) {
		public int endFrame() {
			return startFrame + frames;
		}

		public boolean isCard() {
			return kind != Kind.CLIP;
		}

		/** How bright the picture is at a frame of this segment, 0 (black) to 1, from its fades. */
		public double brightness(int frame) {
			double value = 1;
			if (fadeInFrames > 0 && frame < fadeInFrames) {
				value = Math.min(value, (frame + 0.5) / fadeInFrames);
			}
			if (fadeOutFrames > 0 && frame >= frames - fadeOutFrames) {
				value = Math.min(value, (frames - frame - 0.5) / fadeOutFrames);
			}
			return Math.max(0, Math.min(1, value));
		}
	}

	/** How many video frames a shot file really has, or a negative number when it cannot be read. */
	public interface FrameCounter {
		int frames(String file);
	}

	private final List<Segment> segments;
	private final int fps;

	private FilmTimeline(List<Segment> segments, int fps) {
		this.segments = List.copyOf(segments);
		this.fps = fps;
	}

	/**
	 * @param creditsSeconds how long the credits roll (they get longer than planned when there is a lot to tell)
	 */
	public static FilmTimeline build(EditDecisionList edl, RenderProgress progress, int fps, FrameCounter counter,
									 double creditsSeconds) {
		Map<Integer, Integer> numbers = new HashMap<>();
		for (RenderPlan.Job job : RenderPlan.of(edl).jobs()) {
			numbers.put(job.itemIndex(), job.number());
		}

		// 1. what is in the film: cards and the clips that were shot
		List<Draft> drafts = new ArrayList<>();
		int chapter = -1;
		for (int i = 0; i < edl.items().size(); i++) {
			EditDecisionList.Item item = edl.items().get(i);
			switch (item.kind()) {
				case INTRO -> drafts.add(new Draft(item, 0, List.of(), frames(item.cardSeconds(), fps), -1));
				case CHAPTER -> drafts.add(new Draft(item, 0, List.of(), frames(item.cardSeconds(), fps), ++chapter));
				case CREDITS -> drafts.add(new Draft(item, 0, List.of(), frames(Math.max(item.cardSeconds(), creditsSeconds), fps), -1));
				case CLIP -> {
					Integer number = numbers.get(i);
					if (number == null || progress.status(number) != RenderProgress.Status.DONE) {
						continue;
					}
					List<String> files = progress.files(number);
					int total = 0;
					boolean readable = !files.isEmpty();
					for (String file : files) {
						int n = counter.frames(file);
						if (n <= 0) {
							readable = false;
							break;
						}
						total += n;
					}
					if (readable) {
						drafts.add(new Draft(item, number, files, total, chapter));
					}
				}
				case TIMELAPSE -> {
					// the pictures in a row make one timelapse; each keeps the time it was planned to show
					Integer number = numbers.get(i);
					List<String> files = number == null ? List.of() : progress.files(number);
					if (number == null || progress.status(number) != RenderProgress.Status.DONE || files.isEmpty()
						|| counter.frames(files.getFirst()) <= 0) {
						continue;
					}
					Draft last = drafts.isEmpty() ? null : drafts.getLast();
					if (last != null && last.item.kind() == Kind.TIMELAPSE) {
						List<String> pictures = new ArrayList<>(last.files);
						pictures.add(files.getFirst());
						drafts.set(drafts.size() - 1, new Draft(last.item, 0, pictures, last.frames + frames(item.cardSeconds(), fps), last.chapter));
					} else {
						drafts.add(new Draft(item, 0, List.of(files.getFirst()), frames(item.cardSeconds(), fps), chapter));
					}
				}
			}
		}
		// 2. a chapter without clips loses its card, a timelapse of too few pictures goes; no clips at all means no film
		drafts.removeIf(d -> d.item.kind() == Kind.TIMELAPSE && d.files.size() < MIN_TIMELAPSE_PICTURES);
		List<Draft> kept = new ArrayList<>();
		for (int i = 0; i < drafts.size(); i++) {
			Draft draft = drafts.get(i);
			if (draft.item.kind() == Kind.CHAPTER && (i + 1 >= drafts.size() || !isFootage(drafts.get(i + 1).item.kind()))) {
				continue;
			}
			kept.add(draft);
		}
		if (kept.stream().noneMatch(d -> d.item.kind() == Kind.CLIP)) {
			return new FilmTimeline(List.of(), fps);
		}
		int finaleChapter = finaleChapter(kept);

		// 3. timing and fades
		List<Segment> segments = new ArrayList<>();
		int start = 0;
		for (int i = 0; i < kept.size(); i++) {
			Draft draft = kept.get(i);
			Draft previous = i > 0 ? kept.get(i - 1) : null;
			Draft next = i + 1 < kept.size() ? kept.get(i + 1) : null;
			boolean finale = draft.chapter >= 0 && draft.chapter == finaleChapter;
			int fadeIn = 0;
			int fadeOut = 0;
			switch (draft.item.kind()) {
				case INTRO -> fadeIn = frames(FILM_FADE_IN_SECONDS, fps);
				case CHAPTER -> {
					if (previous == null) {
						fadeIn = frames(FILM_FADE_IN_SECONDS, fps);
					} else if (isFootage(previous.item.kind())) {
						fadeIn = frames(finale ? FINALE_FADE_IN_SECONDS : CHAPTER_FADE_SECONDS, fps);
					}
				}
				case CLIP, TIMELAPSE -> {
					if (previous == null) {
						fadeIn = frames(FILM_FADE_IN_SECONDS, fps);
					}
					if (next == null) {
						fadeOut = frames(FILM_FADE_OUT_SECONDS, fps);
					} else if (next.item.kind() == Kind.CREDITS) {
						fadeOut = frames(LAST_CLIP_FADE_OUT_SECONDS, fps);
					} else if (next.item.kind() == Kind.CHAPTER) {
						boolean intoFinale = next.chapter == finaleChapter;
						fadeOut = frames(intoFinale ? FINALE_FADE_OUT_SECONDS : CHAPTER_FADE_SECONDS, fps);
					}
				}
				case CREDITS -> {
					fadeIn = frames(CREDITS_FADE_IN_SECONDS, fps);
					fadeOut = frames(FILM_FADE_OUT_SECONDS, fps);
				}
			}
			// fades never eat a whole short segment
			fadeIn = Math.min(fadeIn, draft.frames / 2);
			fadeOut = Math.min(fadeOut, draft.frames - fadeIn);
			int dissolve = 0;
			if (previous != null && dissolves(previous.item, draft.item)) {
				dissolve = Math.min(frames(DISSOLVE_SECONDS, fps), Math.min(previous.frames, draft.frames) / 3);
			}
			start -= dissolve;
			segments.add(new Segment(draft.item.kind(), draft.item, draft.number, draft.files, start, draft.frames,
				fadeIn, fadeOut, draft.item.kind() == Kind.CREDITS ? -1 : draft.chapter, finale, dissolve));
			start += draft.frames;
		}
		return new FilmTimeline(segments, fps);
	}

	/**
	 * Two clips of a chapter that show different moments (another time, another recording, another world) dissolve
	 * into each other; two shots of one moment (the same scene from another angle, or right after it) cut.
	 */
	static boolean dissolves(EditDecisionList.Item before, EditDecisionList.Item after) {
		if (before.kind() != Kind.CLIP || after.kind() != Kind.CLIP) {
			return false;
		}
		boolean sameRecording = before.replayId() != null && before.replayId().equals(after.replayId());
		boolean sameWorld = before.dimension() == null || before.dimension().equals(after.dimension());
		return !sameRecording || !sameWorld || after.startTick() - before.endTick() > CONTINUOUS_GAP_TICKS
			|| after.startTick() < before.startTick();
	}

	/** The chapter with the dragon's death, or -1. */
	private static int finaleChapter(List<Draft> drafts) {
		int finale = -1;
		for (Draft draft : drafts) {
			if (draft.item.kind() == Kind.CLIP && draft.item.eventType() == EventType.DRAGON_KILLED) {
				finale = draft.chapter;
			}
		}
		return finale;
	}

	/** A chapter is only worth its card with something filmed after it. */
	private static boolean isFootage(Kind kind) {
		return kind == Kind.CLIP || kind == Kind.TIMELAPSE;
	}

	private record Draft(EditDecisionList.Item item, int number, List<String> files, int frames, int chapter) {
	}

	private static int frames(double seconds, int fps) {
		return Math.max(1, (int) Math.round(seconds * fps));
	}

	public List<Segment> segments() {
		return segments;
	}

	public boolean isEmpty() {
		return segments.isEmpty();
	}

	public int fps() {
		return fps;
	}

	public int totalFrames() {
		return segments.isEmpty() ? 0 : segments.getLast().endFrame();
	}

	public double seconds() {
		return totalFrames() / (double) fps;
	}

	public List<Segment> clips() {
		return segments.stream().filter(s -> s.kind() == Kind.CLIP).toList();
	}

	/**
	 * Film frame at which a replay tick of a clip is seen, or -1 when the tick is outside the clip. Uses the planned
	 * timing (slow motion included), stretched to the clip's real length.
	 */
	public static int frameOf(Segment clip, int tick) {
		EditDecisionList.Item item = clip.item();
		if (clip.kind() != Kind.CLIP || tick < item.startTick() || tick >= item.endTick()) {
			return -1;
		}
		double planned = RenderPlan.filmSeconds(item.startTick(), item.endTick(), item.slowMotionFromTick());
		double offset = RenderPlan.filmSeconds(item.startTick(), tick, item.slowMotionFromTick());
		if (planned <= 0) {
			return clip.startFrame();
		}
		int frame = clip.startFrame() + (int) Math.round(offset / planned * clip.frames());
		return Math.min(frame, clip.endFrame() - 1);
	}

	/** The segment showing that film frame, or null past the end. */
	public Segment segmentAt(int frame) {
		for (Segment segment : segments) {
			if (frame < segment.endFrame()) {
				return frame >= segment.startFrame() ? segment : null;
			}
		}
		return null;
	}
}
