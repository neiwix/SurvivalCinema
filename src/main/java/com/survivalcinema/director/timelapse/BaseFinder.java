package com.survivalcinema.director.timelapse;

import com.survivalcinema.director.camera.Vec;
import com.survivalcinema.director.event.Details;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import com.survivalcinema.director.story.SessionData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where the base is: the place in the world people live in, over the whole playthrough: where the most was built
 * (beds, chests, furnaces and doors count most, the hoppers and pistons of farms least), slept and spent time.
 * <p>
 * Building is logged in 30 second windows ({@link EventType#BUILD_ACTIVITY}: where, how many blocks). A base is not
 * built in one go: people build a bit, go mining for days, come back and build on. So all windows of every session
 * are pooled, and the densest spot wins. To count as a base the spot needs enough blocks and at least two separate
 * building visits (a different session, or half an hour of play apart): a bridge, a one-off tower or the torches of
 * a mine are spread out or done at once and don't win against a home people keep coming back to.
 */
public final class BaseFinder {
	/**
	 * One building window.
	 *
	 * @param home  blocks people live with (beds, chests, furnaces, doors...), see {@link BlockKinds}
	 * @param farm  machinery blocks (hoppers, pistons, redstone, rails)
	 * @param door  a door placed in it, or null
	 */
	public record Window(int sessionIndex, String replayId, int tick, int day, String dimension, Vec position, int blocks,
						 @Nullable double[] box, int home, int farm, int beds, @Nullable Vec door) {
		public Window(int sessionIndex, String replayId, int tick, int day, String dimension, Vec position, int blocks,
					  @Nullable double[] box) {
			this(sessionIndex, replayId, tick, day, dimension, position, blocks, box, 0, 0, 0, null);
		}

		/** How much it says "home": machinery counts little, beds and chests a lot. */
		public double weight() {
			return Math.max(0.5, blocks - FARM_DISCOUNT * farm) + HOME_BONUS * home + BED_BONUS * beds;
		}
	}

	/** Something that says "people live here": building, a night's sleep, time spent. */
	private record Spot(String dimension, Vec position, double weight) {
	}

	/**
	 * @param center    middle of the base (block coordinates)
	 * @param radius    how far it reaches from the center, horizontally
	 * @param minY      lowest block level worth showing
	 * @param maxY      highest block level built
	 * @param windows   its building windows, oldest first
	 * @param visits    how many separate times it was built on
	 * @param entrance  where its doors are (their middle), or null when no door was placed
	 */
	public record Base(String dimension, Vec center, double radius, double minY, double maxY, int blocks,
					   List<Window> windows, int visits, @Nullable Vec entrance) {
		public Base(String dimension, Vec center, double radius, double minY, double maxY, int blocks, List<Window> windows,
					int visits) {
			this(dimension, center, radius, minY, maxY, blocks, windows, visits, null);
		}

		public int firstDay() {
			return windows.getFirst().day();
		}

		public int lastDay() {
			return windows.getLast().day();
		}
	}

	/**
	 * How much building makes a base.
	 *
	 * @param minPictures fewer pictures than this make no timelapse
	 */
	public record Rules(int minBlocks, int minVisits, int minPictures) {
		public static final Rules DEFAULT = new Rules(150, 2, 3);

		public Rules(int minBlocks, int minVisits) {
			this(minBlocks, minVisits, DEFAULT.minPictures());
		}
	}

	/** A machinery block counts this much less than a plain one (a farm grows over days too, but is no home). */
	static final double FARM_DISCOUNT = 0.8;
	/** Each chest, furnace, door... counts this much more. */
	static final double HOME_BONUS = 10;
	/** Placing a bed. */
	static final double BED_BONUS = 40;
	/** Sleeping somewhere: the strongest sign of home. */
	static final double SLEEP_WEIGHT = 100;
	/** Time spent around a place (while really playing: away-from-keyboard time isn't recorded). */
	static final double PRESENCE_PER_MINUTE = 1;
	/** Windows this far from the base still belong to it. */
	static final double REACH = 40;
	static final double MIN_RADIUS = 8;
	static final double MAX_RADIUS = 40;
	/** Building again after this much play (in the same session) is a new visit. */
	static final int NEW_VISIT_TICKS = 20 * 60 * 30;
	private static final int CELL = 16;

	private BaseFinder() {
	}

	public static @Nullable Base find(List<SessionData> sessions, Rules rules) {
		List<Window> windows = windows(sessions);
		if (windows.isEmpty()) {
			return null;
		}
		// 1. the homeliest cell: every cell scored by building (homes over farms), sleep and time spent within reach of it
		List<Spot> anchors = new ArrayList<>();
		for (Window w : windows) {
			anchors.add(new Spot(w.dimension(), w.position(), w.weight()));
		}
		anchors.addAll(sleeps(sessions));
		List<Spot> spots = new ArrayList<>(anchors);
		spots.addAll(presence(sessions));
		Map<String, Map<Long, Double>> cells = new HashMap<>();
		for (Window w : windows) {
			cells.computeIfAbsent(w.dimension(), d -> new HashMap<>()).merge(cell(w.position()), w.weight(), Double::sum);
		}
		String bestDimension = null;
		Vec bestCenter = null;
		double bestScore = 0;
		for (Map.Entry<String, Map<Long, Double>> dim : cells.entrySet()) {
			for (long key : dim.getValue().keySet()) {
				Vec center = new Vec(((int) (key >> 32)) * CELL + CELL / 2.0, 0, ((int) key) * CELL + CELL / 2.0);
				double score = 0;
				for (Spot s : spots) {
					if (s.dimension().equals(dim.getKey()) && horizontal(s.position(), center) <= REACH) {
						score += s.weight();
					}
				}
				if (score > bestScore) {
					bestScore = score;
					bestDimension = dim.getKey();
					bestCenter = center;
				}
			}
		}
		if (bestCenter == null) {
			return null;
		}
		// 2. settle on the middle of what was built and slept in there
		Vec center = bestCenter;
		for (int i = 0; i < 4; i++) {
			center = weightedCenter(anchors, bestDimension, center);
		}
		List<Window> members = near(windows, bestDimension, center);
		int blocks = members.stream().mapToInt(Window::blocks).sum();
		int visits = visits(members);
		if (blocks < rules.minBlocks() || visits < rules.minVisits()) {
			return null;
		}

		// 3. how far and how high it reaches
		double radius = MIN_RADIUS;
		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		List<double[]> distances = new ArrayList<>();
		for (Window w : members) {
			distances.add(new double[]{horizontal(w.position(), center), w.blocks()});
			minY = Math.min(minY, w.position().y());
			maxY = Math.max(maxY, w.position().y());
			if (w.box() != null) {
				double[] b = w.box();
				for (double[] corner : new double[][]{{b[0], b[2]}, {b[3], b[5]}, {b[0], b[5]}, {b[3], b[2]}}) {
					radius = Math.max(radius, Math.hypot(corner[0] - center.x(), corner[1] - center.z()));
				}
				minY = Math.min(minY, b[1]);
				maxY = Math.max(maxY, b[4]);
			}
		}
		radius = Math.max(radius, percentile(distances, 0.9) + 4);
		radius = Math.min(MAX_RADIUS, radius);
		double y = members.stream().mapToDouble(w -> w.position().y() * w.blocks()).sum() / Math.max(1, blocks);
		return new Base(bestDimension, new Vec(center.x(), y, center.z()), radius, Math.min(minY, y) - 2, maxY + 3, blocks,
			members, visits, entrance(members));
	}

	/** The middle of the doors placed at the base: the front people walk in through. */
	private static @Nullable Vec entrance(List<Window> members) {
		double x = 0, y = 0, z = 0;
		int doors = 0;
		for (Window w : members) {
			if (w.door() != null) {
				x += w.door().x();
				y += w.door().y();
				z += w.door().z();
				doors++;
			}
		}
		return doors == 0 ? null : new Vec(x / doors + 0.5, y / doors, z / doors + 0.5);
	}

	/** Where the player went to sleep. */
	private static List<Spot> sleeps(List<SessionData> sessions) {
		List<Spot> spots = new ArrayList<>();
		for (SessionData session : sessions) {
			for (GameEvent event : session.events()) {
				if (event.type() == EventType.SLEEP) {
					spots.add(new Spot(dimensionAt(session, event), new Vec(event.x(), event.y(), event.z()), SLEEP_WEIGHT));
				}
			}
		}
		return spots;
	}

	/** Time spent, per cell: where the player's path was, a minute at a time. */
	private static List<Spot> presence(List<SessionData> sessions) {
		Map<String, Map<Long, double[]>> cells = new HashMap<>();
		for (SessionData session : sessions) {
			if (session.trail() == null) {
				continue;
			}
			List<com.survivalcinema.director.camera.TrailSample> samples = session.trail().samples();
			for (int i = 1; i < samples.size(); i++) {
				com.survivalcinema.director.camera.TrailSample s = samples.get(i);
				int ticks = Math.min(20, s.tick() - samples.get(i - 1).tick());
				if (ticks <= 0) {
					continue;
				}
				double[] cell = cells.computeIfAbsent(TimelapsePlanner.dimension(s), d -> new HashMap<>())
					.computeIfAbsent(cell(s.position()), c -> new double[1]);
				cell[0] += ticks / 1200.0 * PRESENCE_PER_MINUTE;
			}
		}
		List<Spot> spots = new ArrayList<>();
		cells.forEach((dimension, byCell) -> byCell.forEach((key, weight) -> spots.add(new Spot(dimension,
			new Vec(((int) (key >> 32)) * CELL + CELL / 2.0, 0, ((int) (long) key) * CELL + CELL / 2.0), weight[0]))));
		return spots;
	}

	/** Every building window of the playthrough, oldest first. */
	static List<Window> windows(List<SessionData> sessions) {
		List<Window> windows = new ArrayList<>();
		for (SessionData session : sessions) {
			for (GameEvent event : session.events()) {
				if (event.type() != EventType.BUILD_ACTIVITY) {
					continue;
				}
				int blocks = Details.getInt(event.detail(), "count", 0);
				if (blocks <= 0) {
					continue;
				}
				String detail = event.detail();
				windows.add(new Window(session.index(), session.replayId(), event.tick(), event.day(), dimensionAt(session, event),
					new Vec(event.x(), event.y(), event.z()), blocks, box(detail), Details.getInt(detail, "home", 0),
					Details.getInt(detail, "farm", 0), Details.getInt(detail, "bed", 0), door(detail)));
			}
		}
		windows.sort(Comparator.comparingInt(Window::sessionIndex).thenComparingInt(Window::tick));
		return windows;
	}

	private static @Nullable Vec door(@Nullable String detail) {
		List<String> parts = Details.getList(detail, "door");
		if (parts.size() != 3) {
			return null;
		}
		try {
			return new Vec(Double.parseDouble(parts.get(0).trim()), Double.parseDouble(parts.get(1).trim()),
				Double.parseDouble(parts.get(2).trim()));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static double @Nullable [] box(@Nullable String detail) {
		List<String> parts = Details.getList(detail, "box");
		if (parts.size() != 6) {
			return null;
		}
		double[] box = new double[6];
		try {
			for (int i = 0; i < 6; i++) {
				box[i] = Double.parseDouble(parts.get(i).trim());
			}
		} catch (NumberFormatException e) {
			return null;
		}
		return box;
	}

	/**
	 * Where the building happened. A window is logged when it ends, and by then the player may have gone through the
	 * portal they just built: the path recorded at the window's start knows better than the event.
	 */
	private static String dimensionAt(SessionData session, GameEvent event) {
		return session.dimensionOf(event);
	}

	/** Separate building visits: a new session, or a long time since the last window. */
	static int visits(List<Window> members) {
		int visits = 0;
		Window last = null;
		for (Window w : members) {
			if (last == null || w.sessionIndex() != last.sessionIndex() || w.tick() - last.tick() > NEW_VISIT_TICKS) {
				visits++;
			}
			last = w;
		}
		return visits;
	}

	private static List<Window> near(List<Window> windows, String dimension, Vec center) {
		return windows.stream().filter(w -> w.dimension().equals(dimension) && horizontal(w.position(), center) <= REACH).toList();
	}

	/** The weighted middle of the anchors within reach of {@code around}. */
	private static Vec weightedCenter(List<Spot> anchors, String dimension, Vec around) {
		double x = 0, z = 0, total = 0;
		for (Spot s : anchors) {
			if (s.dimension().equals(dimension) && horizontal(s.position(), around) <= REACH) {
				x += s.position().x() * s.weight();
				z += s.position().z() * s.weight();
				total += s.weight();
			}
		}
		return total <= 0 ? around : new Vec(x / total, 0, z / total);
	}

	private static double percentile(List<double[]> valueWeight, double share) {
		List<double[]> sorted = new ArrayList<>(valueWeight);
		sorted.sort(Comparator.comparingDouble(v -> v[0]));
		double total = sorted.stream().mapToDouble(v -> v[1]).sum();
		double seen = 0;
		for (double[] v : sorted) {
			seen += v[1];
			if (seen >= share * total) {
				return v[0];
			}
		}
		return sorted.isEmpty() ? 0 : sorted.getLast()[0];
	}

	static double horizontal(Vec a, Vec b) {
		return Math.hypot(a.x() - b.x(), a.z() - b.z());
	}

	private static long cell(Vec p) {
		long cx = Math.floorDiv((int) Math.floor(p.x()), CELL);
		long cz = Math.floorDiv((int) Math.floor(p.z()), CELL);
		return cx << 32 | (cz & 0xFFFF_FFFFL);
	}

	/** Sessions that were built in at the base (for tests and logs). */
	static Set<Integer> sessionsOf(Base base) {
		Set<Integer> sessions = new HashSet<>();
		for (Window w : base.windows()) {
			sessions.add(w.sessionIndex());
		}
		return sessions;
	}
}
