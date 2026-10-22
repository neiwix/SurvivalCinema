package com.survivalcinema.director.edit;

import com.survivalcinema.director.story.EditDecisionList.Kind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Game sounds the editor adds on top of the clips, like a film's sound design: the level-up chime on an
 * advancement, "challenge complete" when a boss falls, the portal's hum on the card of the first trip to the Nether,
 * the End portal opening before the first visit to the End. Only sounds the replay itself does not play.
 */
public final class SoundCues {
	public enum Effect {
		LEVEL_UP("entity.player.levelup"),
		CHALLENGE("ui.toast.challenge_complete"),
		PORTAL("block.portal.trigger"),
		END_PORTAL("block.end_portal.spawn"),
		/** Not a cue: the wind of an elytra flight, looped under flights ({@link FlightSound}). */
		ELYTRA_WIND("item.elytra.flying");

		/** The game's sound event. */
		public final String sound;

		Effect(String sound) {
			this.sound = sound;
		}
	}

	/** @param volume 0..1 */
	public record Cue(int frame, Effect effect, double volume) {
	}

	private SoundCues() {
	}

	public static List<Cue> plan(FilmTimeline timeline) {
		List<Cue> cues = new ArrayList<>();
		for (FilmTimeline.Segment segment : timeline.segments()) {
			if (segment.kind() == Kind.CHAPTER && segment.item().dimension() != null) {
				switch (segment.item().dimension()) {
					case "minecraft:the_nether" -> cues.add(new Cue(segment.startFrame(), Effect.PORTAL, 0.35));
					case "minecraft:the_end" -> cues.add(new Cue(segment.startFrame(), Effect.END_PORTAL, 0.5));
					default -> {
					}
				}
			}
			if (segment.kind() != Kind.CLIP || segment.item().eventType() == null) {
				continue;
			}
			int frame = FilmTimeline.frameOf(segment, segment.item().eventTick());
			if (frame < 0) {
				continue;
			}
			switch (segment.item().eventType()) {
				case ADVANCEMENT -> cues.add(new Cue(frame, Effect.LEVEL_UP, 0.45));
				case DRAGON_KILLED, BOSS_DEFEATED -> cues.add(new Cue(frame, Effect.CHALLENGE, 0.7));
				default -> {
				}
			}
		}
		cues.sort(Comparator.comparingInt(Cue::frame));
		return cues;
	}
}
