package com.survivalcinema.client.mixin.flashback;

import com.moulberry.flashback.editor.ui.ReplayUI;
import com.survivalcinema.client.flashback.FlashbackHooked;
import com.survivalcinema.client.render.CinemaMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Flashback's editor (its ImGui windows, timeline and resized game view) switched off while we film, so the
 * player only ever sees our cinema screen. Flashback treats this exactly like pressing F1 in a replay.
 */
@Mixin(value = ReplayUI.class, remap = false)
public abstract class ReplayUIMixin implements FlashbackHooked {
	@Inject(method = "isActiveInternal", at = @At("HEAD"), cancellable = true, remap = false)
	private static void survivalcinema$hideWhileFilming(CallbackInfoReturnable<Boolean> cir) {
		if (CinemaMode.isFilming()) {
			cir.setReturnValue(false);
		}
	}
}
