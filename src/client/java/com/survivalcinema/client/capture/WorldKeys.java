package com.survivalcinema.client.capture;

import com.survivalcinema.director.event.WorldKeyFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Stable folder-safe id for "this world": the save folder in singleplayer, the server address in multiplayer
 * (without the port for LAN games, which get a random port each time).
 */
public final class WorldKeys {
	private WorldKeys() {
	}

	public record WorldId(String key, String displayName) {
	}

	public static @Nullable WorldId current(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			String folder = root.getFileName() == null ? "world" : root.getFileName().toString();
			return new WorldId(WorldKeyFormat.key("sp", folder), server.getWorldData().getLevelName());
		}
		ServerData data = mc.getCurrentServer();
		if (data != null) {
			String address = data.isLan() ? WorldKeyFormat.lanHost(data.ip) : data.ip;
			return new WorldId(WorldKeyFormat.key(data.isLan() ? "lan" : "mp", address), data.name);
		}
		return null;
	}
}
