package com.survivalcinema.client.capture;

import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.client.flashback.FlashbackBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Pauses the recording when nobody touches the game for a while (AFK farms, walking away),
 * so those hours neither fill the disk nor end up in the film.
 * <p>
 * Talking counts as being there: friends standing still and chatting are exactly the moments the film wants,
 * so voice chat and text chat keep the recording going.
 */
public final class AfkDetector {
	private Vec3 lastPos;
	private float lastYaw;
	private float lastPitch;
	private int idleTicks;
	private boolean pausedByUs;
	private volatile boolean activity;

	public boolean isPaused() {
		return pausedByUs;
	}

	/** Voice or chat activity; may be called from any thread. */
	public void noteActivity() {
		activity = true;
	}

	public void reset() {
		lastPos = null;
		idleTicks = 0;
		activity = false;
		if (pausedByUs) {
			FlashbackBridge.setRecordingPaused(false);
		}
		pausedByUs = false;
	}

	public void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		int afkMinutes = SurvivalCinemaConfig.get().afkMinutes;
		if (player == null || afkMinutes <= 0) {
			return;
		}
		Vec3 pos = player.position();
		boolean moved = lastPos == null
			|| pos.distanceToSqr(lastPos) > 0.0004
			|| Math.abs(player.getYRot() - lastYaw) > 0.5f
			|| Math.abs(player.getXRot() - lastPitch) > 0.5f;
		boolean usingUi = mc.screen != null && !(mc.screen instanceof PauseScreen);
		boolean social = activity;
		activity = false;
		lastPos = pos;
		lastYaw = player.getYRot();
		lastPitch = player.getXRot();

		if (moved || usingUi || social) {
			idleTicks = 0;
			if (pausedByUs) {
				FlashbackBridge.setRecordingPaused(false);
				pausedByUs = false;
			}
			return;
		}
		idleTicks++;
		if (!pausedByUs && idleTicks >= afkMinutes * 60 * 20 && FlashbackBridge.isRecording() && !FlashbackBridge.isRecordingPaused()) {
			FlashbackBridge.setRecordingPaused(true);
			pausedByUs = true;
		}
	}
}
