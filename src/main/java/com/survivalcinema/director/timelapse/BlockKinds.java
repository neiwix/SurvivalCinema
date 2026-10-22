package com.survivalcinema.director.timelapse;

import java.util.Set;

/**
 * What a placed block says about the place it is in. A home has beds, chests, furnaces, a crafting table, doors;
 * a farm is hoppers, pistons, observers, rails and redstone. Both grow over many days, so the kind of blocks is
 * what tells the base from the big farm next door (or far away).
 */
public final class BlockKinds {
	public enum Kind {
		/** Where people live: beds, storage, workstations, doors. */
		HOME,
		/** Machinery: hoppers, pistons, redstone, rails. */
		FARM,
		/** Any other block (walls, floors, roofs). */
		PLAIN
	}

	private static final Set<String> HOME_EXACT = Set.of("chest", "trapped_chest", "barrel", "ender_chest", "furnace",
		"blast_furnace", "smoker", "crafting_table", "enchanting_table", "anvil", "chipped_anvil", "damaged_anvil",
		"brewing_stand", "bookshelf", "chiseled_bookshelf", "lectern", "cartography_table", "smithing_table",
		"loom", "grindstone", "stonecutter", "fletching_table", "campfire", "soul_campfire", "flower_pot",
		"decorated_pot", "jukebox", "lantern", "soul_lantern", "respawn_anchor");
	private static final Set<String> FARM_EXACT = Set.of("hopper", "piston", "sticky_piston", "observer", "dispenser",
		"dropper", "redstone_wire", "repeater", "comparator", "redstone_torch", "redstone_wall_torch", "redstone_block",
		"lever", "target", "slime_block", "honey_block", "composter", "crafter", "tripwire_hook", "daylight_detector",
		"rail", "powered_rail", "detector_rail", "activator_rail", "magma_block", "soul_sand", "kelp", "note_block");

	private BlockKinds() {
	}

	/** {@code "minecraft:oak_door"} or {@code "oak_door"}. */
	public static Kind of(String blockId) {
		String id = blockId.substring(blockId.indexOf(':') + 1);
		if (isBed(id) || isDoor(id) || id.endsWith("shulker_box") || HOME_EXACT.contains(id)) {
			return Kind.HOME;
		}
		if (FARM_EXACT.contains(id)) {
			return Kind.FARM;
		}
		return Kind.PLAIN;
	}

	public static boolean isBed(String blockId) {
		return blockId.endsWith("_bed") || blockId.endsWith(":bed");
	}

	/** A door people walk through (not a trapdoor). */
	public static boolean isDoor(String blockId) {
		return blockId.endsWith("_door") && !blockId.endsWith("trapdoor");
	}
}
