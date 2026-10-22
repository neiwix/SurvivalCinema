package com.survivalcinema.director.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Folder-safe, collision-free keys for worlds. Keeps letters of any alphabet ("Новый мир" stays readable) and adds a
 * short hash of the exact original name, so two worlds whose cleaned-up names look alike never share a folder.
 */
public final class WorldKeyFormat {
	private static final int MAX_READABLE = 40;

	private WorldKeyFormat() {
	}

	public static String key(String prefix, String raw) {
		String readable = raw.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}._-]+", "_");
		if (readable.length() > MAX_READABLE) {
			readable = readable.substring(0, MAX_READABLE);
		}
		if (readable.isEmpty() || readable.equals(".") || readable.equals("..")) {
			readable = "world";
		}
		return prefix + "-" + readable + "-" + hash(raw);
	}

	/** Multiplayer address without the port for LAN games, where "Open to LAN" picks a random port every time. */
	public static String lanHost(String address) {
		int colon = address.lastIndexOf(':');
		return colon > 0 && address.indexOf(':') == colon ? address.substring(0, colon) : address;
	}

	private static String hash(String raw) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 4);
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(raw.hashCode());
		}
	}
}
