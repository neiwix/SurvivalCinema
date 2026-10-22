package com.survivalcinema.director.camera;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.survivalcinema.director.event.SafeFiles;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code companions.jsonl}: where the other players near us were, once a second. Lets the director film two friends
 * talking together, and know who was actually there.
 */
public final class CompanionIO {
	public static final String FILE_NAME = "companions.jsonl";
	private static final Gson GSON = new Gson();

	/** One player's position at a tick. */
	public record Position(String name, double x, double y, double z) {
	}

	private record Line(int t, List<Position> p) {
	}

	private CompanionIO() {
	}

	/** Each companion's path, by player name. */
	public static Map<String, Trail> read(Path file) throws IOException {
		Map<String, List<TrailSample>> samples = new HashMap<>();
		if (!Files.exists(file)) {
			return Map.of();
		}
		try (BufferedReader reader = SafeFiles.lenientReader(file)) {
			String text;
			while ((text = reader.readLine()) != null) {
				if (text.isBlank()) {
					continue;
				}
				try {
					Line line = GSON.fromJson(text, Line.class);
					if (line == null || line.p() == null) {
						continue;
					}
					for (Position p : line.p()) {
						if (p == null || p.name() == null) {
							continue;
						}
						samples.computeIfAbsent(p.name(), n -> new ArrayList<>())
							.add(new TrailSample(line.t(), p.x(), p.y(), p.z(), 0f, 0f, 0));
					}
				} catch (JsonParseException ignored) {
					// half-written line after a crash
				}
			}
		}
		Map<String, Trail> trails = new HashMap<>();
		samples.forEach((name, list) -> trails.put(name, new Trail(list)));
		return trails;
	}

	public static final class Writer implements Closeable {
		private final BufferedWriter out;

		public Writer(Path file) throws IOException {
			Files.createDirectories(file.getParent());
			out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}

		public void write(int tick, List<Position> positions) throws IOException {
			out.write(GSON.toJson(new Line(tick, positions)));
			out.newLine();
			out.flush();
		}

		@Override
		public void close() throws IOException {
			out.close();
		}
	}
}
