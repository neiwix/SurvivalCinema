package com.survivalcinema.director.camera;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrailIOTest {
	@TempDir
	Path dir;

	@Test
	void roundTripsAndAppends() throws IOException {
		Path file = dir.resolve("session").resolve("trail.bin");
		TrailSample a = new TrailSample(0, 1.5, 64, -3.25, 90f, -10f, TrailSample.FLAG_ON_GROUND);
		TrailSample b = new TrailSample(2, 1.9, 64.5, -3.5, 91f, -9f, TrailSample.FLAG_FLYING_ELYTRA | (1 << 8));
		try (TrailIO.Writer writer = new TrailIO.Writer(file)) {
			writer.write(a);
		}
		try (TrailIO.Writer writer = new TrailIO.Writer(file)) {
			writer.write(b);
		}
		assertEquals(List.of(a, b), TrailIO.read(file));
	}

	@Test
	void anEmptyOrHeaderlessFileIsAnEmptyTrail() throws IOException {
		Path empty = dir.resolve("empty.bin");
		Files.write(empty, new byte[0]);
		assertEquals(List.of(), TrailIO.read(empty));
		Path partial = dir.resolve("partial.bin");
		Files.write(partial, new byte[]{0x53, 0x43, 0x54});
		assertEquals(List.of(), TrailIO.read(partial));
	}

	@Test
	void theHeaderIsOnDiskRightAway() throws IOException {
		Path file = dir.resolve("live.bin");
		try (TrailIO.Writer writer = new TrailIO.Writer(file)) {
			assertEquals(8, Files.size(file), "a crash right after starting must still leave a valid file");
		}
	}

	@Test
	void dropsAHalfWrittenLastRecord() throws IOException {
		Path file = dir.resolve("trail.bin");
		TrailSample a = new TrailSample(10, 0, 70, 0, 0f, 0f, 0);
		try (TrailIO.Writer writer = new TrailIO.Writer(file)) {
			writer.write(a);
		}
		Files.write(file, new byte[]{1, 2, 3}, StandardOpenOption.APPEND);
		assertEquals(List.of(a), TrailIO.read(file));
	}
}
