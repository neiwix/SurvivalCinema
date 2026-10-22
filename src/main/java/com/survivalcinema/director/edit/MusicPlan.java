package com.survivalcinema.director.edit;

import com.survivalcinema.director.story.EditDecisionList.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Where the film has music: nearly everywhere, but never over voices.
 * <p>
 * Music opens the film and carries on through the intro, the first chapter card and into the first clips without a
 * break. It plays under quiet footage and under ordinary play too (the {@link Ducker} turns it down while the game is
 * loud), through the Nether and the End, during the dragon finale and under the credits. Only a clip where friends
 * talk, shout or have a conversation stops it: the music fades out before it and, after it, the same track picks up
 * again where it stopped, so the film has a few long pieces of music instead of a new one every few seconds. Where the
 * place changes (into the Nether, the base timelapse, the credits) one track crosses over into the next.
 */
public final class MusicPlan {
	public enum Mood {
		CALM, NETHER, END, TIMELAPSE, FLIGHT, BOSS, CREDITS
	}

	/**
	 * One stretch of music.
	 *
	 * @param thread cues with the same thread (0 or more) play one track, each going on where the one before stopped;
	 *               -1 for a cue of its own
	 */
	public record Cue(int startFrame, int endFrame, Mood mood, int fadeInFrames, int fadeOutFrames, int thread) {
		public Cue(int startFrame, int endFrame, Mood mood, int fadeInFrames, int fadeOutFrames) {
			this(startFrame, endFrame, mood, fadeInFrames, fadeOutFrames, -1);
		}

		public int frames() {
			return endFrame - startFrame;
		}

		/** Volume of the cue at a film frame from its fades alone, 0..1 (0 outside the cue). */
		public double envelope(int frame) {
			if (frame < startFrame || frame >= endFrame) {
				return 0;
			}
			double value = 1;
			int in = frame - startFrame;
			int out = endFrame - frame;
			if (fadeInFrames > 0 && in < fadeInFrames) {
				value = Math.min(value, (in + 0.5) / fadeInFrames);
			}
			if (fadeOutFrames > 0 && out <= fadeOutFrames) {
				value = Math.min(value, (out - 0.5) / fadeOutFrames);
			}
			// equal-power curve: fades sound even, not like a sudden drop at the end
			return Math.sin(Math.max(0, Math.min(1, value)) * Math.PI / 2);
		}
	}

	/** A stretch of music shorter than this between two voices would only be a snatch of a tune: none there. */
	static final double MIN_CUE_SECONDS = 10;
	/** ...unless the footage is silent: then even a short piece is better than a mute film. */
	static final double MIN_QUIET_CUE_SECONDS = 5;
	static final double MIN_PLACE_CUE_SECONDS = 8;
	static final double MIN_BOSS_CUE_SECONDS = 6;
	static final double FADE_IN_SECONDS = 2;
	static final double FADE_OUT_SECONDS = 2.5;
	/** Where one track gives way to the next, the old one plays on this long under the new one. */
	static final double CROSSFADE_SECONDS = 1.5;
	/** After a voice, the same track goes on where it stopped when the pause was no longer than this. */
	static final double RESUME_WITHIN_SECONDS = 60;
	/** A stretch silent for this share of its time counts as quiet. */
	private static final double QUIET_SHARE = 0.6;

	private MusicPlan() {
	}

	public static List<Cue> plan(FilmTimeline timeline) {
		return plan(timeline, Set.of());
	}

	/**
	 * @param quietClips numbers of clips whose own sound is (nearly) silent: calm travel, flying, nothing happening
	 *                   loud enough to hear; even a short pause between voices gets music there
	 */
	public static List<Cue> plan(FilmTimeline timeline, Set<Integer> quietClips) {
		return plan(timeline, quietClips, Set.of());
	}

	/** @param flights numbers of the clips that are mostly an elytra flight */
	public static List<Cue> plan(FilmTimeline timeline, Set<Integer> quietClips, Set<Integer> flights) {
		List<FilmTimeline.Segment> segments = timeline.segments();
		int fps = timeline.fps();
		int total = timeline.totalFrames();

		// 1. stretches of one kind of music; a voice ends the run of music, a new place starts a new stretch in it
		List<Stretch> stretches = new ArrayList<>();
		Stretch current = null;
		int run = 0;
		int silentFrom = 0;
		for (int i = 0; i < segments.size(); i++) {
			FilmTimeline.Segment segment = segments.get(i);
			Mood mood = mood(segments, i, quietClips, flights);
			if (mood == null) {
				if (current != null) {
					current.end = Math.min(current.end, segment.startFrame()); // gone before the voice starts
					current = null;
					run++;
				}
				silentFrom = segment.endFrame();
				continue;
			}
			int start = Math.max(segment.startFrame(), silentFrom);
			if (current == null || group(current.first) != group(mood)) {
				current = new Stretch(mood, start, run);
				stretches.add(current);
			}
			current.add(segment, mood, start, quietClips);
		}

		// 2. too short to be worth a tune: joins the stretch before it (or after it) in the same run, or goes
		List<Stretch> kept = new ArrayList<>();
		for (int r = 0; r <= run; r++) {
			List<Stretch> inRun = new ArrayList<>();
			for (Stretch stretch : stretches) {
				if (stretch.run == r && stretch.end > stretch.start) {
					inRun.add(stretch);
				}
			}
			boolean merged = true;
			while (merged && inRun.size() > 1) {
				merged = false;
				for (int i = 0; i < inRun.size(); i++) {
					Stretch stretch = inRun.get(i);
					if (stretch.seconds(fps) < stretch.minSeconds()) {
						Stretch into = i > 0 ? inRun.get(i - 1) : inRun.get(i + 1);
						into.absorb(stretch);
						inRun.remove(i);
						merged = true;
						break;
					}
				}
			}
			for (Stretch stretch : inRun) {
				if (stretch.seconds(fps) >= stretch.minSeconds()) {
					kept.add(stretch);
				}
			}
		}

		// 3. cues: fades, a crossover where a stretch hands over to the next in the same run
		List<Cue> cues = new ArrayList<>();
		for (int i = 0; i < kept.size(); i++) {
			Stretch stretch = kept.get(i);
			Stretch next = i + 1 < kept.size() ? kept.get(i + 1) : null;
			int end = stretch.end;
			if (next != null && next.run == stretch.run && next.start <= end) {
				end = Math.min(next.start + frames(CROSSFADE_SECONDS, fps), next.end);
			}
			Mood mood = stretch.mood();
			int frames = end - stretch.start;
			int fadeIn = stretch.start == 0 ? frames(FilmTimeline.FILM_FADE_IN_SECONDS, fps)
				: mood == Mood.CREDITS ? frames(FilmTimeline.CREDITS_FADE_IN_SECONDS, fps)
				: frames(FADE_IN_SECONDS, fps);
			int fadeOut = end >= total ? frames(FilmTimeline.FILM_FADE_OUT_SECONDS, fps) : frames(FADE_OUT_SECONDS, fps);
			fadeIn = Math.min(fadeIn, frames / 2);
			fadeOut = Math.min(fadeOut, frames - fadeIn);
			// the same kind of music again after a pause for voices: the track goes on
			int thread = cues.size();
			if (!cues.isEmpty()) {
				Cue before = cues.getLast();
				Stretch previous = kept.get(i - 1);
				if (previous.run != stretch.run && group(before.mood()) == group(mood)
					&& stretch.start - before.endFrame() <= RESUME_WITHIN_SECONDS * fps) {
					thread = before.thread();
				}
			}
			cues.add(new Cue(stretch.start, end, mood, fadeIn, fadeOut, thread));
		}
		return cues;
	}

	/** The music a segment wants, or null for none. */
	static Mood mood(List<FilmTimeline.Segment> segments, int index, Set<Integer> quietClips) {
		return mood(segments, index, quietClips, Set.of());
	}

	static Mood mood(List<FilmTimeline.Segment> segments, int index, Set<Integer> quietClips, Set<Integer> flights) {
		FilmTimeline.Segment segment = segments.get(index);
		return switch (segment.kind()) {
			case INTRO -> Mood.CALM;
			case CREDITS -> Mood.CREDITS;
			case TIMELAPSE -> Mood.TIMELAPSE; // the base growing: a montage made for music
			case CHAPTER -> {
				// a card sounds like the chapter it opens
				if (index + 1 < segments.size() && segments.get(index + 1).kind() == Kind.TIMELAPSE) {
					yield Mood.TIMELAPSE;
				}
				for (int i = index + 1; i < segments.size() && segments.get(i).kind() == Kind.CLIP; i++) {
					Mood clip = placeMood(segments.get(i));
					if (clip != Mood.CALM) {
						yield clip;
					}
				}
				yield Mood.CALM;
			}
			case CLIP -> {
				if (segment.item().voice()) {
					yield null; // friends talking: theirs is the only sound worth hearing
				}
				Mood place = placeMood(segment);
				if (place != Mood.CALM) {
					yield place;
				}
				yield flights.contains(segment.number()) ? Mood.FLIGHT : Mood.CALM;
			}
		};
	}

	/** Music of where a clip happens: the dragon fight, the Nether, the End, or anything else. */
	private static Mood placeMood(FilmTimeline.Segment clip) {
		String dimension = clip.item().dimension();
		if (clip.finale() && "minecraft:the_end".equals(dimension)) {
			return Mood.BOSS;
		}
		if ("minecraft:the_nether".equals(dimension)) {
			return Mood.NETHER;
		}
		if ("minecraft:the_end".equals(dimension)) {
			return Mood.END;
		}
		return Mood.CALM;
	}

	/** Moods that share one piece of music: a flight in the Overworld does not need a track of its own. */
	private static Mood group(Mood mood) {
		return mood == Mood.FLIGHT ? Mood.CALM : mood;
	}

	private static int frames(double seconds, int fps) {
		return (int) Math.round(seconds * fps);
	}

	/** Consecutive segments that want the same kind of music. */
	private static final class Stretch {
		final Mood first;
		final int run;
		int start;
		int end;
		int frames;
		int flightFrames;
		int quietFrames;
		boolean intro;

		Stretch(Mood first, int start, int run) {
			this.first = first;
			this.start = start;
			this.end = start;
			this.run = run;
		}

		void add(FilmTimeline.Segment segment, Mood mood, int start, Set<Integer> quietClips) {
			int length = segment.endFrame() - Math.max(start, this.end);
			end = segment.endFrame();
			frames += Math.max(0, length);
			if (mood == Mood.FLIGHT) {
				flightFrames += Math.max(0, length);
			}
			if (segment.kind() != Kind.CLIP || quietClips.contains(segment.number())) {
				quietFrames += Math.max(0, length); // cards and silent footage
			}
			intro |= segment.kind() == Kind.INTRO;
		}

		void absorb(Stretch other) {
			start = Math.min(start, other.start);
			end = Math.max(end, other.end);
			frames += other.frames;
			flightFrames += other.flightFrames;
			quietFrames += other.quietFrames;
			intro |= other.intro;
		}

		/** A flight alone has a soaring tune; anything else in the Overworld the calm music. */
		Mood mood() {
			if (group(first) == Mood.CALM) {
				return flightFrames >= frames && frames > 0 ? Mood.FLIGHT : Mood.CALM;
			}
			return first;
		}

		double seconds(int fps) {
			return (end - start) / (double) fps;
		}

		double minSeconds() {
			if (intro) {
				return 0; // the film never opens in silence
			}
			return switch (mood()) {
				case CREDITS, TIMELAPSE, FLIGHT -> 0;
				case BOSS -> MIN_BOSS_CUE_SECONDS;
				case NETHER, END -> MIN_PLACE_CUE_SECONDS;
				case CALM -> quietFrames >= QUIET_SHARE * frames ? MIN_QUIET_CUE_SECONDS : MIN_CUE_SECONDS;
			};
		}
	}
}
