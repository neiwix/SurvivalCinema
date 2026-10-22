package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.event.Details;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the closing credits tell about the world. Counted from the recordings, so the numbers match the story the
 * film shows, and the friends who were seen get their line too (the game only tells us our own statistics).
 *
 * @param firstPlayed    real time of the first recorded moment, epoch milliseconds (0 when unknown)
 * @param playTicks      recorded play time
 * @param distanceBlocks how far we travelled, without teleports
 * @param dimensions     dimension ids in the order they were first visited
 * @param friends        other players who were near us or whose deaths we saw, in order of appearance
 */
public record FilmStats(long firstPlayed, long lastPlayed, int sessions, long playTicks, int days,
						int deaths, int kills, int blocksPlaced, int diamonds, int advancements, long distanceBlocks,
						List<String> dimensions, boolean dragonKilled, List<Friend> friends) {
	public record Friend(String name, int deaths) {
	}

	/** Faster than this between two samples is a teleport or a portal, not travel (elytra with rockets stays below). */
	private static final double MAX_BLOCKS_PER_TICK = 5;
	private static final String DIAMOND = "minecraft:diamond";

	public static FilmStats of(List<SessionData> sessions, String player) {
		long first = Long.MAX_VALUE;
		long last = 0;
		long playTicks = 0;
		int days = 0;
		int deaths = 0;
		int kills = 0;
		int blocks = 0;
		int diamonds = 0;
		int advancements = 0;
		double distance = 0;
		boolean dragon = false;
		Set<String> dimensions = new LinkedHashSet<>();
		Map<String, Integer> friends = new LinkedHashMap<>();

		for (SessionData session : sessions) {
			playTicks += Math.max(0, session.lengthTicks());
			for (GameEvent event : session.events()) {
				if (event.wallTime() > 0) {
					first = Math.min(first, event.wallTime());
					last = Math.max(last, event.wallTime());
				}
				days = Math.max(days, event.day());
				if (event.dimension() != null && !event.dimension().isEmpty()) {
					dimensions.add(event.dimension());
				}
				switch (event.type()) {
					case DEATH -> deaths++;
					case KILL -> kills++;
					case ADVANCEMENT -> advancements++;
					case DRAGON_KILLED -> dragon = true;
					case BUILD_ACTIVITY -> blocks += Math.max(0, Details.getInt(event.detail(), "count", 0));
					case RARE_ITEM -> {
						if (DIAMOND.equals(event.subject())) {
							diamonds += Math.max(1, parseCount(event.detail()));
						}
					}
					case OTHER_DEATH -> {
						String who = event.detail();
						if (isFriend(who, player)) {
							friends.merge(who, 1, Integer::sum);
						}
					}
					default -> {
					}
				}
			}
			for (String name : session.companions().keySet()) {
				if (isFriend(name, player)) {
					friends.putIfAbsent(name, 0);
				}
			}
			if (session.trail() != null) {
				distance += travelled(session.trail().samples());
			}
		}
		List<Friend> cast = new ArrayList<>();
		friends.forEach((name, count) -> cast.add(new Friend(name, count)));
		return new FilmStats(first == Long.MAX_VALUE ? 0 : first, last, sessions.size(), playTicks, Math.max(1, days),
			deaths, kills, blocks, diamonds, advancements, Math.round(distance), List.copyOf(dimensions), dragon, List.copyOf(cast));
	}

	public boolean visited(String dimension) {
		return dimensions.contains(dimension);
	}

	private static double travelled(List<TrailSample> samples) {
		double sum = 0;
		for (int i = 1; i < samples.size(); i++) {
			TrailSample a = samples.get(i - 1);
			TrailSample b = samples.get(i);
			int ticks = Math.max(1, b.tick() - a.tick());
			double step = Math.sqrt(sq(b.x() - a.x()) + sq(b.y() - a.y()) + sq(b.z() - a.z()));
			if (step <= MAX_BLOCKS_PER_TICK * ticks) {
				sum += step;
			}
		}
		return sum;
	}

	private static boolean isFriend(String name, String player) {
		return name != null && !name.isBlank() && !name.equals(player);
	}

	private static int parseCount(String detail) {
		try {
			return detail == null ? 1 : Integer.parseInt(detail.trim());
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private static double sq(double v) {
		return v * v;
	}
}
