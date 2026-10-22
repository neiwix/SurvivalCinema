package com.survivalcinema.client.capture.watch;

import com.survivalcinema.client.capture.EventSink;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.timelapse.BlockKinds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Building activity in 30 second windows, ours and our friends' (theirs heard through the "block placed" sound the
 * server sends us; machines placing blocks make no such sound). Every window with placements is logged with what
 * kind of blocks went in (beds, chests and doors make a home, hoppers and pistons a farm): the base timelapse finds
 * the base from these. A busy window of our own is also a highlight.
 */
public final class BuildWatcher {
	private static final int WINDOW_TICKS = 20 * 30;
	private static final int BURST_PLACEMENTS = 25;
	/** A placement is looked at this many ticks later, once the block is really there. */
	private static final int SETTLE_TICKS = 3;
	private static final int MAX_PENDING = 512;

	/** @param who the friend who placed it, or null for us */
	private record Pending(BlockPos pos, int tick, @Nullable String who) {
	}

	private record Placed(BlockPos pos, BlockKinds.Kind kind, boolean bed, boolean door, @Nullable String who) {
	}

	private final List<Pending> pending = new ArrayList<>();
	private final List<Placed> placements = new ArrayList<>();
	private int windowStartTick = -1;
	private @Nullable ResourceKey<Level> windowDimension;

	public void reset() {
		pending.clear();
		placements.clear();
		windowStartTick = -1;
	}

	/** We used a block item on a block: maybe a placement (or just opening a chest with blocks in hand). */
	public void onPlace(BlockPos pos, EventSink sink) {
		queue(pos, null, sink);
	}

	/** A friend placed a block near us. */
	public void onFriendPlace(BlockPos pos, String who, EventSink sink) {
		queue(pos, who, sink);
	}

	private void queue(BlockPos pos, @Nullable String who, EventSink sink) {
		if (pending.size() < MAX_PENDING) {
			pending.add(new Pending(pos.immutable(), sink.currentTick(), who));
		}
	}

	public void tick(Minecraft mc, EventSink sink) {
		settle(mc, sink.currentTick());
		if (placements.isEmpty()) {
			return;
		}
		// through a portal: the window ends here, its blocks belong to the dimension left behind
		boolean leftDimension = mc.level != null && windowDimension != null && mc.level.dimension() != windowDimension;
		if (!leftDimension && sink.currentTick() - windowStartTick < WINDOW_TICKS) {
			return;
		}
		emit(sink);
	}

	/** Placements old enough to look at: kept when a block really stands there now. */
	private void settle(Minecraft mc, int now) {
		for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
			Pending p = it.next();
			if (now - p.tick() < SETTLE_TICKS && now >= p.tick()) {
				continue;
			}
			it.remove();
			if (mc.level == null) {
				continue;
			}
			BlockState state = mc.level.getBlockState(p.pos());
			if (state.isAir() || !state.getFluidState().isEmpty() && state.getFluidState().isSource()) {
				continue; // nothing was placed after all
			}
			String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			if (placements.isEmpty()) {
				windowStartTick = p.tick();
				windowDimension = mc.level.dimension();
			}
			placements.add(new Placed(p.pos(), BlockKinds.of(id), BlockKinds.isBed(id), BlockKinds.isDoor(id), p.who()));
		}
	}

	private void emit(EventSink sink) {
		// where the blocks were placed: the window is often reported after stepping through a portal
		String where = windowDimension == null ? null : windowDimension.identifier().toString();
		double x = 0, y = 0, z = 0;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		int home = 0, farm = 0, beds = 0, ours = 0;
		BlockPos door = null;
		Set<String> friends = new LinkedHashSet<>();
		for (Placed placed : placements) {
			BlockPos p = placed.pos();
			x += p.getX() + 0.5;
			y += p.getY();
			z += p.getZ() + 0.5;
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX() + 1);
			maxY = Math.max(maxY, p.getY() + 1);
			maxZ = Math.max(maxZ, p.getZ() + 1);
			switch (placed.kind()) {
				case HOME -> home++;
				case FARM -> farm++;
				case PLAIN -> {
				}
			}
			if (placed.bed()) {
				beds++;
			}
			if (placed.door() && door == null) {
				door = p;
			}
			if (placed.who() == null) {
				ours++;
			} else {
				friends.add(placed.who());
			}
		}
		int n = placements.size();
		Vec3 centroid = new Vec3(x / n, y / n, z / n);
		// what the base timelapse needs: how much, how big, what kind, where the door is, who built
		StringBuilder detail = new StringBuilder("count=" + n + ";box=" + minX + "," + minY + "," + minZ + "," + maxX + ","
			+ maxY + "," + maxZ + ";home=" + home + ";farm=" + farm + ";bed=" + beds);
		if (door != null) {
			detail.append(";door=").append(door.getX()).append(',').append(door.getY()).append(',').append(door.getZ());
		}
		if (!friends.isEmpty()) {
			detail.append(";by=").append(String.join(",", friends).replace(";", ""));
		}
		sink.emit(EventType.BUILD_ACTIVITY, "build", "build", centroid, Math.min(1, n / 100.0), false, detail.toString(), windowStartTick,
			where);
		if (ours >= BURST_PLACEMENTS) {
			sink.emit(EventType.BUILD_BURST, "build", Component.translatable("survivalcinema.event.build", ours).getString(),
				centroid, Math.min(1, ours / 100.0), sink.markSeen("build_burst", "first"), "count=" + ours, windowStartTick, where);
		}
		placements.clear();
	}
}
