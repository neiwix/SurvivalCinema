package com.survivalcinema.client.capture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Static entry points for mixins, so mixin classes stay tiny.
 */
public final class CaptureHooks {
	private static final byte ENTITY_EVENT_DEATH = 3;
	private static RecordingController controller;

	private CaptureHooks() {
	}

	public static void install(RecordingController recordingController) {
		controller = recordingController;
	}

	public static void onAdvancementsUpdated(ClientAdvancements advancements, ClientboundUpdateAdvancementsPacket packet) {
		if (controller != null) {
			controller.advancements.onUpdate(advancements, packet, controller.sink());
		}
	}

	public static void onEntityEvent(ClientboundEntityEventPacket packet) {
		Minecraft mc = Minecraft.getInstance();
		if (controller != null && mc.level != null) {
			Entity entity = packet.getEntity(mc.level);
			controller.combat.onEntityEvent(mc, entity, packet.getEventId(), controller.sink());
			if (packet.getEventId() == ENTITY_EVENT_DEATH) {
				controller.bosses.onEntityDeath(entity);
			}
		}
	}

	public static void onDamageEvent(ClientboundDamageEventPacket packet) {
		Minecraft mc = Minecraft.getInstance();
		if (controller != null && mc.level != null) {
			controller.combat.onDamageEvent(mc, packet);
		}
	}

	/** Within this distance of the sound a friend counts as the one who placed the block. */
	private static final double FRIEND_REACH = 6;

	/**
	 * The server plays "block placed" to everyone but the player who placed it, so this sound near a friend is the
	 * friend building (our own placements are caught when we use the item).
	 */
	public static void onSound(ClientboundSoundPacket packet) {
		Minecraft mc = Minecraft.getInstance();
		EventSink sink = controller == null ? null : controller.sink();
		if (sink == null || mc.level == null || mc.player == null || packet.getSource() != SoundSource.BLOCKS
			|| !packet.getSound().value().location().getPath().endsWith(".place")) {
			return;
		}
		Vec3 at = new Vec3(packet.getX(), packet.getY(), packet.getZ());
		Player builder = null;
		double best = FRIEND_REACH;
		for (Player other : mc.level.players()) {
			double distance = other.getEyePosition().distanceTo(at);
			if (other != mc.player && !other.isSpectator() && distance <= best) {
				best = distance;
				builder = other;
			}
		}
		if (builder != null) {
			controller.builds.onFriendPlace(BlockPos.containing(at), builder.getName().getString(), sink);
		}
	}

	public static void onPlayerCombatKill(ClientboundPlayerCombatKillPacket packet) {
		Minecraft mc = Minecraft.getInstance();
		if (controller != null && mc.player != null && packet.playerId() == mc.player.getId()) {
			controller.combat.onOwnDeath(packet.message(), controller.sink());
		}
	}
}
