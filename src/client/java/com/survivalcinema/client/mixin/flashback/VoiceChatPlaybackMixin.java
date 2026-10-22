package com.survivalcinema.client.mixin.flashback;

import com.moulberry.flashback.compat.simple_voice_chat.SimpleVoiceChatPlayback;
import com.moulberry.flashback.packet.FlashbackVoiceChatSound;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.client.flashback.FlashbackHooked;
import com.survivalcinema.client.render.CinemaMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In the slow motion of a clip we shoot, Flashback's playback of the recorded voice chat stays silent: it would play
 * the voices at the replay's speed (stuttering). Our own copy is laid under the slowed picture at normal speed when the
 * film is edited. Only applied when Simple Voice Chat is installed (Flashback loads this class only then).
 */
@Mixin(value = SimpleVoiceChatPlayback.class, remap = false)
public abstract class VoiceChatPlaybackMixin implements FlashbackHooked {
	@Inject(method = "play", at = @At("HEAD"), cancellable = true, remap = false)
	private static void survivalcinema$quietInSlowMotion(FlashbackVoiceChatSound sound, CallbackInfo ci) {
		if (CinemaMode.muteVoiceChatAt(FlashbackBridge.replayTick())) {
			ci.cancel();
		}
	}
}
