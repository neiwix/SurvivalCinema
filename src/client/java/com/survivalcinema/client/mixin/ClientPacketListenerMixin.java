package com.survivalcinema.client.mixin;

import com.survivalcinema.client.capture.CaptureHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TAIL injections only run on the main thread: on the network thread these handlers bail out early
 * by re-scheduling themselves.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleEntityEvent", at = @At("TAIL"))
	private void survivalcinema$afterEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		CaptureHooks.onEntityEvent(packet);
	}

	@Inject(method = "handlePlayerCombatKill", at = @At("TAIL"))
	private void survivalcinema$afterCombatKill(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
		CaptureHooks.onPlayerCombatKill(packet);
	}

	@Inject(method = "handleSoundEvent", at = @At("TAIL"))
	private void survivalcinema$afterSoundEvent(ClientboundSoundPacket packet, CallbackInfo ci) {
		CaptureHooks.onSound(packet);
	}

	@Inject(method = "handleDamageEvent", at = @At("TAIL"))
	private void survivalcinema$afterDamageEvent(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		CaptureHooks.onDamageEvent(packet);
	}
}
