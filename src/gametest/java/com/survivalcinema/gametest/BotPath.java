package com.survivalcinema.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Where a player can walk, worked out on the blocks the client knows (A*): along the ground, one block up with a
 * jump, a few blocks down, around trees, through a door, and (when allowed) swimming on the surface. The playthrough
 * bot follows these paths with the movement keys, the way a player walks, instead of being moved.
 * <p>
 * A path is the list of blocks the feet go through, from the start to the goal. Portals are never walked through by
 * accident: the bot steps into one on purpose.
 */
final class BotPath {
	private static final int MAX_NODES = 60_000;
	private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
	private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	/**
	 * @param maxDrop how far down a step may go (a fall of more than 3 blocks hurts)
	 * @param swim    whether the water surface counts as ground
	 */
	record Rules(int maxDrop, boolean swim) {
		static final Rules WALK = new Rules(3, false);
	}

	private record Entry(double f, long node) {
	}

	private record Move(BlockPos to, double cost) {
	}

	/** Everything reachable from a start, with the cheapest way to each place. */
	record Explored(BlockPos start, Map<Long, Long> parents, Map<Long, Double> costs) {
		List<BlockPos> places() {
			return costs.keySet().stream().map(BlockPos::of).toList();
		}

		List<BlockPos> pathTo(BlockPos place) {
			return BotPath.path(parents, place.asLong());
		}
	}

	/** How many places the last {@link #find} looked at (for the log, when it found no way). */
	static int lastSearched;

	private BotPath() {
	}

	/**
	 * The way from the feet block {@code from} to a place within {@code reach} blocks of {@code to} (horizontally, and
	 * at most a block higher or lower), or null when there is none the client knows of.
	 */
	static @Nullable List<BlockPos> find(BlockGetter level, BlockPos from, BlockPos to, double reach, Rules rules) {
		return find(level, from, to, reach, rules, MAX_NODES);
	}

	/** {@link #find}, giving up after looking at {@code most} places (for a way planned again every second). */
	static @Nullable List<BlockPos> find(BlockGetter level, BlockPos from, BlockPos to, double reach, Rules rules, int most) {
		lastSearched = 0;
		BlockPos start = startNode(level, from, rules);
		if (start == null) {
			return null;
		}
		Map<Long, Long> parents = new HashMap<>();
		Map<Long, Double> costs = new HashMap<>();
		Set<Long> closed = new HashSet<>();
		PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::f));
		costs.put(start.asLong(), 0.0);
		open.add(new Entry(guess(start, to), start.asLong()));
		while (!open.isEmpty() && (lastSearched = closed.size()) < most) {
			Entry entry = open.poll();
			if (!closed.add(entry.node())) {
				continue;
			}
			BlockPos at = BlockPos.of(entry.node());
			if (reached(at, to, reach)) {
				return path(parents, entry.node());
			}
			double cost = costs.get(entry.node());
			for (Move move : moves(level, at, rules)) {
				long key = move.to().asLong();
				double next = cost + move.cost();
				Double known = costs.get(key);
				if (!closed.contains(key) && (known == null || next < known - 1e-9)) {
					costs.put(key, next);
					parents.put(key, entry.node());
					open.add(new Entry(next + guess(move.to(), to), key));
				}
			}
		}
		return null;
	}

	/** Every place reachable from {@code from} within {@code radius} blocks horizontally. */
	static Explored explore(BlockGetter level, BlockPos from, int radius, Rules rules) {
		return explore(level, from, radius, rules, MAX_NODES / 4);
	}

	/** {@link #explore}, looking at no more than {@code most} places. */
	static Explored explore(BlockGetter level, BlockPos from, int radius, Rules rules, int most) {
		BlockPos start = startNode(level, from, rules);
		Map<Long, Long> parents = new HashMap<>();
		Map<Long, Double> costs = new HashMap<>();
		if (start == null) {
			return new Explored(from, parents, costs);
		}
		Set<Long> closed = new HashSet<>();
		PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::f));
		costs.put(start.asLong(), 0.0);
		open.add(new Entry(0, start.asLong()));
		while (!open.isEmpty() && closed.size() < most) {
			Entry entry = open.poll();
			if (!closed.add(entry.node())) {
				continue;
			}
			BlockPos at = BlockPos.of(entry.node());
			for (Move move : moves(level, at, rules)) {
				BlockPos to = move.to();
				if (Math.abs(to.getX() - start.getX()) > radius || Math.abs(to.getZ() - start.getZ()) > radius) {
					continue;
				}
				long key = to.asLong();
				double next = entry.f() + move.cost();
				Double known = costs.get(key);
				if (!closed.contains(key) && (known == null || next < known - 1e-9)) {
					costs.put(key, next);
					parents.put(key, entry.node());
					open.add(new Entry(next, key));
				}
			}
		}
		costs.keySet().retainAll(closed);
		return new Explored(start, parents, costs);
	}

	/** Where the feet can be: room for the body, and ground (or, swimming, water) under it. */
	static boolean standable(BlockGetter level, BlockPos pos, Rules rules) {
		boolean feet = open(level, pos) || rules.swim() && surfaceWater(level, pos);
		return feet && open(level, pos.above()) && (floor(level, pos.below()) || rules.swim() && surfaceWater(level, pos));
	}

	/** Nothing to bump into or burn on: air, grass, flowers, an (openable) door. */
	static boolean open(BlockGetter level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof DoorBlock) {
			return true;
		}
		if (!state.getFluidState().isEmpty() || hazard(state) || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL)) {
			return false;
		}
		return state.getCollisionShape(level, pos).isEmpty();
	}

	/** Something to stand on: a full-height block (or nearly: a chest, a path block), not a fence and not fire. */
	static boolean floor(BlockGetter level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (hazard(state) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
			|| state.getBlock() instanceof DoorBlock) {
			return false;
		}
		VoxelShape shape = state.getCollisionShape(level, pos);
		if (shape.isEmpty()) {
			return false;
		}
		double top = shape.max(Direction.Axis.Y);
		return top >= 0.8 && top <= 1.0;
	}

	private static boolean surfaceWater(BlockGetter level, BlockPos pos) {
		FluidState fluid = level.getFluidState(pos);
		return fluid.is(FluidTags.WATER) && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
			&& level.getFluidState(pos.above()).isEmpty();
	}

	private static boolean hazard(BlockState state) {
		return state.getBlock() instanceof BaseFireBlock || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.COBWEB)
			|| state.is(Blocks.POWDER_SNOW) || state.is(Blocks.CACTUS) || state.is(Blocks.WITHER_ROSE);
	}

	private static List<Move> moves(BlockGetter level, BlockPos at, Rules rules) {
		List<Move> moves = new ArrayList<>(12);
		for (int[] s : SIDES) {
			BlockPos side = at.offset(s[0], 0, s[1]);
			if (standable(level, side, rules)) {
				moves.add(new Move(side, 1 + penalty(level, side)));
				continue;
			}
			BlockPos up = side.above();
			if (open(level, at.above(2)) && standable(level, up, rules)) {
				moves.add(new Move(up, 1.8 + penalty(level, up)));
				continue;
			}
			if (!open(level, side) || !open(level, side.above())) {
				continue;
			}
			for (int k = 1; k <= rules.maxDrop(); k++) {
				BlockPos down = side.below(k);
				if (standable(level, down, rules)) {
					moves.add(new Move(down, 1 + (k <= 3 ? 0.3 * k : 2.0 * k) + penalty(level, down)));
					break;
				}
				if (!open(level, down)) {
					break;
				}
			}
		}
		for (int[] d : DIAGONALS) {
			BlockPos diagonal = at.offset(d[0], 0, d[1]);
			BlockPos a = at.offset(d[0], 0, 0);
			BlockPos b = at.offset(0, 0, d[1]);
			if (standable(level, diagonal, rules) && open(level, a) && open(level, a.above()) && open(level, b) && open(level, b.above())) {
				moves.add(new Move(diagonal, 1.42 + penalty(level, diagonal)));
			}
		}
		return moves;
	}

	/** Swimming is slow, and lava next to the way is best kept away from. */
	private static double penalty(BlockGetter level, BlockPos pos) {
		double extra = level.getFluidState(pos).is(FluidTags.WATER) ? 2 : 0;
		for (int[] s : SIDES) {
			BlockPos side = pos.offset(s[0], 0, s[1]);
			if (level.getFluidState(side).is(FluidTags.LAVA) || level.getFluidState(side.below()).is(FluidTags.LAVA)) {
				extra += 4;
			}
		}
		return extra;
	}

	/** The feet block, or (standing on an edge, a slab or in a doorway) the nearest place next to it that counts. */
	private static @Nullable BlockPos startNode(BlockGetter level, BlockPos from, Rules rules) {
		if (standable(level, from, rules)) {
			return from;
		}
		for (int dy : new int[]{1, -1, 0, 2}) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos near = from.offset(dx, dy, dz);
					if (standable(level, near, rules)) {
						return near;
					}
				}
			}
		}
		return null;
	}

	private static boolean reached(BlockPos at, BlockPos to, double reach) {
		double dx = at.getX() - to.getX();
		double dz = at.getZ() - to.getZ();
		return dx * dx + dz * dz <= reach * reach + 1e-9 && Math.abs(at.getY() - to.getY()) <= 1;
	}

	private static double guess(BlockPos at, BlockPos to) {
		double dx = at.getX() - to.getX();
		double dy = at.getY() - to.getY();
		double dz = at.getZ() - to.getZ();
		return 1.1 * Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static List<BlockPos> path(Map<Long, Long> parents, long end) {
		List<BlockPos> path = new ArrayList<>();
		Long node = end;
		while (node != null) {
			path.add(BlockPos.of(node));
			node = parents.get(node);
		}
		Collections.reverse(path);
		return path;
	}
}
