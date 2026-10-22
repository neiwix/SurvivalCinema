package com.survivalcinema.client.mixin.flashback;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.moulberry.flashback.exporting.ExportJob;
import com.survivalcinema.client.flashback.FlashbackHooked;
import com.survivalcinema.client.render.CinemaMode;
import com.survivalcinema.client.ui.CinemaCurtain;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * While Flashback renders one of our clips it takes over the game loop and draws its own export screen (a preview of
 * the frame, its texts and links). We paint our cinema screen over that picture right before it goes to the window,
 * so the player keeps seeing "Shooting the film..." instead. The exported picture itself is not touched; only the
 * sounds Flashback's run-up to a clip sets off are cut before its first frame.
 */
@Mixin(value = ExportJob.class, remap = false)
public abstract class ExportJobMixin implements FlashbackHooked {
	@Shadow(remap = false)
	private static RenderTarget displayTarget;

	@Shadow(remap = false)
	private long escapeCancelStartMillis;

	@Inject(method = "finishFrame", remap = false,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V",
			shift = At.Shift.AFTER, remap = true))
	private void survivalcinema$paintCinema(RenderTarget frame, List<String> lines, boolean force, boolean allowCancel,
											CallbackInfoReturnable<Boolean> cir) {
		if (CinemaMode.isFilming() && displayTarget != null) {
			ExportJob job = (ExportJob) (Object) this;
			float progress = job.progressOutOf > 0 ? job.progressCount / (float) job.progressOutOf : 0;
			long held = allowCancel && escapeCancelStartMillis > 0 ? System.currentTimeMillis() - escapeCancelStartMillis : 0;
			CinemaCurtain.paintOverExport(displayTarget, progress, held);
		}
	}

	/**
	 * Before the first frame Flashback jumps two seconds back and plays them through to the clip's start; jumping back
	 * replays the recording from its last snapshot, and every sound on the way goes off at once into the clip's first
	 * frames (the zombies of a fight at the base, heard over the next clips there). Those are silenced; the clip's own
	 * sounds start with its first tick.
	 */
	@Inject(method = "setup", at = @At("RETURN"), remap = false)
	private void survivalcinema$quietStart(CallbackInfo ci) {
		if (CinemaMode.isFilming()) {
			Minecraft.getInstance().getSoundManager().stop();
		}
	}

	@Inject(method = "finishFrame", at = @At("RETURN"), remap = false)
	private void survivalcinema$noticeCancel(RenderTarget frame, List<String> lines, boolean force, boolean allowCancel,
											 CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			CinemaMode.noteExportCancelled();
		}
	}
}
