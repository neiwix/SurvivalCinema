package com.survivalcinema.client.mixin;

import com.survivalcinema.client.flashback.FlashbackBridge;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In a replay, a player sometimes comes back with every outer skin layer off (hat, jacket, sleeves: the "which parts
 * of the skin to show" byte arrives empty after some jumps in time), and the film shows a bare head in one shot and the
 * full skin in the next. Nobody turns all of them off to play: in a replay an empty byte is taken as lost, and the
 * player's own one (seen earlier in the replay) or all layers are shown instead.
 */
@Mixin(Avatar.class)
public abstract class AvatarMixin {
	@Shadow
	@Final
	protected static EntityDataAccessor<Byte> DATA_PLAYER_MODE_CUSTOMISATION;

	/** The last real choice of layers of each player seen in the replay. */
	private static final Map<UUID, Byte> survivalcinema$layers = new ConcurrentHashMap<>();
	private static final byte ALL_LAYERS = 0x7F;

	@Inject(method = "isModelPartShown", at = @At("HEAD"), cancellable = true)
	private void survivalcinema$keepSkinLayers(PlayerModelPart part, CallbackInfoReturnable<Boolean> cir) {
		if (!FlashbackBridge.isInReplay()) {
			return;
		}
		Entity self = (Entity) (Object) this;
		byte shown = self.getEntityData().get(DATA_PLAYER_MODE_CUSTOMISATION);
		UUID id = self.getUUID();
		if (shown != 0) {
			survivalcinema$layers.put(id, shown);
			return;
		}
		byte layers = survivalcinema$layers.getOrDefault(id, ALL_LAYERS);
		cir.setReturnValue((layers & part.getMask()) == part.getMask());
	}
}
