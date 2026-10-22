package com.survivalcinema.client.edit;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.voice.apps.CallVoiceRecorder;
import com.survivalcinema.director.edit.Loudness;
import com.survivalcinema.director.edit.VoiceCut;
import com.survivalcinema.director.voice.VoiceTrackIndex;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The voices we recorded ourselves (call apps, the microphone) for the clips of a film: for each shot file, the
 * pieces of its recording's voice tracks that go under its frames ({@link VoiceCut}), decoded from the Ogg files and
 * brought to one level per track kind across the whole film.
 */
final class CallVoices {
	private static final double WINDOW_SECONDS = 0.1;
	/** Windows at least this share of a track's rough level count as speech. */
	private static final double SPEECH_SHARE = 0.5;
	/** The gate opens above this share of the track's speech level. */
	private static final double GATE_SHARE = 0.2;
	private static final double GATE_CLOSED = 0.15;
	private static final double GATE_ATTACK = 0.6;
	private static final double GATE_RELEASE = 0.08;

	/** A voice track of a recording, e.g. {@code sessions/<id>/voice/discord.ogg}. */
	private record Track(String kind, Path audio, VoiceTrackIndex index) {
	}

	private final Path sessions;
	private final Map<String, List<Track>> byReplay = new HashMap<>();
	/** Gain per track kind ("discord", "mic"), from {@link #measure}. */
	private final Map<String, Double> gains = new HashMap<>();
	/** How loud speech is per track kind (RMS before gain). */
	private final Map<String, Double> speech = new HashMap<>();

	CallVoices(Path sessions) {
		this.sessions = sessions;
	}

	/** The voices under one shot file. */
	final class Piece {
		private final List<VoiceCut> cuts;
		private final List<Track> tracks;
		private final List<short[]> samples;
		private final List<Long> firsts;
		/** Per track, the noise gate's current gain: room noise and keyboard clicks between words are kept down. */
		private final double[] gate;

		private Piece(List<VoiceCut> cuts, List<Track> tracks, List<short[]> samples, List<Long> firsts) {
			this.cuts = cuts;
			this.tracks = tracks;
			this.samples = samples;
			this.firsts = firsts;
			this.gate = new double[cuts.size()];
			java.util.Arrays.fill(gate, GATE_CLOSED);
		}

		boolean isEmpty() {
			return cuts.isEmpty();
		}

		/**
		 * Adds the voices of {@code frame} to a stereo mix and returns how loud they were (RMS 0..1, after gain).
		 */
		double mixInto(int frame, int[] mix, double fade) {
			double sum = 0;
			int count = mix.length / 2;
			for (int t = 0; t < cuts.size(); t++) {
				VoiceCut cut = cuts.get(t);
				if (frame >= cut.frames() || cut.start(frame) < 0 || cut.gain(frame) <= 0) {
					continue;
				}
				short[] source = samples.get(t);
				int offset = (int) (cut.start(frame) - firsts.get(t));
				double raw = rms(source, offset, Math.min(count, cut.samplesPerFrame()));
				double level = speech.getOrDefault(tracks.get(t).kind(), 0.0);
				double threshold = level > 0 ? Math.max(Loudness.GATE, level * GATE_SHARE) : Loudness.VOICE_GATE;
				double open = raw >= threshold ? 1 : GATE_CLOSED;
				gate[t] += (open - gate[t]) * (open > gate[t] ? GATE_ATTACK : GATE_RELEASE);
				double gain = cut.gain(frame) * fade * gate[t] * gains.getOrDefault(tracks.get(t).kind(), 1.0);
				for (int i = 0; i < count && i < cut.samplesPerFrame(); i++) {
					int at = offset + i;
					if (at < 0 || at >= source.length) {
						continue;
					}
					double v = source[at] * gain;
					mix[i * 2] += (int) Math.round(v);
					mix[i * 2 + 1] += (int) Math.round(v);
					sum += v * v;
				}
			}
			return count == 0 ? 0 : Math.sqrt(sum / count) / 32768;
		}

		/** Per track kind, the RMS of each tenth of a second where the track has sound (before gain). */
		Map<String, List<Double>> windows() {
			Map<String, List<Double>> out = new HashMap<>();
			for (int t = 0; t < cuts.size(); t++) {
				VoiceCut cut = cuts.get(t);
				short[] source = samples.get(t);
				int perWindow = (int) Math.round(WINDOW_SECONDS * VoiceTrackIndex.RATE);
				List<Double> levels = out.computeIfAbsent(tracks.get(t).kind(), k -> new ArrayList<>());
				double sum = 0;
				int n = 0;
				for (int f = 0; f < cut.frames(); f++) {
					if (cut.start(f) < 0 || cut.gain(f) <= 0) {
						continue;
					}
					int offset = (int) (cut.start(f) - firsts.get(t));
					for (int i = 0; i < cut.samplesPerFrame(); i++) {
						int at = offset + i;
						short s = at >= 0 && at < source.length ? source[at] : 0;
						sum += (double) s * s;
						if (++n == perWindow) {
							levels.add(Math.sqrt(sum / n) / 32768);
							sum = 0;
							n = 0;
						}
					}
				}
			}
			return out;
		}
	}

	private static double rms(short[] source, int offset, int count) {
		double sum = 0;
		for (int i = 0; i < count; i++) {
			int at = offset + i;
			if (at >= 0 && at < source.length) {
				sum += (double) source[at] * source[at];
			}
		}
		return count == 0 ? 0 : Math.sqrt(sum / count) / 32768;
	}

	/**
	 * The voices of the recording {@code replayId} under a file that shows its ticks {@code [fromTick, toTick]}.
	 */
	Piece piece(@Nullable String replayId, int fromTick, int toTick, int slowFromTick, int fps, int frames) {
		List<VoiceCut> cuts = new ArrayList<>();
		List<Track> used = new ArrayList<>();
		List<short[]> samples = new ArrayList<>();
		List<Long> firsts = new ArrayList<>();
		if (replayId != null) {
			for (Track track : tracks(replayId)) {
				// our copy of the voice chat only under slow motion: elsewhere the clip has the voices Flashback recorded
				boolean slowOnly = CallVoiceRecorder.VOICE_CHAT_TRACK.equals(track.kind());
				VoiceCut cut = VoiceCut.plan(track.index(), fromTick, toTick, slowFromTick, fps, frames, slowOnly);
				long[] range = cut.range();
				if (range == null) {
					continue;
				}
				try {
					samples.add(decode(track.audio(), range[0], range[1]));
				} catch (IOException | RuntimeException e) {
					SurvivalCinemaClient.LOGGER.warn("Could not read the voice track {}", track.audio(), e);
					continue;
				}
				cuts.add(cut);
				used.add(track);
				firsts.add(range[0]);
			}
		}
		return new Piece(cuts, used, samples, firsts);
	}

	/** Sets each track kind's gain from how loud it is over the whole film. */
	void measure(Map<String, List<Double>> windows) {
		windows.forEach((kind, levels) -> {
			// twice: the first pass still counts room noise, the second only what is clearly speech
			double[] all = levels.stream().mapToDouble(Double::doubleValue).toArray();
			double rough = Loudness.gatedRms(all);
			double rms = Loudness.gatedRms(java.util.Arrays.stream(all).filter(l -> l >= rough * SPEECH_SHARE).toArray());
			speech.put(kind, rms);
			gains.put(kind, rms <= 0 ? 1.0 : Loudness.gain(rms, Loudness.VOICE_TARGET));
		});
	}

	Map<String, Double> gains() {
		return gains;
	}

	private List<Track> tracks(String replayId) {
		return byReplay.computeIfAbsent(replayId, id -> {
			Path folder = sessions.resolve(id).resolve(CallVoiceRecorder.VOICE_FOLDER);
			List<Track> tracks = new ArrayList<>();
			if (!Files.isDirectory(folder)) {
				return tracks;
			}
			try (Stream<Path> files = Files.list(folder)) {
				for (Path idx : files.filter(p -> p.getFileName().toString().endsWith(VoiceTrackIndex.INDEX_SUFFIX)).sorted().toList()) {
					String base = idx.getFileName().toString();
					base = base.substring(0, base.length() - VoiceTrackIndex.INDEX_SUFFIX.length());
					Path audio = folder.resolve(base + VoiceTrackIndex.AUDIO_SUFFIX);
					if (!Files.isRegularFile(audio)) {
						continue;
					}
					VoiceTrackIndex index = VoiceTrackIndex.parse(Files.readString(idx, StandardCharsets.UTF_8));
					if (!index.isEmpty()) {
						int dash = base.indexOf('-');
						tracks.add(new Track(dash > 0 ? base.substring(0, dash) : base, audio, index));
					}
				}
			} catch (IOException e) {
				SurvivalCinemaClient.LOGGER.warn("Could not list the voice tracks in {}", folder, e);
			}
			return tracks;
		});
	}

	/** Samples {@code [from, to)} of a voice track (silence where the file has none). */
	private static short[] decode(Path audio, long from, long to) throws IOException {
		short[] out = new short[(int) Math.max(0, to - from)];
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(audio.toFile())) {
			grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
			grabber.setSampleRate(VoiceTrackIndex.RATE);
			grabber.setAudioChannels(1);
			grabber.start();
			if (from > VoiceTrackIndex.RATE) {
				grabber.setAudioTimestamp((from - VoiceTrackIndex.RATE / 2) * 1_000_000L / VoiceTrackIndex.RATE);
			}
			Frame frame;
			while ((frame = grabber.grabSamples()) != null) {
				if (frame.samples == null || frame.samples.length == 0) {
					continue;
				}
				long start = Math.round(frame.timestamp * (double) VoiceTrackIndex.RATE / 1_000_000L);
				if (start >= to) {
					break;
				}
				Buffer buffer = frame.samples[0];
				int n = buffer.limit() - buffer.position();
				for (int i = 0; i < n; i++) {
					long at = start + i - from;
					if (at >= 0 && at < out.length) {
						out[(int) at] = sample(buffer, buffer.position() + i);
					}
				}
			}
		}
		return out;
	}

	private static short sample(Buffer buffer, int i) {
		if (buffer instanceof ShortBuffer shorts) {
			return shorts.get(i);
		}
		if (buffer instanceof FloatBuffer floats) {
			return (short) Math.max(-32768, Math.min(32767, Math.round(floats.get(i) * 32767)));
		}
		return 0;
	}
}
