package com.survivalcinema.client.play;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** The picture on the cinema screen: one texture, overwritten with each new frame of the film; smooth when scaled. */
final class VideoTexture extends DynamicTexture {
	VideoTexture(int width, int height) {
		super(() -> "Survival Cinema film", width, height, true);
		this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
	}

	/** Puts a decoded picture of the same size on the texture. On the render thread. */
	void show(NativeImage picture) {
		RenderSystem.getDevice().createCommandEncoder().writeToTexture(getTexture(), picture);
	}
}
