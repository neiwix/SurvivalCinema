package com.survivalcinema.client.render;

import com.survivalcinema.director.camera.BlockProbe;
import com.survivalcinema.director.camera.Vec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Raycasts against the blocks of the open replay. Uses collision shapes, so leaves and walls block the view while
 * grass, flowers and torches don't.
 */
public final class ClientBlockProbe implements BlockProbe {
	private final ClientLevel level;

	public ClientBlockProbe(ClientLevel level) {
		this.level = level;
	}

	@Override
	public double clearFraction(Vec from, Vec to) {
		Vec3 a = new Vec3(from.x(), from.y(), from.z());
		Vec3 b = new Vec3(to.x(), to.y(), to.z());
		double length = a.distanceTo(b);
		if (length < 1e-6) {
			return 1;
		}
		BlockHitResult hit = level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
		if (hit.getType() == HitResult.Type.MISS) {
			return 1;
		}
		return Math.min(1, a.distanceTo(hit.getLocation()) / length);
	}
}
