package com.survivalcinema.client.render;

import com.survivalcinema.director.camera.BlockProbe;
import com.survivalcinema.director.camera.Vec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The blocks of a stretch of the replay world kept as they were at one moment (the end of a clip), to raycast against
 * after the replay has gone back to the clip's start: walls that go up during a clip block the camera too.
 */
final class BlockSnapshot implements BlockProbe {
	/** A box bigger than this many blocks is not kept (a long flight): nothing gets built along it anyway. */
	private static final int MAX_BLOCKS = 700_000;

	private final Map<Long, VoxelShape> shapes;

	private BlockSnapshot(Map<Long, VoxelShape> shapes) {
		this.shapes = shapes;
	}

	/** The collision shapes in the box, or null when the box is too big to keep. */
	static @Nullable BlockSnapshot capture(ClientLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
		if (volume > MAX_BLOCKS || volume <= 0) {
			return null;
		}
		Map<Long, VoxelShape> shapes = new HashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					pos.set(x, y, z);
					VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos, CollisionContext.empty());
					if (!shape.isEmpty()) {
						shapes.put(pos.asLong(), shape);
					}
				}
			}
		}
		return new BlockSnapshot(shapes);
	}

	int size() {
		return shapes.size();
	}

	/** Walks the block cells along the line (Amanatides-Woo) and clips each kept shape in the way. */
	@Override
	public double clearFraction(Vec from, Vec to) {
		double dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length < 1e-6) {
			return 1;
		}
		Vec3 a = new Vec3(from.x(), from.y(), from.z());
		Vec3 b = new Vec3(to.x(), to.y(), to.z());
		int x = (int) Math.floor(from.x()), y = (int) Math.floor(from.y()), z = (int) Math.floor(from.z());
		int stepX = (int) Math.signum(dx), stepY = (int) Math.signum(dy), stepZ = (int) Math.signum(dz);
		double tDeltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
		double tDeltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
		double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
		double tMaxX = stepX == 0 ? Double.MAX_VALUE : ((stepX > 0 ? x + 1 - from.x() : from.x() - x) * tDeltaX);
		double tMaxY = stepY == 0 ? Double.MAX_VALUE : ((stepY > 0 ? y + 1 - from.y() : from.y() - y) * tDeltaY);
		double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : ((stepZ > 0 ? z + 1 - from.z() : from.z() - z) * tDeltaZ);
		double tEnter = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		while (tEnter <= 1) {
			VoxelShape shape = shapes.get(BlockPos.asLong(x, y, z));
			if (shape != null) {
				BlockHitResult hit = shape.clip(a, b, pos.set(x, y, z));
				if (hit != null) {
					return Math.min(1, a.distanceTo(hit.getLocation()) / length);
				}
			}
			if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
				tEnter = tMaxX;
				tMaxX += tDeltaX;
				x += stepX;
			} else if (tMaxY <= tMaxZ) {
				tEnter = tMaxY;
				tMaxY += tDeltaY;
				y += stepY;
			} else {
				tEnter = tMaxZ;
				tMaxZ += tDeltaZ;
				z += stepZ;
			}
		}
		return 1;
	}
}
