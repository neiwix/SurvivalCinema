package com.survivalcinema.client.capture.watch;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.EventSink;
import com.survivalcinema.director.event.EventType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deaths, near-deaths, totems, big hits and kills.
 * <p>
 * Kills: the client is never told who killed a mob, so a kill is "a hostile mob we damaged in the last few seconds
 * just died". Damage we dealt is taken from damage packets (so arrows and tridents count too) and from our own
 * melee swings. Passive animals (food, farming) are never kills: there is nothing cinematic about a cow.
 */
public final class CombatWatcher {
	private static final byte ENTITY_EVENT_DEATH = 3;
	private static final byte ENTITY_EVENT_TOTEM = 35;
	private static final long KILL_CREDIT_MS = 6_000;
	private static final long DAMAGE_SOURCE_MEMORY_MS = 1_500;
	private static final float LOW_HEALTH = 4f;
	private static final int NEAR_DEATH_COOLDOWN_TICKS = 20 * 30;
	private static final int BIG_DAMAGE_COOLDOWN_TICKS = 20 * 3;
	private static final Map<String, Double> NOTABLE_MOBS = Map.ofEntries(
		Map.entry("minecraft:creeper", 0.5), Map.entry("minecraft:enderman", 0.6), Map.entry("minecraft:witch", 0.5),
		Map.entry("minecraft:blaze", 0.6), Map.entry("minecraft:ghast", 0.7), Map.entry("minecraft:wither_skeleton", 0.7),
		Map.entry("minecraft:piglin_brute", 0.8), Map.entry("minecraft:ravager", 0.8), Map.entry("minecraft:evoker", 0.8),
		Map.entry("minecraft:elder_guardian", 1.0), Map.entry("minecraft:warden", 1.0), Map.entry("minecraft:shulker", 0.6),
		Map.entry("minecraft:breeze", 0.6), Map.entry("minecraft:hoglin", 0.6), Map.entry("minecraft:phantom", 0.4),
		Map.entry("minecraft:vindicator", 0.6), Map.entry("minecraft:pillager", 0.4), Map.entry("minecraft:iron_golem", 0.7),
		Map.entry("minecraft:zombified_piglin", 0.4), Map.entry("minecraft:piglin", 0.4), Map.entry("minecraft:guardian", 0.5),
		Map.entry("minecraft:silverfish", 0.3), Map.entry("minecraft:endermite", 0.3), Map.entry("minecraft:slime", 0.25),
		Map.entry("minecraft:magma_cube", 0.35), Map.entry("minecraft:bogged", 0.35), Map.entry("minecraft:creaking", 0.7));

	private final Map<UUID, Long> attacked = new HashMap<>();
	private final Deque<Long> killTimes = new ArrayDeque<>();
	private float lastHealth = -1;
	private int lowHealthSince = -1;
	private int nearDeathAllowedAt;
	private int bigDamageAllowedAt;
	private long killStreakAllowedAt;
	private String lastDamageType = "damage";
	private @Nullable String lastDamageCause;
	private long lastDamageAt;

	/** Every recording restarts replay ticks at 0, so tick-based cooldowns must start over too. */
	public void reset() {
		attacked.clear();
		killTimes.clear();
		lastHealth = -1;
		lowHealthSince = -1;
		nearDeathAllowedAt = 0;
		bigDamageAllowedAt = 0;
		killStreakAllowedAt = 0;
		lastDamageType = "damage";
		lastDamageCause = null;
		lastDamageAt = 0;
	}

	public void tick(Minecraft mc, EventSink sink) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		int now = sink.currentTick();
		float health = player.getHealth();
		if (lastHealth >= 0) {
			float drop = lastHealth - health;
			if (drop >= 6 && health > 0 && now >= bigDamageAllowedAt) {
				boolean recent = System.currentTimeMillis() - lastDamageAt <= DAMAGE_SOURCE_MEMORY_MS;
				String type = recent ? lastDamageType : "damage";
				sink.emit(EventType.BIG_DAMAGE, type, Component.translatable("survivalcinema.event.big_damage").getString(),
					null, drop / 20.0, false, recent ? lastDamageCause : null);
				bigDamageAllowedAt = now + BIG_DAMAGE_COOLDOWN_TICKS;
			}
		}
		lastHealth = health;

		if (player.isDeadOrDying()) {
			lowHealthSince = -1;
		} else if (health <= LOW_HEALTH) {
			if (lowHealthSince < 0) {
				lowHealthSince = now;
			}
		} else if (lowHealthSince >= 0) {
			if (now >= nearDeathAllowedAt) {
				sink.emit(EventType.NEAR_DEATH, "near_death", Component.translatable("survivalcinema.event.near_death").getString(),
					null, 0.8, sink.markSeen("near_death", "any"), null, lowHealthSince);
				nearDeathAllowedAt = now + NEAR_DEATH_COOLDOWN_TICKS;
			}
			lowHealthSince = -1;
		}

		long cutoff = System.currentTimeMillis() - KILL_CREDIT_MS;
		attacked.values().removeIf(time -> time < cutoff);
	}

	public void onAttack(Entity target) {
		attacked.put(target.getUUID(), System.currentTimeMillis());
	}

	/** Damage packets tell us what hurt us (lava, a creeper...) and which mobs we hurt from range. */
	public void onDamageEvent(Minecraft mc, ClientboundDamageEventPacket packet) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		Entity victim = mc.level.getEntity(packet.entityId());
		if (victim == mc.player) {
			lastDamageType = packet.sourceType().unwrapKey().map(k -> k.identifier().toString()).orElse("damage");
			Entity cause = mc.level.getEntity(packet.sourceCauseId());
			lastDamageCause = cause == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(cause.getType()).toString();
			lastDamageAt = System.currentTimeMillis();
		} else if (victim != null && packet.sourceCauseId() == mc.player.getId()) {
			attacked.put(victim.getUUID(), System.currentTimeMillis());
		}
	}

	public void onEntityEvent(Minecraft mc, @Nullable Entity entity, byte eventId, @Nullable EventSink sink) {
		if (entity == null || sink == null) {
			return;
		}
		if (eventId == ENTITY_EVENT_TOTEM && entity == mc.player) {
			sink.emit(EventType.TOTEM, "minecraft:totem_of_undying",
				Component.translatable("item.minecraft.totem_of_undying").getString(), null, 1.0, sink.markSeen("totem", "used"), null);
			return;
		}
		if (eventId != ENTITY_EVENT_DEATH || !(entity instanceof LivingEntity) || entity == mc.player) {
			return;
		}
		Long hitAt = attacked.remove(entity.getUUID());
		if (hitAt == null || System.currentTimeMillis() - hitAt > KILL_CREDIT_MS) {
			if (mc.player != null && entity.distanceTo(mc.player) < 16 && entity.getType().getCategory() == MobCategory.MONSTER) {
				// to find kills we miss: a monster dying right next to us without a hit we know of
				SurvivalCinemaClient.LOGGER.debug("A {} died {} blocks away with no hit of ours known ({})", entity.getType().getDescriptionId(),
					Math.round(entity.distanceTo(mc.player)), hitAt == null ? "never hit" : "hit too long ago");
			}
			return;
		}
		String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		Double notable = NOTABLE_MOBS.get(id);
		if (notable == null && entity.getType().getCategory() != MobCategory.MONSTER) {
			return; // animals, villagers, fish...
		}
		double intensity = notable == null ? 0.2 : notable;
		boolean first = sink.markSeen("kill", id);
		sink.emit(EventType.KILL, id, entity.getType().getDescription().getString(), entity.position(), intensity, first, null);

		long now = System.currentTimeMillis();
		killTimes.addLast(now);
		while (!killTimes.isEmpty() && now - killTimes.peekFirst() > 20_000) {
			killTimes.removeFirst();
		}
		if (killTimes.size() >= 3 && now >= killStreakAllowedAt) {
			sink.emit(EventType.KILL_STREAK, "kill_streak", Component.translatable("survivalcinema.event.kill_streak", killTimes.size()).getString(),
				null, Math.min(1, killTimes.size() / 6.0), false, String.valueOf(killTimes.size()));
			killStreakAllowedAt = now + 30_000;
		}
	}

	/** Our own death; the subject is the death message key, so "first death by lava" counts as new. */
	public void onOwnDeath(Component message, @Nullable EventSink sink) {
		if (sink == null) {
			return;
		}
		String cause = message.getContents() instanceof TranslatableContents tc ? tc.getKey() : "death";
		boolean first = sink.markSeen("death", cause);
		sink.markSeen("death", "any");
		sink.emit(EventType.DEATH, cause, message.getString(), null, 1.0, first, null);
	}
}
