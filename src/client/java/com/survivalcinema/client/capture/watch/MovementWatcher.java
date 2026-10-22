package com.survivalcinema.client.capture.watch;

import com.survivalcinema.client.capture.EventSink;
import com.survivalcinema.director.event.EventType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Elytra flights and survived big falls.
 */
public final class MovementWatcher {
	private static final int MIN_FLIGHT_TICKS = 20 * 4;
	private static final double BIG_FALL_BLOCKS = 15;

	private int flightStartTick = -1;
	private Vec3 flightStart;
	private double lastFallDistance;
	private boolean wasOnGround = true;

	public void reset() {
		flightStartTick = -1;
		lastFallDistance = 0;
		wasOnGround = true;
	}

	public void tick(Minecraft mc, EventSink sink) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		int now = sink.currentTick();
		if (player.isFallFlying()) {
			if (flightStartTick < 0) {
				flightStartTick = now;
				flightStart = player.position();
			}
		} else if (flightStartTick >= 0) {
			int duration = now - flightStartTick;
			if (duration >= MIN_FLIGHT_TICKS) {
				boolean first = sink.markSeen("elytra", "flight");
				sink.emit(EventType.ELYTRA_FLIGHT, "minecraft:elytra",
					Component.translatable(first ? "survivalcinema.event.first_flight" : "survivalcinema.event.flight").getString(),
					flightStart, Math.min(1, duration / 400.0), first, String.valueOf(duration), flightStartTick);
			}
			flightStartTick = -1;
		}

		boolean onGround = player.onGround();
		if (onGround && !wasOnGround && lastFallDistance >= BIG_FALL_BLOCKS && !player.isDeadOrDying() && !player.isFallFlying()) {
			sink.emit(EventType.BIG_FALL, "fall", Component.translatable("survivalcinema.event.big_fall", (int) lastFallDistance).getString(),
				null, Math.min(1, lastFallDistance / 40), false, String.valueOf((int) lastFallDistance));
		}
		wasOnGround = onGround;
		lastFallDistance = player.fallDistance;
	}
}
