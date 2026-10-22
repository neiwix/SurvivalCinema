package com.survivalcinema.director.render;

import com.survivalcinema.director.camera.BlockProbe;
import com.survivalcinema.director.camera.CameraKey;
import com.survivalcinema.director.camera.CameraPathBuilder;
import com.survivalcinema.director.camera.ShotType;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.camera.Vec;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.Shot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.survivalcinema.director.render.RenderTestData.clip;
import static com.survivalcinema.director.render.RenderTestData.walkingEast;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipCameraTest {
	private static final BlockProbe OPEN_AIR = (from, to) -> 1;

	@Test
	void aClipIsCutAtATeleport() {
		List<TrailSample> samples = new ArrayList<>(walkingEast(0, 200, 0).samples());
		for (int t = 202; t <= 400; t += 2) {
			samples.add(new TrailSample(t, 5000 + t * 0.2, 64, 0, -90f, 0f, TrailSample.FLAG_ON_GROUND));
		}
		Trail trail = new Trail(samples);
		List<ClipCamera.Piece> pieces = ClipCamera.pieces(clip("a", 0, 100, 300), trail);
		assertEquals(2, pieces.size());
		assertEquals(new ClipCamera.Piece(0, 100, 200), pieces.get(0));
		assertEquals(new ClipCamera.Piece(1, 202 + Trail.SETTLE_TICKS, 300), pieces.get(1));
	}

	/**
	 * Building at night, then the orbit of the next moment: the camera went 120° round the house in a second and a
	 * half, which looked like a sharp turn. The glide takes its time.
	 */
	@Test
	void aShotThatGoesOnTurnsRoundThePlayerGently() {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= 200; t += 2) {
			samples.add(new TrailSample(t, 0.5, 64, 0.5, 0f, 0f, TrailSample.FLAG_ON_GROUND));
		}
		Trail standing = new Trail(samples);
		Vec aim = CameraPathBuilder.aimAt(standing, 0);
		CameraKey before = CameraPathBuilder.lookAt(0, aim.add(new Vec(10, 4, 0)), aim);
		double angle = Math.toRadians(120);
		Vec place = aim.add(new Vec(6 * Math.cos(angle), 2, 6 * Math.sin(angle)));
		List<CameraKey> keys = new ArrayList<>();
		for (int t = 0; t <= 200; t += 2) {
			keys.add(CameraPathBuilder.lookAt(t, place, aim));
		}
		List<CameraKey> glide = ClipCamera.glideFrom(before, keys, standing);
		double fastest = 0;
		for (int i = 1; i < glide.size(); i++) {
			Vec a = glide.get(i - 1).position().sub(aim).horizontal();
			Vec b = glide.get(i).position().sub(aim).horizontal();
			fastest = Math.max(fastest, a.angleTo(b) / (glide.get(i).tick() - glide.get(i - 1).tick()));
		}
		assertTrue(fastest * 20 <= 40, "at most 40° a second round the player, got " + Math.round(fastest * 20) + "°");
		assertTrue(glide.getFirst().position().sub(before.position()).length() < 0.01, "it starts where the last shot stood");
	}

	/** In bed from tick 60 to 140: the orbit stops while the player sleeps and goes on round when they wake. */
	private static Trail sleepingFrom60To140() {
		List<TrailSample> samples = new ArrayList<>();
		for (int t = 0; t <= 200; t += 2) {
			int flags = TrailSample.FLAG_ON_GROUND | (t >= 60 && t < 140 ? TrailSample.FLAG_SLEEPING : 0);
			samples.add(new TrailSample(t, 0.5, 64, 0.5, 0f, 0f, flags));
		}
		return new Trail(samples);
	}

	@Test
	void theCameraHoldsStillWhileThePlayerSleeps() {
		Trail trail = sleepingFrom60To140();
		List<CameraKey> orbit = CameraPathBuilder.build(trail, ShotType.ORBIT, 0, 200, CameraPathBuilder.Params.defaults(ShotType.ORBIT));
		Vec asleep = null;
		for (CameraKey key : orbit) {
			if (key.tick() >= 66 && key.tick() <= 140) {
				if (asleep == null) {
					asleep = key.position();
				}
				assertTrue(key.position().distance(asleep) < 1e-6, "still at tick " + key.tick());
			}
		}
		assertTrue(orbit.getFirst().position().distance(asleep) > 1, "it went round before");
		assertTrue(orbit.getLast().position().distance(asleep) > 1, "and goes on round after");

		// the next shot of the same moment waits where the orbit left off until the player wakes
		CameraKey before = CameraPathBuilder.lookAt(80, asleep, CameraPathBuilder.aimAt(trail, 80));
		List<CameraKey> still = CameraPathBuilder.build(trail, ShotType.STATIC_LOOKAT, 90, 200, CameraPathBuilder.Params.defaults(ShotType.STATIC_LOOKAT));
		for (CameraKey key : ClipCamera.glideFrom(before, still, trail)) {
			if (key.tick() <= 140) {
				assertTrue(key.position().distance(asleep) < 1e-6, "waiting at tick " + key.tick());
			}
		}
	}

	@Test
	void piecesStayInsideTheRecordedPath() {
		Trail trail = walkingEast(100, 300, 0);
		List<ClipCamera.Piece> pieces = ClipCamera.pieces(clip("a", 0, 0, 1000), trail);
		assertEquals(List.of(new ClipCamera.Piece(0, 100, 300)), pieces);
		assertTrue(ClipCamera.pieces(clip("a", 0, 0, 1000), null).isEmpty());
		assertTrue(ClipCamera.pieces(clip("a", 0, 295, 1000), trail).isEmpty(), "too short to film");
	}

	@Test
	void slowMotionEasesInBeforeTheMoment() {
		assertTrue(ClipCamera.speedKeys(0, 100, -1).isEmpty());
		assertTrue(ClipCamera.speedKeys(0, 100, 100).isEmpty());
		assertEquals(List.of(new ClipCamera.SpeedKey(50, Shot.SLOW_MOTION_SPEED)), ClipCamera.speedKeys(50, 100, 20));
		assertEquals(List.of(
				new ClipCamera.SpeedKey(0, 1f),
				new ClipCamera.SpeedKey(40, 1f),
				new ClipCamera.SpeedKey(50, Shot.SLOW_MOTION_SPEED)),
			ClipCamera.speedKeys(0, 100, 50));
		assertEquals(List.of(
				new ClipCamera.SpeedKey(0, 1f),
				new ClipCamera.SpeedKey(5, Shot.SLOW_MOTION_SPEED)),
			ClipCamera.speedKeys(0, 100, 5));
	}

	@Test
	void buildsCameraAndSpeedForAPiece() {
		Trail trail = walkingEast(0, 400, 0);
		EditDecisionList.Item item = clip("a", 0, 100, 300, ShotType.CHASE, 200, null);
		ClipCamera.Piece piece = ClipCamera.pieces(item, trail).getFirst();
		ClipCamera.Keys keys = ClipCamera.build(item, piece, trail, null, OPEN_AIR);
		assertEquals(ShotType.CHASE, keys.shot());
		assertEquals(100, keys.camera().getFirst().tick());
		assertEquals(300, keys.camera().getLast().tick());
		assertEquals(3, keys.speed().size());
		for (CameraKey key : keys.camera()) {
			assertTrue(key.position().x() < trail.positionAt(key.tick()).x(), "chasing from behind");
		}
	}

	@Test
	void aTwoShotWithoutItsFriendBecomesAStaticShot() {
		Trail trail = walkingEast(0, 400, 0);
		EditDecisionList.Item item = clip("a", 0, 100, 300, ShotType.TWO_SHOT, -1, "Alex");
		ClipCamera.Keys keys = ClipCamera.build(item, ClipCamera.pieces(item, trail).getFirst(), trail, null, OPEN_AIR);
		assertEquals(ShotType.STATIC_LOOKAT, keys.shot());
		assertFalse(keys.camera().isEmpty());

		// the friend was only around for the first few seconds
		Trail briefly = walkingEast(0, 120, 4);
		keys = ClipCamera.build(item, ClipCamera.pieces(item, trail).getFirst(), trail, briefly, OPEN_AIR);
		assertEquals(ShotType.STATIC_LOOKAT, keys.shot());
	}

	@Test
	void aTwoShotLooksBetweenTheFriends() {
		Trail us = walkingEast(0, 400, 0);
		// the friend walks next to us, 4 blocks to the side, sampled once a second like companions.jsonl
		List<TrailSample> friendSamples = new ArrayList<>();
		for (int t = 0; t <= 400; t += 20) {
			friendSamples.add(new TrailSample(t, t * 0.2, 64, 4, 0f, 0f, 0));
		}
		Trail friend = new Trail(friendSamples);
		EditDecisionList.Item item = clip("a", 0, 100, 300, ShotType.TWO_SHOT, -1, "Alex");
		ClipCamera.Keys keys = ClipCamera.build(item, ClipCamera.pieces(item, us).getFirst(), us, friend, OPEN_AIR);
		assertEquals(ShotType.TWO_SHOT, keys.shot());
		CameraKey middle = keys.camera().get(keys.camera().size() / 2);
		Vec between = us.positionAt(middle.tick()).lerp(friend.positionAt(middle.tick()), 0.5);
		Vec toMiddle = between.sub(middle.position()).horizontal().normalize();
		Vec looking = Vec.fromYaw(middle.yaw());
		assertTrue(toMiddle.x() * looking.x() + toMiddle.z() * looking.z() > 0.9, "camera looks at the point between them");
	}

	@Test
	void aBossSceneIsFilmedTowardsTheBossOnceItIsSeen() {
		Trail us = walkingEast(0, 400, 0);
		EditDecisionList.Item planned = clip("a", 0, 100, 300, ShotType.ORBIT, 120, null);
		EditDecisionList.Item kill = new EditDecisionList.Item(EditDecisionList.Kind.CLIP, 0, 1, 1, "minecraft:the_end", null, "a", 0, 100, 300,
			ShotType.ORBIT, 10.8, 4, 180, -1, 120, EventType.DRAGON_KILLED, "Ender Dragon", 2, null, 110, false);
		ClipCamera.Piece piece = ClipCamera.pieces(kill, us).getFirst();
		// the dragon 30 blocks north of us and 20 up, seen every 10 ticks
		List<TrailSample> seen = new ArrayList<>();
		for (int t = 100; t <= 300; t += 10) {
			seen.add(new TrailSample(t, t * 0.2, 84, -30, 0f, 0f, 0));
		}
		Trail dragon = new Trail(seen);

		ClipCamera.Keys keys = ClipCamera.build(kill, piece, us, null, dragon, OPEN_AIR);
		assertEquals(ShotType.OVER_SHOULDER, keys.shot());
		assertEquals(ClipCamera.speedKeys(100, 300, 120), keys.speed(), "slow motion stays");
		for (CameraKey key : keys.camera()) {
			assertTrue(key.position().z() > us.positionAt(key.tick()).z(), "camera behind us, away from the dragon");
			assertTrue(key.pitch() < 0, "looking up past us at the dragon");
		}
		assertEquals(ShotType.ORBIT, ClipCamera.build(kill, piece, us, null, null, OPEN_AIR).shot(), "the dragon unseen: as planned");
		assertEquals(ShotType.ORBIT, ClipCamera.build(planned, piece, us, null, dragon, OPEN_AIR).shot(), "not a boss scene");
	}

	@Test
	void missingCameraSettingsGetDefaults() {
		EditDecisionList.Item item = new EditDecisionList.Item(EditDecisionList.Kind.CLIP, 0, 1, 1, null, null, "a", 0, 0, 100,
			ShotType.ORBIT, 0, 1, 0, 0, -1, null, null, 0, null, -1, false);
		assertEquals(6, ClipCamera.params(item, ShotType.ORBIT).distance(), 1e-9);
		assertEquals(1, ClipCamera.params(item, ShotType.ORBIT).side());
	}
}
