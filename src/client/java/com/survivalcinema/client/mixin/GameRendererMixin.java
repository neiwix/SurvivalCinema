package com.survivalcinema.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.survivalcinema.client.ui.CinemaCurtain;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The cinema curtain: while a film is being shot, opening a replay goes through vanilla's "saving world", title and
 * "loading terrain" screens, and between them the bare replay world shows for a moment. All of that is drawn under
 * one cinema picture identical to our screen, so the player sees one calm screen from start to end.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@WrapWithCondition(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/Screen;renderWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
	private boolean survivalcinema$skipCoveredScreen(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		return !CinemaCurtain.covers(screen);
	}

	@Inject(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
	private void survivalcinema$paintCurtain(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci,
											 @Local GuiGraphics graphics) {
		CinemaCurtain.paintOverGui(graphics);
	}
}
