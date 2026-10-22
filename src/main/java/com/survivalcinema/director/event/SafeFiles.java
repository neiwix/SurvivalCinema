package com.survivalcinema.director.event;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * File helpers that survive crashes and damaged files.
 */
public final class SafeFiles {
	private SafeFiles() {
	}

	/**
	 * Replaces a file without ever leaving it half-written: writes a temporary file next to it and moves it into
	 * place. The previous version is kept as {@code <name>.bak}.
	 */
	public static void writeAtomically(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, text, StandardCharsets.UTF_8);
		if (Files.exists(file)) {
			Files.copy(file, backup(file), StandardCopyOption.REPLACE_EXISTING);
		}
		try {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static Path backup(Path file) {
		return file.resolveSibling(file.getFileName() + ".bak");
	}

	/**
	 * A UTF-8 reader that replaces broken bytes (a line cut in the middle of a Cyrillic letter by a crash)
	 * instead of failing the whole file.
	 */
	public static BufferedReader lenientReader(Path file) throws IOException {
		return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8));
	}
}
