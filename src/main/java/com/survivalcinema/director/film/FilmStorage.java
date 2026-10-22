package com.survivalcinema.director.film;

import com.survivalcinema.director.render.FilmFolder;
import com.survivalcinema.director.render.RenderProgress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * What the films of a world take on disk, and what of it can go. The shot clips are only needed to finish a film or
 * edit it again: once a newer film is edited, the clips of the older edited films are spare (their finished films
 * stay). Unfinished films keep everything, they can still be finished.
 * <p>
 * Deleting is only ever done inside a folder the caller names, so a wrong path can never reach anything else.
 */
public final class FilmStorage {
	private FilmStorage() {
	}

	/**
	 * @param films      finished film files and everything else of the films except their clips
	 * @param clips      every shot clip
	 * @param spareClips clips of older edited films, which can be deleted without losing a film
	 */
	public record Usage(long films, long clips, long spareClips) {
		public long total() {
			return films + clips;
		}
	}

	public static Usage usage(Path worldFilms) {
		long all = size(worldFilms);
		long clips = 0;
		for (Path film : filmFolders(worldFilms)) {
			clips += size(film.resolve(FilmFolder.CLIPS));
		}
		long spare = 0;
		for (Path folder : spareClipFolders(worldFilms)) {
			spare += size(folder);
		}
		return new Usage(all - clips, clips, spare);
	}

	/** The clip folders of every edited film but the newest one. */
	public static List<Path> spareClipFolders(Path worldFilms) {
		List<Path> spare = new ArrayList<>();
		boolean newestKept = false;
		for (Path film : filmFolders(worldFilms)) {
			RenderProgress progress = RenderProgress.read(FilmFolder.progressFile(film));
			boolean edited = progress != null && progress.film != null && !FilmFolder.needsWork(film, progress);
			if (!edited) {
				continue;
			}
			if (!newestKept) {
				newestKept = true; // kept for editing it again (other music...)
				continue;
			}
			Path clips = film.resolve(FilmFolder.CLIPS);
			if (Files.isDirectory(clips)) {
				spare.add(clips);
			}
		}
		return spare;
	}

	/** Film folders of a world, newest first (their names are dates). */
	public static List<Path> filmFolders(Path worldFilms) {
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

	/** Bytes of a file, or of everything in a folder; 0 when it is not there. */
	public static long size(Path path) {
		try {
			if (Files.isRegularFile(path)) {
				return Files.size(path);
			}
			if (!Files.isDirectory(path)) {
				return 0;
			}
			try (Stream<Path> files = Files.walk(path)) {
				return files.filter(Files::isRegularFile).mapToLong(file -> {
					try {
						return Files.size(file);
					} catch (IOException e) {
						return 0;
					}
				}).sum();
			}
		} catch (IOException | RuntimeException e) {
			return 0;
		}
	}

	/**
	 * Deletes a file or a folder with everything in it, but only when it lies inside {@code allowedRoot} (and is not
	 * the root itself).
	 *
	 * @return bytes freed
	 * @throws IllegalArgumentException for anything outside {@code allowedRoot}
	 */
	public static long delete(Path target, Path allowedRoot) throws IOException {
		Path root = allowedRoot.toAbsolutePath().normalize();
		Path path = target.toAbsolutePath().normalize();
		if (!path.startsWith(root) || path.equals(root)) {
			throw new IllegalArgumentException(path + " is not inside " + root);
		}
		if (!Files.exists(path)) {
			return 0;
		}
		long freed = size(path);
		if (Files.isDirectory(path)) {
			List<Path> all;
			try (Stream<Path> files = Files.walk(path)) {
				all = files.sorted(Comparator.reverseOrder()).toList();
			}
			for (Path file : all) {
				Files.deleteIfExists(file);
			}
		} else {
			Files.delete(path);
		}
		return freed;
	}
}
