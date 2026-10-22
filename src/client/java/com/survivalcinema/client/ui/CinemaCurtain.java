package com.survivalcinema.client.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.render.CinemaMode;
import com.survivalcinema.client.render.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * Paints the cinema picture over whatever else the game would show while a film is being shot: vanilla loading
 * screens and the bare replay world (through GameRendererMixin), and Flashback's export screen (ExportJobMixin).
 * When our {@link CinemaScreen} itself is open it draws the same picture, so nothing changes for the player.
 */
public final class CinemaCurtain {
	private static boolean exportPaintingBroken;

	private CinemaCurtain() {
	}

	/** Whether this screen is hidden behind the curtain (anything but our own cinema screen, while filming). */
	public static boolean covers(Screen screen) {
		return CinemaMode.isFilming() && !(screen instanceof CinemaScreen);
	}

	/** Called at the end of every normal game frame. */
	public static void paintOverGui(GuiGraphics graphics) {
		Minecraft mc = Minecraft.getInstance();
		RenderPipeline pipeline = RenderPipeline.current();
		if (!CinemaMode.isFilming() || pipeline == null || mc.screen instanceof CinemaScreen) {
			return;
		}
		CinemaMode.rememberGuiScale(mc.getWindow().getGuiScale());
		graphics.nextStratum(); // above the HUD, toasts and anything else drawn this frame
		CinemaPainter.paint(new GuiCanvas(graphics, mc.font, graphics.guiWidth(), graphics.guiHeight()),
			pipeline.status(-1), 0, false);
	}

	/**
	 * Called for every frame Flashback shows while it renders one of our clips.
	 *
	 * @param exportProgress share of the clip rendered, 0..1
	 * @param escHeldMillis  how long Esc has been held (Flashback cancels the clip at three seconds)
	 */
	public static void paintOverExport(RenderTarget window, float exportProgress, long escHeldMillis) {
		RenderPipeline pipeline = RenderPipeline.current();
		if (pipeline == null || exportPaintingBroken) {
			return;
		}
		try {
			ExportCanvas canvas = new ExportCanvas(window, CinemaMode.guiScale());
			CinemaPainter.paint(canvas, pipeline.status(exportProgress), escHeldMillis, true);
			canvas.finish();
		} catch (RuntimeException e) {
			// An exception here would end the export (and the game) in the middle of a clip. Showing Flashback's own
			// export screen is much better than that.
			exportPaintingBroken = true;
			SurvivalCinemaClient.LOGGER.error("Could not draw the cinema screen during the export, showing Flashback's", e);
		}
	}
}
