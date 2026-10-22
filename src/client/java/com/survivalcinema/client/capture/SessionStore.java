package com.survivalcinema.client.capture;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Where our per-recording data lives: {@code .minecraft/survivalcinema/sessions/<replay id>/}.
 * The folder name is the Flashback replay id, so a replay zip can always be matched to its data.
 */
public final class SessionStore {
	public static final String TRAIL_FILE = "trail.bin";

	private SessionStore() {
	}

	public static Path root() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("survivalcinema");
	}

	public static Path sessionFolder(UUID replayId) {
		return root().resolve("sessions").resolve(replayId.toString());
	}

	public static Path trailFile(UUID replayId) {
		return sessionFolder(replayId).resolve(TRAIL_FILE);
	}

	/** Every film shot of a world: {@code survivalcinema/films/<world key>/<date>/}. */
	public static Path filmsFolder(String worldKey) {
		return root().resolve("films").resolve(worldKey);
	}
}
