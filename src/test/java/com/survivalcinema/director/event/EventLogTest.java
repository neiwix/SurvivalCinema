package com.survivalcinema.director.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventLogTest {
	@TempDir
	Path dir;

	private static GameEvent event(int tick, EventType type, String label) {
		return new GameEvent(tick, 1_760_000_000_000L + tick, type, "minecraft:story/mine_diamond", label, "Steve",
			10.5, 64, -3, "minecraft:overworld", 3, 0.7, true, null);
	}

	@Test
	void roundTripsUnicodeAndAppends() throws IOException {
		Path file = dir.resolve("s").resolve(EventLog.FILE_NAME);
		GameEvent a = event(100, EventType.ADVANCEMENT, "Алмазы!");
		GameEvent b = new GameEvent(250, 1L, EventType.DEATH, "death", "Steve was blown up by Creeper", "Steve",
			0, 70, 0, "minecraft:the_nether", 4, 1.0, false, "Взорван крипером");
		try (EventLog.Writer writer = new EventLog.Writer(file)) {
			writer.append(a);
		}
		try (EventLog.Writer writer = new EventLog.Writer(file)) {
			writer.append(b);
		}
		assertEquals(List.of(a, b), EventLog.read(file));
	}

	@Test
	void oldOrPartialLinesGetSafeDefaults() throws IOException {
		Path file = dir.resolve(EventLog.FILE_NAME);
		Files.writeString(file, "{\"tick\":5,\"type\":\"DEATH\"}\n{\"tick\":6}\n", StandardCharsets.UTF_8);
		List<GameEvent> events = EventLog.read(file);
		assertEquals(1, events.size(), "a line without a type is skipped");
		assertEquals("", events.getFirst().subject());
		assertEquals("", events.getFirst().label());
		assertEquals("", events.getFirst().dimension());
	}

	@Test
	void aLineCutInsideACyrillicLetterDoesNotLoseTheFile() throws IOException {
		Path file = dir.resolve(EventLog.FILE_NAME);
		GameEvent a = event(5, EventType.ADVANCEMENT, "Алмазы!");
		try (EventLog.Writer writer = new EventLog.Writer(file)) {
			writer.append(a);
		}
		byte[] cyrillic = "{\"tick\":7,\"type\":\"DEATH\",\"label\":\"Ж".getBytes(StandardCharsets.UTF_8);
		Files.write(file, java.util.Arrays.copyOf(cyrillic, cyrillic.length - 1), StandardOpenOption.APPEND);
		assertEquals(List.of(a), EventLog.read(file));
	}

	@Test
	void skipsABrokenLastLine() throws IOException {
		Path file = dir.resolve(EventLog.FILE_NAME);
		GameEvent a = event(5, EventType.KILL, "Zombie");
		try (EventLog.Writer writer = new EventLog.Writer(file)) {
			writer.append(a);
		}
		Files.writeString(file, "{\"tick\":7,\"type\":\"DEA", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		assertEquals(List.of(a), EventLog.read(file));
	}
}
