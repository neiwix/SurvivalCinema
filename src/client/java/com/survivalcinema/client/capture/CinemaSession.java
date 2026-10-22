package com.survivalcinema.client.capture;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.director.event.EventLog;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * One recorded play session = one Flashback replay. Writes the noticed moments to {@code events.jsonl}
 * next to the trail, and mirrors them as Flashback markers.
 */
public final class CinemaSession implements EventSink {
	private static final int RECENT_LIMIT = 50;

	private final UUID replayId;
	private final WorldMemory world;
	private final EventLog.Writer writer;
	private final Deque<GameEvent> recent = new ArrayDeque<>();
	private boolean dragonKilled;
	private int lastMarkerTick = -1;
	private String lastMarkerText = "";
	private EventType lastMarkerType = EventType.SESSION_START;
	private @Nullable Runnable onDragonKilled;

	public CinemaSession(UUID replayId, WorldMemory world) throws IOException {
		this.replayId = replayId;
		this.world = world;
		this.writer = new EventLog.Writer(SessionStore.sessionFolder(replayId).resolve(EventLog.FILE_NAME));
		if (!world.sessions.contains(replayId.toString())) {
			world.sessions.add(replayId.toString());
			world.save();
		}
	}

	public UUID replayId() {
		return replayId;
	}

	public WorldMemory world() {
		return world;
	}

	@Override
	public int currentTick() {
		return Math.max(0, FlashbackBridge.recordingTick());
	}

	@Override
	public boolean markSeen(String category, String id) {
		return world.markSeen(category, id);
	}

	@Override
	public void dragonKilled(@Nullable Vec3 position) {
		if (dragonKilled) {
			return;
		}
		dragonKilled = true;
		boolean first = markSeen("dragon", "killed");
		emit(EventType.DRAGON_KILLED, "minecraft:ender_dragon",
			Component.translatable("entity.minecraft.ender_dragon").getString(), position, 1.0, first, null);
		if (onDragonKilled != null) {
			onDragonKilled.run();
		}
	}

	/** Called once when the dragon dies in this session (the "make the movie now?" offer hooks in here). */
	public void setOnDragonKilled(@Nullable Runnable onDragonKilled) {
		this.onDragonKilled = onDragonKilled;
	}

	@Override
	public void emit(EventType type, String subject, String label, @Nullable Vec3 position, double intensity,
					 boolean first, @Nullable String detail, int tickOverride, @Nullable String dimensionOverride) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		Vec3 pos = position != null ? position : player != null ? player.position() : Vec3.ZERO;
		String dimension = dimensionOverride != null ? dimensionOverride
			: mc.level == null ? "" : mc.level.dimension().identifier().toString();
		int day = mc.level == null ? 0 : (int) (mc.level.getDayTime() / 24000L) + 1;
		String actor = player == null ? "" : player.getName().getString();
		int tick = tickOverride >= 0 ? tickOverride : currentTick();

		GameEvent event = new GameEvent(tick, System.currentTimeMillis(), type, subject, label, actor,
			pos.x, pos.y, pos.z, dimension, day, Math.max(0, Math.min(1, intensity)), first, detail);
		try {
			writer.append(event);
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.error("Could not write event {}", event, e);
		}
		recent.addLast(event);
		while (recent.size() > RECENT_LIMIT) {
			recent.removeFirst();
		}

		SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
		if (config.addFlashbackMarkers && type.showAsMarker()) {
			addMarker(type, (first ? "★ " : "") + label, pos, dimension);
		}
		if (config.debugChat) {
			mc.gui.getChat().addMessage(Component.literal("§7[cinema] §f" + type + (first ? " §e(first)" : "") + " §7" + label));
		}
		SurvivalCinemaClient.LOGGER.debug("Event {}", event);
	}

	/**
	 * Flashback keeps one marker per tick, so moments noticed on the same tick (a kill and a kill streak, the dragon
	 * and its advancement) are joined into one marker instead of overwriting each other.
	 */
	private void addMarker(EventType type, String text, Vec3 pos, String dimension) {
		int tick = FlashbackBridge.recordingTick();
		if (tick == lastMarkerTick) {
			text = lastMarkerText + " · " + text;
			if (lastMarkerType.baseWeight() > type.baseWeight()) {
				type = lastMarkerType;
			}
		}
		FlashbackBridge.addMarker(text, type.markerColour(), pos, dimension);
		lastMarkerTick = tick;
		lastMarkerText = text;
		lastMarkerType = type;
	}

	public List<GameEvent> recentEvents() {
		return new ArrayList<>(recent);
	}

	public void close() {
		try {
			writer.close();
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.warn("Could not close event log", e);
		}
	}
}
