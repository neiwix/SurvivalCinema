package com.survivalcinema.director.story;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.survivalcinema.director.camera.ShotType;
import com.survivalcinema.director.event.Details;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.timelapse.TimelapsePlanner;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The finished screenplay of the film: an ordered list of title cards and camera shots. Rendering and editing only
 * follow this list, so a film can be re-rendered (other quality, other music) without planning it again.
 * Text that needs translating is stored as data (day numbers, dimension ids); labels are already translated.
 *
 * @param stats     what the credits tell about the world (null in screenplays planned before the credits existed)
 * @param timelapse where the base is and from which side to film it, when the film has a base timelapse
 */
public record EditDecisionList(int version, String worldName, String playerName, int targetSeconds, long createdAt,
							   List<Item> items, @Nullable FilmStats stats, @Nullable TimelapsePlanner.View timelapse) {
	public static final int VERSION = 1;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public EditDecisionList(int version, String worldName, String playerName, int targetSeconds, long createdAt, List<Item> items) {
		this(version, worldName, playerName, targetSeconds, createdAt, items, null);
	}

	public EditDecisionList(int version, String worldName, String playerName, int targetSeconds, long createdAt, List<Item> items,
							@Nullable FilmStats stats) {
		this(version, worldName, playerName, targetSeconds, createdAt, items, stats, null);
	}

	/** {@code TIMELAPSE}: one picture of the base timelapse (a short clip shot from {@link #timelapse()}'s camera). */
	public enum Kind {INTRO, CHAPTER, CLIP, TIMELAPSE, CREDITS}

	/** The dimension id of the chapter card that opens the base timelapse ("Our base"). */
	public static final String BASE_CHAPTER = "survivalcinema:base";

	/**
	 * Flat on purpose so it reads well as JSON. Card fields are null for clips and the other way round, except
	 * {@code dimension}: where a clip happens, or which new dimension a chapter opens with.
	 *
	 * @param eventTick replay tick of the clip's main moment (for sound effects), or -1
	 * @param voice     somebody talks or shouts during the clip, so the film plays no music over it
	 */
	public record Item(Kind kind, double cardSeconds,
					   int dayFrom, int dayTo, @Nullable String dimension, @Nullable String subtitle,
					   @Nullable String replayId, int sessionIndex, int startTick, int endTick,
					   @Nullable ShotType shot, double distance, double height, double sweep, int side,
					   int slowMotionFromTick, @Nullable EventType eventType, @Nullable String label, double score,
					   @Nullable String partner, int eventTick, boolean voice) {

		public static Item card(Kind kind, double seconds, int dayFrom, int dayTo, @Nullable String dimension, @Nullable String subtitle) {
			return new Item(kind, seconds, dayFrom, dayTo, dimension, subtitle, null, -1, 0, 0, null, 0, 0, 0, 0, -1, null, null, 0,
				null, -1, false);
		}

		public static Item clip(Scene scene, Shot shot) {
			return new Item(Kind.CLIP, 0, scene.day(), scene.day(), scene.session().dimensionOf(scene.primary().event()), null,
				scene.session().replayId(), scene.session().index(), shot.startTick(), shot.endTick(),
				shot.type(), shot.params().distance(), shot.params().height(), shot.params().sweep(), shot.params().side(),
				shot.slowMotionFromTick(), scene.primary().event().type(), scene.primary().event().label(), scene.score(),
				shot.partner(), scene.primary().tick(), hasVoice(scene, shot));
		}

		/** One shot of the base timelapse: its ticks shown in its seconds (sped up), or a still held. */
		public static Item timelapse(TimelapsePlanner.Snapshot snapshot, String dimension) {
			return timelapse(snapshot, dimension, snapshot.seconds());
		}

		/** One shot of the base timelapse, shown for {@code seconds}. */
		public static Item timelapse(TimelapsePlanner.Snapshot snapshot, String dimension, double seconds) {
			return new Item(Kind.TIMELAPSE, seconds, snapshot.day(), snapshot.day(), dimension, null, snapshot.replayId(),
				snapshot.sessionIndex(), snapshot.tick(), snapshot.endTick(), null, 0, 0, 0, 0, -1,
				null, null, snapshot.blocksSoFar(), null, -1, false);
		}

		/**
		 * How many times faster than it happened a shot of the base timelapse plays: its ticks in its seconds (1 for a
		 * still, which is held).
		 */
		public double timelapseSpeed() {
			int ticks = endTick - startTick;
			return kind != Kind.TIMELAPSE || ticks <= TimelapsePlanner.SNAPSHOT_TICKS || cardSeconds <= 0 ? 1
				: Math.max(1, ticks / (cardSeconds * 20));
		}

		/** A voice reaction or a conversation overlaps the shot. */
		private static boolean hasVoice(Scene scene, Shot shot) {
			for (ScoredEvent scored : scene.events()) {
				EventType type = scored.event().type();
				if (!type.isVoice() && type != EventType.CONVERSATION) {
					continue;
				}
				int length = type == EventType.CONVERSATION ? Details.getInt(scored.event().detail(), "ticks", 300) : 40;
				if (scored.tick() < shot.endTick() && scored.tick() + length > shot.startTick()) {
					return true;
				}
			}
			return false;
		}

		/** Length in the finished film; slow motion stretches a clip. */
		public double filmSeconds() {
			if (kind != Kind.CLIP) {
				return cardSeconds;
			}
			int normal = endTick - startTick;
			int slow = 0;
			if (slowMotionFromTick >= startTick && slowMotionFromTick < endTick) {
				slow = endTick - slowMotionFromTick;
				normal -= slow;
			}
			return (normal + slow / Shot.SLOW_MOTION_SPEED) / 20.0;
		}
	}

	public double totalSeconds() {
		return items.stream().mapToDouble(Item::filmSeconds).sum();
	}

	public List<Item> clips() {
		return items.stream().filter(i -> i.kind() == Kind.CLIP).toList();
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	public static EditDecisionList fromJson(String json) {
		return GSON.fromJson(json, EditDecisionList.class);
	}
}
