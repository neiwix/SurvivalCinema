package com.survivalcinema.client.mixin;

import com.survivalcinema.client.capture.CaptureHooks;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientAdvancements.class)
public abstract class ClientAdvancementsMixin {
	@Inject(method = "update", at = @At("TAIL"))
	private void survivalcinema$afterUpdate(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
		CaptureHooks.onAdvancementsUpdated((ClientAdvancements) (Object) this, packet);
	}
}
