package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.CameraPathBuilder;
import com.survivalcinema.director.camera.ShotType;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.event.Details;
import com.survivalcinema.director.event.EventType;

import java.util.ArrayList;
import java.util.List;

/**
 * The cinematographer: picks how each scene is filmed. Each kind of moment has its favourite shots, a shot type is
 * never used twice in a row, long scenes are covered by two shots (not a flight or a ride), and big moments get slow
 * motion.
 */
public final class ShotPlanner {
	static final int SPLIT_ABOVE_TICKS = 20 * 12;
	static final double STANDING_SPEED = 1.0;
	static final double FAST_SPEED = 7.0;
	static final double RIDE_SHARE = 0.6;

	private ShotType lastType;
	private int nextSide = 1;

	public List<Shot> plan(Scene scene) {
		List<ShotType> options = new ArrayList<>(preferred(scene.primary().event().type()));
		String partner = partner(scene);
		if (partner == null) {
			options.remove(ShotType.TWO_SHOT);
		}
		double speed = averageSpeed(scene.session().trail(), scene.startTick(), scene.endTick());
		if (speed < STANDING_SPEED) {
			// Following someone who stands still from behind is boring: circle or watch them instead.
			options.removeIf(t -> t == ShotType.CHASE || t == ShotType.SIDE);
			if (options.isEmpty()) {
				options.add(ShotType.ORBIT);
			}
		} else if (speed > FAST_SPEED && !options.contains(ShotType.CHASE)) {
			options.addFirst(ShotType.CHASE);
		}

		List<Shot> shots = new ArrayList<>();
		int slowFrom = slowMotionFrom(scene);
		if (scene.primary().event().type() == EventType.ELYTRA_FLIGHT) {
			// a flight is one take from right behind the player, from the take-off to the landing: a side shot lost
			// them climbing on a rocket, and a cut in the middle of a flight breaks it
			lastType = ShotType.CHASE;
			shots.add(shot(scene, ShotType.CHASE, scene.startTick(), scene.endTick(), slowFrom, partner));
		} else if (DragonFinale.deathStart(scene) >= 0) {
			// the dragon's death: the blow, then the whole death animation in one shot, never cut in the middle
			for (int[] part : DragonFinale.parts(scene)) {
				shots.add(shot(scene, pick(options), part[0], part[1], slowFrom, partner));
			}
		} else if (scene.primary().event().type() == EventType.ARRIVAL || scene.durationTicks() > SPLIT_ABOVE_TICKS && isRide(scene)) {
			// a long ride is one take too: cut in the middle, the camera jumps across water that goes on moving; and so is
			// coming ashore, from the boat to the first steps on land
			ShotType type = lastType == ShotType.CHASE ? ShotType.SIDE : ShotType.CHASE;
			lastType = type;
			shots.add(shot(scene, type, scene.startTick(), scene.endTick(), slowFrom, partner));
		} else if (scene.durationTicks() > SPLIT_ABOVE_TICKS && !crossesPortal(scene)) {
			int middle = scene.startTick() + scene.durationTicks() / 2;
			// Cut where the main moment is not, so the climax plays in one continuous shot.
			int primary = scene.primary().tick();
			if (Math.abs(primary - middle) < 40) {
				middle = primary - 60 > scene.startTick() + 40 ? primary - 60 : primary + 60;
			}
			shots.add(shot(scene, pick(options), scene.startTick(), middle, slowFrom, partner));
			shots.add(shot(scene, pick(options), middle, scene.endTick(), slowFrom, partner));
		} else {
			shots.add(shot(scene, pick(options), scene.startTick(), scene.endTick(), slowFrom, partner));
		}
		return shots;
	}

	/** Riding a boat, a horse or a minecart most of the scene. */
	static boolean isRide(Scene scene) {
		Trail trail = scene.session().trail();
		if (trail == null || trail.isEmpty()) {
			return false;
		}
		int riding = 0;
		int samples = 0;
		for (int t = scene.startTick(); t <= scene.endTick(); t += 10) {
			samples++;
			if ((trail.sampleAt(t).flags() & TrailSample.FLAG_IN_VEHICLE) != 0) {
				riding++;
			}
		}
		return riding >= samples * RIDE_SHARE;
	}

	/** A trip through a portal: the camera cuts on the jump already, it needs no cut of its own. */
	static boolean crossesPortal(Scene scene) {
		Trail trail = scene.session().trail();
		return trail != null && trail.continuousRanges(scene.startTick(), scene.endTick(), 1).size() > 1;
	}

	/** The friend talking with us who was standing close enough to film together, if we know where they were. */
	static String partner(Scene scene) {
		if (scene.primary().event().type() != EventType.CONVERSATION) {
			return null;
		}
		for (String name : Details.getList(scene.primary().event().detail(), "near")) {
			Trail trail = scene.session().companions().get(name);
			if (trail != null && !trail.isEmpty() && trail.firstTick() <= scene.endTick() && trail.lastTick() >= scene.startTick()) {
				return name;
			}
		}
		return null;
	}

	private ShotType pick(List<ShotType> options) {
		for (ShotType type : options) {
			if (type != lastType) {
				lastType = type;
				return type;
			}
		}
		ShotType fallback = lastType == ShotType.ORBIT ? ShotType.STATIC_LOOKAT : ShotType.ORBIT;
		lastType = fallback;
		return fallback;
	}

	private Shot shot(Scene scene, ShotType type, int start, int end, int slowFrom, String partner) {
		CameraPathBuilder.Params d = CameraPathBuilder.Params.defaults(type);
		double distance = d.distance();
		double height = d.height();
		double sweep = d.sweep();
		switch (scene.primary().event().type()) {
			case BOSS_FIGHT_START, BOSS_DEFEATED, DRAGON_KILLED -> {
				distance *= 1.8;
				height += 2.5;
			}
			case KILL, KILL_STREAK, BIG_DAMAGE, NEAR_DEATH -> distance *= 0.8;
			case DEATH, TOTEM -> sweep = sweep == 0 ? 0 : 120;
			case NEW_BIOME, DIMENSION_CHANGE, NIGHT_FALL, BUILD_BURST -> {
				distance *= 1.4;
				height += 1.5;
			}
			// A quiet talk gets a slow, gentle camera: a short arc instead of a full circle.
			case CONVERSATION -> sweep = Math.min(sweep, 40);
			default -> {
			}
		}
		int side = nextSide;
		nextSide = -nextSide;
		if (type == ShotType.ORBIT) {
			sweep *= side;
		}
		// Slow motion runs from its start to the end of the scene, across a cut between two shots too.
		int slow = slowFrom >= 0 && slowFrom < end ? Math.max(start, slowFrom) : -1;
		return new Shot(type, start, end, new CameraPathBuilder.Params(distance, height, sweep, side, d.step()), slow,
			type == ShotType.TWO_SHOT ? partner : null);
	}

	static List<ShotType> preferred(EventType type) {
		return switch (type) {
			case DEATH, OTHER_DEATH, TOTEM -> List.of(ShotType.ORBIT, ShotType.STATIC_LOOKAT);
			case NEAR_DEATH, BIG_DAMAGE, KILL, KILL_STREAK, BIG_FALL -> List.of(ShotType.CHASE, ShotType.SIDE, ShotType.ORBIT);
			case BOSS_FIGHT_START -> List.of(ShotType.STATIC_LOOKAT, ShotType.PULLBACK, ShotType.ORBIT);
			case BOSS_DEFEATED, DRAGON_KILLED -> List.of(ShotType.ORBIT, ShotType.PULLBACK);
			case DIMENSION_CHANGE, NEW_BIOME -> List.of(ShotType.PULLBACK, ShotType.STATIC_LOOKAT);
			case ELYTRA_FLIGHT -> List.of(ShotType.CHASE);
			case ADVANCEMENT, OTHER_ADVANCEMENT, RARE_ITEM -> List.of(ShotType.ORBIT, ShotType.SIDE, ShotType.CHASE);
			case VOICE_PEAK, VOICE_CROWD -> List.of(ShotType.STATIC_LOOKAT, ShotType.ORBIT, ShotType.SIDE);
			case NIGHT_FALL, SLEEP, BUILD_BURST, NEW_DAY -> List.of(ShotType.STATIC_LOOKAT, ShotType.PULLBACK);
			case SESSION_START -> List.of(ShotType.PULLBACK, ShotType.ORBIT);
			case SCENERY -> List.of(ShotType.SIDE, ShotType.PULLBACK, ShotType.CHASE);
			case ARRIVAL -> List.of(ShotType.CHASE, ShotType.SIDE);
			// the first tree: around the player at work
			case FIRST_STEPS -> List.of(ShotType.ORBIT, ShotType.STATIC_LOOKAT);
			// a place found: pulling back shows it; its chest: around the player opening it
			case STRUCTURE -> List.of(ShotType.PULLBACK, ShotType.STATIC_LOOKAT, ShotType.ORBIT);
			case LOOT -> List.of(ShotType.ORBIT, ShotType.SIDE, ShotType.STATIC_LOOKAT);
			// together: the dialogue two-shot; apart (talking over voice chat): their voices over gentle footage of us
			case CONVERSATION -> List.of(ShotType.TWO_SHOT, ShotType.ORBIT, ShotType.STATIC_LOOKAT, ShotType.SIDE);
			default -> List.of(ShotType.CHASE, ShotType.ORBIT);
		};
	}

	/**
	 * Big moments slow down one second before they happen; the dragon slows down for its death animation only (the
	 * blow and its flight back to the portal play at normal speed).
	 */
	static int slowMotionFrom(Scene scene) {
		int dragonDying = DragonFinale.deathStart(scene);
		if (dragonDying >= 0) {
			return dragonDying;
		}
		EventType type = scene.primary().event().type();
		boolean big = type == EventType.DEATH || type == EventType.TOTEM || type == EventType.DRAGON_KILLED || type == EventType.BOSS_DEFEATED;
		return big ? Math.max(scene.startTick(), scene.primary().tick() - 20) : -1;
	}

	static double averageSpeed(Trail trail, int from, int to) {
		if (trail == null || trail.isEmpty() || to <= from) {
			return 0;
		}
		double distance = 0;
		for (int[] range : trail.continuousRanges(from, to, 1)) {
			for (int t = range[0]; t + 10 <= range[1]; t += 10) {
				distance += trail.positionAt(t + 10).sub(trail.positionAt(t)).horizontalLength();
			}
		}
		return distance / ((to - from) / 20.0);
	}
}
