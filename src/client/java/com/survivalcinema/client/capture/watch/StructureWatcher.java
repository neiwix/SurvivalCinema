package com.survivalcinema.client.capture.watch;

import com.survivalcinema.client.capture.EventSink;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.StructureKinds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Places explored: coming into a structure (a shipwreck, an outpost, a village) for the first time, and opening one of
 * its chests while still full. Only the game's own server knows where structures are and what a chest holds, so this
 * works in single player (and in a LAN world we host); on someone else's server nothing is noticed.
 * <p>
 * The questions are asked on the server's thread; the answers come back to ours.
 */
public final class StructureWatcher {
	private static final int CHECK_EVERY_TICKS = 20;

	private record Entered(String structureId, String instance, Vec3 at) {
	}

	private record Opened(String lootTable, String dimension, BlockPos pos) {
	}

	private final Queue<Object> answers = new ConcurrentLinkedQueue<>();
	private int nextCheck;

	public void reset() {
		answers.clear();
		nextCheck = 0;
	}

	/**
	 * We right-clicked a block: when it is a chest of a structure still holding its loot table, we are opening it for
	 * the first time (opening fills it and drops the table; a chest people placed never had one). The question is
	 * queued before our click reaches the server, so it is answered before the chest opens.
	 */
	public void onUse(Minecraft mc, BlockPos pos) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.level == null) {
			return;
		}
		ResourceKey<Level> dimension = mc.level.dimension();
		BlockPos at = pos.immutable();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			if (level != null && level.isLoaded(at) && level.getBlockEntity(at) instanceof RandomizableContainer container
				&& container.getLootTable() != null) {
				answers.add(new Opened(container.getLootTable().identifier().toString(), dimension.identifier().toString(), at));
			}
		});
	}

	public void tick(Minecraft mc, EventSink sink) {
		report(sink);
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null || mc.level == null || sink.currentTick() < nextCheck) {
			return;
		}
		nextCheck = sink.currentTick() + CHECK_EVERY_TICKS;
		ResourceKey<Level> dimension = mc.level.dimension();
		BlockPos at = mc.player.blockPosition();
		Vec3 position = mc.player.position();
		server.execute(() -> {
			ServerLevel level = server.getLevel(dimension);
			if (level == null || !level.isLoaded(at)) {
				return;
			}
			StructureManager structures = level.structureManager();
			Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
			for (Structure structure : structures.getAllStructuresAt(at).keySet()) {
				StructureStart start = structures.getStructureWithPieceAt(at, structure);
				Identifier id = registry.getKey(structure);
				if (start.isValid() && id != null) {
					answers.add(new Entered(id.toString(), dimension.identifier() + "/" + id + "@" + start.getChunkPos().toLong(), position));
				}
			}
		});
	}

	/** Each structure once (its first visit), each chest once; the first of a kind in the world is marked as such. */
	private void report(EventSink sink) {
		for (Object answer = answers.poll(); answer != null; answer = answers.poll()) {
			if (answer instanceof Entered e) {
				String kind = StructureKinds.ofStructure(e.structureId());
				if (kind != null && sink.markSeen("structure", e.instance())) {
					sink.emit(EventType.STRUCTURE, kind, name(kind), e.at(), 0.7, sink.markSeen("structure_kind", kind),
						"id=" + e.structureId());
				}
			} else if (answer instanceof Opened o) {
				String kind = StructureKinds.ofLootTable(o.lootTable());
				String chest = o.dimension() + "/" + o.pos().getX() + "," + o.pos().getY() + "," + o.pos().getZ();
				if (kind != null && sink.markSeen("loot", chest)) {
					sink.emit(EventType.LOOT, kind, name(kind), Vec3.atCenterOf(o.pos()), 0.7, sink.markSeen("loot_kind", kind),
						"loot=" + o.lootTable());
				}
			}
		}
	}

	private static String name(String kind) {
		return Component.translatableWithFallback("survivalcinema.structure." + kind, StructureKinds.readable(kind)).getString();
	}
}
