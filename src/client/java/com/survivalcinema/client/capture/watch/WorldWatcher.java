package com.survivalcinema.client.capture.watch;

import com.survivalcinema.client.capture.EventSink;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.client.ui.ChapterTitles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Dimensions, new biomes, days and nights, sleeping.
 */
public final class WorldWatcher {
	private static final int NIGHT_START = 13_000;

	private String lastDimension;
	private long lastDay = -1;
	private long lastDayPhase = -1;
	private boolean wasSleeping;
	private int ticks;

	public void reset() {
		lastDimension = null;
		lastDay = -1;
		lastDayPhase = -1;
		wasSleeping = false;
	}

	public void tick(Minecraft mc, EventSink sink) {
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) {
			return;
		}
		ticks++;

		Identifier dimensionId = level.dimension().identifier();
		String dimension = dimensionId.toString();
		if (lastDimension != null && !lastDimension.equals(dimension)) {
			boolean first = sink.markSeen("dimension", dimension);
			sink.emit(EventType.DIMENSION_CHANGE, dimension, dimensionName(dimensionId), null, first ? 1.0 : 0.4, first, lastDimension);
		} else if (lastDimension == null) {
			sink.markSeen("dimension", dimension);
		}
		lastDimension = dimension;

		if (ticks % 20 == 0) {
			level.getBiome(player.blockPosition()).unwrapKey().ifPresent(key -> {
				Identifier id = key.identifier();
				if (sink.markSeen("biome", id.toString())) {
					String name = Component.translatable("biome." + id.getNamespace() + "." + id.getPath()).getString();
					sink.emit(EventType.NEW_BIOME, id.toString(), name, null, 0.4, true, null);
				}
			});
		}

		long dayTime = level.getDayTime();
		long day = dayTime / 24_000L;
		long phase = dayTime % 24_000L;
		if (lastDay >= 0 && day > lastDay) {
			sink.emit(EventType.NEW_DAY, "day", Component.translatable("survivalcinema.event.day", day + 1).getString(), null, 0.1, false, null);
		}
		if (lastDayPhase >= 0 && lastDay == day && lastDayPhase < NIGHT_START && phase >= NIGHT_START) {
			boolean first = sink.markSeen("night", "first");
			sink.emit(EventType.NIGHT_FALL, "night", Component.translatable(first ? "survivalcinema.event.first_night" : "survivalcinema.event.night").getString(),
				null, first ? 0.8 : 0.2, first, null);
		}
		lastDay = day;
		lastDayPhase = phase;

		boolean sleeping = player.isSleeping();
		if (sleeping && !wasSleeping) {
			sink.emit(EventType.SLEEP, "sleep", Component.translatable("survivalcinema.event.sleep").getString(), null, 0.2, sink.markSeen("sleep", "first"), null);
		}
		wasSleeping = sleeping;
	}

	private static String dimensionName(Identifier id) {
		return ChapterTitles.dimension(id.toString()).getString();
	}
}
