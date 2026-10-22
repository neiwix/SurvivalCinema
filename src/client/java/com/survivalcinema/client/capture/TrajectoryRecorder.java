package com.survivalcinema.client.capture;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.director.camera.TrailIO;
import com.survivalcinema.director.camera.TrailSample;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.UUID;

/**
 * While Flashback records, writes the local player's position every other replay tick. Cameras are later built
 * from this trail, which is far smoother than following the player's head like Flashback's own tracking does.
 */
public final class TrajectoryRecorder {
	private static final int SAMPLE_EVERY_TICKS = 2;

	private @Nullable UUID replayId;
	private @Nullable TrailIO.Writer writer;
	private int lastWrittenTick = Integer.MIN_VALUE;

	public void onClientTick(Minecraft mc) {
		UUID recording = FlashbackBridge.recordingReplayId();
		if (recording == null || !recording.equals(replayId)) {
			close();
			if (recording == null) {
				return;
			}
			open(recording);
		}
		if (writer == null || FlashbackBridge.isRecordingPaused()) {
			return;
		}
		LocalPlayer player = mc.player;
		int tick = FlashbackBridge.recordingTick();
		if (player == null || tick < 0 || tick == lastWrittenTick || tick % SAMPLE_EVERY_TICKS != 0) {
			return;
		}
		lastWrittenTick = tick;
		try {
			writer.write(new TrailSample(tick, player.getX(), player.getY(), player.getZ(),
				player.getYRot(), player.getXRot(), flags(player)));
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.error("Failed to write trail, stopping trail recording for this session", e);
			close();
		}
	}

	private void open(UUID id) {
		replayId = id;
		lastWrittenTick = Integer.MIN_VALUE;
		try {
			writer = new TrailIO.Writer(SessionStore.trailFile(id));
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.error("Failed to open trail file for {}", id, e);
			writer = null;
		}
	}

	public void close() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException e) {
				SurvivalCinemaClient.LOGGER.warn("Failed to close trail file", e);
			}
		}
		writer = null;
		replayId = null;
	}

	private static int flags(LocalPlayer player) {
		int flags = 0;
		if (player.onGround()) {
			flags |= TrailSample.FLAG_ON_GROUND;
		}
		if (player.isFallFlying()) {
			flags |= TrailSample.FLAG_FLYING_ELYTRA;
		}
		if (player.isPassenger()) {
			flags |= TrailSample.FLAG_IN_VEHICLE;
		}
		if (player.isSwimming()) {
			flags |= TrailSample.FLAG_SWIMMING;
		}
		if (player.isShiftKeyDown()) {
			flags |= TrailSample.FLAG_SNEAKING;
		}
		if (player.isDeadOrDying()) {
			flags |= TrailSample.FLAG_DEAD; // the respawn is then a cut, however close to the body it is
		}
		if (player.isSleeping()) {
			flags |= TrailSample.FLAG_SLEEPING; // the camera holds still while they sleep
		}
		flags |= dimensionIndex(player.level()) << 8;
		return flags;
	}

	private static int dimensionIndex(Level level) {
		if (level.dimension() == Level.OVERWORLD) {
			return 0;
		}
		if (level.dimension() == Level.NETHER) {
			return 1;
		}
		if (level.dimension() == Level.END) {
			return 2;
		}
		return 3;
	}
}
