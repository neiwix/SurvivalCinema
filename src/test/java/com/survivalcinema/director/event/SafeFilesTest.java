package com.survivalcinema.director.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SafeFilesTest {
	@TempDir
	Path dir;

	@Test
	void replacesAndKeepsTheOldVersion() throws IOException {
		Path file = dir.resolve("world").resolve("world.json");
		SafeFiles.writeAtomically(file, "first");
		SafeFiles.writeAtomically(file, "второй");
		assertEquals("второй", Files.readString(file, StandardCharsets.UTF_8));
		assertEquals("first", Files.readString(SafeFiles.backup(file), StandardCharsets.UTF_8));
		assertFalse(Files.exists(file.resolveSibling("world.json.tmp")), "temporary file left behind");
	}
}
