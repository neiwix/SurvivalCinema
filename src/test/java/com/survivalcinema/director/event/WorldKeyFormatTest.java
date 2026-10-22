package com.survivalcinema.director.event;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldKeyFormatTest {
	@Test
	void cyrillicWorldsDontCollide() {
		List<String> names = List.of("Новый мир", "Мой мир", "Выживание с друзьями", "New World", "New World (1)", "新世界", "мир!", "мир?");
		Set<String> keys = new HashSet<>();
		for (String name : names) {
			keys.add(WorldKeyFormat.key("sp", name));
		}
		assertEquals(names.size(), keys.size(), "two worlds got the same key: " + keys);
	}

	@Test
	void keysAreStableReadableAndFolderSafe() {
		String key = WorldKeyFormat.key("sp", "Новый мир");
		assertEquals(key, WorldKeyFormat.key("sp", "Новый мир"));
		assertTrue(key.startsWith("sp-новый_мир-"), key);
		assertTrue(key.matches("[\\p{L}\\p{N}._-]+"), key);
		assertTrue(WorldKeyFormat.key("mp", "..").matches("mp-world-[0-9a-f]+"));
		assertTrue(WorldKeyFormat.key("sp", "x".repeat(300)).length() < 60);
	}

	@Test
	void lanKeysIgnoreTheRandomPort() {
		assertEquals("192.168.1.5", WorldKeyFormat.lanHost("192.168.1.5:54321"));
		assertEquals("192.168.1.5", WorldKeyFormat.lanHost("192.168.1.5"));
		assertEquals("[::1]:25565".length(), WorldKeyFormat.lanHost("[::1]:25565").length(), "IPv6 left alone");
	}
}
