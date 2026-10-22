package com.survivalcinema.director.camera;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Binary trail file: a 4 byte magic + version, then fixed-size records. Appending is crash-safe enough:
 * a half-written last record is simply dropped when reading.
 */
public final class TrailIO {
	private static final int MAGIC = 0x53435452; // "SCTR"
	private static final int VERSION = 1;

	private TrailIO() {
	}

	public static List<TrailSample> read(Path file) throws IOException {
		List<TrailSample> samples = new ArrayList<>();
		if (!Files.exists(file) || Files.size(file) < 8) {
			return samples; // missing, or the game crashed before even the header was written
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != MAGIC) {
				throw new IOException("Not a trail file: " + file);
			}
			int version = in.readInt();
			if (version != VERSION) {
				throw new IOException("Unsupported trail version " + version);
			}
			while (true) {
				try {
					samples.add(new TrailSample(in.readInt(), in.readDouble(), in.readDouble(), in.readDouble(),
						in.readFloat(), in.readFloat(), in.readInt()));
				} catch (EOFException end) {
					break;
				}
			}
		}
		return samples;
	}

	public static final class Writer implements Closeable {
		private final DataOutputStream out;
		private int unflushed;

		public Writer(Path file) throws IOException {
			Files.createDirectories(file.getParent());
			boolean fresh = !Files.exists(file) || Files.size(file) == 0;
			out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND)));
			if (fresh) {
				out.writeInt(MAGIC);
				out.writeInt(VERSION);
				out.flush();
			}
		}

		public void write(TrailSample s) throws IOException {
			out.writeInt(s.tick());
			out.writeDouble(s.x());
			out.writeDouble(s.y());
			out.writeDouble(s.z());
			out.writeFloat(s.yaw());
			out.writeFloat(s.pitch());
			out.writeInt(s.flags());
			if (++unflushed >= 100) {
				out.flush();
				unflushed = 0;
			}
		}

		@Override
		public void close() throws IOException {
			out.close();
		}
	}
}
