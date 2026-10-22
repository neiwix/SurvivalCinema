package com.survivalcinema.director.film;

import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import com.survivalcinema.director.render.FilmFolder;
import com.survivalcinema.director.render.RenderProgress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageTest {
	@TempDir
	Path dir;

	/** A film folder with a clip of that size; edited films also have their film file. */
	private Path film(String name, boolean edited, boolean finished, int clipBytes) throws IOException {
		Path film = dir.resolve(name);
		Files.createDirectories(film.resolve(FilmFolder.CLIPS));
		Files.write(film.resolve(FilmFolder.clipPath(1, 0)), new byte[clipBytes]);
		RenderProgress progress = new RenderProgress();
		progress.finished = finished;
		progress.clipCount = 1;
		progress.clip(1).status = RenderProgress.Status.DONE;
		if (edited) {
			progress.film = "World.mp4";
			Files.write(film.resolve("World.mp4"), new byte[100]);
		}
		progress.write(FilmFolder.progressFile(film));
		return film;
	}

	@Test
	void onlyOlderEditedFilmsGiveUpTheirClips() throws IOException {
		Path oldest = film("2026-10-01_10-00-00", true, true, 1000);
		Path older = film("2026-10-05_10-00-00", true, true, 2000);
		Path newestEdited = film("2026-10-10_10-00-00", true, true, 3000);
		Path unfinished = film("2026-10-12_10-00-00", false, false, 4000);

		List<Path> spare = FilmStorage.spareClipFolders(dir);
		assertEquals(List.of(older.resolve(FilmFolder.CLIPS), oldest.resolve(FilmFolder.CLIPS)), spare,
			"the newest edited film keeps its clips (edit again), the unfinished one too (finish it)");

		FilmStorage.Usage usage = FilmStorage.usage(dir);
		assertEquals(10_000, usage.clips());
		assertEquals(3000, usage.spareClips());
		assertTrue(usage.films() >= 300, "three film files and the progress files");

		for (Path folder : spare) {
			FilmStorage.delete(folder, dir);
		}
		assertTrue(Files.isRegularFile(oldest.resolve("World.mp4")), "the films stay");
		assertFalse(Files.exists(oldest.resolve(FilmFolder.CLIPS)));
		assertTrue(Files.isDirectory(newestEdited.resolve(FilmFolder.CLIPS)));
		assertTrue(Files.isDirectory(unfinished.resolve(FilmFolder.CLIPS)));
		assertEquals(List.of(), FilmStorage.spareClipFolders(dir), "nothing more to free");
		RenderProgress read = RenderProgress.read(FilmFolder.progressFile(oldest));
		assertFalse(FilmFolder.needsWork(oldest, read), "a film without its clips is still a finished film, not one to shoot again");
	}

	@Test
	void neverDeletesOutsideTheAllowedFolder() throws IOException {
		Path inside = Files.createDirectories(dir.resolve("films/a"));
		Path outside = Files.createDirectories(dir.resolve("other"));
		Files.writeString(outside.resolve("keep.txt"), "x");
		assertThrows(IllegalArgumentException.class, () -> FilmStorage.delete(outside, dir.resolve("films")));
		assertThrows(IllegalArgumentException.class, () -> FilmStorage.delete(dir.resolve("films/../other"), dir.resolve("films")));
		assertThrows(IllegalArgumentException.class, () -> FilmStorage.delete(dir.resolve("films"), dir.resolve("films")), "not the root itself");
		assertTrue(Files.exists(outside.resolve("keep.txt")));
		Files.writeString(inside.resolve("clip.mp4"), "12345");
		assertEquals(5, FilmStorage.delete(inside, dir.resolve("films")));
		assertFalse(Files.exists(inside));
		assertEquals(0, FilmStorage.delete(inside, dir.resolve("films")), "already gone");
	}

	private static GameEvent event(int tick, long wallTime, int day) {
		return new GameEvent(tick, wallTime, EventType.SESSION_START, "s", "s", "me", 0, 64, 0, "minecraft:overworld", day, 0.1, false, null);
	}

	@Test
	void summarizesASession() {
		long start = 1_792_000_000_000L;
		SessionSummary summary = SessionSummary.of("a", List.of(event(0, start, 3), event(30_000, start + 1_500_000, 4),
			event(60_000, start + 3_000_000, 5)), 72_000, 5000, true);
		assertEquals(start, summary.startedAt());
		assertEquals(60, summary.minutes(), "an hour of trail");
		assertEquals(3, summary.firstDay());
		assertEquals(5, summary.lastDay());
		assertTrue(summary.usable());

		SessionSummary lost = SessionSummary.of("b", List.of(), 1200, 0, false);
		assertFalse(lost.usable(), "the replay was deleted");
		assertEquals(0, lost.firstDay());
		assertEquals(1, lost.minutes());
		assertEquals(0, SessionSummary.of("c", List.of(), -1, 0, true).minutes());
		SessionSummary live = SessionSummary.of("d", List.of(event(0, start, 7)), 2400, 100, false, true);
		assertTrue(live.usable(), "the recording running now has no replay file yet, but it goes into the film");
		assertEquals(2, live.minutes());

		SessionSummary later = SessionSummary.of("c", List.of(event(100, start + 9_000_000, 6)), 0, 0, true);
		assertEquals(start + 9_000_000 - 5000, later.startedAt(), "the start is worked out from the first event");
		assertEquals(List.of("c"), SessionSummary.startedAfter(List.of(summary, lost, later), start + 5_000_000));
	}
}
