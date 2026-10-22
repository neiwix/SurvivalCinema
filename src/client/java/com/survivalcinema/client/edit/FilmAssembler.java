package com.survivalcinema.client.edit;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.SessionStore;
import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailIO;
import com.survivalcinema.director.edit.CreditsRoll;
import com.survivalcinema.director.edit.Ducker;
import com.survivalcinema.director.edit.FilmTimeline;
import com.survivalcinema.director.edit.FlightSound;
import com.survivalcinema.director.edit.Loudness;
import com.survivalcinema.director.edit.MusicPlan;
import com.survivalcinema.director.edit.SoundCues;
import com.survivalcinema.director.render.FilmFolder;
import com.survivalcinema.director.render.RenderPlan;
import com.survivalcinema.director.render.RenderProgress;
import com.survivalcinema.director.render.RenderQuality;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.EditDecisionList.Kind;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Edits the shot clips into the finished film, one MP4 next to them: the intro, chapter cards that pull focus into
 * their first clip, the clips with fades through black between chapters, rarely and only away from voices some
 * music (ducked under loud game sound), a few game sounds as effects, and the rolling credits. Runs on its own
 * thread with the FFmpeg inside Flashback, so the game stays responsive and the cinema screen shows the progress.
 */
public final class FilmAssembler {
	private static final Logger LOGGER = SurvivalCinemaClient.LOGGER;
	/** Music against the game's own sound, both brought to their levels first ({@link Loudness}). */
	private static final double MUSIC_LEVEL = 0.6;
	/** Loudness is measured on windows of this many seconds. */
	private static final double WINDOW_SECONDS = 0.1;
	/** How much of a track is listened to for its level. */
	private static final double MUSIC_MEASURE_SECONDS = 45;
	/** A track's near-silent beginning (quieter than this RMS, up to 20 s) is skipped: the cue starts with music. */
	private static final double LEAD_IN_LEVEL = 0.01;
	private static final double MAX_LEAD_IN_SECONDS = 20;
	private static final int AUDIO_BITRATE = 192_000;
	private static final double DEFAULT_CREDITS_SECONDS = 10;
	/** Each shot of the base timelapse dissolves into the next over its last moments (at most a third of it). */
	private static final double TIMELAPSE_DISSOLVE_SECONDS = 0.3;
	/** How loud the wind of an elytra flight is at full speed, next to the game's levelled sound. */
	private static final double WIND_LEVEL = 0.45;

	/** @param gameMusic the film uses the game's soundtrack (worth a note about video sites) */
	public record Result(Path file, double seconds, List<String> music, boolean gameMusic) {
	}

	/** Everything the editor needs from the game, read on the client thread before the work moves to the background. */
	/** @param sessions where the recordings' own data lives (the voice tracks we recorded) */
	public record Ingredients(McFont font, MusicLibrary music, Map<SoundCues.Effect, byte[]> effects, Path sessions) {
		public static Ingredients gather(Minecraft mc) {
			ResourceManager resources = mc.getResourceManager();
			MusicLibrary.Source source = MusicLibrary.Source.parse(SurvivalCinemaConfig.get().music);
			return new Ingredients(McFont.load(resources), MusicLibrary.scan(resources, mc.gameDirectory.toPath(), source),
				GameSounds.load(resources), SessionStore.root().resolve("sessions"));
		}
	}

	private final Path film;
	private final EditDecisionList edl;
	private final RenderProgress shot;
	private final RenderQuality quality;
	private final String encoder;
	private final Ingredients ingredients;
	/** The screenplay item of each shot file of the base timelapse (for its day). */
	private final Map<String, EditDecisionList.Item> pictures = new HashMap<>();
	/** The filmed player's path in each recording, read when a clip needs it (the wind of a flight). */
	private final Map<String, Trail> trails = new HashMap<>();

	private volatile double progress;
	private volatile boolean cancelled;

	public FilmAssembler(Path film, EditDecisionList edl, RenderProgress shot, String encoder, Ingredients ingredients) {
		this.film = film;
		this.edl = edl;
		this.shot = shot;
		this.quality = shot.quality;
		this.encoder = encoder;
		this.ingredients = ingredients;
		for (RenderPlan.Job job : RenderPlan.of(edl).jobs()) {
			if (job.item().kind() == Kind.TIMELAPSE) {
				for (String file : shot.files(job.number())) {
					pictures.put(file, job.item());
				}
			}
		}
	}

	/** 0..1 */
	public double progress() {
		return progress;
	}

	/** Stops at the next frame; the half-made film is deleted. */
	public void cancel() {
		cancelled = true;
	}

	public CompletableFuture<Result> start() {
		CompletableFuture<Result> result = new CompletableFuture<>();
		Thread thread = new Thread(() -> {
			try {
				result.complete(run());
			} catch (Throwable t) {
				result.completeExceptionally(t);
			}
		}, "SurvivalCinema-Edit");
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		thread.start();
		return result;
	}

	// ---- the edit ----

	private Result run() throws Exception {
		EncoderSelector.prepareEncodingThread();
		int fps = quality.framerate;
		FilmPainter painter = new FilmPainter(ingredients.font(), quality.width, quality.height);
		Map<String, Integer> lengths = new HashMap<>();
		FilmTimeline.FrameCounter counter = file -> lengths.computeIfAbsent(file, f -> countFrames(film.resolve(f), fps));
		double creditsMinimum = edl.items().stream().filter(i -> i.kind() == Kind.CREDITS)
			.mapToDouble(EditDecisionList.Item::cardSeconds).findFirst().orElse(DEFAULT_CREDITS_SECONDS);

		// The credits name the music, and their length depends on what they say: plan twice.
		CreditsRoll roll = FilmTexts.roll(edl, List.of(), painter);
		FilmTimeline timeline = FilmTimeline.build(edl, shot, fps, counter, roll.seconds(creditsMinimum));
		if (timeline.isEmpty()) {
			throw new IllegalStateException("none of the film's clips could be read");
		}
		CallVoices voices = new CallVoices(ingredients.sessions());
		Hearing hearing = listen(timeline, lengths, voices);
		Set<Integer> quiet = hearing.quiet();
		Set<Integer> flights = flights(timeline);
		List<MusicLibrary.Track> tracks = ingredients.music().choose(MusicPlan.plan(timeline, quiet, flights), edl.createdAt());
		roll = FilmTexts.roll(edl, titles(tracks), painter);
		timeline = FilmTimeline.build(edl, shot, fps, counter, roll.seconds(creditsMinimum));
		List<MusicPlan.Cue> cues = MusicPlan.plan(timeline, quiet, flights);
		tracks = ingredients.music().choose(cues, edl.createdAt());
		List<String> titles = titles(tracks);
		roll = FilmTexts.roll(edl, titles, painter);

		String name = FilmFolder.filmFileName(edl.worldName());
		Path output = film.resolve(name);
		Path part = film.resolve(name + ".part");
		LOGGER.info("Editing the film {}: {} s, {} clips, game sound x{}, voice tracks {}, quiet clips {}, flights {}, music {} at {}",
			output, Math.round(timeline.seconds()), timeline.clips().size(), String.format(java.util.Locale.ROOT, "%.2f", hearing.gameGain()),
			voices.gains(), quiet, flights, titles, cues.stream().map(c -> c.mood() + " " + c.startFrame() / fps + "-" + c.endFrame() / fps + "s").toList());
		Files.deleteIfExists(part);
		try (Writer writer = new Writer(part, timeline, cues, tracks, SoundCues.plan(timeline), hearing.gameGain(), voices)) {
			for (int i = 0; i < timeline.segments().size(); i++) {
				FilmTimeline.Segment segment = timeline.segments().get(i);
				switch (segment.kind()) {
					case CLIP -> writer.clip(segment, i + 1 < timeline.segments().size() ? timeline.segments().get(i + 1) : null, lengths);
					case TIMELAPSE -> writer.timelapse(segment, painter);
					case INTRO, CHAPTER -> {
						Neighbour next = neighbour(timeline, i, true);
						boolean pullFocus = next != null && i + 1 < timeline.segments().size() && isFootage(timeline.segments().get(i + 1).kind());
						// the focus pulls into the clip's very first frame, so the cut into it does not show
						writer.card(segment, painter, backdropStill(next), pullFocus ? still(next.file(), false) : null);
					}
					case CREDITS -> writer.credits(segment, painter, roll, backdropStill(neighbour(timeline, i, false)));
				}
			}
		} catch (Throwable t) {
			deleteQuietly(part);
			throw t;
		}
		try {
			Files.move(part, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(part, output, StandardCopyOption.REPLACE_EXISTING);
		}
		progress = 1;
		return new Result(output, timeline.seconds(), titles, ingredients.music().usesGameMusic() && !titles.isEmpty());
	}

	/**
	 * @param gameGain brings the clips' sound to its level (they are as loud as the volume settings were while shooting)
	 * @param quiet    clips that are mostly silent: the replay had little to hear there
	 */
	private record Hearing(double gameGain, Set<Integer> quiet) {
	}

	/**
	 * Listens to every clip once: how loud the film's game sound and our voice tracks are, and which clips have room
	 * for music (a clip where friends talk in a call is not quiet, however silent the game is).
	 */
	private Hearing listen(FilmTimeline timeline, Map<String, Integer> lengths, CallVoices voices) {
		Map<Integer, double[]> windows = new HashMap<>();
		List<Double> all = new ArrayList<>();
		Map<String, List<Double>> voiceWindows = new HashMap<>();
		Set<Integer> talking = new HashSet<>();
		for (FilmTimeline.Segment clip : timeline.clips()) {
			List<Double> levels = new ArrayList<>();
			int speech = 0;
			for (String file : clip.files()) {
				try (AudioStream sound = AudioStream.open(film.resolve(file))) {
					levels.addAll(windowLevels(sound, Double.MAX_VALUE));
				} catch (IOException e) {
					LOGGER.warn("Could not listen to the clip {}", file, e);
				}
				for (Map.Entry<String, List<Double>> track : voicePiece(clip, file, lengths, voices).windows().entrySet()) {
					voiceWindows.computeIfAbsent(track.getKey(), k -> new ArrayList<>()).addAll(track.getValue());
					speech += (int) track.getValue().stream().filter(l -> l >= Loudness.VOICE_GATE).count();
				}
			}
			windows.put(clip.number(), levels.stream().mapToDouble(Double::doubleValue).toArray());
			all.addAll(levels);
			if (speech > Math.max(5, levels.size() / 10)) {
				talking.add(clip.number());
			}
		}
		voices.measure(voiceWindows);
		double gain = Loudness.gain(Loudness.gatedRms(all.stream().mapToDouble(Double::doubleValue).toArray()), Loudness.GAME_TARGET);
		Set<Integer> quiet = new HashSet<>();
		windows.forEach((number, levels) -> {
			if (Loudness.isQuiet(levels, gain) && !talking.contains(number)) {
				quiet.add(number);
			}
		});
		return new Hearing(gain, quiet);
	}

	/** The clips that are mostly an elytra flight. */
	private Set<Integer> flights(FilmTimeline timeline) {
		Set<Integer> flights = new HashSet<>();
		for (FilmTimeline.Segment clip : timeline.clips()) {
			Trail trail = trail(clip.item().replayId());
			int flown = 0;
			for (String file : clip.files()) {
				int[] ticks = shot.ticks(clip.number(), file);
				if (trail != null && ticks != null && FlightSound.isFlight(trail, ticks[0], ticks[1])) {
					flown++;
				}
			}
			if (flown * 2 > clip.files().size()) {
				flights.add(clip.number());
			}
		}
		return flights;
	}

	/** The filmed player's path in a recording, or null when it is gone. */
	private @Nullable Trail trail(@Nullable String replayId) {
		if (replayId == null) {
			return null;
		}
		return trails.computeIfAbsent(replayId, id -> {
			try {
				return new Trail(TrailIO.read(ingredients.sessions().resolve(id).resolve(SessionStore.TRAIL_FILE)));
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Could not read the path of {}", id, e);
				return new Trail(List.of());
			}
		});
	}

	/** The voices we recorded under one shot file of a clip (none for films shot before their ticks were kept). */
	private CallVoices.Piece voicePiece(FilmTimeline.Segment clip, String file, Map<String, Integer> lengths, CallVoices voices) {
		int[] ticks = shot.ticks(clip.number(), file);
		int frames = lengths.getOrDefault(file, -1);
		if (ticks == null || frames <= 0) {
			return voices.piece(null, 0, 0, -1, quality.framerate, 0);
		}
		return voices.piece(clip.item().replayId(), ticks[0], ticks[1], clip.item().slowMotionFromTick(), quality.framerate, frames);
	}

	/** RMS of each short window of a sound, up to {@code seconds} of it. */
	private static List<Double> windowLevels(AudioStream sound, double seconds) throws IOException {
		int size = (int) Math.round(WINDOW_SECONDS * AudioStream.RATE) * AudioStream.CHANNELS;
		short[] chunk = new short[size];
		List<Double> levels = new ArrayList<>();
		while (!sound.isEnded() && levels.size() * WINDOW_SECONDS < seconds) {
			int n = sound.read(chunk, size);
			if (n > 0) {
				levels.add(Ducker.rms(chunk, n));
			}
		}
		return levels;
	}

	private static List<String> titles(List<MusicLibrary.Track> tracks) {
		List<String> titles = new ArrayList<>();
		for (MusicLibrary.Track track : tracks) {
			if (track != null && !titles.contains(track.title())) {
				titles.add(track.title());
			}
		}
		return titles;
	}

	private static boolean isFootage(Kind kind) {
		return kind == Kind.CLIP || kind == Kind.TIMELAPSE;
	}

	/**
	 * The footage a card sits on: the first file of the clip after it, or for the credits the last file of the clip
	 * before them.
	 *
	 * @param fromEnd the card comes after the footage (its end is what was seen last)
	 */
	private record Neighbour(Path file, boolean fromEnd) {
	}

	private @Nullable Neighbour neighbour(FilmTimeline timeline, int index, boolean next) {
		List<FilmTimeline.Segment> segments = timeline.segments();
		for (int i = next ? index + 1 : index - 1; i >= 0 && i < segments.size(); i += next ? 1 : -1) {
			FilmTimeline.Segment segment = segments.get(i);
			if (isFootage(segment.kind())) {
				return new Neighbour(film.resolve(next ? segment.files().getFirst() : segment.files().getLast()), !next);
			}
		}
		return null;
	}

	/** Where a backdrop is looked for: this many seconds into the footage (or before its end). */
	private static final double[] BACKDROP_CANDIDATES = {0.5, 0.9, 1.3, 1.7, 2.2, 2.7};

	/**
	 * The picture to blur behind a card: not the very first frame of a clip, which is often blocky (the encoder's
	 * first keyframe) or still loading, but the best of a few frames around a second in, bright and detailed rather
	 * than dark or smeared.
	 */
	private @Nullable BufferedImage backdropStill(@Nullable Neighbour neighbour) {
		if (neighbour == null) {
			return null;
		}
		BufferedImage best = null;
		double bestScore = -1;
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(neighbour.file().toFile());
			 Java2DFrameConverter converter = new Java2DFrameConverter()) {
			grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
			grabber.start();
			double length = grabber.getLengthInTime() / 1e6;
			List<Double> times = new ArrayList<>();
			for (double candidate : BACKDROP_CANDIDATES) {
				double at = neighbour.fromEnd() ? length - candidate : candidate;
				if (at > 0.1 && at < length - 0.1) {
					times.add(at);
				}
			}
			times.sort(null);
			if (times.isEmpty()) {
				return still(neighbour.file(), neighbour.fromEnd());
			}
			if (neighbour.fromEnd()) {
				grabber.setTimestamp(Math.max(0, Math.round((times.getFirst() - 0.5) * 1e6)));
			}
			int next = 0;
			Frame frame;
			while (next < times.size() && (frame = grabber.grabImage()) != null) {
				if (frame.timestamp / 1e6 < times.get(next)) {
					continue;
				}
				BufferedImage picture = copy(converter.convert(frame));
				// the nearer to the cut, the better it matches it: a small preference for the first candidates
				int fromCut = neighbour.fromEnd() ? times.size() - 1 - next : next;
				double score = backdropScore(picture) - 0.03 * fromCut;
				if (score > bestScore) {
					best = picture;
					bestScore = score;
				}
				while (next < times.size() && times.get(next) <= frame.timestamp / 1e6) {
					next++;
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Could not read a backdrop from {}", neighbour.file(), e);
		}
		return best != null ? best : still(neighbour.file(), neighbour.fromEnd());
	}

	/**
	 * How good a picture is to blur behind a card: light enough (a dark one is a black smudge) and with detail at a
	 * coarse scale (a smeared or half-loaded picture has none), roughly 0..2.
	 */
	static double backdropScore(BufferedImage picture) {
		int w = 160;
		int h = Math.max(1, picture.getHeight() * w / Math.max(1, picture.getWidth()));
		BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = small.createGraphics();
		try {
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(picture, 0, 0, w, h, null);
		} finally {
			g.dispose();
		}
		int[] rgb = small.getRGB(0, 0, w, h, null, 0, w);
		double[] luma = new double[rgb.length];
		double mean = 0;
		for (int i = 0; i < rgb.length; i++) {
			luma[i] = (0.299 * (rgb[i] >> 16 & 0xFF) + 0.587 * (rgb[i] >> 8 & 0xFF) + 0.114 * (rgb[i] & 0xFF)) / 255;
			mean += luma[i];
		}
		mean /= rgb.length;
		double detail = 0;
		for (int y = 0; y < h - 1; y++) {
			for (int x = 0; x < w - 1; x++) {
				detail += Math.abs(luma[y * w + x + 1] - luma[y * w + x]) + Math.abs(luma[(y + 1) * w + x] - luma[y * w + x]);
			}
		}
		detail /= Math.max(1, (w - 1) * (h - 1));
		return Math.min(mean, 0.4) / 0.4 + Math.min(detail, 0.1) / 0.1;
	}

	private @Nullable BufferedImage still(Path file, boolean last) {
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toFile());
			 Java2DFrameConverter converter = new Java2DFrameConverter()) {
			grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
			grabber.start();
			if (last) {
				grabber.setTimestamp(Math.max(0, grabber.getLengthInTime() - 400_000));
			}
			BufferedImage picture = null;
			Frame frame;
			while ((frame = grabber.grabImage()) != null) {
				picture = copy(converter.convert(frame));
				if (!last) {
					break;
				}
			}
			return picture;
		} catch (Exception e) {
			LOGGER.warn("Could not read a still from {}", file, e);
			return null;
		}
	}

	/** @param leadIn samples (both channels) of near silence at the track's start, skipped */
	private record TrackLevel(double gain, long leadIn) {
	}

	/** Smoothstep: starts and ends gently. */
	private static double ease(double v) {
		double t = Math.max(0, Math.min(1, v));
		return t * t * (3 - 2 * t);
	}

	private static BufferedImage copy(BufferedImage source) {
		BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = copy.createGraphics();
		try {
			g.drawImage(source, 0, 0, null);
		} finally {
			g.dispose();
		}
		return copy;
	}

	private static int countFrames(Path file, int fps) {
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toFile())) {
			grabber.start();
			int frames = grabber.getLengthInVideoFrames();
			if (frames <= 0) {
				frames = (int) Math.round(grabber.getLengthInTime() / 1e6 * fps);
			}
			return frames;
		} catch (Exception e) {
			LOGGER.warn("Could not read the clip {}", file, e);
			return -1;
		}
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			LOGGER.warn("Could not delete {}", file, e);
		}
	}

	/** Writes the film frame by frame, mixing the sound of each frame as it goes. */
	private final class Writer implements AutoCloseable {
		private final FFmpegFrameRecorder recorder;
		private final Java2DFrameConverter converter = new Java2DFrameConverter();
		private final int fps;
		private final int samplesPerFrame;
		private final int total;
		private final Ducker ducker;
		private final double gameGain;
		private final short[] chunk;
		private final int[] mix;
		private final short[] out;
		private final List<MusicVoice> music = new ArrayList<>();
		/** Where each music thread stopped (samples into its track), so the next cue of it goes on from there. */
		private final Map<Integer, Long> threadPositions = new HashMap<>();
		/** Each track's level and silent beginning, measured once. */
		private final Map<String, TrackLevel> trackLevels = new HashMap<>();
		private final List<SoundCues.Cue> effects;
		private final Map<SoundCues.Effect, short[]> effectSamples = new EnumMap<>(SoundCues.Effect.class);
		private final List<int[]> playing = new ArrayList<>(); // {effect ordinal, position, volume per mille}
		private int nextEffect;
		private int frame;
		/** The elytra wind: its volume for the frame being written (0 = none) and where in its loop it is. */
		private double wind;
		private int windPosition;
		private final CallVoices voices;
		private @Nullable Frame black;
		/** The clip the one being written dissolves into, already opened for the dissolve. */
		private @Nullable ClipReader incoming;

		Writer(Path file, FilmTimeline timeline, List<MusicPlan.Cue> cues, List<MusicLibrary.Track> tracks,
			   List<SoundCues.Cue> effects, double gameGain, CallVoices voices) throws IOException {
			this.voices = voices;
			this.fps = timeline.fps();
			this.gameGain = gameGain;
			this.samplesPerFrame = AudioStream.RATE / fps * AudioStream.CHANNELS;
			this.total = Math.max(1, timeline.totalFrames());
			this.ducker = new Ducker(fps);
			this.chunk = new short[samplesPerFrame];
			this.mix = new int[samplesPerFrame];
			this.out = new short[samplesPerFrame];
			for (int i = 0; i < cues.size(); i++) {
				if (tracks.get(i) != null) {
					music.add(new MusicVoice(cues.get(i), tracks.get(i)));
				}
			}
			this.effects = effects;
			ingredients.effects().forEach((effect, data) -> {
				try {
					effectSamples.put(effect, AudioStream.decodeAll(data));
				} catch (IOException e) {
					LOGGER.warn("Could not decode the sound {}", effect.sound, e);
				}
			});

			recorder = new FFmpegFrameRecorder(file.toFile(), quality.width, quality.height, AudioStream.CHANNELS);
			recorder.setFormat("mp4");
			recorder.setVideoCodecName(encoder);
			recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
			recorder.setFrameRate(fps);
			recorder.setVideoBitrate(quality.bitrate);
			recorder.setGopSize(fps * 2);
			recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
			recorder.setSampleRate(AudioStream.RATE);
			recorder.setAudioChannels(AudioStream.CHANNELS);
			recorder.setAudioBitrate(AUDIO_BITRATE);
			recorder.setOption("movflags", "+faststart");
			recorder.start();
		}

		/**
		 * A clip, frame by frame. Its last frames dissolve into the next clip when that one starts with a dissolve
		 * (both are read at once); its first frames were already written when it starts with one.
		 */
		void clip(FilmTimeline.Segment segment, FilmTimeline.@Nullable Segment next, Map<String, Integer> lengths) throws IOException {
			ClipReader reader = incoming != null && incoming.segment == segment ? incoming : new ClipReader(segment, lengths);
			incoming = null;
			int dissolve = next != null && next.kind() == Kind.CLIP ? next.dissolveFrames() : 0;
			try {
				for (int i = reader.read; i < segment.frames(); i++) {
					double fade = segment.brightness(i);
					Frame picture = reader.next(fade);
					java.util.Arrays.fill(mix, 0);
					int into = i - (segment.frames() - dissolve);
					if (into < 0) {
						double level = reader.mixInto(mix, fade, 1);
						wind = reader.wind * fade;
						write(picture, level);
						continue;
					}
					if (incoming == null) {
						incoming = new ClipReader(next, lengths);
					}
					// a dissolve: the pictures blend, the sounds cross over at equal power
					double nextFade = next.brightness(incoming.read);
					Frame coming = incoming.next(nextFade);
					double blend = ease((into + 1) / (double) (dissolve + 1));
					Frame both = blend(picture, coming, blend);
					double out = Math.cos(blend * Math.PI / 2);
					double in = Math.sin(blend * Math.PI / 2);
					double level = reader.mixInto(mix, fade, out) + incoming.mixInto(mix, nextFade, in);
					wind = reader.wind * fade * out + incoming.wind * nextFade * in;
					write(both, level);
				}
			} finally {
				reader.close();
				wind = 0;
			}
		}

		/** {@code a} blended into {@code b} by {@code amount} (0 = all a), in place in {@code b}; black frames are shared. */
		private Frame blend(Frame a, Frame b, double amount) {
			if (b == black) {
				return a;
			}
			if (a == black) {
				dim(b, amount);
				return b;
			}
			int keep = (int) Math.round(Math.max(0, Math.min(1, amount)) * 256);
			ByteBuffer from = (ByteBuffer) a.image[0];
			ByteBuffer to = (ByteBuffer) b.image[0];
			int rowBytes = b.imageWidth * b.imageChannels;
			for (int y = 0; y < b.imageHeight; y++) {
				int rowA = y * a.imageStride;
				int rowB = y * b.imageStride;
				for (int x = 0; x < rowBytes; x++) {
					int va = from.get(rowA + x) & 0xFF;
					int vb = to.get(rowB + x) & 0xFF;
					to.put(rowB + x, (byte) (va + ((vb - va) * keep >> 8)));
				}
			}
			return b;
		}

		/** The frames and sound of one clip's files in a row, read as they are written. */
		private final class ClipReader implements AutoCloseable {
			final FilmTimeline.Segment segment;
			private final Map<String, Integer> lengths;
			/** Frames of the segment read so far. */
			int read;
			/** The wind of the frame read last (0..1). */
			double wind;
			private final short[] sound = new short[samplesPerFrame];
			private int file = -1;
			private int inFile;
			private int expected;
			private @Nullable FFmpegFrameGrabber video;
			private @Nullable AudioStream audio;
			private CallVoices.@Nullable Piece voice;
			private double[] winds = new double[0];

			ClipReader(FilmTimeline.Segment segment, Map<String, Integer> lengths) {
				this.segment = segment;
				this.lengths = lengths;
			}

			/** The next frame, darkened for a fade; black (and silent) past the end of the files. */
			Frame next(double fade) throws IOException {
				while (file < 0 || inFile >= expected) {
					closeFile();
					if (++file >= segment.files().size()) {
						java.util.Arrays.fill(sound, (short) 0);
						wind = 0;
						read++;
						return black();
					}
					openFile(segment.files().get(file));
				}
				Frame picture = video.grabImage();
				if (picture == null) {
					picture = black(); // the file is a little shorter than it said
				} else {
					dim(picture, fade);
				}
				java.util.Arrays.fill(sound, (short) 0);
				audio.read(sound, samplesPerFrame);
				wind = inFile < winds.length ? winds[inFile] : 0;
				inFile++;
				read++;
				return picture;
			}

			/** Adds the sound of the frame read last (the game's and our recorded voices) to a mix; returns how loud it was. */
			double mixInto(int[] mix, double fade, double weight) {
				double gain = gameGain * fade * weight;
				for (int i = 0; i < samplesPerFrame; i++) {
					mix[i] += (int) Math.round(sound[i] * gain);
				}
				double level = Ducker.rms(sound, samplesPerFrame) * gain;
				if (voice != null && !voice.isEmpty()) {
					level += voice.mixInto(inFile - 1, mix, fade * weight); // music ducks under friends talking too
				}
				return level;
			}

			private void openFile(String name) throws IOException {
				inFile = 0;
				expected = lengths.getOrDefault(name, 0);
				Path path = film.resolve(name);
				voice = voicePiece(segment, name, lengths, voices);
				winds = winds(segment, name, expected);
				FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(path.toFile());
				grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
				grabber.setImageWidth(quality.width);
				grabber.setImageHeight(quality.height);
				grabber.start();
				video = grabber;
				audio = AudioStream.open(path);
			}

			private void closeFile() throws IOException {
				voice = null;
				try {
					if (video != null) {
						video.close();
					}
				} finally {
					video = null;
					if (audio != null) {
						audio.close();
						audio = null;
					}
				}
			}

			@Override
			public void close() throws IOException {
				closeFile();
			}
		}

		/** The wind's volume for each frame of one shot file of a clip (silent where nobody flies). */
		private double[] winds(FilmTimeline.Segment segment, String file, int frames) {
			int[] ticks = shot.ticks(segment.number(), file);
			Trail trail = trail(segment.item().replayId());
			if (ticks == null || trail == null || frames <= 0 || !effectSamples.containsKey(SoundCues.Effect.ELYTRA_WIND)) {
				return new double[Math.max(0, frames)];
			}
			double[] volumes = FlightSound.volumes(trail, ticks[0], ticks[1], segment.item().slowMotionFromTick(), fps, frames);
			long windy = java.util.Arrays.stream(volumes).filter(v -> v > 0.05).count();
			if (windy > 0) {
				LOGGER.info("Elytra wind under {} s of clip {} ({}), up to {}", Math.round(windy / (double) fps * 10) / 10.0,
					segment.number(), file, String.format(java.util.Locale.ROOT, "%.2f", java.util.Arrays.stream(volumes).max().orElse(0)));
			}
			return volumes;
		}

		/** @param first the next clip's first frame to pull focus into at the end, or null */
		void card(FilmTimeline.Segment segment, FilmPainter painter, @Nullable BufferedImage still, @Nullable BufferedImage first) throws IOException {
			BufferedImage backdrop = painter.backdrop(still, FilmPainter.CARD_BACKDROP);
			BufferedImage focus = first != null ? painter.sharp(first) : null;
			List<FilmPainter.CardLine> lines = segment.kind() == Kind.INTRO
				? FilmTexts.intro(painter, edl)
				: FilmTexts.chapter(painter, segment.item());
			double centre = segment.kind() == Kind.INTRO ? 0.5 : 0.42;
			double seconds = segment.frames() / (double) fps;
			for (int i = 0; i < segment.frames(); i++) {
				BufferedImage picture = segment.kind() == Kind.INTRO
					? painter.typed(backdrop, lines, centre, i / (double) fps, seconds, segment.brightness(i))
					: painter.card(backdrop, focus, lines, centre, i / (double) fps, seconds, segment.brightness(i));
				writeSilent(converter.convert(picture));
			}
		}

		/**
		 * The base timelapse: each shot plays in its share of the time (a stretch of building as fast as it was shot, a
		 * still held), dissolving into the next over its last moments, with the day in the corner. No game sound: the
		 * music carries it.
		 */
		void timelapse(FilmTimeline.Segment segment, FilmPainter painter) throws IOException {
			List<String> files = segment.files();
			int[] shares = new int[files.size()];
			String[] days = new String[files.size()];
			int planned = 0;
			for (int i = 0; i < files.size(); i++) {
				EditDecisionList.Item shot = pictures.get(files.get(i));
				shares[i] = Math.max(1, shot == null ? segment.frames() / files.size() : (int) Math.round(shot.cardSeconds() * fps));
				days[i] = shot == null ? "" : FilmTexts.day(shot.dayFrom());
				planned += shares[i];
			}
			shares[shares.length - 1] = Math.max(1, shares[shares.length - 1] + segment.frames() - planned);
			int written = 0;
			for (int i = 0; i < files.size() && written < segment.frames(); i++) {
				BufferedImage next = i + 1 < files.size() ? still(film.resolve(files.get(i + 1)), false) : null;
				int dissolve = next == null ? 0 : Math.min(shares[i] / 3, (int) Math.round(TIMELAPSE_DISSOLVE_SECONDS * fps));
				int share = Math.min(shares[i], segment.frames() - written);
				try (FFmpegFrameGrabber video = new FFmpegFrameGrabber(film.resolve(files.get(i)).toFile());
					 Java2DFrameConverter reader = new Java2DFrameConverter()) {
					video.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
					video.start();
					int length = Math.max(1, video.getLengthInVideoFrames());
					int decoded = -1;
					BufferedImage current = null;
					for (int j = 0; j < share; j++) {
						// the shot's frames spread over its share: skipped when it is shorter, held when it is longer
						int wanted = Math.min(length - 1, (int) ((long) j * length / Math.max(1, shares[i])));
						while (decoded < wanted) {
							Frame frame = video.grabImage();
							if (frame == null) {
								length = decoded + 1;
								break;
							}
							decoded++;
							if (decoded == wanted || current == null) {
								current = copy(reader.convert(frame));
							}
						}
						double blend = dissolve > 0 && j >= share - dissolve ? (j - (share - dissolve) + 1) / (double) (dissolve + 1) : 0;
						if (current == null) {
							writeSilent(black());
						} else {
							BufferedImage picture = painter.timelapse(current, next, blend, blend > 0.5 ? days[i + 1] : days[i],
								segment.brightness(written));
							writeSilent(converter.convert(picture));
						}
						written++;
					}
				} catch (FFmpegFrameGrabber.Exception e) {
					LOGGER.warn("Could not read the timelapse shot {}", files.get(i), e);
				}
			}
			while (written < segment.frames()) {
				writeSilent(black()); // keeps the film in time with its music and cards
				written++;
			}
		}

		void credits(FilmTimeline.Segment segment, FilmPainter painter, CreditsRoll roll, @Nullable BufferedImage still) throws IOException {
			BufferedImage backdrop = painter.backdrop(still, FilmPainter.CREDITS_BACKDROP);
			double rowPixels = painter.creditsRowPixels();
			for (int i = 0; i < segment.frames(); i++) {
				BufferedImage picture = painter.credits(backdrop, roll, rowPixels, i / (double) fps, segment.brightness(i));
				writeSilent(converter.convert(picture));
			}
		}

		/** A frame of a card: no sound of its own, only music and effects. */
		private void writeSilent(Frame picture) throws IOException {
			java.util.Arrays.fill(mix, 0);
			write(picture, 0);
		}

		/**
		 * One video frame and its sound.
		 *
		 * @param level how loud the clip's own sound already in {@link #mix} is (the game's and friends' voices): the
		 *              music ducks under it
		 */
		private void write(Frame picture, double level) throws IOException {
			if (cancelled) {
				throw new CancellationException("editing stopped");
			}
			recorder.record(picture, avutil.AV_PIX_FMT_BGR24);
			double duck = ducker.next(level);
			for (MusicVoice voice : music) {
				voice.mixInto(frame, mix, duck);
			}
			mixEffects();
			mixWind();
			for (int i = 0; i < samplesPerFrame; i++) {
				out[i] = Loudness.limit(mix[i]);
			}
			recorder.recordSamples(AudioStream.RATE, AudioStream.CHANNELS, ShortBuffer.wrap(out));
			frame++;
			progress = Math.min(0.999, frame / (double) total);
		}

		private void mixEffects() {
			while (nextEffect < effects.size() && effects.get(nextEffect).frame() <= frame) {
				SoundCues.Cue cue = effects.get(nextEffect++);
				if (effectSamples.containsKey(cue.effect())) {
					playing.add(new int[]{cue.effect().ordinal(), 0, (int) Math.round(cue.volume() * 1000)});
				}
			}
			for (int p = playing.size() - 1; p >= 0; p--) {
				int[] sound = playing.get(p);
				short[] samples = effectSamples.get(SoundCues.Effect.values()[sound[0]]);
				int n = Math.min(samplesPerFrame, samples.length - sound[1]);
				for (int i = 0; i < n; i++) {
					mix[i] += samples[sound[1] + i] * sound[2] / 1000;
				}
				sound[1] += n;
				if (sound[1] >= samples.length) {
					playing.remove(p);
				}
			}
		}

		/** The elytra wind under a flight, looped, at this frame's volume; it does not push the music down. */
		private void mixWind() {
			short[] samples = effectSamples.get(SoundCues.Effect.ELYTRA_WIND);
			if (wind <= 0 || samples == null || samples.length < samplesPerFrame) {
				return;
			}
			double gain = wind * WIND_LEVEL;
			for (int i = 0; i < samplesPerFrame; i++) {
				mix[i] += (int) Math.round(samples[windPosition] * gain);
				windPosition = (windPosition + 1) % samples.length;
			}
		}

		/** Darkens a decoded frame in place for a fade (BGR, one byte per channel). */
		private void dim(Frame picture, double brightness) {
			if (brightness >= 0.999) {
				return;
			}
			int factor = (int) Math.round(Math.max(0, brightness) * 256);
			ByteBuffer pixels = (ByteBuffer) picture.image[0];
			int stride = picture.imageStride;
			int rowBytes = picture.imageWidth * picture.imageChannels;
			for (int y = 0; y < picture.imageHeight; y++) {
				int row = y * stride;
				for (int x = 0; x < rowBytes; x++) {
					int v = pixels.get(row + x) & 0xFF;
					pixels.put(row + x, (byte) (v * factor >> 8));
				}
			}
		}

		private Frame black() {
			if (black == null) {
				black = new Frame(quality.width, quality.height, Frame.DEPTH_UBYTE, 3);
			}
			return black;
		}

		@Override
		public void close() throws IOException {
			try {
				if (incoming != null) {
					incoming.close();
				}
				for (MusicVoice voice : music) {
					voice.close();
				}
				recorder.stop();
			} finally {
				recorder.release();
				converter.close();
			}
		}

		/** One cue of music: its track, opened when the cue starts, from the beginning or where its thread stopped. */
		private final class MusicVoice {
			private final MusicPlan.Cue cue;
			private final MusicLibrary.Track track;
			private @Nullable AudioStream stream;
			private boolean broken;
			/** Samples of the track (both channels) read so far, from its very beginning. */
			private long played;

			MusicVoice(MusicPlan.Cue cue, MusicLibrary.Track track) {
				this.cue = cue;
				this.track = track;
			}

			void mixInto(int frame, int[] mix, double duck) throws IOException {
				if (broken || frame < cue.startFrame()) {
					return;
				}
				if (frame >= cue.endFrame()) {
					close();
					return;
				}
				if (stream == null || stream.isEnded()) {
					boolean over = stream != null;
					close();
					// a track shorter than its cue starts over; a thread goes on where it stopped
					stream = open(over || cue.thread() < 0 ? 0 : threadPositions.getOrDefault(cue.thread(), 0L));
					if (stream == null) {
						return;
					}
				}
				stream.read(chunk, samplesPerFrame);
				played += samplesPerFrame;
				double gain = MUSIC_LEVEL * level().gain() * cue.envelope(frame) * duck;
				for (int i = 0; i < samplesPerFrame; i++) {
					mix[i] += (int) Math.round(chunk[i] * gain);
				}
			}

			private TrackLevel level() {
				return trackLevels.getOrDefault(track.id(), new TrackLevel(1, 0));
			}

			private @Nullable AudioStream open(long from) {
				try (InputStream in = track.opener().open()) {
					byte[] data = in.readAllBytes();
					if (!trackLevels.containsKey(track.id())) {
						// the game's tracks are mastered quietly, and everyone's own music differently: bring it to level
						try (AudioStream measure = AudioStream.open(data)) {
							double[] levels = windowLevels(measure, MUSIC_MEASURE_SECONDS).stream().mapToDouble(Double::doubleValue).toArray();
							double gain = Loudness.gain(Loudness.gatedRms(levels), Loudness.MUSIC_TARGET);
							int leadIn = 0;
							while (leadIn < levels.length && leadIn * WINDOW_SECONDS < MAX_LEAD_IN_SECONDS && levels[leadIn] < LEAD_IN_LEVEL) {
								leadIn++;
							}
							long leadInSamples = Math.round(leadIn * WINDOW_SECONDS * AudioStream.RATE) * AudioStream.CHANNELS;
							trackLevels.put(track.id(), new TrackLevel(gain, leadInSamples));
							LOGGER.info("Music {} at x{}, from {} s", track.title(), String.format(java.util.Locale.ROOT, "%.2f", gain),
								String.format(java.util.Locale.ROOT, "%.1f", leadIn * WINDOW_SECONDS));
						}
					}
					AudioStream started = AudioStream.open(data);
					played = Math.max(from, level().leadIn()); // the silent beginning is skipped
					long skip = played;
					while (skip > 0 && !started.isEnded()) {
						int n = (int) Math.min(chunk.length, skip);
						started.read(chunk, n);
						skip -= n;
					}
					if (from > 0) {
						LOGGER.info("Music {} goes on from {} s", track.title(),
							String.format(java.util.Locale.ROOT, "%.1f", from / (double) (AudioStream.RATE * AudioStream.CHANNELS)));
					}
					return started;
				} catch (Exception e) {
					LOGGER.warn("Could not play the track {}", track.title(), e);
					broken = true;
					return null;
				}
			}

			void close() throws IOException {
				if (stream != null) {
					if (cue.thread() >= 0) {
						threadPositions.put(cue.thread(), played);
					}
					stream.close();
					stream = null;
				}
			}
		}
	}
}
