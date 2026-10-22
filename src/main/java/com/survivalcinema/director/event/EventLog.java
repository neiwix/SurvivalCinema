package com.survivalcinema.director.event;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code events.jsonl}: one JSON object per line, appended as events happen, so a crash loses at most one line.
 */
public final class EventLog {
	public static final String FILE_NAME = "events.jsonl";
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private EventLog() {
	}

	public static List<GameEvent> read(Path file) throws IOException {
		List<GameEvent> events = new ArrayList<>();
		if (!Files.exists(file)) {
			return events;
		}
		try (BufferedReader reader = SafeFiles.lenientReader(file)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				try {
					GameEvent event = GSON.fromJson(line, GameEvent.class);
					if (event != null && event.type() != null) {
						events.add(normalize(event));
					}
				} catch (JsonParseException ignored) {
					// half-written line after a crash
				}
			}
		}
		return events;
	}

	/** Gson leaves missing fields null; old or damaged lines must never crash the director. */
	static GameEvent normalize(GameEvent e) {
		if (e.subject() != null && e.label() != null && e.actor() != null && e.dimension() != null) {
			return e;
		}
		return new GameEvent(e.tick(), e.wallTime(), e.type(), orEmpty(e.subject()), orEmpty(e.label()), orEmpty(e.actor()),
			e.x(), e.y(), e.z(), orEmpty(e.dimension()), e.day(), e.intensity(), e.first(), e.detail());
	}

	private static String orEmpty(String s) {
		return s == null ? "" : s;
	}

	public static String toJson(GameEvent event) {
		return GSON.toJson(event);
	}

	public static final class Writer implements Closeable {
		private final BufferedWriter out;

		public Writer(Path file) throws IOException {
			Files.createDirectories(file.getParent());
			out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}

		public void append(GameEvent event) throws IOException {
			out.write(toJson(event));
			out.newLine();
			out.flush();
		}

		@Override
		public void close() throws IOException {
			out.close();
		}
	}
}
