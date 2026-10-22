package com.survivalcinema.client.ui;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.WorldMemory;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.client.render.RenderPipeline;
import com.survivalcinema.director.film.DragonOffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Hears about the dragon dying in a recorded session and shows {@link DragonOfferScreen} at the right moment (after the
 * poem and the credits, see {@link DragonOffer}). Once per world: remembered in the world's memory.
 */
public final class DragonOfferWatcher {
	private static final String SEEN_CATEGORY = "offer";
	private static final String SEEN_ID = "dragon";

	private final DragonOffer offer = new DragonOffer();
	private @Nullable WorldMemory world;

	/** The dragon died in a recording of this world. */
	public void dragonKilled(WorldMemory world) {
		if (world.hasSeen(SEEN_CATEGORY, SEEN_ID) || this.world != null) {
			return;
		}
		SurvivalCinemaClient.LOGGER.info("The dragon is dead: the film will be offered after the credits");
		this.world = world;
		offer.dragonKilled();
	}

	/** Every client tick. */
	public void tick(Minecraft mc) {
		WorldMemory waiting = world;
		if (waiting == null) {
			return;
		}
		if (FlashbackBridge.isInReplay() || RenderPipeline.isRunning() || SurvivalCinemaClient.controller().world() != waiting) {
			offer.reset(); // left the world first: the moment is gone
			world = null;
			return;
		}
		LocalPlayer player = mc.player;
		boolean credits = mc.screen instanceof WinScreen;
		boolean inEnd = mc.level != null && mc.level.dimension() == Level.END;
		boolean calm = mc.screen == null && mc.getOverlay() == null && player != null && player.isAlive()
			&& player.hurtTime == 0 && player.onGround();
		if (offer.tick(credits, inEnd, calm)) {
			waiting.markSeen(SEEN_CATEGORY, SEEN_ID);
			world = null;
			mc.setScreen(new DragonOfferScreen());
		}
	}
}
