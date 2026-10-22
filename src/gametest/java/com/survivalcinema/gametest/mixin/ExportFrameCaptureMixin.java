package com.survivalcinema.gametest.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.moulberry.flashback.exporting.ExportJob;
import com.survivalcinema.client.render.CinemaMode;
import com.survivalcinema.gametest.FilmAutoTest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Film test only: saves what the window shows half-way through a clip's export (our cinema picture). */
@Mixin(value = ExportJob.class, remap = false)
public abstract class ExportFrameCaptureMixin {
	@Shadow(remap = false)
	private static RenderTarget displayTarget;

	@Inject(method = "finishFrame", remap = false,
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen()V", remap = true))
	private void survivalcinemaTest$capture(RenderTarget frame, List<String> lines, boolean force, boolean allowCancel,
											CallbackInfoReturnable<Boolean> cir) {
		ExportJob job = (ExportJob) (Object) this;
		if (CinemaMode.isFilming() && displayTarget != null && job.progressOutOf > 0
			&& job.progressCount * 2 >= job.progressOutOf) {
			FilmAutoTest.captureExportFrame(displayTarget);
		}
	}
}
