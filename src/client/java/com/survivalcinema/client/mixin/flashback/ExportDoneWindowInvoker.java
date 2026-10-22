package com.survivalcinema.client.mixin.flashback;

import com.moulberry.flashback.editor.ui.windows.ExportDoneWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Empties Flashback's list of finished exports (closing their thumbnails). Only used through FlashbackBridge.
 */
@Mixin(value = ExportDoneWindow.class, remap = false)
public interface ExportDoneWindowInvoker {
	@Invoker(value = "clear", remap = false)
	static void survivalcinema$clear() {
		throw new AssertionError();
	}
}
