package com.survivalcinema.client.capture.watch;

import com.survivalcinema.client.capture.EventSink;
import com.survivalcinema.client.mixin.BossHealthOverlayAccessor;
import com.survivalcinema.director.event.EventType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Boss fights, read from the boss bars on screen. Works for vanilla bosses, raids and modded bosses.
 * <p>
 * Only bars named after an entity or a raid count: servers also use boss bars for timers, countdowns and
 * announcements, and those are not boss fights.
 * <p>
 * A win is the bar disappearing at (almost) no health, or disappearing right after a boss died near us: the Wither
 * stops updating its bar while dying, so the last health the client sees is from before the killing blow.
 */
public final class BossWatcher {
	private static final String DRAGON_KEY = "entity.minecraft.ender_dragon";
	/** The same boss bar reappearing within this time (respawn, re-entering range) is the same fight. */
	private static final int SAME_FIGHT_TICKS = 20 * 30;
	private static final long BOSS_DEATH_MATCH_MS = 5_000;
	private static final Set<String> BOSS_ENTITIES = Set.of("minecraft:wither", "minecraft:ender_dragon",
		"minecraft:elder_guardian", "minecraft:warden");

	private record Bar(String key, String name, float lowestProgress) {
	}

	private final Map<UUID, Bar> bars = new HashMap<>();
	private final Map<String, Integer> lastSeenByKey = new HashMap<>();
	private long lastBossDeathMs;

	public void reset() {
		bars.clear();
		lastSeenByKey.clear();
		lastBossDeathMs = 0;
	}

	/** A known boss died near us (entity event 3). */
	public void onEntityDeath(@Nullable Entity entity) {
		if (entity != null && BOSS_ENTITIES.contains(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())) {
			lastBossDeathMs = System.currentTimeMillis();
		}
	}

	public void tick(Minecraft mc, EventSink sink) {
		Map<UUID, LerpingBossEvent> current = ((BossHealthOverlayAccessor) mc.gui.getBossOverlay()).survivalcinema$events();
		int now = sink.currentTick();
		Set<UUID> gone = new HashSet<>(bars.keySet());
		for (Map.Entry<UUID, LerpingBossEvent> entry : current.entrySet()) {
			LerpingBossEvent event = entry.getValue();
			Component name = event.getName();
			String key = bossKey(name);
			if (key == null) {
				continue;
			}
			gone.remove(entry.getKey());
			Bar previous = bars.get(entry.getKey());
			float lowest = previous == null ? event.getProgress() : Math.min(previous.lowestProgress(), event.getProgress());
			bars.put(entry.getKey(), new Bar(key, name.getString(), lowest));
			Integer lastSeen = lastSeenByKey.put(key, now);
			boolean sameFight = lastSeen != null && now - lastSeen <= SAME_FIGHT_TICKS;
			if (previous == null && !sameFight) {
				sink.emit(EventType.BOSS_FIGHT_START, key, name.getString(), null, 0.8, sink.markSeen("boss", key), null);
			}
		}
		for (UUID id : gone) {
			Bar bar = bars.remove(id);
			boolean bossJustDied = System.currentTimeMillis() - lastBossDeathMs <= BOSS_DEATH_MATCH_MS;
			if (bar.lowestProgress() > 0.05f && !bossJustDied) {
				continue; // walked away or the bar was hidden, not a win
			}
			sink.emit(EventType.BOSS_DEFEATED, bar.key(), bar.name(), null, 1.0, sink.markSeen("boss_defeated", bar.key()), null);
			if (DRAGON_KEY.equals(bar.key())) {
				sink.dragonKilled(null);
			}
		}
	}

	/** The translation key of a real boss or raid bar, or null for server timers, announcements and the like. */
	private static @Nullable String bossKey(Component name) {
		if (!(name.getContents() instanceof TranslatableContents tc)) {
			return null;
		}
		String key = tc.getKey();
		return key.startsWith("entity.") || key.startsWith("event.minecraft.raid") ? key : null;
	}
}
