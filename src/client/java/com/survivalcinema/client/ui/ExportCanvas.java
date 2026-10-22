package com.survivalcinema.client.ui;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;

/**
 * The cinema picture on Flashback's export window. The normal GUI does not run during an export, so this paints with
 * the same two primitives Flashback's own export screen uses: cleared rectangles and batched Minecraft-font text.
 * Must be used while the window's render target is the current output (inside Flashback's frame).
 */
final class ExportCanvas implements CinemaCanvas {
	private static CachedOrthoProjectionMatrixBuffer projection;

	private final RenderTarget target;
	private final int scale;
	private final Font font;
	private final MultiBufferSource.BufferSource buffers;
	private final CommandEncoder encoder;

	ExportCanvas(RenderTarget target, int scale) {
		Minecraft mc = Minecraft.getInstance();
		this.target = target;
		this.scale = Math.max(1, scale);
		this.font = mc.font;
		this.buffers = mc.renderBuffers().bufferSource();
		this.encoder = RenderSystem.getDevice().createCommandEncoder();
	}

	@Override
	public int width() {
		return (int) Math.ceil(target.width / (double) scale);
	}

	@Override
	public int height() {
		return (int) Math.ceil(target.height / (double) scale);
	}

	@Override
	public void fill(int x, int y, int width, int height, int argb) {
		GpuTexture color = target.getColorTexture();
		GpuTexture depth = target.getDepthTexture();
		if (color == null || depth == null) {
			return;
		}
		int left = Math.max(0, x * scale);
		int top = Math.max(0, y * scale);
		int right = Math.min(target.width, (x + width) * scale);
		int bottom = Math.min(target.height, (y + height) * scale);
		if (right <= left || bottom <= top) {
			return;
		}
		// Rectangles are cleared right away (text is drawn later, on top); GL counts rows from the bottom.
		encoder.clearColorAndDepthTextures(color, argb, depth, 1.0, left, target.height - bottom, right - left, bottom - top);
	}

	@Override
	public void text(String text, int x, int y, int argb, int scale) {
		Matrix4f matrix = new Matrix4f().translate(x, y, 0).scale(scale, scale, 1);
		font.drawInBatch(text, 0, 0, argb, true, matrix, buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
	}

	@Override
	public int textWidth(String text) {
		return font.width(text);
	}

	/** Draws the queued text in GUI units over the whole window. */
	void finish() {
		if (projection == null) {
			projection = new CachedOrthoProjectionMatrixBuffer("survivalcinema cinema", -1000f, 1000f, true);
		}
		RenderSystem.setProjectionMatrix(projection.getBuffer(target.width / (float) scale, target.height / (float) scale),
			ProjectionType.ORTHOGRAPHIC);
		buffers.endBatch();
	}
}
