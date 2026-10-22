package com.survivalcinema.client.mixin;

import com.survivalcinema.client.ui.FilmPlayerScreen;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The game's music stops while the film plays in the in-game cinema, and does not start a new track until it closes. */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
	@Shadow
	private SoundInstance currentMusic;

	@Shadow
	public abstract void stopPlaying();

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void survivalcinema$quietDuringFilm(CallbackInfo ci) {
		if (FilmPlayerScreen.isOpen()) {
			if (currentMusic != null) {
				stopPlaying();
			}
			ci.cancel();
		}
	}
}
