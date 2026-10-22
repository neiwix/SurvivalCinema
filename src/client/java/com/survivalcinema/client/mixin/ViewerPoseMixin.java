package com.survivalcinema.client.mixin;

import com.survivalcinema.client.render.CinemaMode;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While we film, the camera sits at the replay viewer's standing eye height, whatever pose the viewer is in. Flashback
 * films through its viewer's eyes, and the viewer is no spectator: with its feet in a wall (a camera planned just over
 * one) the game crouched it or laid it down, and the picture dropped to the top of the wall, all planks.
 */
@Mixin(Entity.class)
public abstract class ViewerPoseMixin {
	@Inject(method = "getEyeHeight()F", at = @At("HEAD"), cancellable = true)
	private void survivalcinema$standingEyesWhileFilming(CallbackInfoReturnable<Float> cir) {
		if (CinemaMode.isFilming() && (Object) this == Minecraft.getInstance().player) {
			cir.setReturnValue(((Entity) (Object) this).getEyeHeight(Pose.STANDING));
		}
	}
}
