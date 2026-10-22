package com.survivalcinema.director.story;

import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The timing of the dragon's death, the film's climax. The killing blow is not the death animation: a dragon hit in
 * flight first flies back to the exit portal (seconds, sometimes ten and more), and only there rises for ten seconds,
 * bursting with beams of light, until it explodes and its boss bar disappears ({@code BOSS_DEFEATED}). A dragon hit
 * while sitting on the portal starts dying at once.
 * <p>
 * So the finale shows the last hits, the blow and the flight back to the portal at normal speed, then cuts to the whole
 * death animation in one shot, in slow motion; slow motion only there. Without the bar's disappearance (an old
 * recording) the animation is taken to start at the blow, as before.
 */
public final class DragonFinale {
	/** The dragon's death animation, from the first beam of light to the explosion (vanilla: 200 ticks). */
	static final int DEATH_ANIMATION_TICKS = 200;
	/** The longest flight back to the portal looked for after the blow. */
	static final int MAX_FLIGHT_TICKS = 20 * 60;
	/** The last hits before the blow. */
	static final int KILL_LEAD_TICKS = 200;
	/** The death shot starts a little before the animation (the dragon arriving over the portal)... */
	static final int DEATH_LEAD_TICKS = 30;
	/** ...and ends a moment after the explosion (the egg, the portal lighting up). */
	static final int DEATH_FOLLOW_TICKS = 25;
	/** Shorter parts are not worth a shot of their own. */
	static final int MIN_PART_TICKS = 40;
	/** Before the bar's disappearance was known, the scene was the blow plus this much (the animation from the blow). */
	private static final int[] OLD_WINDOW = {200, 210};

	private DragonFinale() {
	}

	/** The dragon's boss bar disappearing: the end of its death animation. */
	static boolean isDragonDeath(GameEvent event) {
		return event.type() == EventType.BOSS_DEFEATED && event.subject() != null && event.subject().contains("ender_dragon");
	}

	/**
	 * When the death animation ended (the dragon's boss bar disappeared) for a blow at {@code killTick}, or -1. The kill
	 * can also be noticed only at the bar's disappearance (the advancement was already had): then they are the same tick.
	 */
	static int deathEnd(List<GameEvent> events, int killTick) {
		int end = -1;
		for (GameEvent e : events) {
			if (isDragonDeath(e) && e.tick() >= killTick - 5 && e.tick() <= killTick + MAX_FLIGHT_TICKS && (end < 0 || e.tick() < end)) {
				end = e.tick();
			}
		}
		return end;
	}

	/** {lead-in, follow-up} around the blow: the last hits through to the explosion. */
	static int[] window(SessionData session, GameEvent kill) {
		int end = deathEnd(session.events(), kill.tick());
		if (end < 0) {
			return OLD_WINDOW;
		}
		int start = Math.min(kill.tick() - KILL_LEAD_TICKS, end - DEATH_ANIMATION_TICKS - DEATH_LEAD_TICKS);
		return new int[]{kill.tick() - start, end + DEATH_FOLLOW_TICKS - kill.tick()};
	}

	/** The dragon's bar disappearing is the end of the animation: the whole animation before it, a moment after. */
	static int[] deathWindow() {
		return new int[]{DEATH_ANIMATION_TICKS + DEATH_LEAD_TICKS, DEATH_FOLLOW_TICKS};
	}

	/** The scene of the dragon's death: its main moment is the blow. */
	static boolean isFinale(Scene scene) {
		return scene.primary().event().type() == EventType.DRAGON_KILLED;
	}

	/** Where the death animation ends in the finale, or -1 when it is not known (or not in the scene). */
	private static int deathEnd(Scene scene) {
		if (!isFinale(scene)) {
			return -1;
		}
		int end = -1;
		for (ScoredEvent e : scene.events()) {
			if (isDragonDeath(e.event()) && e.tick() >= scene.primary().tick() - 5 && (end < 0 || e.tick() < end)) {
				end = e.tick();
			}
		}
		return end;
	}

	/**
	 * Where the death animation starts, which is where slow motion starts; -1 when it is not known. Then slow motion
	 * starts a second before the blow, like for any big moment.
	 */
	static int deathStart(Scene scene) {
		int end = deathEnd(scene);
		if (end < 0) {
			return -1;
		}
		int start = end - DEATH_ANIMATION_TICKS;
		return start + MIN_PART_TICKS / 2 < scene.endTick() ? Math.max(scene.startTick(), start) : -1;
	}

	/**
	 * The shots of the finale, one after the other with nothing left out: the fight, the blow and the flight back to the
	 * portal, then a cut to the death animation in a shot of its own. Any other scene is one part.
	 */
	static List<int[]> parts(Scene scene) {
		int deathStart = deathStart(scene);
		List<int[]> parts = new ArrayList<>();
		if (deathStart < 0) {
			parts.add(new int[]{scene.startTick(), scene.endTick()});
			return parts;
		}
		int deathFrom = Math.max(scene.startTick(), deathStart - DEATH_LEAD_TICKS);
		if (deathFrom - scene.startTick() >= MIN_PART_TICKS) {
			parts.add(new int[]{scene.startTick(), deathFrom}); // a cut just before the slow motion begins
		} else {
			deathFrom = scene.startTick();
		}
		parts.add(new int[]{deathFrom, scene.endTick()});
		return parts;
	}
}
