package com.survivalcinema.director.camera;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcclusionSolverTest {
	/** A probe made of solid axis-aligned boxes; the line is sampled every 5 cm. */
	private record Boxes(List<double[]> boxes) implements BlockProbe {
		@Override
		public double clearFraction(Vec from, Vec to) {
			int steps = Math.max(1, (int) (from.distance(to) / 0.05));
			for (int i = 0; i <= steps; i++) {
				double t = i / (double) steps;
				Vec p = from.lerp(to, t);
				for (double[] b : boxes) {
					if (p.x() >= b[0] && p.x() <= b[3] && p.y() >= b[1] && p.y() <= b[4] && p.z() >= b[2] && p.z() <= b[5]) {
						return t;
					}
				}
			}
			return 1;
		}
	}

	/** Player standing at the origin facing +Z, so a chase camera sits north of them (negative Z). */
	private static Trail standingAtOrigin() {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= 100; t += 2) {
			samples.add(new TrailSample(t, 0, 64, 0, 0f, 0f, TrailSample.FLAG_ON_GROUND));
		}
		return new Trail(samples);
	}

	private static List<CameraKey> chase(Trail trail) {
		return CameraPathBuilder.build(trail, ShotType.CHASE, 10, 90, CameraPathBuilder.Params.defaults(ShotType.CHASE));
	}

	private static void assertPlayerVisible(Trail trail, List<CameraKey> keys, BlockProbe probe) {
		for (CameraKey key : keys) {
			Vec aim = CameraPathBuilder.aimAt(trail, key.tick());
			assertTrue(probe.clearFraction(aim, key.position()) >= 0.99, "player hidden at tick " + key.tick());
		}
	}

	@Test
	void leavesAClearShotAlone() {
		Trail trail = standingAtOrigin();
		List<CameraKey> keys = chase(trail);
		List<CameraKey> solved = OcclusionSolver.solve(trail, keys, BlockProbe.EMPTY);
		for (int i = 0; i < keys.size(); i++) {
			assertEquals(0, keys.get(i).position().distance(solved.get(i).position()), 1e-9);
		}
	}

	@Test
	void swingsAroundATreeBehindThePlayer() {
		Trail trail = standingAtOrigin();
		// A tree trunk with leaves right between the player and the chase camera.
		BlockProbe tree = new Boxes(List.of(new double[]{-1, 60, -3, 1, 72, -2}));
		List<CameraKey> solved = OcclusionSolver.solve(trail, chase(trail), tree);
		assertPlayerVisible(trail, solved, tree);
		for (CameraKey key : solved) {
			Vec aim = CameraPathBuilder.aimAt(trail, key.tick());
			assertTrue(key.position().distance(aim) > 3.5, "should go around the tree, not hug the player");
		}
	}

	@Test
	void movesInCloseInsideACave() {
		Trail trail = standingAtOrigin();
		// Rock everywhere further than 3 blocks from the player, in every direction.
		BlockProbe cave = (from, to) -> {
			double distance = from.distance(to);
			return distance <= 3 ? 1 : 3 / distance;
		};
		List<CameraKey> solved = OcclusionSolver.solve(trail, chase(trail), cave);
		assertPlayerVisible(trail, solved, cave);
		for (CameraKey key : solved) {
			Vec aim = CameraPathBuilder.aimAt(trail, key.tick());
			assertTrue(key.position().distance(aim) <= 3.0);
		}
	}

	/**
	 * A staircase mine dug into solid rock during the shot (the playthrough bot's: three blocks out of each column, a
	 * step down each time). The camera is planned against the world at the shot's start and at its end, blocked where
	 * either has a block, so the whole mine counts as rock: the camera stayed in it and the picture showed the caves
	 * through the stone. It follows the player down the stairs instead, in the air they just walked through, which it
	 * checks in the world at the end.
	 */
	@Test
	void followsThePlayerDownAStaircaseMine() {
		java.util.Set<Long> dug = new java.util.HashSet<>();
		for (int k = 1; k <= 16; k++) {
			for (int y = 64 - k; y <= 66 - k; y++) {
				dug.add(((long) k << 32) ^ (y & 0xffffffffL));
			}
		}
		BlockProbe rock = (from, to) -> {
			int steps = Math.max(1, (int) (from.distance(to) / 0.05));
			for (int i = 0; i <= steps; i++) {
				Vec p = from.lerp(to, i / (double) steps);
				int x = (int) Math.floor(p.x());
				int y = (int) Math.floor(p.y());
				boolean inColumn = (int) Math.floor(p.z()) == 0;
				if (y < 64 && !(inColumn && dug.contains(((long) x << 32) ^ (y & 0xffffffffL)))) {
					return i / (double) steps;
				}
			}
			return 1;
		};
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= 340; t += 2) {
			// digging for half a second, then a step forward and down the next half: a step every second
			int k = Math.min(16, t / 20);
			double s = k < 16 ? Math.max(0, (t % 20 - 10) / 10.0) : 0;
			samples.add(new TrailSample(t, k + 0.5 + s, 64 - k - s, 0.5, -90f, 30f, TrailSample.FLAG_ON_GROUND));
		}
		Trail trail = new Trail(samples);
		List<CameraKey> planned = CameraPathBuilder.build(trail, ShotType.ORBIT, 200, 340, CameraPathBuilder.Params.defaults(ShotType.ORBIT));
		BlockProbe undug = (from, to) -> {
			int steps = Math.max(1, (int) (from.distance(to) / 0.05));
			for (int i = 0; i <= steps; i++) {
				if (from.lerp(to, i / (double) steps).y() < 64) {
					return i / (double) steps;
				}
			}
			return 1;
		};
		List<CameraKey> solved = OcclusionSolver.solve(trail, planned, BlockProbe.both(undug, rock), false, trail, rock);
		int seen = 0;
		StringBuilder where = new StringBuilder();
		for (CameraKey key : solved) {
			// ten blocks down and more: rock all round
			Vec aim = CameraPathBuilder.aimAt(trail, key.tick());
			boolean sees = rock.clearFraction(aim, key.position()) >= 0.99;
			boolean fits = OcclusionSolver.viewerFits(key.position(), rock);
			if (sees && fits) {
				seen++;
			}
			where.append(sees ? (fits ? '.' : 'i') : (fits ? 'h' : 'X'));
		}
		assertEquals(solved.size(), seen, "the camera sees the player from the air of the tunnel: " + where);
		for (int i = 1; i < solved.size(); i++) {
			double jump = solved.get(i).position().distance(solved.get(i - 1).position());
			// a small shift where it has to get round the edge of a step as the player drops onto the next
			assertTrue(jump < 1.0, "no jumps: " + jump + " at tick " + solved.get(i).tick());
		}
	}

	/**
	 * A camera in the open with a wall between it and the player (them walking out of a house through the door) is not
	 * a camera in the rock: it does not jump to right behind their back.
	 */
	@Test
	void aCameraInTheOpenIsNoTunnel() {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= 100; t += 2) {
			samples.add(new TrailSample(t, t * 0.06, 64, 0, -90f, 0f, TrailSample.FLAG_ON_GROUND));
		}
		Trail trail = new Trail(samples);
		// a cave so tight the camera cannot see the player from any distance it may film from, but it is in the air
		BlockProbe cave = (from, to) -> {
			double distance = from.distance(to);
			return distance <= 2 ? 1 : 2 / distance;
		};
		List<CameraKey> planned = CameraPathBuilder.build(trail, ShotType.CHASE, 40, 100, CameraPathBuilder.Params.defaults(ShotType.CHASE));
		List<CameraKey> without = OcclusionSolver.solve(trail, planned, cave, false, trail, null, false);
		List<CameraKey> with = OcclusionSolver.solve(trail, planned, cave, false, trail, null, true);
		for (int i = 0; i < with.size(); i++) {
			assertEquals(0, with.get(i).position().distance(without.get(i).position()), 1e-9, "moved at tick " + with.get(i).tick());
		}
	}

	@Test
	void neverMovesInToACloseUpOfTheFace() {
		Trail trail = standingAtOrigin();
		// A cave so tight the player could only be seen from right in front of their face.
		BlockProbe cave = (from, to) -> {
			double distance = from.distance(to);
			return distance <= 1.5 ? 1 : 1.5 / distance;
		};
		for (CameraKey key : OcclusionSolver.solve(trail, chase(trail), cave)) {
			Vec aim = CameraPathBuilder.aimAt(trail, key.tick());
			assertTrue(key.position().distance(aim) >= OcclusionSolver.MIN_DISTANCE - 1e-6, "close-up at tick " + key.tick());
		}
	}

	@Test
	void movesAwayFromLeavesRightNextToTheLens() {
		Trail trail = standingAtOrigin();
		List<CameraKey> keys = chase(trail);
		Vec lens = keys.getFirst().position();
		// Leaves just beside the lens: the centre line to the player is clear, but they would fill half the frame.
		BlockProbe leaves = new Boxes(List.of(new double[]{lens.x() + 0.15, lens.y() - 0.6, lens.z() - 0.4, lens.x() + 1.0, lens.y() + 0.6, lens.z() + 0.4}));
		Vec aim = CameraPathBuilder.aimAt(trail, keys.getFirst().tick());
		assertEquals(1.0, leaves.clearFraction(aim, lens), 1e-9, "test setup: the thin centre ray must not see the leaves");
		assertTrue(OcclusionSolver.visibility(aim, lens.sub(aim), leaves) < 0.99, "test setup: the thick ray must see them");

		List<CameraKey> solved = OcclusionSolver.solve(trail, keys, leaves);
		for (CameraKey key : solved) {
			Vec a = CameraPathBuilder.aimAt(trail, key.tick());
			assertTrue(OcclusionSolver.visibility(a, key.position().sub(a), leaves) >= 0.99, "leaves still at the lens at tick " + key.tick());
		}
	}

	@Test
	void staysOnOneSideOfAWall() {
		Trail trail = standingAtOrigin();
		BlockProbe wall = new Boxes(List.of(new double[]{-0.5, 60, -6, 0.5, 72, -1.5}));
		List<CameraKey> solved = OcclusionSolver.solve(trail, chase(trail), wall);
		assertPlayerVisible(trail, solved, wall);
		double side = Math.signum(solved.getFirst().position().x());
		for (CameraKey key : solved) {
			assertEquals(side, Math.signum(key.position().x()), "camera should not flip between sides of the wall");
		}
	}
}
