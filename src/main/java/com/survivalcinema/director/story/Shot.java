package com.survivalcinema.director.story;

import com.survivalcinema.director.camera.CameraPathBuilder;
import com.survivalcinema.director.camera.ShotType;
import org.jetbrains.annotations.Nullable;

/**
 * One camera setup inside a scene.
 *
 * @param slowMotionFromTick from this replay tick on the shot plays at {@link #SLOW_MOTION_SPEED}, or -1
 * @param partner            for a {@link ShotType#TWO_SHOT}: the friend filmed together with us
 */
public record Shot(ShotType type, int startTick, int endTick, CameraPathBuilder.Params params, int slowMotionFromTick,
				   @Nullable String partner) {
	public static final float SLOW_MOTION_SPEED = 0.5f;

	public Shot(ShotType type, int startTick, int endTick, CameraPathBuilder.Params params, int slowMotionFromTick) {
		this(type, startTick, endTick, params, slowMotionFromTick, null);
	}

	public int durationTicks() {
		return endTick - startTick;
	}
}
