package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A simulated survival playthrough with friends on voice chat, with event rates taken from how people really play:
 * ~30 hours to the dragon over 25 sessions, constant small fights, a mob farm, a handful of deaths, ~60 advancements
 * (only a few of them story ones), dozens of new biomes, regular Nether trips, elytra commuting at the end, and voice
 * reactions mostly around the dangerous moments.
 */
final class RealisticWorld {
	static final int TICKS_PER_HOUR = 20 * 60 * 60;
	static final int TICKS_PER_DAY = 20 * 60 * 20;

	final List<SessionData> sessions = new ArrayList<>();
	private final Random random;
	private long playedTicks;

	private RealisticWorld(long seed) {
		this.random = new Random(seed);
	}

	static RealisticWorld playthrough(long seed) {
		RealisticWorld world = new RealisticWorld(seed);
		int sessions = 25;
		for (int s = 0; s < sessions; s++) {
			int minutes = 40 + world.random.nextInt(60);
			world.session(s, sessions, minutes * 60 * 20);
		}
		return world;
	}

	/** One evening on a fresh world: 25 minutes, very little happening. */
	static RealisticWorld quietEvening(long seed) {
		RealisticWorld world = new RealisticWorld(seed);
		List<GameEvent> events = new ArrayList<>();
		int length = 25 * 60 * 20;
		events.add(world.ev(0, EventType.SESSION_START, "session", 0.1, true, null));
		events.add(world.ev(2_000, EventType.NEW_BIOME, "minecraft:plains", 0.4, true, null));
		events.add(world.ev(9_000, EventType.ADVANCEMENT, "minecraft:story/mine_stone", 0.4, true, null));
		events.add(world.ev(14_000, EventType.KILL, "minecraft:zombie", 0.2, true, null));
		events.add(world.ev(20_000, EventType.BIG_DAMAGE, "minecraft:fall", 0.4, false, null));
		events.add(world.ev(24_100, EventType.NIGHT_FALL, "night", 0.8, true, null));
		world.sessions.add(new SessionData("quiet", 0, length, events, world.trail(length, List.of())));
		return world;
	}

	private void session(int index, int count, int length) {
		double progress = index / (double) (count - 1); // 0 = start of the world, 1 = the dragon
		boolean farmSession = index == 9 || index == 14 || index == 18;
		boolean buildSession = index % 4 == 2;
		boolean hasElytra = progress > 0.85;
		List<GameEvent> events = new ArrayList<>();
		List<Integer> respawns = new ArrayList<>();
		events.add(ev(0, EventType.SESSION_START, "session", 0.1, index == 0, null));

		double hours = length / (double) TICKS_PER_HOUR;
		scatter(events, length, (int) (30 * hours), t -> ev(t, EventType.BIG_DAMAGE, damageType(), 0.3 + random.nextDouble() * 0.4, false, null));
		scatter(events, length, (int) (40 * hours), t -> ev(t, EventType.KILL, hostile(), random.nextInt(10) == 0 ? 0.7 : 0.2, false, null));
		scatter(events, length, (int) (3 * hours), t -> ev(t, EventType.NEAR_DEATH, "near_death", 0.8, false, null));
		scatter(events, length, (int) (1 * hours), t -> ev(t, EventType.BIG_FALL, "fall", 0.5, false, null));
		scatter(events, length, (int) (2 * hours), t -> ev(t, EventType.OTHER_ADVANCEMENT, "chat.type.advancement.task", 0.4, false, null));
		scatter(events, length, (int) (4 * hours), t -> ev(t, EventType.VOICE_PEAK, "voice", 0.5 + random.nextDouble() * 0.5, false, null));
		scatter(events, length, (int) (1 * hours), t -> ev(t, EventType.VOICE_CROWD, "voice", 0.8, false, null));
		scatter(events, length, (int) Math.round(2 * hours), t -> conversation(t, 400 + random.nextInt(700), random.nextInt(10) < 6));
		if (index == 0) {
			events.add(conversation(1_500, 600, true));
		}
		if (buildSession) {
			scatter(events, length, (int) (10 * hours), t -> ev(t, EventType.BUILD_BURST, "build", 0.4, false, null));
		}
		if (farmSession) {
			// An hour at the mob farm: hundreds of kills in one spot.
			int start = length / 4;
			for (int t = start; t < start + TICKS_PER_HOUR && t < length; t += 120) {
				events.add(at(ev(t, EventType.KILL, "minecraft:zombie", 0.2, false, null), 500, 64, 500));
				if (t % 600 == 0) {
					events.add(at(ev(t + 10, EventType.KILL_STREAK, "kill_streak", 0.6, false, null), 500, 64, 500));
				}
			}
		}
		if (hasElytra) {
			scatter(events, length, (int) (15 * hours), t -> ev(t, EventType.ELYTRA_FLIGHT, "minecraft:elytra", 0.6, false, "240"));
		}
		// About one death every 3-4 sessions; friends scream at half of them.
		if (random.nextInt(10) < 3) {
			int t = 2_000 + random.nextInt(length - 4_000);
			events.add(ev(t, EventType.DEATH, deathCause(), 1.0, random.nextBoolean(), null));
			respawns.add(t + 40);
			if (random.nextBoolean()) {
				events.add(ev(t - 30, EventType.VOICE_PEAK, "voice", 0.9, false, null));
			}
		}
		if (random.nextInt(10) < 3) {
			events.add(ev(1_000 + random.nextInt(length - 2_000), EventType.OTHER_DEATH, "death.attack.mob", 0.6, false, null));
		}
		// Voice reactions to near deaths are common.
		for (GameEvent e : List.copyOf(events)) {
			if (e.type() == EventType.NEAR_DEATH && random.nextInt(3) == 0) {
				events.add(ev(e.tick() + 20, EventType.VOICE_PEAK, "voice", 0.8, false, null));
			}
		}
		// Exploration is front-loaded: lots of new biomes early, few late.
		int biomes = (int) Math.round((1 - progress) * 4 + random.nextInt(2));
		scatter(events, length, biomes, t -> ev(t, EventType.NEW_BIOME, "minecraft:biome_" + index + "_" + t, 0.4, true, null));
		scatter(events, length, 2 + random.nextInt(2), t -> ev(t, EventType.ADVANCEMENT, sideAdvancement(), 0.4, true, null));
		storyBeats(index, count, length, events);

		for (long day = (playedTicks / TICKS_PER_DAY) + 1; day * TICKS_PER_DAY < playedTicks + length; day++) {
			int t = (int) (day * TICKS_PER_DAY - playedTicks);
			events.add(ev(t, EventType.NEW_DAY, "day", 0.1, false, null));
			events.add(ev(t + 13_000, EventType.NIGHT_FALL, "night", 0.2, false, null));
		}
		if (index == 0) {
			events.add(ev(13_000, EventType.NIGHT_FALL, "night", 0.8, true, null));
		}

		events.removeIf(e -> e.tick() < 0 || e.tick() > length);
		int sessionStart = (int) playedTicks;
		List<GameEvent> withDays = events.stream()
			.map(e -> new GameEvent(e.tick(), e.wallTime(), e.type(), e.subject(), e.label(), e.actor(), e.x(), e.y(), e.z(),
				e.dimension(), (int) ((sessionStart + e.tick()) / TICKS_PER_DAY) + 1, e.intensity(), e.first(), e.detail()))
			.sorted(Comparator.comparingInt(GameEvent::tick))
			.toList();
		Trail trail = trail(length, respawns);
		sessions.add(new SessionData("session-" + index, index, length, withDays, trail, Map.of("Alex", beside(trail))));
		playedTicks += length;
	}

	private void storyBeats(int index, int count, int length, List<GameEvent> events) {
		int mid = length / 2;
		switch (index) {
			case 0 -> {
				events.add(ev(6_000, EventType.ADVANCEMENT, "minecraft:story/mine_stone", 0.4, true, null));
				events.add(ev(20_000, EventType.RARE_ITEM, "minecraft:iron_ingot", 0.3, true, null));
			}
			case 1 -> events.add(ev(mid, EventType.ADVANCEMENT, "minecraft:story/smelt_iron", 0.4, true, null));
			case 3 -> {
				events.add(ev(mid, EventType.RARE_ITEM, "minecraft:diamond", 0.6, true, null));
				events.add(ev(mid + 5, EventType.ADVANCEMENT, "minecraft:story/mine_diamond", 0.7, true, null));
				events.add(ev(mid - 40, EventType.VOICE_PEAK, "voice", 1.0, false, null));
			}
			case 6 -> events.add(ev(mid, EventType.DIMENSION_CHANGE, "minecraft:the_nether", 1.0, true, null));
			case 8 -> events.add(ev(mid, EventType.ADVANCEMENT, "minecraft:nether/find_fortress", 0.7, true, null));
			case 10 -> events.add(ev(mid, EventType.ADVANCEMENT, "minecraft:nether/obtain_blaze_rod", 0.4, true, null));
			case 20 -> {
				events.add(ev(mid, EventType.ADVANCEMENT, "minecraft:story/follow_ender_eye", 0.7, true, null));
				events.add(ev(mid + 30, EventType.VOICE_CROWD, "voice", 1.0, false, null));
			}
			default -> {
			}
		}
		if (index >= 6 && index < count - 1 && index % 2 == 0) {
			events.add(ev(length / 5, EventType.DIMENSION_CHANGE, "minecraft:the_nether", 0.4, false, null));
			events.add(ev(length / 3, EventType.DIMENSION_CHANGE, "minecraft:overworld", 0.4, false, null));
		}
		if (index == 21) {
			events.add(ev(mid, EventType.RARE_ITEM, "minecraft:elytra", 1.0, true, null));
		}
		if (index == count - 1) {
			int end = length - 6_000;
			events.add(ev(end - 4_000, EventType.DIMENSION_CHANGE, "minecraft:the_end", 1.0, true, null));
			events.add(ev(end - 3_000, EventType.BOSS_FIGHT_START, "entity.minecraft.ender_dragon", 0.8, true, null));
			events.add(ev(end - 2_000, EventType.VOICE_CROWD, "voice", 1.0, false, null));
			events.add(ev(end - 1_200, EventType.NEAR_DEATH, "near_death", 0.8, false, null));
			events.add(ev(end - 1_180, EventType.VOICE_PEAK, "voice", 1.0, false, null));
			events.add(ev(end, EventType.DRAGON_KILLED, "minecraft:ender_dragon", 1.0, true, null));
			events.add(ev(end + 20, EventType.VOICE_CROWD, "voice", 1.0, false, null));
			events.add(ev(end + 5, EventType.ADVANCEMENT, "minecraft:end/kill_dragon", 1.0, true, null));
			events.add(conversation(end + 400, 900, true)); // "we actually did it..."
		}
	}

	private GameEvent conversation(int tick, int ticks, boolean near) {
		String detail = "ticks=" + ticks + ";speakers=Steve,Alex;near=" + (near ? "Alex" : "") + ";turns=" + (6 + random.nextInt(20));
		return ev(tick, EventType.CONVERSATION, "conversation", 0.5 + random.nextDouble() * 0.4, false, detail);
	}

	/** Alex walks a few blocks next to us. */
	private static Trail beside(Trail trail) {
		List<TrailSample> samples = new ArrayList<>();
		for (TrailSample s : trail.samples()) {
			if (s.tick() % 20 == 0) {
				samples.add(new TrailSample(s.tick(), s.x() + 3, s.y(), s.z() + 2, 0f, 0f, 0));
			}
		}
		return new Trail(samples);
	}

	private interface EventAt {
		GameEvent at(int tick);
	}

	private void scatter(List<GameEvent> events, int length, int count, EventAt maker) {
		for (int i = 0; i < count; i++) {
			events.add(maker.at(random.nextInt(Math.max(1, length))));
		}
	}

	private GameEvent ev(int tick, EventType type, String subject, double intensity, boolean first, String detail) {
		double x = random.nextGaussian() * 300;
		double z = random.nextGaussian() * 300;
		return new GameEvent(tick, 0, type, subject, type + " " + subject, "Steve", x, 64, z,
			"minecraft:overworld", 1, intensity, first, detail);
	}

	private static GameEvent at(GameEvent e, double x, double y, double z) {
		return new GameEvent(e.tick(), e.wallTime(), e.type(), e.subject(), e.label(), e.actor(), x, y, z,
			e.dimension(), e.day(), e.intensity(), e.first(), e.detail());
	}

	/** The player wanders between walking, standing (mining, building) and sprinting, respawning after deaths. */
	private Trail trail(int length, List<Integer> respawns) {
		List<TrailSample> samples = new ArrayList<>();
		double x = 0, z = 0, yaw = 0, speed = 4;
		for (int t = 0; t <= length; t += 10) {
			if (t % 600 == 0) {
				int mode = random.nextInt(4);
				speed = mode == 0 ? 0 : mode == 1 ? 1 : mode == 2 ? 4.3 : 5.6;
				yaw += random.nextGaussian() * 60;
			}
			for (int r : respawns) {
				if (r >= t - 10 && r < t) {
					x += 900;
				}
			}
			double step = speed * 10 / 20.0;
			x += -Math.sin(Math.toRadians(yaw)) * step;
			z += Math.cos(Math.toRadians(yaw)) * step;
			samples.add(new TrailSample(t, x, 64, z, (float) yaw, 0f, TrailSample.FLAG_ON_GROUND));
		}
		return new Trail(samples);
	}

	private String damageType() {
		String[] types = {"minecraft:explosion", "minecraft:fall", "minecraft:mob_attack", "minecraft:arrow", "minecraft:lava"};
		return types[random.nextInt(types.length)];
	}

	private String hostile() {
		String[] mobs = {"minecraft:zombie", "minecraft:skeleton", "minecraft:spider", "minecraft:creeper", "minecraft:enderman"};
		return mobs[random.nextInt(mobs.length)];
	}

	private String deathCause() {
		String[] causes = {"death.attack.explosion.player", "death.attack.lava", "death.fell.accident.generic", "death.attack.mob", "death.attack.drown"};
		return causes[random.nextInt(causes.length)];
	}

	private String sideAdvancement() {
		String[] ids = {"minecraft:husbandry/plant_seed", "minecraft:husbandry/breed_an_animal", "minecraft:adventure/sleep_in_bed",
			"minecraft:adventure/trade", "minecraft:husbandry/fishy_business", "minecraft:adventure/shoot_arrow"};
		return ids[random.nextInt(ids.length)] + "_" + random.nextInt(1000);
	}
}
