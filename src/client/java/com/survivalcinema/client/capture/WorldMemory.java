package com.survivalcinema.client.capture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.director.event.SafeFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What we know about one world across all its sessions: the recording choice, and which "firsts" already happened
 * (first diamond, first Nether visit...), so the film can tell them apart from the hundredth time.
 * Stored in {@code survivalcinema/worlds/<world key>/world.json}.
 */
public final class WorldMemory {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public enum Consent {ASK, ALWAYS, NEVER}

	public String worldKey = "";
	public String displayName = "";
	public Consent consent = Consent.ASK;
	public boolean recordVoiceChat = true;
	/** Replay ids of every recorded session, oldest first. */
	public List<String> sessions = new ArrayList<>();
	/** Replay ids the player left out of the next film (in the list of what goes into it). */
	public Set<String> skippedSessions = new LinkedHashSet<>();
	/** category -> ids seen, e.g. "biome" -> {"minecraft:desert"}. */
	public Map<String, Set<String>> seen = new LinkedHashMap<>();

	private transient Path file;

	public static Path folder(String worldKey) {
		return SessionStore.root().resolve("worlds").resolve(worldKey);
	}

	public static WorldMemory load(String worldKey, String displayName) {
		Path file = folder(worldKey).resolve("world.json");
		WorldMemory memory = read(file);
		if (memory == null && Files.exists(SafeFiles.backup(file))) {
			// The world's whole film history lives here: never start fresh while a backup can be read.
			memory = read(SafeFiles.backup(file));
			SurvivalCinemaClient.LOGGER.warn("{} was unreadable, restored the previous version", file);
		}
		if (memory == null) {
			memory = new WorldMemory();
		}
		memory.worldKey = worldKey;
		memory.displayName = displayName;
		if (memory.sessions == null) {
			memory.sessions = new ArrayList<>();
		}
		if (memory.skippedSessions == null) {
			memory.skippedSessions = new LinkedHashSet<>();
		}
		if (memory.seen == null) {
			memory.seen = new LinkedHashMap<>();
		}
		if (memory.consent == null) {
			memory.consent = Consent.ASK;
		}
		memory.file = file;
		return memory;
	}

	private static WorldMemory read(Path file) {
		if (!Files.exists(file)) {
			return null;
		}
		try {
			return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), WorldMemory.class);
		} catch (Exception e) {
			SurvivalCinemaClient.LOGGER.warn("Broken {}", file, e);
			return null;
		}
	}

	/** Records that something happened; returns true when it is the first time in this world. */
	public boolean markSeen(String category, String id) {
		boolean first = seen.computeIfAbsent(category, k -> new LinkedHashSet<>()).add(id);
		if (first) {
			save();
		}
		return first;
	}

	public boolean hasSeen(String category, String id) {
		Set<String> ids = seen.get(category);
		return ids != null && ids.contains(id);
	}

	public void save() {
		try {
			SafeFiles.writeAtomically(file, GSON.toJson(this));
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.warn("Could not save world memory {}", file, e);
		}
	}
}
