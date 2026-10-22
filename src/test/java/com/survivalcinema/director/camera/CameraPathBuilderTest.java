package com.survivalcinema.director.camera;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraPathBuilderTest {
	/** Player walking along +X at 4 blocks per second (yaw -90 faces +X). */
	private static Trail walkingEast(int ticks) {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= ticks; t += 2) {
			samples.add(new TrailSample(t, t * 0.2, 64, 0, -90f, 0f, TrailSample.FLAG_ON_GROUND));
		}
		return new Trail(samples);
	}

	private static Trail standingStill(float yaw, int ticks) {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= ticks; t += 2) {
			samples.add(new TrailSample(t, 10, 64, 10, yaw, 0f, TrailSample.FLAG_ON_GROUND));
		}
		return new Trail(samples);
	}

	@Test
	void yawConventionsMatchMinecraft() {
		Vec south = Vec.fromYaw(0);
		assertEquals(0, south.x(), 1e-9);
		assertEquals(1, south.z(), 1e-9);
		Vec west = Vec.fromYaw(90);
		assertEquals(-1, west.x(), 1e-9);
		assertEquals(90, west.yaw(), 1e-9);
		assertEquals(-90, new Vec(1, 0, 0).yaw(), 1e-9);
	}

	@Test
	void lookAtPointsTheCameraAtTheTarget() {
		CameraKey key = CameraPathBuilder.lookAt(0, new Vec(0, 0, -5), Vec.ZERO);
		assertEquals(0, key.yaw(), 1e-4);
		assertEquals(0, key.pitch(), 1e-4);

		CameraKey fromAbove = CameraPathBuilder.lookAt(0, new Vec(0, 5, -5), Vec.ZERO);
		assertEquals(45, fromAbove.pitch(), 1e-4);
	}

	@Test
	void chaseStaysBehindAMovingPlayer() {
		Trail trail = walkingEast(200);
		List<CameraKey> keys = CameraPathBuilder.build(trail, ShotType.CHASE, 40, 160, CameraPathBuilder.Params.defaults(ShotType.CHASE));
		assertFalse(keys.isEmpty());
		for (CameraKey key : keys) {
			Vec player = trail.positionAt(key.tick());
			assertTrue(key.position().x() < player.x() - 3, "camera should be behind (west of) the player at tick " + key.tick());
			assertEquals(0, key.position().z(), 0.01, "camera should be right behind, not to the side");
			assertEquals(-90, key.yaw(), 2, "camera should look east, like the player");
		}
	}

	@Test
	void chaseUsesFacingWhenStandingStill() {
		Trail trail = standingStill(0f, 100);
		List<CameraKey> keys = CameraPathBuilder.build(trail, ShotType.CHASE, 20, 80, CameraPathBuilder.Params.defaults(ShotType.CHASE));
		for (CameraKey key : keys) {
			assertTrue(key.position().z() < 10 - 3, "player faces +Z, so the camera should be north of them");
		}
	}

	@Test
	void orbitFollowsTheMovingPlayerAtAConstantDistance() {
		Trail trail = walkingEast(200);
		CameraPathBuilder.Params params = CameraPathBuilder.Params.defaults(ShotType.ORBIT);
		List<CameraKey> keys = CameraPathBuilder.build(trail, ShotType.ORBIT, 40, 160, params);
		for (CameraKey key : keys) {
			Vec aim = CameraPathBuilder.aimAt(trail, key.tick());
			assertEquals(params.distance(), key.position().sub(aim).horizontalLength(), 0.01);
		}
		double first = keys.getFirst().yaw();
		double last = keys.getLast().yaw();
		assertEquals(params.sweep(), Math.abs(last - first), 5, "orbit should sweep the configured angle");
	}

	@Test
	void staticCameraDoesNotMoveButTurns() {
		Trail trail = walkingEast(200);
		List<CameraKey> keys = CameraPathBuilder.build(trail, ShotType.STATIC_LOOKAT, 40, 100, CameraPathBuilder.Params.defaults(ShotType.STATIC_LOOKAT));
		Vec first = keys.getFirst().position();
		for (CameraKey key : keys) {
			assertEquals(0, key.position().distance(first), 1e-9);
		}
		assertTrue(Math.abs(keys.getLast().yaw() - keys.getFirst().yaw()) > 10, "camera should turn to follow the player");
	}

	@Test
	void keyframesCoverTheWholeShot() {
		Trail trail = walkingEast(200);
		List<CameraKey> keys = CameraPathBuilder.build(trail, ShotType.SIDE, 40, 157, CameraPathBuilder.Params.defaults(ShotType.SIDE));
		assertEquals(40, keys.getFirst().tick());
		assertEquals(157, keys.getLast().tick());
	}

	/** Walks east for 100 ticks, then the given second part. */
	private static Trail eastThen(double vxAfter, float yawAfter) {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= 100; t += 2) {
			samples.add(new TrailSample(t, t * 0.2, 64, 0, -90f, 0f, TrailSample.FLAG_ON_GROUND));
		}
		for (int t = 102; t <= 240; t += 2) {
			samples.add(new TrailSample(t, 20 + (t - 100) * vxAfter, 64, 0, yawAfter, 0f, TrailSample.FLAG_ON_GROUND));
		}
		return new Trail(samples);
	}

	private static void assertSmooth(List<CameraKey> keys) {
		for (int i = 1; i < keys.size(); i++) {
			CameraKey a = keys.get(i - 1);
			CameraKey b = keys.get(i);
			double seconds = (b.tick() - a.tick()) / 20.0;
			double yawPerSecond = Math.abs(b.yaw() - a.yaw()) / seconds;
			double blocksPerSecond = b.position().distance(a.position()) / seconds;
			assertTrue(yawPerSecond <= 80, "camera turned " + yawPerSecond + " deg/s at tick " + b.tick());
			assertTrue(blocksPerSecond <= 10, "camera moved " + blocksPerSecond + " blocks/s at tick " + b.tick());
		}
	}

	@Test
	void chaseDoesNotSnapWhenThePlayerStopsAndTurns() {
		// Stops dead after walking east and looks north (yaw 180).
		Trail trail = eastThen(0, 180f);
		List<CameraKey> keys = CameraPathBuilder.build(trail, ShotType.CHASE, 40, 220, CameraPathBuilder.Params.defaults(ShotType.CHASE));
		assertSmooth(keys);
	}

	@Test
	void chaseDoesNotSnapWhenThePlayerTurnsAround() {
		// Walks back west at the same speed.
		Trail trail = eastThen(-0.2, 90f);
		List<CameraKey> keys = CameraPathBuilder.build(trail, ShotType.CHASE, 40, 220, CameraPathBuilder.Params.defaults(ShotType.CHASE));
		assertSmooth(keys);
	}

	@Test
	void aSwingAroundThePlayerStartsAndStopsGently() {
		// The player stops and looks north: the chase camera swings around them, but eases into it and out of it.
		Trail trail = eastThen(0, 180f);
		List<CameraKey> keys = CameraPathBuilder.build(trail, ShotType.CHASE, 40, 220, CameraPathBuilder.Params.defaults(ShotType.CHASE));
		double previousSpeed = 0;
		double fastest = 0;
		for (int i = 1; i < keys.size(); i++) {
			double seconds = (keys.get(i).tick() - keys.get(i - 1).tick()) / 20.0;
			double speed = Math.abs(keys.get(i).yaw() - keys.get(i - 1).yaw()) / seconds;
			fastest = Math.max(fastest, speed);
			assertTrue(Math.abs(speed - previousSpeed) / seconds <= 250,
				"the swing jumped from " + previousSpeed + " to " + speed + " deg/s at tick " + keys.get(i).tick());
			previousSpeed = speed;
		}
		assertTrue(fastest > 20, "test setup: the camera should swing around at all");
	}

	@Test
	void neverRunsAheadOfThePlayer() {
		// Flies east and stops dead at a tree (x = 20) at tick 100, still facing east.
		Trail trail = eastThen(0, -90f);
		List<CameraKey> keys = CameraPathBuilder.build(trail, ShotType.CHASE, 40, 200, CameraPathBuilder.Params.defaults(ShotType.CHASE));
		for (CameraKey key : keys) {
			Vec player = trail.positionAt(key.tick());
			assertTrue(key.position().x() < player.x() - 3, "camera got ahead of the player at tick " + key.tick());
			assertTrue(CameraPathBuilder.aimAt(trail, key.tick()).x() <= player.x() + 1e-9, "aim anticipates the future at tick " + key.tick());
		}
	}

	@Test
	void sideShotDoesNotSnapEither() {
		Trail trail = eastThen(0, 180f);
		List<CameraKey> keys = CameraPathBuilder.build(trail, ShotType.SIDE, 40, 220, CameraPathBuilder.Params.defaults(ShotType.SIDE));
		assertSmooth(keys);
	}

	@Test
	void followAnglesRespectsTheSpeedLimit() {
		List<Integer> ticks = List.of(0, 5, 10, 15, 20);
		double[] out = CameraPathBuilder.followAngles(ticks, new double[]{0, 180, 180, 180, 180}, 0.5,
			CameraPathBuilder.MAX_HEADING_DEGREES_PER_SECOND);
		for (int i = 1; i < out.length; i++) {
			assertTrue(Math.abs(out[i] - out[i - 1]) <= CameraPathBuilder.MAX_HEADING_DEGREES_PER_SECOND * 0.25 + 1e-9);
		}
	}

	@Test
	void splitsAtRespawnsAndDimensionChanges() {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= 100; t += 2) {
			samples.add(new TrailSample(t, t * 0.1, 64, 0, 0f, 0f, 0));
		}
		for (int t = 102; t <= 200; t += 2) { // respawned 500 blocks away
			samples.add(new TrailSample(t, 500 + t * 0.1, 70, 0, 0f, 0f, 0));
		}
		for (int t = 202; t <= 300; t += 2) { // walked into a Nether portal
			samples.add(new TrailSample(t, 500 + t * 0.1, 70, 0, 0f, 0f, 1 << 8));
		}
		Trail trail = new Trail(samples);
		List<int[]> ranges = trail.continuousRanges(0, 300, 20);
		assertEquals(3, ranges.size());
		assertEquals(0, ranges.get(0)[0]);
		assertEquals(100, ranges.get(0)[1]);
		assertEquals(102, ranges.get(1)[0]);
		assertEquals(200, ranges.get(1)[1]);
		assertEquals(202, ranges.get(2)[0]);
		assertEquals(300, ranges.get(2)[1]);
	}

	/** Dies, lies still for two seconds and respawns at a bed three blocks away: too close for a teleport, still a cut. */
	private static Trail deathAndRespawn(boolean marked) {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= 100; t += 2) {
			samples.add(new TrailSample(t, t * 0.1, 64, 0, 0f, 0f, TrailSample.FLAG_ON_GROUND));
		}
		for (int t = 102; t <= 140; t += 2) {
			samples.add(new TrailSample(t, 10, 64, 0, 0f, 0f, TrailSample.FLAG_ON_GROUND | (marked ? TrailSample.FLAG_DEAD : 0)));
		}
		for (int t = 142; t <= 240; t += 2) {
			samples.add(new TrailSample(t, 12 + (t - 142) * 0.1, 64, 2, 0f, 0f, TrailSample.FLAG_ON_GROUND));
		}
		return new Trail(samples);
	}

	@Test
	void aRespawnNextToTheBodyIsACut() {
		List<int[]> ranges = deathAndRespawn(true).continuousRanges(0, 240, 20);
		assertEquals(2, ranges.size());
		assertEquals(140, ranges.get(0)[1]);
		assertEquals(142, ranges.get(1)[0]);
		// an old recording does not know when the player was dead: the respawn is found when asked about a death
		assertEquals(1, deathAndRespawn(false).continuousRanges(0, 240, 20).size());
		assertEquals(142, deathAndRespawn(true).respawnAfter(100, 240));
		assertEquals(142, deathAndRespawn(false).respawnAfter(100, 240));
		assertEquals(-1, deathAndRespawn(false).respawnAfter(100, 141));
		assertEquals(-1, walkingEast(400).respawnAfter(100, 400), "walking on is not a respawn");
	}

	@Test
	void aPieceStartingRightAfterATeleportWaitsForThePlayerToShowUp() {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= 100; t += 2) {
			samples.add(new TrailSample(t, 0, t < 50 ? 64 : 140, 0, 0f, 0f, 0));
		}
		Trail trail = new Trail(samples);
		assertEquals(50 + Trail.SETTLE_TICKS, trail.settledStart(50));
		assertEquals(50 + Trail.SETTLE_TICKS, trail.settledStart(52), "teleported just before the start");
		assertEquals(60, trail.settledStart(60));
		assertEquals(30, trail.settledStart(30));
	}

	@Test
	void aStaticCameraComesDownWithAFallingPlayer() {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= 120; t += 2) {
			double y = t < 20 ? 110 : Math.max(64, 110 - 0.02 * (t - 20) * (t - 20)); // falls 46 blocks, then lies there
			samples.add(new TrailSample(t, 0, y, 0, 0f, 0f, y <= 64 ? TrailSample.FLAG_ON_GROUND : 0));
		}
		Trail trail = new Trail(samples);
		CameraPathBuilder.Params params = CameraPathBuilder.Params.defaults(ShotType.STATIC_LOOKAT);
		List<CameraKey> keys = CameraPathBuilder.build(trail, ShotType.STATIC_LOOKAT, 0, 120, params, 70);
		CameraKey last = keys.getLast();
		Vec body = CameraPathBuilder.aimAt(trail, last.tick());
		assertTrue(last.position().distance(body) < params.distance() + 3, "camera " + last.position().distance(body) + " blocks from the body");
		assertEquals(params.distance(), last.position().sub(body).horizontalLength(), 1e-6);
	}

	@Test
	void aStaticCameraIsCarriedAfterAPlayerWhoWalksFarAway() {
		Trail trail = walkingEast(600);
		CameraPathBuilder.Params params = CameraPathBuilder.Params.defaults(ShotType.STATIC_LOOKAT);
		List<CameraKey> keys = CameraPathBuilder.build(trail, ShotType.STATIC_LOOKAT, 0, 600, params);
		for (CameraKey key : keys) {
			double d = key.position().sub(CameraPathBuilder.aimAt(trail, key.tick())).horizontalLength();
			assertTrue(d <= params.distance() * CameraPathBuilder.TRIPOD_LEASH + 2, "camera " + d + " blocks away at " + key.tick());
		}
		assertSmooth(keys);
	}

	@Test
	void walkingIsNotATeleport() {
		Trail trail = walkingEast(400);
		List<int[]> ranges = trail.continuousRanges(0, 400, 20);
		assertEquals(1, ranges.size());
	}

	private static Trail line(double x0, double x1, int ticks) {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= ticks; t += 2) {
			double x = x0 + (x1 - x0) * t / ticks;
			samples.add(new TrailSample(t, x, 64, 0, 0f, 0f, TrailSample.FLAG_ON_GROUND));
		}
		return new Trail(samples);
	}

	private static double angleBetween(Vec a, Vec b) {
		double dot = (a.x() * b.x() + a.y() * b.y() + a.z() * b.z()) / (a.length() * b.length());
		return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
	}

	@Test
	void twoShotKeepsBothFriendsInFrame() {
		Trail steve = line(0, 0, 200);
		Trail alex = line(4, 4, 200);
		List<CameraKey> keys = CameraPathBuilder.buildTwoShot(steve, alex, 20, 180, CameraPathBuilder.Params.defaults(ShotType.TWO_SHOT));
		assertFalse(keys.isEmpty());
		for (CameraKey key : keys) {
			Vec toSteve = CameraPathBuilder.aimAt(steve, key.tick()).sub(key.position());
			Vec toAlex = CameraPathBuilder.aimAt(alex, key.tick()).sub(key.position());
			assertTrue(angleBetween(toSteve, toAlex) <= 60, "friends too far apart in the frame at tick " + key.tick());
			assertTrue(Math.abs(key.position().z()) >= 4, "camera should stand to the side of them");
			Vec look = Vec.fromYaw(key.yaw());
			Vec toMiddle = new Vec(2, 0, 0).sub(key.position()).horizontal();
			assertTrue(angleBetween(look, toMiddle) < 10, "camera should look between them");
		}
	}

	@Test
	void twoShotDoesNotJumpWhenTheyWalkPastEachOther() {
		Trail steve = line(-6, 6, 300);
		Trail alex = line(6, -6, 300);
		List<CameraKey> keys = CameraPathBuilder.buildTwoShot(steve, alex, 20, 280, CameraPathBuilder.Params.defaults(ShotType.TWO_SHOT));
		double side = Math.signum(keys.getFirst().position().z());
		for (CameraKey key : keys) {
			assertEquals(side, Math.signum(key.position().z()), "camera jumped to the other side at tick " + key.tick());
		}
	}

	/** Where a camera key looks (Minecraft yaw and pitch). */
	private static Vec view(CameraKey key) {
		double y = Math.toRadians(key.yaw());
		double p = Math.toRadians(key.pitch());
		return new Vec(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
	}

	/** A boss sampled every 10 ticks, like the replay is asked for it. */
	private static Trail boss(java.util.function.IntFunction<Vec> at, int ticks) {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= ticks; t += 10) {
			Vec p = at.apply(t);
			samples.add(new TrailSample(t, p.x(), p.y(), p.z(), 0, 0, 0));
		}
		return new Trail(samples);
	}

	@Test
	void overTheShoulderHasThePlayerInFrontAndTheBossBeyond() {
		Trail player = standingStill(0, 200);
		// the dragon 30 blocks north and 20 up, drifting east
		Trail dragon = boss(t -> new Vec(10 + t * 0.05, 85, -20), 200);
		List<CameraKey> keys = CameraPathBuilder.buildOverShoulder(player, dragon, 20, 180,
			CameraPathBuilder.Params.defaults(ShotType.OVER_SHOULDER));
		assertFalse(keys.isEmpty());
		for (CameraKey key : keys) {
			Vec aim = CameraPathBuilder.aimAt(player, key.tick());
			assertTrue(key.position().z() > aim.z() + 3, "camera should stand south, away from the dragon, at tick " + key.tick());
			assertTrue(angleBetween(view(key), aim.sub(key.position())) <= 30, "player out of the picture at tick " + key.tick());
			assertTrue(angleBetween(view(key), dragon.positionAt(key.tick()).sub(key.position())) <= 33,
				"dragon out of the picture at tick " + key.tick());
		}
	}

	@Test
	void overTheShoulderStepsBackFromABossRisingOverhead() {
		Trail player = standingStill(0, 220);
		// a dying dragon two blocks east of the player, rising from 5 to 27 blocks above them
		Trail dragon = boss(t -> new Vec(12, 69 + t * 0.1, 10), 220);
		List<CameraKey> keys = CameraPathBuilder.buildOverShoulder(player, dragon, 0, 220,
			CameraPathBuilder.Params.defaults(ShotType.OVER_SHOULDER));
		double firstDistance = keys.getFirst().position().distance(CameraPathBuilder.aimAt(player, 0));
		double lastDistance = keys.getLast().position().distance(CameraPathBuilder.aimAt(player, 220));
		assertTrue(lastDistance > firstDistance + 3, "camera should step back as the dragon rises: " + firstDistance + " -> " + lastDistance);
		for (CameraKey key : keys) {
			Vec aim = CameraPathBuilder.aimAt(player, key.tick());
			assertTrue(angleBetween(view(key), aim.sub(key.position())) <= 23, "player out of the picture at tick " + key.tick());
			if (key.tick() <= 120) {
				assertTrue(angleBetween(view(key), dragon.positionAt(key.tick()).sub(key.position())) <= 33,
					"dragon out of the picture at tick " + key.tick());
			}
		}
	}

	@Test
	void unwrapNeverJumpsMoreThanHalfATurn() {
		List<CameraKey> keys = List.of(
			new CameraKey(0, Vec.ZERO, 170, 0),
			new CameraKey(5, Vec.ZERO, -175, 0),
			new CameraKey(10, Vec.ZERO, -160, 0),
			new CameraKey(15, Vec.ZERO, 179, 0));
		List<CameraKey> unwrapped = CameraPathBuilder.unwrapYaw(keys);
		for (int i = 1; i < unwrapped.size(); i++) {
			assertTrue(Math.abs(unwrapped.get(i).yaw() - unwrapped.get(i - 1).yaw()) <= 180);
		}
		assertEquals(185, unwrapped.get(1).yaw(), 1e-4);
	}
}
