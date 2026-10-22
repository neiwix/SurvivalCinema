package com.survivalcinema.director.event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small {@code key=value;key=value} payloads stored in {@link GameEvent#detail()}, e.g. a conversation's
 * {@code ticks=600;speakers=Alex,Steve;near=Alex}.
 */
public final class Details {
	private Details() {
	}

	public static String of(Map<String, String> values) {
		StringBuilder out = new StringBuilder();
		values.forEach((k, v) -> {
			if (!out.isEmpty()) {
				out.append(';');
			}
			out.append(k).append('=').append(v.replace(";", ","));
		});
		return out.toString();
	}

	public static Map<String, String> parse(String detail) {
		Map<String, String> values = new LinkedHashMap<>();
		if (detail == null || detail.isEmpty()) {
			return values;
		}
		for (String part : detail.split(";")) {
			int eq = part.indexOf('=');
			if (eq > 0) {
				values.put(part.substring(0, eq), part.substring(eq + 1));
			}
		}
		return values;
	}

	public static int getInt(String detail, String key, int fallback) {
		try {
			String value = parse(detail).get(key);
			return value == null ? fallback : Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	public static List<String> getList(String detail, String key) {
		String value = parse(detail).get(key);
		if (value == null || value.isBlank()) {
			return List.of();
		}
		return new ArrayList<>(Arrays.asList(value.split(",")));
	}
}
