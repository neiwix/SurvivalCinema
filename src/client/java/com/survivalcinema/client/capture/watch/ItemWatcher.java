package com.survivalcinema.client.capture.watch;

import com.survivalcinema.client.capture.EventSink;
import com.survivalcinema.director.event.EventType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Notable items showing up in the inventory: diamonds, netherite, elytra... Cheap ones only count the first time.
 * <p>
 * Only real gains count, not items moving around: nothing counts while a chest or other container is open, and
 * after a death the items picked back up don't count again (we remember the most the player ever had and only
 * lower that when items are used up, not when they are lost by dying).
 */
public final class ItemWatcher {
	private record Notable(double intensity, boolean firstOnly) {
	}

	/** After respawning, picking your own stuff back up for this long is not a new find. */
	private static final int AFTER_DEATH_TICKS = 20 * 60 * 3;

	private static final Map<String, Notable> NOTABLE = Map.ofEntries(
		Map.entry("minecraft:iron_ingot", new Notable(0.3, true)),
		Map.entry("minecraft:diamond", new Notable(0.6, false)),
		Map.entry("minecraft:emerald", new Notable(0.3, true)),
		Map.entry("minecraft:ancient_debris", new Notable(0.7, false)),
		Map.entry("minecraft:netherite_scrap", new Notable(0.6, true)),
		Map.entry("minecraft:netherite_ingot", new Notable(0.8, false)),
		Map.entry("minecraft:elytra", new Notable(1.0, true)),
		Map.entry("minecraft:totem_of_undying", new Notable(0.8, true)),
		Map.entry("minecraft:enchanted_golden_apple", new Notable(0.8, false)),
		Map.entry("minecraft:golden_apple", new Notable(0.4, true)),
		Map.entry("minecraft:nether_star", new Notable(1.0, false)),
		Map.entry("minecraft:dragon_egg", new Notable(1.0, true)),
		Map.entry("minecraft:trident", new Notable(0.8, true)),
		Map.entry("minecraft:heart_of_the_sea", new Notable(0.7, true)),
		Map.entry("minecraft:blaze_rod", new Notable(0.5, true)),
		Map.entry("minecraft:ender_pearl", new Notable(0.3, true)),
		Map.entry("minecraft:ender_eye", new Notable(0.5, true)),
		Map.entry("minecraft:diamond_pickaxe", new Notable(0.6, true)),
		Map.entry("minecraft:diamond_sword", new Notable(0.6, true)),
		Map.entry("minecraft:netherite_pickaxe", new Notable(0.8, true)),
		Map.entry("minecraft:netherite_sword", new Notable(0.8, true)),
		Map.entry("minecraft:beacon", new Notable(0.9, true)),
		Map.entry("minecraft:shulker_shell", new Notable(0.6, true)),
		Map.entry("minecraft:wither_skeleton_skull", new Notable(0.7, true)),
		Map.entry("minecraft:mace", new Notable(0.9, true)),
		Map.entry("minecraft:heavy_core", new Notable(0.8, true)));

	/** The most of each notable item the player has had at once. */
	private final Map<String, Integer> highWater = new HashMap<>();
	private int ticks;
	private int deathTick = Integer.MIN_VALUE / 2;
	private boolean initialized;

	public void reset() {
		highWater.clear();
		initialized = false;
		deathTick = Integer.MIN_VALUE / 2;
	}

	public void tick(Minecraft mc, EventSink sink) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		int now = sink.currentTick();
		if (player.isDeadOrDying()) {
			deathTick = now;
			return;
		}
		if (++ticks % 10 != 0) {
			return;
		}
		Map<String, Integer> counts = new HashMap<>();
		Map<String, ItemStack> examples = new HashMap<>();
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			if (NOTABLE.containsKey(id)) {
				counts.merge(id, stack.getCount(), Integer::sum);
				examples.putIfAbsent(id, stack);
			}
		}
		boolean moving = mc.screen instanceof AbstractContainerScreen<?> && !(mc.screen instanceof InventoryScreen);
		boolean recovering = now - deathTick < AFTER_DEATH_TICKS;
		if (!initialized || moving) {
			// What the player already had (or is moving between chests) is not a find.
			counts.forEach((id, count) -> highWater.merge(id, count, Math::max));
			initialized = true;
			return;
		}
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			String id = entry.getKey();
			int previousMax = highWater.getOrDefault(id, 0);
			int gained = entry.getValue() - previousMax;
			if (gained <= 0) {
				continue;
			}
			highWater.put(id, entry.getValue());
			Notable notable = NOTABLE.get(id);
			boolean first = sink.markSeen("item", id);
			if (notable.firstOnly() && !first) {
				continue;
			}
			String label = examples.get(id).getHoverName().getString() + (gained > 1 ? " ×" + gained : "");
			sink.emit(EventType.RARE_ITEM, id, label, null, first ? Math.min(1, notable.intensity() + 0.2) : notable.intensity(),
				first, String.valueOf(gained));
		}
		if (!recovering) {
			// Items used up (crafting, eating, placing) lower the mark, so finding more later counts again.
			for (Map.Entry<String, Integer> entry : highWater.entrySet()) {
				entry.setValue(Math.min(entry.getValue(), counts.getOrDefault(entry.getKey(), 0)));
			}
		}
	}
}
