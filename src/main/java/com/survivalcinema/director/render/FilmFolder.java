package com.survivalcinema.director.render;

import com.survivalcinema.director.story.EditDecisionList;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * One film on disk: {@code survivalcinema/films/<world>/<date>/} with its screenplay ({@code edl.json}), the shooting
 * progress ({@code render.json}) and the shot clips ({@code clips/001.mp4}...). The screenplay is copied in, so a film
 * can be finished or re-edited even after the world's next plan replaced {@code worlds/<world>/edl.json}.
 */
public final class FilmFolder {
	public static final String EDL_FILE = "edl.json";
	public static final String CLIPS = "clips";
	private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private FilmFolder() {
	}

	/** Folder name of a film started at that time; sorts by date. */
	public static String name(LocalDateTime startedAt) {
		return startedAt.format(NAME);
	}

	public static Path edlFile(Path film) {
		return film.resolve(EDL_FILE);
	}

	public static Path progressFile(Path film) {
		return film.resolve(RenderProgress.FILE_NAME);
	}

	/** Path of a clip file relative to the film folder, as stored in the progress. */
	public static String clipPath(int number, int piece) {
		return CLIPS + "/" + RenderPlan.clipFileName(number, piece);
	}

	public static @Nullable EditDecisionList readEdl(Path film) {
		try {
			return EditDecisionList.fromJson(Files.readString(edlFile(film), StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * File name of the edited film: the world's name, so a film keeps saying what it is after it is copied away.
	 * Characters Windows does not allow in file names are replaced.
	 */
	public static String filmFileName(String worldName) {
		String name = worldName == null ? "" : worldName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
		name = name.replaceAll("[. ]+$", "");
		if (name.matches("(?i)(con|prn|aux|nul|com\\d|lpt\\d)")) {
			name = name + "_film";
		}
		return (name.isEmpty() ? "film" : name) + ".mp4";
	}

	/** Clips are left to shoot, or the film still has to be edited (again, when its file is gone). */
	public static boolean needsWork(Path film, RenderProgress progress) {
		return progress.needsWork() || progress.film != null && !Files.isRegularFile(film.resolve(progress.film));
	}

	/** The newest film of a world with every clip shot (edited or not), or null. */
	public static @Nullable Path latestShot(Path worldFilms) {
		for (Path film : newestFirst(worldFilms)) {
			RenderProgress progress = RenderProgress.read(progressFile(film));
			if (progress != null && progress.finished && progress.count(RenderProgress.Status.DONE) > 0
				&& Files.isRegularFile(edlFile(film))) {
				return film;
			}
		}
		return null;
	}

	/** The newest edited film file of a world ({@code <date>/<world>.mp4}), or null when none is made yet. */
	public static @Nullable Path latestEdited(Path worldFilms) {
		for (Path film : newestFirst(worldFilms)) {
			RenderProgress progress = RenderProgress.read(progressFile(film));
			if (progress != null && progress.film != null) {
				Path file = film.resolve(progress.film);
				if (Files.isRegularFile(file)) {
					return file;
				}
			}
		}
		return null;
	}

	private static List<Path> newestFirst(Path worldFilms) {
		if (!Files.isDirectory(worldFilms)) {
			return List.of();
		}
		try (Stream<Path> children = Files.list(worldFilms)) {
			return children.filter(Files::isDirectory)
				.sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
				.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** The newest film of a world that was started but not finished, or null. */
	public static @Nullable Path latestUnfinished(Path worldFilms) {
		for (Path film : newestFirst(worldFilms)) {
			RenderProgress progress = RenderProgress.read(progressFile(film));
			if (progress != null && needsWork(film, progress) && Files.isRegularFile(edlFile(film))) {
				return film;
			}
		}
		return null;
	}
}
