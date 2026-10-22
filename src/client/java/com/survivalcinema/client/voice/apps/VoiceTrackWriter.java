package com.survivalcinema.client.voice.apps;

import com.survivalcinema.director.voice.VoiceTrackIndex;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * One voice track of a recording: {@code <name>.ogg} (Opus, 48 kHz mono, about 15 MB an hour) and
 * {@code <name>.idx} (which replay tick is where, see {@link VoiceTrackIndex}). Ogg is written page by page, so a
 * crash loses only the last seconds. Use it on one thread only (FFmpeg).
 */
public final class VoiceTrackWriter implements AutoCloseable {
	private static final int BITRATE = 32_000;

	private final FFmpegFrameRecorder recorder;
	private final BufferedWriter index;
	private final short[] silence = new short[VoiceTrackIndex.RATE / 10];
	private long written;
	private int lastIndexedTick = Integer.MIN_VALUE;

	public VoiceTrackWriter(Path folder, String name) throws IOException {
		Files.createDirectories(folder);
		Path audio = folder.resolve(name + VoiceTrackIndex.AUDIO_SUFFIX);
		Path idx = folder.resolve(name + VoiceTrackIndex.INDEX_SUFFIX);
		// a track that already exists (the recording went on after a restart of the capture) gets a new name
		for (int n = 2; Files.exists(audio) || Files.exists(idx); n++) {
			audio = folder.resolve(name + "-" + n + VoiceTrackIndex.AUDIO_SUFFIX);
			idx = folder.resolve(name + "-" + n + VoiceTrackIndex.INDEX_SUFFIX);
		}
		recorder = new FFmpegFrameRecorder(audio.toFile(), 1);
		recorder.setFormat("ogg");
		recorder.setAudioCodec(avcodec.AV_CODEC_ID_OPUS);
		recorder.setSampleRate(VoiceTrackIndex.RATE);
		recorder.setAudioChannels(1);
		recorder.setAudioBitrate(BITRATE);
		try {
			recorder.start();
		} catch (FFmpegFrameRecorder.Exception e) {
			recorder.release();
			throw new IOException("Could not start the voice track " + audio, e);
		}
		index = Files.newBufferedWriter(idx, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
	}

	/** Samples written so far. */
	public long written() {
		return written;
	}

	public void write(short[] samples, int count) throws IOException {
		if (count <= 0) {
			return;
		}
		try {
			recorder.recordSamples(VoiceTrackIndex.RATE, 1, ShortBuffer.wrap(samples, 0, count));
		} catch (FFmpegFrameRecorder.Exception e) {
			throw new IOException(e);
		}
		written += count;
	}

	public void writeSilence(long count) throws IOException {
		while (count > 0) {
			int n = (int) Math.min(count, silence.length);
			write(silence, n);
			count -= n;
		}
	}

	/** Notes that the track is at {@link #written()} at replay tick {@code tick}; often enough, not every call. */
	public void mark(int tick, boolean force) throws IOException {
		if (!force && tick >= lastIndexedTick && (long) tick - lastIndexedTick < VoiceTrackIndex.EVERY_TICKS) {
			return;
		}
		lastIndexedTick = tick;
		index.write(VoiceTrackIndex.line(tick, written));
		index.newLine();
		index.flush();
	}

	@Override
	public void close() throws IOException {
		try {
			index.close();
		} finally {
			try {
				recorder.stop();
			} catch (FFmpegFrameRecorder.Exception e) {
				throw new IOException(e);
			} finally {
				recorder.release();
			}
		}
	}
}
