package com.survivalcinema.director.camera;

/**
 * A camera keyframe: where the camera is and where it looks at a replay tick. Angles use Minecraft conventions.
 */
public record CameraKey(int tick, Vec position, float yaw, float pitch) {
}
