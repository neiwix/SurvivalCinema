package com.survivalcinema.director.story;

import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.survivalcinema.director.story.Worlds.ev;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dragon's death in the film: the blow, then the whole death animation in slow motion. */
class DragonFinaleTest {
	private static GameEvent kill(int tick) {
		return ev(tick, EventType.DRAGON_KILLED, "minecraft:ender_dragon", 3, 1.0, true);
	}

	/** The boss bar disappearing: the explosion at the end of the death animation. */
	private static GameEvent explosion(int tick) {
		return ev(tick, EventType.BOSS_DEFEATED, "entity.minecraft.ender_dragon", 3, 1.0, true);
	}

	private static Scene finale(GameEvent... events) {
		SessionData session = Worlds.session(0, 20_000, List.of(events));
		List<Scene> scenes = new SceneBuilder().build(new HighlightScorer().score(List.of(session)));
		return scenes.stream().filter(s -> s.events().stream().anyMatch(e -> e.event().type() == EventType.DRAGON_KILLED))
			.findFirst().orElseThrow();
	}

	@Test
	void aDragonHitInFlightIsFilmedThroughItsFlightToTheDeath() {
		// hit at 1000, flew back to the portal for 12 s, died there from 1250 to 1450
		Scene scene = finale(kill(1000), explosion(1450));
		assertEquals(EventType.DRAGON_KILLED, scene.primary().event().type(), "the blow is the scene's moment");
		assertTrue(scene.events().stream().anyMatch(e -> e.event().type() == EventType.BOSS_DEFEATED), "one scene with the explosion");
		assertEquals(800, scene.startTick());
		assertEquals(1475, scene.endTick(), "until a moment after the explosion");

		List<Shot> shots = new ShotPlanner().plan(scene);
		assertEquals(2, shots.size());
		Shot blow = shots.get(0);
		Shot dying = shots.get(1);
		assertEquals(800, blow.startTick());
		assertEquals(1220, blow.endTick(), "the blow and the whole flight back to the portal");
		assertEquals(-1, blow.slowMotionFromTick(), "the blow and the flight at normal speed");
		assertEquals(1220, dying.startTick(), "nothing left out");
		assertEquals(1475, dying.endTick());
		assertEquals(1250, dying.slowMotionFromTick(), "slow motion for the death animation only");

		// in the film: 420 ticks of the fight and the flight, 30 before the animation, 225 slowed down to half speed
		assertEquals(420 + 30 + 2 * 225, StoryPlanner.filmTicks(scene));
	}

	@Test
	void aDragonHitOnThePortalDiesAtOnce() {
		Scene scene = finale(kill(1000), explosion(1200));
		assertEquals(800, scene.startTick());
		assertEquals(1225, scene.endTick());
		List<Shot> shots = new ShotPlanner().plan(scene);
		assertEquals(2, shots.size());
		assertEquals(970, shots.get(0).endTick(), "the fight, cut just before the blow");
		assertEquals(-1, shots.get(0).slowMotionFromTick());
		assertEquals(970, shots.get(1).startTick());
		assertEquals(1000, shots.get(1).slowMotionFromTick(), "the blow and the whole death in slow motion");
		assertEquals(1225, shots.get(1).endTick());
	}

	@Test
	void theKillNoticedOnlyAtTheExplosionStillShowsTheWholeDeath() {
		// the advancement was had before: the kill is noticed when the bar disappears, at the end of the animation
		Scene scene = finale(kill(1450), explosion(1450));
		assertTrue(scene.startTick() <= 1220, "the whole animation is in: " + scene.startTick());
		List<Shot> shots = new ShotPlanner().plan(scene);
		Shot last = shots.getLast();
		assertEquals(1250, last.slowMotionFromTick());
		assertTrue(last.startTick() <= 1220 && last.endTick() == 1475, "one shot of the whole death: " + last);
	}

	@Test
	void anOldRecordingWithoutTheExplosionKeepsTheOldTiming() {
		Scene scene = finale(kill(1000));
		assertEquals(800, scene.startTick());
		assertEquals(1210, scene.endTick());
		for (Shot shot : new ShotPlanner().plan(scene)) {
			if (shot.endTick() > 980) {
				assertEquals(Math.max(shot.startTick(), 980), shot.slowMotionFromTick(), "a second before the blow, as before");
			}
		}
	}
}
