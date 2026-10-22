package com.survivalcinema.director.camera;

import com.survivalcinema.director.render.ClipCamera;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The camera on real terrain: scenes saved by {@code gradlew runFilmTest -Preshoot=...} from a player's film (the
 * blocks around the player, their path and the shot), in {@code src/test/resources/camera}.
 */
class RealTerrainCameraTest {
	static CameraScene scene(String name) throws IOException {
		try (InputStream in = RealTerrainCameraTest.class.getResourceAsStream("/camera/" + name + ".scene.gz")) {
			assertNotNull(in, "scene " + name);
			return CameraScene.read(in);
		}
	}

	static List<CameraKey> shoot(CameraScene scene) {
		return ClipCamera.build(scene.item, new ClipCamera.Piece(0, scene.fromTick, scene.toTick), scene.trail, null, scene).camera();
	}

	static CameraReport report(CameraScene scene, List<CameraKey> keys) {
		return CameraReport.of(scene.trail.slice(scene.fromTick, scene.toTick), keys, scene);
	}

	/** The shot as planned, before obstacles are taken into account. */
	static List<CameraKey> planned(CameraScene scene) {
		CameraPathBuilder.Params params = new CameraPathBuilder.Params(scene.item.distance(), scene.item.height(),
			scene.item.sweep(), scene.item.side() < 0 ? -1 : 1, CameraPathBuilder.Params.defaults(scene.item.shot()).step());
		return CameraPathBuilder.build(scene.trail.slice(scene.fromTick, scene.toTick), scene.item.shot(), scene.fromTick,
			scene.toTick, params);
	}

	/**
	 * Around the player and through it all, the camera keeps them in view, keeps leaves away from the lens and moves
	 * like a camera operator would: no jumps, no sudden swings.
	 */
	private static void assertFilmedWell(String name) throws IOException {
		CameraScene scene = scene(name);
		CameraReport plan = report(scene, planned(scene));
		assertTrue(plan.hidden() >= 0.3, "test setup: obstacles should hide the player from the planned shot, " + plan);

		CameraReport shot = report(scene, shoot(scene));
		assertTrue(shot.hidden() <= 0.05, "player hidden too often: " + shot);
		assertTrue(shot.covered() <= 0.15, "leaves at the lens too often: " + shot);
		assertTrue(shot.clutter() <= 0.05, "picture cluttered: " + shot);
		assertTrue(shot.maxSpeed() <= 6, "camera rushes: " + shot);
		assertTrue(shot.maxAccel() <= 20, "camera jerks: " + shot);
		assertTrue(shot.maxTurn() <= 60, "view swings too fast: " + shot);
		assertTrue(shot.maxTurnAccel() <= 250, "view snaps: " + shot);
	}

	/** The shot a player reported: an orbit around them among oaks and birches, which jumped around the trunks. */
	@Test
	void orbitAmongTrees() throws IOException {
		assertFilmedWell("forest-orbit");
	}

	/** Pulling back from a player who just came through a portal, into the Nether's ceiling and walls. */
	@Test
	void pullbackInTheNether() throws IOException {
		assertFilmedWell("nether-pullback");
	}

	/** Pulling back from a player at the shore, with trees around. */
	@Test
	void pullbackAtTheShore() throws IOException {
		assertFilmedWell("shore-pullback");
	}

	/**
	 * The dragon finale of the bot's playthrough: the player floats down onto the End island next to a slope of end
	 * stone. The orbit moved in until the face filled the picture (1.3 blocks, the name above the head as big as the
	 * screen); now it rises or goes around the slope, and never comes closer than {@link OcclusionSolver#MIN_DISTANCE}.
	 */
	@Test
	void dragonFinaleOrbitWithoutACloseUp() throws IOException {
		CameraScene scene = scene("end-dragon");
		List<CameraKey> keys = shoot(scene);
		Trail trail = scene.trail.slice(scene.fromTick, scene.toTick);
		for (CameraKey key : keys) {
			double distance = key.position().distance(CameraPathBuilder.aimAt(trail, key.tick()));
			assertTrue(distance >= OcclusionSolver.MIN_DISTANCE - 1e-6, "close-up at tick " + key.tick() + ": " + distance);
		}
		CameraReport shot = report(scene, keys);
		assertTrue(shot.hidden() <= 0.05, "player hidden too often: " + shot);
		assertTrue(shot.maxSpeed() <= 6, "camera rushes: " + shot);
		assertTrue(shot.maxTurn() <= 60, "view swings too fast: " + shot);
	}

	/**
	 * The same finale as the game films it once the replay has shown where the dragon is: from behind the player,
	 * over the shoulder, with the dying dragon in the picture.
	 */
	@Test
	void dragonFinaleHasTheDragonInThePicture() throws IOException {
		CameraScene scene = scene("end-dragon");
		assertNotNull(scene.boss, "test setup: the scene knows where the dragon was");
		ClipCamera.Keys keys = ClipCamera.build(scene.item, new ClipCamera.Piece(0, scene.fromTick, scene.toTick), scene.trail,
			null, scene.boss, scene);
		assertEquals(ShotType.OVER_SHOULDER, keys.shot());
		Trail trail = scene.trail.slice(scene.fromTick, scene.toTick);
		int dragonSeen = 0;
		for (CameraKey key : keys.camera()) {
			Vec aim = CameraPathBuilder.aimAt(trail, key.tick());
			Vec view = direction(key);
			assertTrue(key.position().distance(aim) >= OcclusionSolver.MIN_DISTANCE - 1e-6, "close-up at tick " + key.tick());
			assertTrue(view.angleTo(aim.sub(key.position())) <= 30, "player out of the picture at tick " + key.tick());
			if (view.angleTo(scene.boss.positionAt(key.tick()).sub(key.position())) <= 33) {
				dragonSeen++;
			}
		}
		assertTrue(dragonSeen >= 0.8 * keys.camera().size(), "dragon in the picture in " + dragonSeen + " of "
			+ keys.camera().size() + " keys");
		CameraReport shot = report(scene, keys.camera());
		assertTrue(shot.hidden() <= 0.05, "player hidden too often: " + shot);
		assertTrue(shot.maxSpeed() <= 6, "camera rushes: " + shot);
		assertTrue(shot.maxTurn() <= 60, "view swings too fast: " + shot);
	}

	/**
	 * The bot's death in its playthrough: teleported up, it falls 45 blocks next to its house, dies, and respawns at
	 * its bed 8 blocks away. The static camera stayed at the top of the fall and filmed the body from 48 blocks above;
	 * then the respawn, too close to count as a teleport, swung it round to the bed. Now the camera comes down with the
	 * fall and the clip ends with the body, before the respawn.
	 */
	@Test
	void aFatalFallEndsOnTheBody() throws IOException {
		CameraScene scene = scene("fall-death");
		List<ClipCamera.Piece> pieces = ClipCamera.pieces(scene.item, scene.trail);
		assertEquals(1, pieces.size());
		ClipCamera.Piece piece = pieces.getFirst();
		assertEquals(1110 + Trail.SETTLE_TICKS, piece.fromTick(), "starts once the replay shows the bot up there");
		assertTrue(piece.toTick() < 1190 && piece.toTick() >= 1186, "ends with the body, before the respawn: " + piece.toTick());

		List<CameraKey> keys = ClipCamera.build(scene.item, piece, scene.trail, null, scene).camera();
		Trail trail = scene.trail.slice(piece.fromTick(), piece.toTick());
		for (CameraKey key : keys) {
			double distance = key.position().distance(CameraPathBuilder.aimAt(trail, key.tick()));
			assertTrue(distance <= 14, "camera " + distance + " blocks from the bot at tick " + key.tick());
		}
		CameraKey last = keys.getLast();
		assertTrue(scene.clearFraction(CameraPathBuilder.aimAt(trail, last.tick()), last.position()) >= 0.995, "the body is in view at the end");
		CameraReport shot = CameraReport.of(trail, keys, scene);
		assertTrue(shot.hidden() <= 0.1, "bot hidden too often (the camera comes down past the house): " + shot);
		assertTrue(shot.maxTurn() <= 60, "view swings too fast: " + shot);
	}

	/**
	 * The bot's elytra flight: teleported 70 blocks up, it falls and opens the wings. The clip started on the teleport,
	 * while the replay still drew the bot on its way up, so the first frames were empty sky and the bot popped in.
	 */
	@Test
	void aFlightAfterATeleportOpensOnThePlayer() throws IOException {
		CameraScene scene = scene("elytra-takeoff");
		List<ClipCamera.Piece> pieces = ClipCamera.pieces(scene.item, scene.trail);
		assertEquals(290 + Trail.SETTLE_TICKS, pieces.getFirst().fromTick());
		ClipCamera.Piece piece = pieces.getFirst();
		List<CameraKey> keys = ClipCamera.build(scene.item, piece, scene.trail, null, scene).camera();
		CameraReport shot = CameraReport.of(scene.trail.slice(piece.fromTick(), piece.toTick()), keys, scene);
		assertTrue(shot.hidden() <= 0.05, "player hidden too often: " + shot);
		assertTrue(shot.maxSpeed() <= 6, "camera rushes: " + shot);
		assertTrue(shot.maxTurn() <= 60, "view swings too fast: " + shot);
	}

	/**
	 * The bot going to bed in its house of three-block walls without a roof. The orbit passed right over the walls,
	 * where the replay viewer whose eyes are the camera stood in the wall: the game laid it down and the picture
	 * dropped onto the planks. Getting into the bed moved the bot a block and a half at once, and the camera jumped
	 * with it. Now the camera keeps the viewer out of the walls, keeps the body (not just the head) in view, and
	 * glides after the bed.
	 */
	@Test
	void anOrbitAroundABedInAHouseShowsTheBotNotTheWalls() throws IOException {
		CameraScene scene = scene("house-bed-orbit");
		CameraReport before = report(scene, scene.savedKeys);
		assertTrue(before.viewerInside() >= 0.1, "test setup: the bot's film had the camera's viewer in a wall, " + before);
		List<CameraKey> keys = shoot(scene);
		CameraReport shot = report(scene, keys);
		assertTrue(shot.hidden() <= 0.05, "bot hidden too often: " + shot);
		assertTrue(shot.bodyHidden() <= 0.1, "only the head over a wall: " + shot);
		assertEquals(0, shot.viewerInside(), "the viewer in a wall: " + shot);
		assertTrue(shot.maxTurn() <= 60, "view swings too fast: " + shot);
		assertTrue(fastest(keys) <= 6, "the camera jumped (getting into bed): " + fastest(keys) + " blocks/s");
	}

	/** The fastest the camera moves through the world between two keys, blocks per second. */
	private static double fastest(List<CameraKey> keys) {
		double fastest = 0;
		for (int i = 1; i < keys.size(); i++) {
			double seconds = (keys.get(i).tick() - keys.get(i - 1).tick()) / 20.0;
			fastest = Math.max(fastest, keys.get(i).position().distance(keys.get(i - 1).position()) / seconds);
		}
		return fastest;
	}

	/**
	 * The bot wakes up: the orbit round its bed cut to a static shot of the same moment from elsewhere, and the picture
	 * jumped. Now the static shot takes over without a cut: it starts where the orbit ended and glides round the bot
	 * to its own place, then stands still while the bot gets up.
	 */
	@Test
	void theShotAfterTheOrbitTakesOverWithoutACut() throws IOException {
		CameraScene orbit = scene("house-bed-orbit");
		CameraScene wake = scene("house-wake-static");
		CameraKey orbitEnd = shoot(orbit).getLast();
		ClipCamera.Piece piece = new ClipCamera.Piece(0, wake.fromTick, wake.toTick);
		List<CameraKey> alone = ClipCamera.build(wake.item, piece, wake.trail, null, null, wake).camera();
		assertTrue(alone.getFirst().position().distance(orbitEnd.position()) > 2, "test setup: the static shot stands elsewhere");
		List<CameraKey> keys = ClipCamera.build(wake.item, piece, wake.trail, null, null, wake, orbitEnd).camera();
		assertTrue(keys.getFirst().position().distance(orbitEnd.position()) < 0.3, "starts where the orbit ended: "
			+ keys.getFirst().position() + " vs " + orbitEnd.position());
		assertEquals(alone.getLast().position().distance(keys.getLast().position()), 0, 1e-6, "and ends where the static shot stands");
		assertTrue(fastest(keys) <= 6, "the glide rushes: " + fastest(keys) + " blocks/s");
		double turn = 0;
		for (int i = 1; i < keys.size(); i++) {
			double seconds = (keys.get(i).tick() - keys.get(i - 1).tick()) / 20.0;
			turn = Math.max(turn, Math.abs(keys.get(i).yaw() - keys.get(i - 1).yaw()) / seconds);
		}
		assertTrue(turn <= 60, "the view swings: " + turn + " degrees/s");
		CameraReport shot = report(wake, keys);
		assertTrue(shot.hidden() <= 0.1, "bot in view: " + shot);
		// after the glide the camera stands still
		List<CameraKey> rest = keys.subList(keys.size() / 2, keys.size());
		assertTrue(fastest(rest) <= 0.01, "the static camera moved: " + fastest(rest) + " blocks/s");
	}

	/** Where a camera key looks (Minecraft yaw and pitch). */
	private static Vec direction(CameraKey key) {
		double y = Math.toRadians(key.yaw());
		double p = Math.toRadians(key.pitch());
		return new Vec(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
	}

	/**
	 * Following the player through a tunnel only takes over where the camera would be inside a block: in every saved
	 * scene, each key with the camera in the open (and not right next to one inside a block) is exactly what it was.
	 */
	@Test
	void followingThroughTunnelsChangesNothingInTheOpen() throws IOException {
		for (String name : List.of("forest-orbit", "shore-pullback", "house-bed-orbit", "house-wake-static", "nether-pullback",
			"end-dragon", "elytra-takeoff", "fall-death")) {
			CameraScene scene = scene(name);
			Trail slice = scene.trail.slice(scene.fromTick, scene.toTick);
			List<CameraKey> planned = planned(scene);
			boolean tripod = scene.item.shot() == ShotType.STATIC_LOOKAT;
			List<CameraKey> without = OcclusionSolver.solve(slice, planned, scene, tripod, scene.trail, null, false);
			List<CameraKey> with = OcclusionSolver.solve(slice, planned, scene, tripod, scene.trail, null, true);
			for (int i = 0; i < with.size(); i++) {
				int tick = with.get(i).tick();
				boolean nearBuried = false;
				for (CameraKey other : without) {
					nearBuried |= Math.abs(other.tick() - tick) <= 8 && OcclusionSolver.insideBlock(other.position(), scene);
				}
				if (!nearBuried) {
					assertEquals(0, with.get(i).position().distance(without.get(i).position()), 1e-9, name + " moved at tick " + tick);
				}
			}
		}
	}

	/**
	 * The bot walks out of its house through the door, filmed from outside, above the wall. Pulled in to keep them in
	 * sight, the camera touched the top of the wall for a key or two, and following through tunnels took those keys to
	 * behind the player, across the house, and straight back: a jerk ("everything very sharp" at the door).
	 */
	@Test
	void walkingOutOfTheHouseHasNoJerk() throws IOException {
		assertNoJerk(shoot(scene("house-door-exit")));
	}

	/**
	 * A side shot of a zombie fight goes on from an orbit of the same fight, gliding round the player from where the
	 * orbit left off. The place it glides to drifted past straight across from the start, the shorter way round flipped
	 * and the camera jumped to the other side of the player (0:20 of the bot's film).
	 */
	@Test
	void aShotGoingOnFromAnotherNeverJumpsAcross() throws IOException {
		CameraKey before = scene("zombies-orbit").savedKeys.getLast();
		CameraScene scene = scene("zombies-side");
		List<CameraKey> keys = ClipCamera.glideFrom(before, shoot(scene), scene.trail);
		assertEquals(0, keys.getFirst().position().distance(before.position()), 1e-6, "sets off from where the orbit left off");
		assertEquals(before.pitch(), keys.getFirst().pitch(), 1e-3, "and looks where it looked");
		assertNoJerk(keys);
	}

	/**
	 * In the Nether the bot digs out ancient debris, filmed from a tripod, then walks back to the portal: the next shot
	 * went on from the tripod, gliding round the bot through the netherrack, and the picture showed the caves through
	 * the stone for a second. It cuts instead; nowhere is the camera inside a block.
	 */
	@Test
	void aGlideNeverGoesThroughRock() throws IOException {
		CameraKey before = scene("nether-debris-static").savedKeys.getLast();
		CameraScene scene = scene("nether-to-portal-pullback");
		List<CameraKey> keys = ClipCamera.build(scene.item, new ClipCamera.Piece(0, scene.fromTick, scene.toTick), scene.trail,
			null, null, scene, before).camera();
		for (CameraKey key : keys) {
			assertFalse(OcclusionSolver.insideBlock(key.position(), scene), "camera in the rock at tick " + key.tick());
		}
		List<CameraKey> glided = ClipCamera.glideFrom(before, shoot(scene), scene.trail);
		assertTrue(glided.stream().anyMatch(k -> OcclusionSolver.insideBlock(k.position(), scene)),
			"test setup: the glide went through the rock");
	}

	private static void assertNoJerk(List<CameraKey> keys) {
		for (int i = 1; i < keys.size(); i++) {
			CameraKey last = keys.get(i - 1);
			CameraKey key = keys.get(i);
			double seconds = (key.tick() - last.tick()) / 20.0;
			double speed = key.position().distance(last.position()) / seconds;
			double turn = Math.toDegrees(Math.acos(Math.min(1, direction(key).dot(direction(last))))) / seconds;
			assertTrue(speed < 12, "camera jumps " + speed + " blocks a second at tick " + key.tick());
			assertTrue(turn < 360, "view snaps " + turn + " degrees a second at tick " + key.tick());
		}
	}
}
