package com.survivalcinema.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.director.render.RenderQuality;
import com.survivalcinema.director.story.FilmLength;
import com.survivalcinema.director.voice.CallApps;
import com.survivalcinema.director.voice.VoiceSource;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code config/survivalcinema.json}. Plain fields so Gson can read and write it; changed in {@link com.survivalcinema.client.ui.SettingsScreen}
 * (from Mod Menu or "Make a film"), the voice settings and the film setup.
 */
public final class SurvivalCinemaConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static SurvivalCinemaConfig instance;

	/** Minutes without any input before recording pauses (0 = never). */
	public int afkMinutes = 3;
	public boolean showRecordingIndicator = true;
	/** Mirror noticed moments as Flashback timeline markers. */
	public boolean addFlashbackMarkers = true;
	/** Developer chat messages for every noticed moment. */
	public boolean debugChat = false;
	/** Last chosen film length in minutes (5, 10, 15 or 20); the film screens start with it. */
	public int filmMinutes = FilmLength.DEFAULT.minutes();

	/** Video quality of the shot clips: DRAFT (720p, 30 fps, fast) or CINEMA (1080p, 60 fps). */
	public String renderQuality = RenderQuality.DRAFT.name();
	/**
	 * Music of the film: AUTO (your tracks in survivalcinema/music, else the game's soundtrack), GAME, FOLDER or OFF.
	 */
	public String music = "AUTO";
	/** Volume of the in-game cinema, 0..1 (on top of the game's master volume). */
	public float playerVolume = 1.0f;
	/**
	 * Where voices come from besides the game's voice chat: AUTO (call apps when they run, plus your microphone when
	 * voice chat isn't carrying it), VOICE_CHAT (only Simple Voice Chat), APPS (call apps and your microphone) or OFF.
	 */
	public String voiceSource = VoiceSource.AUTO.name();
	/** Call apps (ids from {@link CallApps#ALL}) never to record. */
	public List<String> voiceAppsOff = new ArrayList<>();
	/** Record your microphone even without a call (talking over your solo game). */
	public boolean micCommentary = false;
	/** Once a film is edited, delete the recordings it was made of (frees the disk; the film and its clips stay). */
	public boolean deleteRecordingsAfterFilm = false;
	/** The {@code /cinema} developer commands outside the development environment (for bug reports). */
	public boolean debugCommands = false;
	/** "Recording for the film, make it in the pause menu" was shown once. */
	public boolean recordingTipShown = false;

	public FilmLength filmLength() {
		return FilmLength.ofMinutes(filmMinutes);
	}

	public VoiceSource voiceSource() {
		return VoiceSource.parse(voiceSource);
	}

	public RenderQuality renderQuality() {
		return RenderQuality.parse(renderQuality);
	}

	public static SurvivalCinemaConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("survivalcinema.json");
	}

	private static SurvivalCinemaConfig load() {
		Path file = file();
		if (Files.exists(file)) {
			try {
				SurvivalCinemaConfig config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), SurvivalCinemaConfig.class);
				if (config != null) {
					return config;
				}
			} catch (Exception e) {
				SurvivalCinemaClient.LOGGER.warn("Broken {}, using defaults", file, e);
			}
		}
		SurvivalCinemaConfig config = new SurvivalCinemaConfig();
		config.save();
		return config;
	}

	public void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.warn("Could not save config", e);
		}
	}
}
