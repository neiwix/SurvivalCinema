package com.survivalcinema.client.capture;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.director.camera.CompanionIO;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Once a second while recording, notes which other players are near us and where.
 */
public final class CompanionTracker {
	private static final int EVERY_TICKS = 20;
	private static final double RANGE = 96;

	private @Nullable UUID replayId;
	private @Nullable CompanionIO.Writer writer;
	/** Not Integer.MIN_VALUE: "tick - lastTick" would overflow and nothing would ever be recorded. */
	private int lastTick = -EVERY_TICKS;

	public void tick(Minecraft mc, CinemaSession session) {
		if (!session.replayId().equals(replayId)) {
			close();
			replayId = session.replayId();
			try {
				writer = new CompanionIO.Writer(SessionStore.sessionFolder(replayId).resolve(CompanionIO.FILE_NAME));
			} catch (IOException e) {
				SurvivalCinemaClient.LOGGER.warn("Cannot record companions", e);
			}
		}
		int tick = FlashbackBridge.recordingTick();
		if (writer == null || mc.player == null || mc.level == null || FlashbackBridge.isRecordingPaused()
			|| tick < 0 || tick - lastTick < EVERY_TICKS) {
			return;
		}
		lastTick = tick;
		List<CompanionIO.Position> nearby = new ArrayList<>();
		for (Player other : mc.level.players()) {
			if (other == mc.player || other.isSpectator() || other.distanceTo(mc.player) > RANGE) {
				continue;
			}
			nearby.add(new CompanionIO.Position(other.getName().getString(), other.getX(), other.getY(), other.getZ()));
		}
		if (nearby.isEmpty()) {
			return;
		}
		try {
			writer.write(tick, nearby);
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.warn("Cannot record companions", e);
			close();
		}
	}

	public void close() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException ignored) {
			}
		}
		writer = null;
		replayId = null;
		lastTick = -EVERY_TICKS;
	}
}
