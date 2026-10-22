package com.survivalcinema.client.mixin.flashback;

import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.record.Recorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads Flashback's private recorder state. Only used through FlashbackBridge.
 */
@Mixin(value = Recorder.class, remap = false)
public interface RecorderAccessor {
	/** The replay tick Flashback will write next; our trail and events are keyed by it. */
	@Accessor(value = "writtenTicks", remap = false)
	int survivalcinema$writtenTicks();

	@Accessor(value = "metadata", remap = false)
	FlashbackMeta survivalcinema$metadata();
}
