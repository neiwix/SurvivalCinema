package com.survivalcinema.gametest;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A stand-in for a friend in a Discord call, for the film test: a separate program (PowerShell) beeping a 600 Hz
 * tone, a quarter second on and off, which the mod captures like a call app. Afterwards the tone is looked for in
 * the film's sound.
 */
final class TestCall {
	static final double TONE_HZ = 600;
	private static final int RATE = 48_000;
	private static final int WINDOW = RATE / 10;

	private TestCall() {
	}

	/** Starts beeping for {@code seconds}; the program is killed afterwards by the caller. */
	static Process start(Path folder, int seconds) throws IOException {
		Path wav = folder.resolve("test-call-tone.wav");
		writeTone(wav);
		String script = "$p = New-Object Media.SoundPlayer '" + wav.toAbsolutePath().toString().replace("'", "''")
			+ "'; $p.PlayLooping(); Start-Sleep " + seconds;
		return new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).start();
	}

	/** Two seconds of beeps: 0.25 s of tone, 0.25 s of silence. */
	private static void writeTone(Path file) throws IOException {
		int samples = RATE * 2;
		ByteBuffer data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
		data.put("RIFF".getBytes()).putInt(36 + samples * 2).put("WAVE".getBytes());
		data.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
			.putShort((short) 2).putShort((short) 16);
		data.put("data".getBytes()).putInt(samples * 2);
		int beep = RATE / 4;
		int edge = RATE / 200;
		for (int i = 0; i < samples; i++) {
			int inBeep = i % (beep * 2);
			double v = 0;
			if (inBeep < beep) {
				double envelope = Math.min(1, Math.min(inBeep, beep - inBeep) / (double) edge);
				v = 0.3 * envelope * Math.sin(2 * Math.PI * TONE_HZ * i / RATE);
			}
			data.putShort((short) Math.round(v * 32767));
		}
		Files.write(file, data.array());
	}

	/** How many tenths of a second of a file's sound are mostly the test tone. */
	static int toneWindows(Path media) throws IOException {
		int count = 0;
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(media.toFile())) {
			grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
			grabber.setSampleRate(RATE);
			grabber.setAudioChannels(1);
			grabber.start();
			if (!grabber.hasAudio()) {
				return 0;
			}
			double[] window = new double[WINDOW];
			int filled = 0;
			Frame frame;
			while ((frame = grabber.grabSamples()) != null) {
				if (frame.samples == null) {
					continue;
				}
				Buffer buffer = frame.samples[0];
				for (int i = buffer.position(); i < buffer.limit(); i++) {
					window[filled++] = buffer instanceof ShortBuffer s ? s.get(i) / 32768.0
						: buffer instanceof FloatBuffer f ? f.get(i) : 0;
					if (filled == WINDOW) {
						if (toneShare(window) > 0.25) {
							count++;
						}
						filled = 0;
					}
				}
			}
		}
		return count;
	}

	/** Share of a window's power at the tone's frequency (Goertzel), 0..1; 0 for near silence. */
	private static double toneShare(double[] x) {
		double energy = 0;
		for (double v : x) {
			energy += v * v;
		}
		if (energy / x.length < 1e-6) {
			return 0;
		}
		double coeff = 2 * Math.cos(2 * Math.PI * TONE_HZ / RATE);
		double s1 = 0;
		double s2 = 0;
		for (double v : x) {
			double s = v + coeff * s1 - s2;
			s2 = s1;
			s1 = s;
		}
		double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
		return power / (x.length / 2.0 * energy);
	}
}
