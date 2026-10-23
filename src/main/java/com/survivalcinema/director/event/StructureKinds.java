package com.survivalcinema.director.event;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * What kind of place a structure is, for the film: every village is "a village" whatever its biome, the three chests of
 * a shipwreck are all "the shipwreck". Found from the structure's id (entering it) or from a chest's loot table
 * (opening it: a chest still holding its loot table was never opened, and a chest people placed has none).
 */
public final class StructureKinds {
	/** Structure ids and loot table names that are another name for a kind. */
	private static final Map<String, String> ALIASES = Map.ofEntries(
		Map.entry("village_plains", "village"), Map.entry("village_desert", "village"), Map.entry("village_savanna", "village"),
		Map.entry("village_snowy", "village"), Map.entry("village_taiga", "village"),
		Map.entry("shipwreck_beached", "shipwreck"), Map.entry("shipwreck_map", "shipwreck"),
		Map.entry("shipwreck_supply", "shipwreck"), Map.entry("shipwreck_treasure", "shipwreck"),
		Map.entry("ocean_ruin_cold", "ocean_ruin"), Map.entry("ocean_ruin_warm", "ocean_ruin"),
		Map.entry("underwater_ruin_big", "ocean_ruin"), Map.entry("underwater_ruin_small", "ocean_ruin"),
		Map.entry("ruined_portal_desert", "ruined_portal"), Map.entry("ruined_portal_jungle", "ruined_portal"),
		Map.entry("ruined_portal_swamp", "ruined_portal"), Map.entry("ruined_portal_mountain", "ruined_portal"),
		Map.entry("ruined_portal_ocean", "ruined_portal"), Map.entry("ruined_portal_nether", "ruined_portal"),
		Map.entry("jungle_temple", "jungle_pyramid"), Map.entry("jungle_temple_dispenser", "jungle_pyramid"),
		Map.entry("igloo_chest", "igloo"), Map.entry("woodland_mansion", "mansion"),
		Map.entry("stronghold_corridor", "stronghold"), Map.entry("stronghold_crossing", "stronghold"),
		Map.entry("stronghold_library", "stronghold"), Map.entry("abandoned_mineshaft", "mineshaft"),
		Map.entry("mineshaft_mesa", "mineshaft"), Map.entry("nether_bridge", "fortress"),
		Map.entry("bastion_bridge", "bastion_remnant"), Map.entry("bastion_hoglin_stable", "bastion_remnant"),
		Map.entry("bastion_other", "bastion_remnant"), Map.entry("bastion_treasure", "bastion_remnant"),
		Map.entry("end_city_treasure", "end_city"), Map.entry("ancient_city_ice_box", "ancient_city"),
		Map.entry("simple_dungeon", "dungeon"));
	/** Not a place to show: buried under the sand, or bits of bone in the Nether; the bonus chest at spawn. */
	private static final Set<String> NOT_PLACES = Set.of("buried_treasure", "nether_fossil", "spawn_bonus_chest");

	private StructureKinds() {
	}

	/** The kind of a structure from its id ("minecraft:village_plains" is "village"); null for what is no place to show. */
	public static @Nullable String ofStructure(String id) {
		return kind(path(id));
	}

	/**
	 * The kind of place a chest belongs to from its loot table ("minecraft:chests/shipwreck_treasure" is "shipwreck",
	 * "minecraft:chests/village/village_weaponsmith" is "village"); null for what is no structure's chest.
	 */
	public static @Nullable String ofLootTable(String table) {
		String path = path(table);
		if (!path.startsWith("chests/")) {
			return null;
		}
		path = path.substring("chests/".length());
		int slash = path.indexOf('/');
		return kind(slash < 0 ? path : path.substring(0, slash));
	}

	private static @Nullable String kind(String name) {
		if (name.isEmpty()) {
			return null;
		}
		String kind = ALIASES.getOrDefault(name, name);
		return NOT_PLACES.contains(kind) ? null : kind;
	}

	private static String path(String id) {
		int colon = id.indexOf(':');
		return colon < 0 ? id : id.substring(colon + 1);
	}

	/** "pillager_outpost" as words, for a kind the language files don't name. */
	public static String readable(String kind) {
		String words = kind.replace('_', ' ');
		return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}
}
