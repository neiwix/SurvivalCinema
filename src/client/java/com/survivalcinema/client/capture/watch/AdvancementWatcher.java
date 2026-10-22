package com.survivalcinema.client.capture.watch;

import com.survivalcinema.client.capture.EventSink;
import com.survivalcinema.client.mixin.ClientAdvancementsAccessor;
import com.survivalcinema.director.event.EventType;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Advancements are the story of a survival world: first iron, diamonds, the Nether, the End...
 * The first packet after joining resends everything already done, so that one only fills our state.
 */
public final class AdvancementWatcher {
	public static final String KILL_DRAGON = "minecraft:end/kill_dragon";

	private final Set<Identifier> done = new HashSet<>();
	private boolean initialized;

	public void reset() {
		done.clear();
		initialized = false;
	}

	public void onUpdate(ClientAdvancements advancements, ClientboundUpdateAdvancementsPacket packet, @Nullable EventSink sink) {
		Map<AdvancementHolder, AdvancementProgress> progress = ((ClientAdvancementsAccessor) advancements).survivalcinema$progress();
		if (packet.shouldReset() || !initialized) {
			done.clear();
			progress.forEach((holder, p) -> {
				if (p.isDone()) {
					done.add(holder.id());
				}
			});
			initialized = true;
			return;
		}
		for (Identifier id : packet.getProgress().keySet()) {
			AdvancementHolder holder = advancements.get(id);
			if (holder == null) {
				continue;
			}
			AdvancementProgress p = progress.get(holder);
			if (p == null || !p.isDone() || !done.add(id)) {
				continue;
			}
			Optional<DisplayInfo> display = holder.value().display();
			if (display.isEmpty() || sink == null || holder.value().isRoot()) {
				continue; // recipe unlocks, and the tab roots ("Adventure", "Husbandry") that are granted for nothing
			}
			DisplayInfo info = display.get();
			double intensity = switch (info.getType()) {
				case TASK -> 0.4;
				case GOAL -> 0.7;
				case CHALLENGE -> 1.0;
			};
			String subject = id.toString();
			boolean first = sink.markSeen("advancement", subject);
			sink.emit(EventType.ADVANCEMENT, subject, info.getTitle().getString(), null, intensity, first,
				info.getDescription().getString());
			if (KILL_DRAGON.equals(subject)) {
				sink.dragonKilled(null);
			}
		}
	}
}
