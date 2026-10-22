package com.survivalcinema.director.timelapse;

import com.survivalcinema.director.camera.BlockProbe;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.camera.Vec;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.EditDecisionList.Kind;
import com.survivalcinema.director.story.FilmDirector;
import com.survivalcinema.director.story.SessionData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A playthrough like real ones: the base is built a bit at a time over several sessions and days, with long trips
 * mining far away in between, a bridge and the torches of a mine elsewhere.
 */
class BaseTimelapseTest {
	private static final double BASE_X = 200;
	private static final double BASE_Z = -100;
	private static final int LENGTH = 12_000; // 10 minutes per session
	private static final String OVERWORLD = "minecraft:overworld";

	/** At the base (walking around its east side) until {@code leaveAt}, mining far away, back from {@code backAt}. */
	private static Trail trail(int leaveAt, int backAt, int dimension) {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= LENGTH; t += 2) {
			boolean home = t < leaveAt || t >= backAt;
			double x = home ? BASE_X + 14 + 6 * Math.sin(t / 300.0) : 900;
			double z = home ? BASE_Z + 6 * Math.cos(t / 300.0) : 400;
			double y = home ? 70 : 20;
			samples.add(new TrailSample(t, x, y, z, 0, 0, TrailSample.FLAG_ON_GROUND | dimension << 8));
		}
		return new Trail(samples);
	}

	private static GameEvent build(int tick, int day, double x, double z, int blocks) {
		return new GameEvent(tick, tick * 50L, EventType.BUILD_ACTIVITY, "build", "build", "Steve", x, 70, z, OVERWORLD, day,
			0.5, false, "count=" + blocks + ";box=" + (int) (x - 5) + ",66," + (int) (z - 5) + "," + (int) (x + 5) + ",78," + (int) (z + 5));
	}

	private static GameEvent event(int tick, EventType type, String subject, int day, boolean first) {
		return new GameEvent(tick, tick * 50L, type, subject, subject, "Steve", BASE_X, 70, BASE_Z, OVERWORLD, day, 0.8, first, null);
	}

	private static SessionData session(int index, List<GameEvent> events, Trail trail) {
		return new SessionData("replay-" + index, index, LENGTH, events, trail);
	}

	/** Four sessions: building on and off at the base, a mine, a bridge, a session without building. */
	static List<SessionData> world() {
		List<SessionData> sessions = new ArrayList<>();
		List<GameEvent> s0 = new ArrayList<>(List.of(
			build(600, 1, BASE_X, BASE_Z, 30), build(1200, 1, BASE_X + 3, BASE_Z, 40), build(1800, 1, BASE_X, BASE_Z + 4, 30)));
		for (int t = 4000; t < 8000; t += 700) {
			s0.add(build(t, 2, 900 + (t - 4000) / 50.0, 400, 5)); // torches in the mine
		}
		sessions.add(session(0, s0, trail(3000, 9000, 0)));
		List<GameEvent> s1 = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			s1.add(build(700 + i * 700, 4, BASE_X - 4 + i * 3, BASE_Z - 3, 60));
		}
		for (int i = 0; i < 9; i++) {
			s1.add(build(4000 + i * 600, 5, i * 50, -500, 20)); // a long bridge
		}
		sessions.add(session(1, s1, trail(3000, 9000, 0)));
		sessions.add(session(2, new ArrayList<>(), trail(500, 11_000, 0))); // away all session
		sessions.add(session(3, new ArrayList<>(List.of(build(9500, 12, BASE_X + 2, BASE_Z + 2, 80),
			build(10_100, 12, BASE_X - 2, BASE_Z + 1, 50))), trail(2000, 9000, 0)));
		return sessions;
	}

	@Test
	void findsTheBaseBuiltOverManySessions() {
		BaseFinder.Base base = BaseFinder.find(world(), BaseFinder.Rules.DEFAULT);
		assertNotNull(base);
		assertEquals(OVERWORLD, base.dimension());
		assertTrue(BaseFinder.horizontal(base.center(), new Vec(BASE_X, 0, BASE_Z)) < 6, "center " + base.center());
		assertEquals(30 + 40 + 30 + 3 * 60 + 80 + 50, base.blocks(), "neither the mine nor the bridge belong to it");
		assertEquals(3, base.visits());
		assertEquals(java.util.Set.of(0, 1, 3), BaseFinder.sessionsOf(base));
		assertTrue(base.radius() >= 8 && base.radius() <= 40, "radius " + base.radius());
		assertTrue(base.maxY() >= 78 && base.minY() <= 66, "height " + base.minY() + ".." + base.maxY());
		assertEquals(1, base.firstDay());
		assertEquals(12, base.lastDay());
	}

	@Test
	void aHutBuiltOnceIsNoBase() {
		List<SessionData> once = List.of(session(0, List.of(build(600, 1, BASE_X, BASE_Z, 90), build(1200, 1, BASE_X, BASE_Z, 90)),
			trail(3000, 9000, 0)));
		assertNull(BaseFinder.find(once, BaseFinder.Rules.DEFAULT), "one visit");
		List<SessionData> small = List.of(session(0, List.of(build(600, 1, BASE_X, BASE_Z, 20)), trail(3000, 9000, 0)),
			session(1, List.of(build(600, 2, BASE_X, BASE_Z, 20)), trail(3000, 9000, 0)));
		assertNull(BaseFinder.find(small, BaseFinder.Rules.DEFAULT), "too few blocks");
		assertNotNull(BaseFinder.find(small, new BaseFinder.Rules(10, 2)), "unless the rules allow it");
		assertNull(BaseFinder.find(List.of(session(0, List.of(), trail(3000, 9000, 0))), BaseFinder.Rules.DEFAULT));
	}

	@Test
	void aPortalBuiltThenUsedStaysInTheOverworld() {
		// the window was logged after the player went through: it says the Nether, the path says where it was built
		GameEvent logged = new GameEvent(600, 0, EventType.BUILD_ACTIVITY, "build", "build", "Steve", BASE_X, 70, BASE_Z,
			"minecraft:the_nether", 1, 0.5, false, "count=200");
		SessionData session = session(0, List.of(logged), trail(3000, 9000, 0));
		List<BaseFinder.Window> windows = BaseFinder.windows(List.of(session));
		assertEquals(OVERWORLD, windows.getFirst().dimension());
	}

	@Test
	void picturesFollowTheBuildingNotTheClock() {
		List<SessionData> world = world();
		TimelapsePlanner.Plan plan = TimelapsePlanner.plan(world, BaseFinder.Rules.DEFAULT);
		assertNotNull(plan);
		List<TimelapsePlanner.Snapshot> pictures = plan.snapshots();
		assertTrue(pictures.size() >= TimelapsePlanner.MIN_SNAPSHOTS);
		// the bare land first, the finished base last
		assertEquals(0, pictures.getFirst().blocksSoFar());
		assertTrue(pictures.getFirst().tick() < 600 && pictures.getFirst().sessionIndex() == 0);
		assertEquals(plan.base().blocks(), pictures.getLast().blocksSoFar());
		assertEquals(3, pictures.getLast().sessionIndex());
		assertTrue(pictures.getLast().tick() >= 10_100);
		// in order, never while the player was away (the base would not be in the replay), none from the session without building
		for (int i = 0; i < pictures.size(); i++) {
			TimelapsePlanner.Snapshot p = pictures.get(i);
			if (i > 0) {
				TimelapsePlanner.Snapshot before = pictures.get(i - 1);
				assertTrue(p.sessionIndex() > before.sessionIndex() || p.tick() > before.tick(), "in order");
				assertTrue(p.blocksSoFar() >= before.blocksSoFar(), "the base only grows");
			}
			SessionData session = world.get(p.sessionIndex());
			assertTrue(TimelapsePlanner.isNear(session.trail().sampleAt(p.tick()), plan.base()), "near the base at " + p);
			assertTrue(p.endTick() <= LENGTH);
			assertFalse(p.sessionIndex() == 2);
		}
		assertTrue(plan.seconds() >= TimelapsePlanner.MIN_SECONDS && plan.seconds() <= TimelapsePlanner.MAX_SECONDS);
	}

	@Test
	void theBaseGrowsBlockByBlockInStretchesOfBuildingSpedUp() {
		List<SessionData> world = world();
		TimelapsePlanner.Plan plan = TimelapsePlanner.plan(world, BaseFinder.Rules.DEFAULT);
		assertNotNull(plan);
		List<TimelapsePlanner.Snapshot> shots = plan.snapshots();
		assertTrue(shots.getFirst().isStill() && shots.getLast().isStill(), "the bare land and the finished base are held");
		List<TimelapsePlanner.Snapshot> building = shots.stream().filter(s -> !s.isStill()).toList();
		// one stretch per visit that built: the windows of a visit run together, the mine and the bridge are not the base
		assertEquals(3, building.size(), "stretches: " + building);
		for (TimelapsePlanner.Snapshot s : building) {
			SessionData session = world.get(s.sessionIndex());
			assertTrue(TimelapsePlanner.isNear(session.trail().sampleAt(s.tick()), plan.base())
				&& TimelapsePlanner.isNear(session.trail().sampleAt(s.endTick()), plan.base()), "the base is in the replay all along: " + s);
			assertTrue(s.speed() >= TimelapsePlanner.MIN_SPEED && s.speed() <= TimelapsePlanner.MAX_SPEED, "sped up: " + s.speed());
			assertTrue(s.seconds() >= TimelapsePlanner.MIN_RUN_SECONDS - 1e-9 && s.seconds() <= TimelapsePlanner.MAX_RUN_SECONDS + 1e-9);
		}
		// the visit that built most gets the most time
		assertTrue(building.get(1).seconds() > building.get(0).seconds(), "180 blocks get more time than 100: " + building);
		assertEquals(plan.seconds(), shots.stream().mapToDouble(TimelapsePlanner.Snapshot::seconds).sum(), 1e-9);
	}

	@Test
	void aVeryLongStretchOfBuildingKeepsItsEnd() {
		// ten hours at the base in one go
		TimelapsePlanner.Snapshot marathon = new TimelapsePlanner.Snapshot("r", 0, 1000, 1000 + 20 * 3600 * 10, 1, 5000, 0);
		List<TimelapsePlanner.Snapshot> timed = TimelapsePlanner.timed(List.of(
			new TimelapsePlanner.Snapshot("r", 0, 900, 1, 0), marathon, new TimelapsePlanner.Snapshot("r", 0, 800_000, 2, 5000)));
		TimelapsePlanner.Snapshot shot = timed.get(1);
		assertEquals(marathon.endTick(), shot.endTick(), "the finished end of it");
		assertEquals(TimelapsePlanner.MAX_SPEED, shot.speed(), 1, "as fast as it goes, no faster");
	}

	@Test
	void manyWindowsAreThinnedEvenlyByGrowth() {
		List<TimelapsePlanner.Snapshot> all = new ArrayList<>();
		int built = 0;
		for (int i = 0; i < 200; i++) {
			built += i < 100 ? 1 : 20; // slow start, then a lot of building
			all.add(new TimelapsePlanner.Snapshot("r", 0, i * 100, 1, built));
		}
		List<TimelapsePlanner.Snapshot> chosen = TimelapsePlanner.choose(all, built);
		assertEquals(TimelapsePlanner.MAX_SNAPSHOTS, chosen.size());
		assertEquals(all.getFirst(), chosen.getFirst());
		assertEquals(all.getLast(), chosen.getLast());
		long fromSlowStart = chosen.stream().filter(s -> s.tick() < 100 * 100).count();
		assertTrue(fromSlowStart <= 3, "the slow start gets few pictures: " + fromSlowStart);
	}

	@Test
	void cameraLooksFromTheOpenSideAndAroundHills() {
		TimelapsePlanner.Plan plan = TimelapsePlanner.plan(world(), BaseFinder.Rules.DEFAULT);
		assertNotNull(plan);
		TimelapsePlanner.View view = plan.view();
		assertTrue(view.openX() > 0.8, "the player walks east of the base: " + view.openX());
		TimelapsePlanner.Camera free = view.camera(BlockProbe.EMPTY);
		assertTrue(free.position().x() > view.x() + 15, "camera east of the base: " + free.position());
		assertTrue(free.position().y() > view.target().y(), "a little above it");
		assertTrue(free.pitch() > 10 && free.pitch() < 20, "a low view of the front, not from above: " + free.pitch());
		double distance = free.position().sub(view.target()).length();
		assertTrue(distance > view.radius() * 1.5, "far enough to see all of it: " + distance);
		// a hill on the east side: the camera goes elsewhere
		BlockProbe hillEast = (from, to) -> from.x() > view.x() + 10 ? 0.4 : 1;
		TimelapsePlanner.Camera turned = view.camera(hillEast);
		assertTrue(turned.position().x() <= view.x() + 10, "moved away from the hill: " + turned.position());
		Vec look = Vec.fromYaw(turned.yaw());
		Vec toBase = view.target().sub(turned.position()).horizontal().normalize();
		assertTrue(look.x() * toBase.x() + look.z() * toBase.z() > 0.99, "still facing the base");
	}

	@Test
	void theBaseOwnWallsDoNotChaseTheCameraUp() {
		TimelapsePlanner.View view = TimelapsePlanner.plan(world(), BaseFinder.Rules.DEFAULT).view();
		// the house itself stands around the middle: every line stops at its walls
		BlockProbe house = (from, to) -> {
			double length = to.sub(from).length();
			double wall = BaseFinder.horizontal(to, new Vec(view.x(), 0, view.z())) + view.radius() * 0.4;
			return Math.max(0, (length - wall) / length);
		};
		TimelapsePlanner.Camera camera = view.camera(house);
		assertTrue(camera.pitch() < 20, "still the low front view: " + camera.pitch());
	}

	/** A window with what kind of blocks went in, as the build watcher logs it. */
	private static GameEvent build(int tick, int day, double x, double z, int blocks, String kinds) {
		GameEvent plain = build(tick, day, x, z, blocks);
		return new GameEvent(plain.tick(), plain.wallTime(), plain.type(), plain.subject(), plain.label(), plain.actor(), x, 70, z,
			OVERWORLD, day, 0.5, false, plain.detail() + ";" + kinds);
	}

	@Test
	void aHomeWinsOverABiggerFarm() {
		double farmX = BASE_X + 300;
		List<SessionData> sessions = new ArrayList<>();
		for (int s = 0; s < 3; s++) {
			List<GameEvent> events = new ArrayList<>();
			// the farm: lots of hoppers, pistons and rails, built on over three sessions
			for (int i = 0; i < 4; i++) {
				events.add(build(600 + i * 600, 1 + s * 3, farmX, BASE_Z, 70, "home=0;farm=40;bed=0"));
			}
			// the home: fewer blocks, but beds, chests, furnaces, and the player sleeps there
			events.add(build(5000, 1 + s * 3, BASE_X, BASE_Z, 60, "home=6;farm=0;bed=" + (s == 0 ? 1 : 0)));
			events.add(new GameEvent(9500, 0, EventType.SLEEP, "sleep", "sleep", "Steve", BASE_X + 2, 70, BASE_Z, OVERWORLD,
				1 + s * 3, 0.2, false, null));
			sessions.add(session(s, events, trail(3000, 9000, 0)));
		}
		BaseFinder.Base base = BaseFinder.find(sessions, BaseFinder.Rules.DEFAULT);
		assertNotNull(base);
		assertTrue(BaseFinder.horizontal(base.center(), new Vec(BASE_X, 0, BASE_Z)) < 8, "the home, not the farm: " + base.center());
		// without beds, chests and sleep the farm would have won
		List<SessionData> noHome = new ArrayList<>();
		for (SessionData s : sessions) {
			List<GameEvent> plain = new ArrayList<>();
			for (GameEvent e : s.events()) {
				if (e.type() == EventType.BUILD_ACTIVITY) {
					plain.add(new GameEvent(e.tick(), e.wallTime(), e.type(), e.subject(), e.label(), e.actor(), e.x(), e.y(), e.z(),
						e.dimension(), e.day(), e.intensity(), e.first(), "count=" + com.survivalcinema.director.event.Details.getInt(e.detail(), "count", 0)));
				}
			}
			noHome.add(new SessionData(s.replayId(), s.index(), s.lengthTicks(), plain, s.trail()));
		}
		BaseFinder.Base farm = BaseFinder.find(noHome, BaseFinder.Rules.DEFAULT);
		assertNotNull(farm);
		assertTrue(BaseFinder.horizontal(farm.center(), new Vec(farmX, 0, BASE_Z)) < 8, "plain counting picks the farm: " + farm.center());
	}

	@Test
	void theCameraLooksAtTheFrontDoor() {
		List<SessionData> world = new ArrayList<>(world());
		SessionData first = world.removeFirst();
		List<GameEvent> events = new ArrayList<>(first.events());
		// the door went in on the south side (+z), while the player mostly walks around the east side
		events.add(build(2400, 1, BASE_X, BASE_Z + 5, 4, "home=1;farm=0;bed=0;door=" + (int) BASE_X + ",70," + (int) (BASE_Z + 6)));
		world.addFirst(new SessionData(first.replayId(), first.index(), first.lengthTicks(), events, first.trail()));
		TimelapsePlanner.Plan plan = TimelapsePlanner.plan(world, BaseFinder.Rules.DEFAULT);
		assertNotNull(plan.base().entrance());
		assertTrue(plan.openZ() > 0.8, "faces its door: " + plan.openX() + ", " + plan.openZ());
		TimelapsePlanner.Camera camera = plan.view().camera(BlockProbe.EMPTY);
		assertTrue(camera.position().z() > plan.base().center().z() + 10, "in front of the door: " + camera.position());
	}

	@Test
	void blockKinds() {
		assertEquals(BlockKinds.Kind.HOME, BlockKinds.of("minecraft:red_bed"));
		assertEquals(BlockKinds.Kind.HOME, BlockKinds.of("minecraft:chest"));
		assertEquals(BlockKinds.Kind.HOME, BlockKinds.of("minecraft:spruce_door"));
		assertEquals(BlockKinds.Kind.PLAIN, BlockKinds.of("minecraft:spruce_trapdoor"));
		assertEquals(BlockKinds.Kind.FARM, BlockKinds.of("minecraft:hopper"));
		assertEquals(BlockKinds.Kind.FARM, BlockKinds.of("minecraft:powered_rail"));
		assertEquals(BlockKinds.Kind.PLAIN, BlockKinds.of("minecraft:cobblestone"));
		assertTrue(BlockKinds.isBed("minecraft:white_bed"));
		assertTrue(BlockKinds.isDoor("minecraft:iron_door"));
		assertFalse(BlockKinds.isDoor("minecraft:iron_trapdoor"));
	}

	@Test
	void theFilmShowsTheBaseBeforeTheEnd() {
		List<SessionData> world = new ArrayList<>(world());
		// the last session goes to the End and kills the dragon
		SessionData last = world.removeLast();
		List<GameEvent> events = new ArrayList<>(last.events());
		events.add(event(10_500, EventType.DIMENSION_CHANGE, "minecraft:the_end", 13, true));
		events.add(event(11_000, EventType.DRAGON_KILLED, "minecraft:ender_dragon", 13, true));
		world.add(new SessionData(last.replayId(), last.index(), LENGTH, events, last.trail()));
		EditDecisionList edl = FilmDirector.plan(world, new FilmDirector.Options(300, "SMP", "Steve"));
		assertNotNull(edl.timelapse());
		List<EditDecisionList.Item> items = edl.items();
		int card = -1;
		int pictures = 0;
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).kind() == Kind.CHAPTER && EditDecisionList.BASE_CHAPTER.equals(items.get(i).dimension())) {
				card = i;
			}
			if (items.get(i).kind() == Kind.TIMELAPSE) {
				pictures++;
				assertTrue(card >= 0 && i > card, "the pictures follow their card");
			}
		}
		assertTrue(card > 0 && pictures >= TimelapsePlanner.MIN_SNAPSHOTS);
		int end = -1;
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i).kind() == Kind.CHAPTER && "minecraft:the_end".equals(items.get(i).dimension())) {
				end = i;
			}
		}
		if (end >= 0) {
			assertTrue(card < end, "the base comes before the journey to the End");
		}
		assertEquals(Kind.CREDITS, items.getLast().kind());
		assertTrue(edl.totalSeconds() <= 300 + 15, "the timelapse fits in the film's length: " + edl.totalSeconds());
		// shot like clips, at the base's moments
		long jobs = com.survivalcinema.director.render.RenderPlan.of(edl).jobs().stream()
			.filter(j -> j.item().kind() == Kind.TIMELAPSE).count();
		assertEquals(pictures, jobs);
	}
}
