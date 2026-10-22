package com.survivalcinema.director.render;

import com.survivalcinema.director.event.SafeFiles;
import com.survivalcinema.director.story.EditDecisionList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static com.survivalcinema.director.render.RenderTestData.card;
import static com.survivalcinema.director.render.RenderTestData.clip;
import static com.survivalcinema.director.render.RenderTestData.edl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderProgressTest {
	private static EditDecisionList film(long createdAt) {
		return edl(createdAt, List.of(
			card(EditDecisionList.Kind.INTRO),
			clip("a", 0, 100, 200),
			clip("a", 0, 300, 400),
			clip("b", 1, 100, 200),
			card(EditDecisionList.Kind.CREDITS)));
	}

	@Test
	void freshProgressHasEverythingToShoot() {
		EditDecisionList edl = film(42);
		RenderPlan plan = RenderPlan.of(edl);
		RenderProgress progress = RenderProgress.fresh(edl, "world", RenderQuality.DRAFT, 1000);
		assertEquals(3, progress.clipCount);
		assertEquals(plan.jobs(), progress.pending(plan));
		assertFalse(progress.isComplete(plan));
		assertTrue(progress.matches(edl));
	}

	@Test
	void resumesWithTheNextClipAfterACrash() throws IOException {
		EditDecisionList edl = film(42);
		RenderPlan plan = RenderPlan.of(edl);
		RenderProgress progress = RenderProgress.fresh(edl, "world", RenderQuality.CINEMA, 1000);
		progress.pieceDone(1, FilmFolder.clipPath(1, 0));
		progress.clipDone(1);
		// clip 2 was cut in two pieces, the game crashed after the first one
		progress.pieceDone(2, FilmFolder.clipPath(2, 0));

		String json = progress.toJson();
		RenderProgress loaded = RenderProgress.fromJson(json);
		assertNotNull(loaded);
		assertTrue(loaded.matches(edl));
		assertEquals(RenderQuality.CINEMA, loaded.quality);
		assertEquals(List.of(2, 3), loaded.pending(plan).stream().map(RenderPlan.Job::number).toList());
		assertEquals(1, loaded.piecesDone(2), "the first piece of clip 2 is not shot again");
		assertEquals(0, loaded.piecesDone(3));
	}

	@Test
	void progressOfAnotherPlanIsNotUsed() {
		RenderProgress progress = RenderProgress.fresh(film(42), "world", RenderQuality.DRAFT, 1000);
		assertFalse(progress.matches(film(43)));
		EditDecisionList shorter = edl(42, List.of(clip("a", 0, 100, 200)));
		assertFalse(progress.matches(shorter));
	}

	@Test
	void clipsThatKeepFailingAreSkipped() {
		EditDecisionList edl = film(42);
		RenderPlan plan = RenderPlan.of(edl);
		RenderProgress progress = RenderProgress.fresh(edl, "world", RenderQuality.DRAFT, 1000);
		assertFalse(progress.failed(3, 2));
		assertTrue(progress.failed(3, 2));
		progress.skip(3, "export failed");
		assertEquals(List.of(1, 2), progress.pending(plan).stream().map(RenderPlan.Job::number).toList());
		assertEquals(1, progress.count(RenderProgress.Status.SKIPPED));

		progress.pieceDone(1, "clips/001.mp4");
		assertEquals(0, progress.clip(1).failures, "a finished piece resets the failure count");
	}

	@Test
	void deletedClipsAreShotAgain(@TempDir Path film) throws IOException {
		EditDecisionList edl = film(42);
		RenderPlan plan = RenderPlan.of(edl);
		RenderProgress progress = RenderProgress.fresh(edl, "world", RenderQuality.DRAFT, 1000);
		Files.createDirectories(film.resolve(FilmFolder.CLIPS));
		for (int number = 1; number <= 3; number++) {
			Files.writeString(film.resolve(FilmFolder.clipPath(number, 0)), "video");
			progress.pieceDone(number, FilmFolder.clipPath(number, 0));
			progress.clipDone(number);
		}
		progress.finished = true;
		Files.delete(film.resolve(FilmFolder.clipPath(2, 0)));

		assertEquals(1, progress.forgetMissingFiles(film));
		assertFalse(progress.finished);
		assertEquals(List.of(2), progress.pending(plan).stream().map(RenderPlan.Job::number).toList());
		assertEquals(0, progress.piecesDone(2));
	}

	@Test
	void aBrokenFileFallsBackToItsBackup(@TempDir Path film) throws IOException {
		RenderProgress progress = RenderProgress.fresh(film(42), "world", RenderQuality.DRAFT, 1000);
		Path file = FilmFolder.progressFile(film);
		progress.write(file);
		progress.clipDone(1);
		progress.write(file); // the first version is now the backup
		Files.writeString(file, "{\"clips\": [", StandardCharsets.UTF_8); // half-written by a crash

		RenderProgress read = RenderProgress.read(file);
		assertNotNull(read);
		assertTrue(Files.exists(SafeFiles.backup(file)));
		assertEquals(42, read.edlCreatedAt);
		assertNull(RenderProgress.fromJson("not json at all {"));
	}

	@Test
	void findsTheNewestUnfinishedFilm(@TempDir Path worldFilms) throws IOException {
		EditDecisionList edl = film(42);
		Path old = worldFilms.resolve(FilmFolder.name(LocalDateTime.of(2026, 1, 1, 10, 0)));
		Path done = worldFilms.resolve(FilmFolder.name(LocalDateTime.of(2026, 1, 2, 10, 0)));
		Path noScreenplay = worldFilms.resolve(FilmFolder.name(LocalDateTime.of(2026, 1, 3, 10, 0)));
		for (Path film : List.of(old, done, noScreenplay)) {
			RenderProgress progress = RenderProgress.fresh(edl, "world", RenderQuality.DRAFT, 1000);
			progress.finished = film == done;
			progress.write(FilmFolder.progressFile(film));
			if (film != noScreenplay) {
				Files.writeString(FilmFolder.edlFile(film), edl.toJson(), StandardCharsets.UTF_8);
			}
		}
		assertEquals(old, FilmFolder.latestUnfinished(worldFilms));
		assertNotNull(FilmFolder.readEdl(old));
		assertNull(FilmFolder.latestUnfinished(worldFilms.resolve("missing")));
		assertEquals("2026-01-01_10-00-00", old.getFileName().toString());
	}

	@Test
	void aShotFilmStillWaitsToBeEditedUntilItsFileExists(@TempDir Path worldFilms) throws IOException {
		EditDecisionList edl = film(42);
		Path film = worldFilms.resolve(FilmFolder.name(LocalDateTime.of(2026, 1, 1, 10, 0)));
		RenderProgress progress = RenderProgress.fresh(edl, "world", RenderQuality.DRAFT, 1000);
		for (int number = 1; number <= 3; number++) {
			progress.pieceDone(number, FilmFolder.clipPath(number, 0));
			progress.clipDone(number);
		}
		progress.finished = true;
		progress.write(FilmFolder.progressFile(film));
		Files.writeString(FilmFolder.edlFile(film), edl.toJson(), StandardCharsets.UTF_8);
		assertTrue(progress.needsWork());
		assertEquals(film, FilmFolder.latestUnfinished(worldFilms));

		progress.film = FilmFolder.filmFileName(edl.worldName());
		progress.write(FilmFolder.progressFile(film));
		Files.writeString(film.resolve(progress.film), "video");
		assertFalse(progress.needsWork());
		assertNull(FilmFolder.latestUnfinished(worldFilms));
		assertEquals(film, FilmFolder.latestShot(worldFilms), "an edited film can be edited again");

		Files.delete(film.resolve(progress.film));
		assertEquals(film, FilmFolder.latestUnfinished(worldFilms), "a deleted film is edited again");
		Files.createDirectories(film.resolve(FilmFolder.CLIPS));
		for (int number = 1; number <= 3; number++) {
			Files.writeString(film.resolve(FilmFolder.clipPath(number, 0)), "video");
		}
		assertEquals(0, progress.forgetMissingFiles(film));
		assertNull(progress.film);
	}

	@Test
	void theNewestEditedFilmIsTheOneToWatch(@TempDir Path worldFilms) throws IOException {
		EditDecisionList edl = film(42);
		assertNull(FilmFolder.latestEdited(worldFilms));
		Path older = worldFilms.resolve(FilmFolder.name(LocalDateTime.of(2026, 1, 1, 10, 0)));
		Path newer = worldFilms.resolve(FilmFolder.name(LocalDateTime.of(2026, 2, 1, 10, 0)));
		for (Path film : List.of(older, newer)) {
			RenderProgress progress = RenderProgress.fresh(edl, "world", RenderQuality.DRAFT, 1000);
			progress.film = FilmFolder.filmFileName(edl.worldName());
			progress.write(FilmFolder.progressFile(film));
		}
		Files.writeString(older.resolve(FilmFolder.filmFileName(edl.worldName())), "video");
		assertEquals(older.resolve(FilmFolder.filmFileName(edl.worldName())), FilmFolder.latestEdited(worldFilms),
			"the newer film's file is gone, so the older one is shown");
		Files.writeString(newer.resolve(FilmFolder.filmFileName(edl.worldName())), "video");
		assertEquals(newer.resolve(FilmFolder.filmFileName(edl.worldName())), FilmFolder.latestEdited(worldFilms));
	}

	@Test
	void filmFilesAreNamedAfterTheWorld() {
		assertEquals("Мой мир.mp4", FilmFolder.filmFileName("Мой мир"));
		assertEquals("a_b_c_.mp4", FilmFolder.filmFileName("a/b:c?"));
		assertEquals("Survival.mp4", FilmFolder.filmFileName("Survival... "));
		assertEquals("CON_film.mp4", FilmFolder.filmFileName("CON"));
		assertEquals("film.mp4", FilmFolder.filmFileName(""));
		assertEquals("film.mp4", FilmFolder.filmFileName(null));
	}
}
