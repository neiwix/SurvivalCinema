package com.survivalcinema.client.edit;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.director.edit.MusicPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Where the film's music comes from: the player's own tracks in {@code survivalcinema/music/}, or else the game's
 * soundtrack from the player's own game files (nothing is bundled with the mod). Game tracks are grouped by where
 * the game plays them: Overworld and menu music for calm footage, Nether music in the Nether, "Boss" for the dragon
 * fight and "Alpha" under the credits, like after the End.
 */
public final class MusicLibrary {
	public enum Source {
		/** The player's folder when it has tracks, the game's music otherwise. */
		AUTO,
		GAME,
		FOLDER,
		OFF;

		public static Source parse(String value) {
			try {
				return value == null ? AUTO : valueOf(value.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return AUTO;
			}
		}
	}

	public interface Opener {
		InputStream open() throws IOException;
	}

	/**
	 * @param title shown in the credits, "C418 - Sweden"
	 */
	public record Track(String id, String title, Opener opener) {
	}

	private static final Set<String> AUDIO_FILES = Set.of("mp3", "ogg", "wav", "flac", "m4a", "aac", "opus");

	private final Map<MusicPlan.Mood, List<Track>> byMood = new EnumMap<>(MusicPlan.Mood.class);
	private final List<Track> own = new ArrayList<>();
	private final boolean gameMusic;

	private MusicLibrary(boolean gameMusic) {
		this.gameMusic = gameMusic;
	}

	public static Path folder(Path gameDirectory) {
		return gameDirectory.resolve("survivalcinema").resolve("music");
	}

	/** Opens the folder for your own tracks in the file explorer, making it first. */
	public static void openFolder(Minecraft mc) {
		Path folder = folder(mc.gameDirectory.toPath());
		try {
			Files.createDirectories(folder);
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.warn("Could not make the music folder {}", folder, e);
		}
		Util.getPlatform().openPath(folder);
	}

	/** Lists the tracks; call on the client thread (reads the game's resources). */
	public static MusicLibrary scan(ResourceManager resources, Path gameDirectory, Source source) {
		if (source == Source.OFF) {
			return new MusicLibrary(false);
		}
		List<Track> folder = source == Source.GAME ? List.of() : folderTracks(folder(gameDirectory));
		if (!folder.isEmpty() || source == Source.FOLDER) {
			MusicLibrary library = new MusicLibrary(false);
			library.own.addAll(folder);
			return library;
		}
		MusicLibrary library = new MusicLibrary(true);
		try {
			Map<Identifier, Resource> found = resources.listResources("sounds/music",
				id -> id.getNamespace().equals("minecraft") && id.getPath().endsWith(".ogg"));
			for (Map.Entry<Identifier, Resource> entry : found.entrySet()) {
				String path = entry.getKey().getPath();
				String relative = path.substring("sounds/music/".length(), path.length() - ".ogg".length());
				MusicPlan.Mood mood = moodOf(relative);
				if (mood == null) {
					continue;
				}
				Resource resource = entry.getValue();
				String title = Language.getInstance().getOrDefault("music." + relative.replace('/', '.'), prettyName(relative));
				library.byMood.computeIfAbsent(mood, m -> new ArrayList<>()).add(new Track(relative, title, resource::open));
			}
		} catch (RuntimeException e) {
			SurvivalCinemaClient.LOGGER.warn("Could not list the game's music", e);
		}
		library.byMood.values().forEach(list -> list.sort((a, b) -> a.id().compareTo(b.id())));
		return library;
	}

	/** Which part of the film a game track suits, or null for tracks the film does not use (underwater music). */
	static MusicPlan.Mood moodOf(String relative) {
		if (relative.startsWith("game/nether/")) {
			return MusicPlan.Mood.NETHER;
		}
		return switch (relative) {
			case "game/end/boss" -> MusicPlan.Mood.BOSS;
			case "game/end/alpha" -> MusicPlan.Mood.CREDITS;
			case "game/end/the_end" -> MusicPlan.Mood.END;
			default -> relative.startsWith("game/water/") || relative.startsWith("game/end/") ? null : MusicPlan.Mood.CALM;
		};
	}

	private static List<Track> folderTracks(Path folder) {
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(Files::isRegularFile)
				.filter(f -> AUDIO_FILES.contains(extension(f)))
				.sorted()
				.map(f -> {
					String name = f.getFileName().toString();
					String title = name.substring(0, name.length() - extension(f).length() - 1);
					return new Track(f.toString(), title, () -> Files.newInputStream(f));
				})
				.toList();
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.warn("Could not read the music folder {}", folder, e);
			return List.of();
		}
	}

	private static String extension(Path file) {
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private static String prettyName(String relative) {
		String name = relative.substring(relative.lastIndexOf('/') + 1).replace('_', ' ');
		return name.isEmpty() ? relative : Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	public boolean isEmpty() {
		return own.isEmpty() && byMood.isEmpty();
	}

	/** The music is the game's own soundtrack (it can get Content ID claims on video sites). */
	public boolean usesGameMusic() {
		return gameMusic && !byMood.isEmpty();
	}

	/**
	 * One track for each cue, different tracks where possible; cues of one thread (the music going on after a pause
	 * for voices) share theirs. The same film always gets the same music.
	 */
	public List<Track> choose(List<MusicPlan.Cue> cues, long seed) {
		Random random = new Random(seed);
		List<Track> chosen = new ArrayList<>();
		Set<String> used = new HashSet<>();
		Map<Integer, Track> threads = new HashMap<>();
		List<Track> shuffledOwn = new ArrayList<>(own);
		Collections.shuffle(shuffledOwn, random);
		int picked = 0;
		for (MusicPlan.Cue cue : cues) {
			if (cue.thread() >= 0 && threads.containsKey(cue.thread())) {
				chosen.add(threads.get(cue.thread()));
				continue;
			}
			Track track;
			if (!shuffledOwn.isEmpty()) {
				track = shuffledOwn.get(picked % shuffledOwn.size());
			} else {
				track = pick(cue.mood(), random, used);
			}
			picked++;
			if (track != null) {
				used.add(track.id());
			}
			if (cue.thread() >= 0) {
				threads.put(cue.thread(), track);
			}
			chosen.add(track);
		}
		return chosen;
	}

	private Track pick(MusicPlan.Mood mood, Random random, Set<String> used) {
		for (MusicPlan.Mood candidate : fallbacks(mood)) {
			List<Track> tracks = byMood.getOrDefault(candidate, List.of()).stream().filter(t -> !used.contains(t.id())).toList();
			if (!tracks.isEmpty()) {
				return tracks.get(random.nextInt(tracks.size()));
			}
		}
		List<Track> any = byMood.getOrDefault(MusicPlan.Mood.CALM, List.of());
		return any.isEmpty() ? null : any.get(random.nextInt(any.size()));
	}

	private static List<MusicPlan.Mood> fallbacks(MusicPlan.Mood mood) {
		return switch (mood) {
			case BOSS -> List.of(MusicPlan.Mood.BOSS, MusicPlan.Mood.END, MusicPlan.Mood.CALM);
			case END -> List.of(MusicPlan.Mood.END, MusicPlan.Mood.CALM);
			case CREDITS -> List.of(MusicPlan.Mood.CREDITS, MusicPlan.Mood.CALM);
			case NETHER -> List.of(MusicPlan.Mood.NETHER, MusicPlan.Mood.CALM);
			case TIMELAPSE -> List.of(MusicPlan.Mood.TIMELAPSE, MusicPlan.Mood.CALM);
			case FLIGHT -> List.of(MusicPlan.Mood.FLIGHT, MusicPlan.Mood.CALM);
			case CALM -> List.of(MusicPlan.Mood.CALM);
		};
	}
}
