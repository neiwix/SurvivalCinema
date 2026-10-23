package com.survivalcinema.client.capture.watch;

import com.survivalcinema.client.capture.EventSink;
import com.survivalcinema.director.event.EventType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Logs we break, in 30 second windows (where, how many): the director opens the film on the first tree of the world.
 * Bookkeeping only, never a highlight by itself.
 */
public final class WoodWatcher {
	private static final int WINDOW_TICKS = 20 * 30;

	private final List<BlockPos> logs = new ArrayList<>();
	private int windowStartTick = -1;
	private @Nullable ResourceKey<Level> windowDimension;

	public void reset() {
		logs.clear();
		windowStartTick = -1;
	}

	/** We broke a block. */
	public void onBreak(Minecraft mc, BlockPos pos, BlockState state, EventSink sink) {
		if (!state.is(BlockTags.LOGS) || mc.level == null) {
			return;
		}
		if (logs.isEmpty()) {
			windowStartTick = sink.currentTick();
			windowDimension = mc.level.dimension();
		}
		logs.add(pos.immutable());
	}

	public void tick(Minecraft mc, EventSink sink) {
		if (logs.isEmpty()) {
			return;
		}
		boolean leftDimension = mc.level != null && windowDimension != null && mc.level.dimension() != windowDimension;
		if (leftDimension || sink.currentTick() - windowStartTick >= WINDOW_TICKS) {
			emit(sink);
		}
	}

	private void emit(EventSink sink) {
		double x = 0, y = 0, z = 0;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (BlockPos p : logs) {
			x += p.getX() + 0.5;
			y += p.getY();
			z += p.getZ() + 0.5;
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX() + 1);
			maxY = Math.max(maxY, p.getY() + 1);
			maxZ = Math.max(maxZ, p.getZ() + 1);
		}
		int n = logs.size();
		String where = windowDimension == null ? null : windowDimension.identifier().toString();
		sink.emit(EventType.WOOD_ACTIVITY, "wood", "wood", new Vec3(x / n, y / n, z / n), Math.min(1, n / 32.0), false,
			"count=" + n + ";box=" + minX + "," + minY + "," + minZ + "," + maxX + "," + maxY + "," + maxZ, windowStartTick, where);
		logs.clear();
	}
}
