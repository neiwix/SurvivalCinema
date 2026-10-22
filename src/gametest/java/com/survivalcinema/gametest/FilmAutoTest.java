package com.survivalcinema.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.survivalcinema.client.capture.WorldFilmLoader;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.client.play.FilmPlayer;
import com.survivalcinema.client.ui.CinemaScreen;
import com.survivalcinema.client.ui.DragonOfferScreen;
import com.survivalcinema.client.ui.FilmSetupScreen;
import com.survivalcinema.client.ui.FilmPlayerScreen;
import com.survivalcinema.client.render.RenderPipeline;
import com.survivalcinema.client.voice.apps.CallVoiceRecorder;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.event.SafeFiles;
import com.survivalcinema.director.render.ClipCamera;
import com.survivalcinema.director.render.FilmFolder;
import com.survivalcinema.director.render.RenderPlan;
import com.survivalcinema.director.render.RenderProgress;
import com.survivalcinema.director.render.RenderQuality;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.FilmDirector;
import com.survivalcinema.director.story.SessionData;
import com.survivalcinema.director.timelapse.BaseFinder;
import com.survivalcinema.director.voice.VoiceTrackIndex;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * {@code gradlew runFilmTest}: shoots a one-minute film end to end from the recordings of the dev client
 * ({@code run/}), the way a player would: starts it from the title screen, presses "Esc" once a clip is done
 * (stop after the clip being shot), stops, continues the film from its saved progress until every clip is shot and
 * the film is edited, then closes the game. The result is logged as "FILM TEST PASSED" or "FILM TEST FAILED".
 */
public class FilmAutoTest implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("survivalcinema-filmtest");
	private static final int FILM_SECONDS = 60;
	/**
	 * {@code -Preshoot=<world>/<film>} (a folder in {@code run/survivalcinema/films}): instead of the test, shoots that
	 * film of the dev client again, to look at changes to the camera. {@code -Pclips=3,5} limits it to those clips.
	 */
	private static final String RESHOOT = System.getProperty("survivalcinema.filmtest.reshoot", "").strip();
	private static final String CLIPS = System.getProperty("survivalcinema.filmtest.clips", "").strip();
	/** {@code -Pfrom=<folder>}: another game folder than {@code run/} to take the recordings from (the playthrough's). */
	private static final String FROM = System.getProperty("survivalcinema.filmtest.from", "").strip();

	private enum Stage {WAIT_TITLE, CALL, CALL_SAVED, SHOOT, ASK_STOP, STOPPING, WAIT_RESUME, RESUMED, WAIT_PLAYER, WATCHING, PAUSED, SEEKED, LOOK_SETUP, LOOK_OFFER, RESHOOT, OVER}

	private Stage stage = Stage.WAIT_TITLE;
	private int ticks;
	private int stageTicks;
	private String summary = "";
	private double pausedAt;
	private double seekTarget;
	private int shownBeforeSeek;
	private net.minecraft.client.gui.screens.Screen cinemaScreen;
	private Path film;
	private EditDecisionList edl;
	private int shotBeforeStop;
	/** The test call under the first clip, and that clip. */
	private Process call;
	private RenderPlan.Job called;
	private int timelapsePictures;

	@Override
	public void onInitializeClient() {
		if (Boolean.getBoolean("survivalcinema.filmtest")) {
			ClientTickEvents.END_CLIENT_TICK.register(this::tick);
			RenderPipeline.listenToCameras(this::saveCamera);
		}
	}

	/** Saves the window once half-way through an export (from ExportFrameCaptureMixin). */
	public static void captureExportFrame(RenderTarget window) {
		if (!Boolean.getBoolean("survivalcinema.filmtest") || !CAPTURED.add("film-export")) {
			return;
		}
		Screenshot.grab(Minecraft.getInstance().gameDirectory, "film-export.png", window, 1,
			message -> LOGGER.info("Export window: {}", message.getString()));
	}

	/** Saves the last normal frame once per phase, to look at the cinema screen afterwards. */
	private static void captureGui(Minecraft mc, RenderPipeline pipeline) {
		if (pipeline == null || FlashbackBridge.isExporting()) {
			return;
		}
		String name = "film-" + pipeline.phase().name().toLowerCase(Locale.ROOT);
		if (CAPTURED.add(name)) {
			Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), 1, message -> {
			});
		}
	}

	private static final Set<String> CAPTURED = ConcurrentHashMap.newKeySet();

	private void tick(Minecraft mc) {
		if (stage == Stage.OVER) {
			return;
		}
		try {
			captureGui(mc, RenderPipeline.current());
			step(mc);
		} catch (Throwable t) {
			LOGGER.error("FILM TEST FAILED", t);
			over(mc);
		}
	}

	private void stage(Stage next) {
		stage = next;
		stageTicks = 0;
	}

	private static FilmPlayerScreen player(Minecraft mc) {
		check(mc.screen instanceof FilmPlayerScreen, "the in-game cinema stays open, the screen is " + mc.screen);
		return (FilmPlayerScreen) mc.screen;
	}

	private void step(Minecraft mc) throws IOException {
		RenderPipeline pipeline = RenderPipeline.current();
		ticks++;
		stageTicks++;
		switch (stage) {
			case WAIT_TITLE -> {
				// the title screen, or the first-launch accessibility screen of a fresh game folder
				if (mc.level == null && mc.screen != null && mc.getOverlay() == null && ticks > 40) {
					prepare(mc);
					if (RESHOOT.isEmpty()) {
						startCall(mc);
						stage(Stage.CALL);
					} else {
						RenderPipeline.start(film, edl, RenderProgress.read(FilmFolder.progressFile(film)));
						stage = Stage.RESHOOT;
					}
				}
			}
			case CALL -> {
				// a "friend in Discord" beeps over the first clip, captured the way a real call is
				CallVoiceRecorder.TestRecording recording = CallVoiceRecorder.test;
				if (recording != null && recording.startTick() + (System.nanoTime() - recording.startNanos()) / 50_000_000L > called.item().endTick() + 20) {
					CallVoiceRecorder.test = null; // the next tick closes the track
					call.destroyForcibly();
					stage(Stage.CALL_SAVED);
				}
			}
			case CALL_SAVED -> {
				if (stageTicks == 20) {
					Path voice = mc.gameDirectory.toPath().resolve("survivalcinema/sessions").resolve(called.replayId()).resolve(CallVoiceRecorder.VOICE_FOLDER);
					Path audio = voice.resolve("test" + VoiceTrackIndex.AUDIO_SUFFIX);
					Path index = voice.resolve("test" + VoiceTrackIndex.INDEX_SUFFIX);
					check(Files.isRegularFile(audio) && Files.size(audio) > 1000, "the call was recorded into " + audio);
					VoiceTrackIndex lines = VoiceTrackIndex.parse(Files.readString(index, StandardCharsets.UTF_8));
					check(lines.firstTick() <= called.item().startTick() + 2 && lines.lastTick() >= called.item().endTick(),
						"the call covers the first clip: " + lines + " for ticks " + called.item().startTick() + "-" + called.item().endTick());
					int beeps = TestCall.toneWindows(audio);
					LOGGER.info("Test call recorded: {} ({} bytes, {}), {} tenths of a second of tone", audio, Files.size(audio), lines, beeps);
					check(beeps > 10, "the call's beeps are in the recorded track: " + beeps);
					RenderPipeline.start(film, edl, RenderProgress.read(FilmFolder.progressFile(film)));
					stage(Stage.SHOOT);
				}
			}
			case SHOOT -> {
				check(!pipeline.phase().isFinal(), "the film ended before it was stopped: " + pipeline.phase());
				if (pipeline.status(-1).shotClips() >= 1) {
					// Esc, possibly in the middle of the next clip's export: the question comes after it
					pipeline.requestStop();
					stage = Stage.ASK_STOP;
				}
			}
			case ASK_STOP -> {
				check(!pipeline.phase().isFinal(), "the film ended instead of asking: " + pipeline.phase());
				if (pipeline.phase() == RenderPipeline.Phase.CONFIRM_STOP && !FlashbackBridge.isExporting()) {
					pipeline.stop();
					stage = Stage.STOPPING;
				}
			}
			case STOPPING -> {
				if (pipeline.phase() == RenderPipeline.Phase.STOPPED && settled()) {
					RenderProgress stopped = RenderProgress.read(FilmFolder.progressFile(film));
					check(stopped != null && !stopped.finished, "a stopped film is not finished");
					shotBeforeStop = stopped.count(RenderProgress.Status.DONE);
					check(shotBeforeStop >= 1, "the clip shot before stopping is kept");
					LOGGER.info("Stopped after {} clips", shotBeforeStop);
					pipeline.dismiss();
					ticks = 0;
					stage = Stage.WAIT_RESUME;
				}
			}
			case WAIT_RESUME -> {
				if (mc.screen instanceof TitleScreen && ticks > 40) {
					RenderProgress resumed = RenderProgress.read(FilmFolder.progressFile(film));
					check(resumed != null && resumed.matches(edl), "the saved progress belongs to the film");
					resumed.forgetMissingFiles(film);
					RenderPlan plan = RenderPlan.of(edl);
					check(resumed.pending(plan).size() < plan.size(), "clips shot before stopping are not shot again");
					RenderPipeline.start(film, edl, resumed);
					stage = Stage.RESUMED;
				}
			}
			case RESUMED -> {
				if (pipeline.phase().isFinal() && settled()) {
					check(pipeline.phase() == RenderPipeline.Phase.DONE, "the film finished, got " + pipeline.phase());
					RenderProgress done = RenderProgress.read(FilmFolder.progressFile(film));
					check(done != null && done.finished, "a finished film is marked finished");
					int files = 0;
					for (RenderProgress.Clip clip : done.clips) {
						for (String file : clip.files) {
							check(Files.size(film.resolve(file)) > 0, "clip file " + file + " has video in it");
							files++;
						}
					}
					check(done.count(RenderProgress.Status.DONE) > shotBeforeStop, "more clips were shot after continuing");
					check(done.film != null, "the clips were edited into a film");
					Path edited = film.resolve(done.film);
					check(Files.isRegularFile(edited) && Files.size(edited) > 0, "the film " + edited + " has video in it");
					check(edited.equals(pipeline.filmFile()), "the cinema screen offers the film to watch");
					check(!Files.exists(film.resolve(done.film + ".part")), "no half-made film is left behind");
					int lapseShot = 0;
					String firstPicture = null;
					for (RenderPlan.Job job : RenderPlan.of(edl).jobs()) {
						if (job.item().kind() == EditDecisionList.Kind.TIMELAPSE && done.status(job.number()) == RenderProgress.Status.DONE) {
							lapseShot++;
							if (firstPicture == null) {
								firstPicture = done.files(job.number()).getFirst();
							}
						}
					}
					LOGGER.info("Timelapse: {} of {} pictures shot, camera {}", lapseShot, timelapsePictures, java.util.Arrays.toString(done.timelapseCamera));
					check(lapseShot == timelapsePictures, "every picture of the base was shot: " + lapseShot + " of " + timelapsePictures);
					check(done.timelapseCamera != null, "the timelapse camera was chosen and kept");
					saveStill(film.resolve(firstPicture), mc.gameDirectory.toPath().resolve("screenshots/timelapse-picture.png"));
					int filmBeeps = TestCall.toneWindows(edited);
					int clipBeeps = 0;
					for (RenderProgress.Clip clip : done.clips) {
						for (String file : clip.files) {
							clipBeeps += TestCall.toneWindows(film.resolve(file));
						}
					}
					LOGGER.info("Test call in the film: {} tenths of a second of tone (the shot clips alone: {})", filmBeeps, clipBeeps);
					check(filmBeeps > clipBeeps + 10, "the call is heard in the film: " + filmBeeps + " vs " + clipBeeps);
					LOGGER.info("Film made: {} clips shot, {} skipped, {} files in {}, film {} ({} bytes)",
						done.count(RenderProgress.Status.DONE), done.count(RenderProgress.Status.SKIPPED), files, film, edited,
						Files.size(edited));
					summary = String.format(Locale.ROOT, "%d clips shot, %d skipped, film %s (%d bytes)",
						done.count(RenderProgress.Status.DONE), done.count(RenderProgress.Status.SKIPPED), edited, Files.size(edited));
					stage(Stage.WAIT_PLAYER);
				}
			}
			case WAIT_PLAYER -> {
				// the finished film starts in the in-game cinema by itself, after "The film is ready!"
				if (mc.screen instanceof FilmPlayerScreen) {
					stage(Stage.WATCHING);
				} else {
					check(stageTicks < 20 * 20, "the film starts playing by itself, the screen is " + mc.screen);
				}
			}
			case WATCHING -> {
				FilmPlayerScreen player = player(mc);
				if (stageTicks == 20 * 5) {
					FilmPlayerScreen.Status status = player.status();
					LOGGER.info("Cinema after 5 s: {}", status);
					check(status.state() == FilmPlayer.State.PLAYING, "the film plays, got " + status.state());
					check(status.position() > 2, "the film moves on, at " + status.position());
					check(status.shownPictures() > 30, "pictures are shown: " + status.shownPictures());
					check(status.duration() > 10, "the film knows its length: " + status.duration());
					check(status.hasSound() && status.soundPlaying(), "the film's sound plays");
					Screenshot.grab(mc.gameDirectory, "film-player.png", mc.getMainRenderTarget(), 1, message -> {
					});
					player.setPaused(true);
					pausedAt = player.status().position();
					stage(Stage.PAUSED);
				}
			}
			case PAUSED -> {
				FilmPlayerScreen player = player(mc);
				if (stageTicks == 30) {
					FilmPlayerScreen.Status status = player.status();
					check(status.state() == FilmPlayer.State.PAUSED, "paused, got " + status.state());
					check(Math.abs(status.position() - pausedAt) < 0.05, "a paused film stands still: " + pausedAt + " -> " + status.position());
					Screenshot.grab(mc.gameDirectory, "film-player-paused.png", mc.getMainRenderTarget(), 1, message -> {
					});
					shownBeforeSeek = status.shownPictures();
					seekTarget = status.duration() / 2;
					player.seekTo(seekTarget);
					player.setPaused(false);
					stage(Stage.SEEKED);
				}
			}
			case SEEKED -> {
				FilmPlayerScreen player = player(mc);
				if (stageTicks == 20 * 3) {
					FilmPlayerScreen.Status status = player.status();
					LOGGER.info("Cinema 3 s after seeking to {}: {}", seekTarget, status);
					check(status.state() == FilmPlayer.State.PLAYING, "plays on after seeking, got " + status.state());
					check(status.position() >= seekTarget && status.position() < seekTarget + 5,
						"plays from where it was sent: " + status.position() + " for " + seekTarget);
					check(status.shownPictures() > shownBeforeSeek + 20, "new pictures after seeking");
					check(status.soundPlaying(), "the sound plays after seeking");
					Screenshot.grab(mc.gameDirectory, "film-player-seek.png", mc.getMainRenderTarget(), 1, message -> {
					});
					player.onClose();
					check(mc.screen instanceof CinemaScreen, "closing the cinema goes back to the finished film screen, got " + mc.screen);
					cinemaScreen = mc.screen;
					mc.setScreen(new FilmSetupScreen(cinemaScreen));
					stage(Stage.LOOK_SETUP);
				}
			}
			case LOOK_SETUP -> {
				// the other film screens, for a look at their layout (no world here, so the setup shows why it can't start)
				if (stageTicks == 20) {
					Screenshot.grab(mc.gameDirectory, "film-setup.png", mc.getMainRenderTarget(), 1, message -> {
					});
					mc.setScreen(new DragonOfferScreen());
					stage(Stage.LOOK_OFFER);
				}
			}
			case LOOK_OFFER -> {
				if (stageTicks == 20) {
					Screenshot.grab(mc.gameDirectory, "film-offer.png", mc.getMainRenderTarget(), 1, message -> {
					});
					mc.setScreen(cinemaScreen);
					LOGGER.info("FILM TEST PASSED: {}, watched in the in-game cinema", summary);
					pipeline.dismiss();
					over(mc);
				}
			}
			case RESHOOT -> {
				if (pipeline.phase().isFinal() && settled()) {
					check(pipeline.phase() == RenderPipeline.Phase.DONE, "the film was shot again, got " + pipeline.phase());
					RenderProgress done = RenderProgress.read(FilmFolder.progressFile(film));
					LOGGER.info("RESHOOT DONE: {} clips shot, {} skipped, in {}", done.count(RenderProgress.Status.DONE),
						done.count(RenderProgress.Status.SKIPPED), film);
					pipeline.dismiss();
					over(mc);
				}
			}
			case OVER -> {
			}
		}
	}

	private void prepare(Minecraft mc) throws IOException {
		Path gameDir = mc.gameDirectory.toPath().toAbsolutePath();
		Path project = gameDir.getParent().getParent().getParent();
		Path devRun = project.resolve(FROM.isEmpty() ? "run" : FROM);
		copyTree(devRun.resolve("flashback/replays"), gameDir.resolve("flashback/replays"));
		copyTree(devRun.resolve("survivalcinema/sessions"), gameDir.resolve("survivalcinema/sessions"));
		deleteVoiceTracks(gameDir.resolve("survivalcinema/sessions"));
		if (!RESHOOT.isEmpty()) {
			Path source = devRun.resolve("survivalcinema/films").resolve(RESHOOT);
			EditDecisionList original = FilmFolder.readEdl(source);
			check(original != null, "a screenplay in " + source);
			edl = onlyClips(original, CLIPS);
			LOGGER.info("Shooting again: {} clips of {}", RenderPlan.of(edl).size(), source);
			film = gameDir.resolve("survivalcinema/films/reshoot").resolve(FilmFolder.name(LocalDateTime.now()));
			SafeFiles.writeAtomically(FilmFolder.edlFile(film), edl.toJson());
			RenderProgress.fresh(edl, "reshoot", RenderQuality.DRAFT, System.currentTimeMillis()).write(FilmFolder.progressFile(film));
			return;
		}
		Path world = recordedWorld(devRun);
		check(world != null, "recordings in " + devRun + " (record a world with the dev client first)");

		JsonObject worldJson = readJson(world.resolve("world.json"));
		List<String> sessionIds = new ArrayList<>();
		for (JsonElement id : worldJson.getAsJsonArray("sessions")) {
			sessionIds.add(id.getAsString());
		}
		List<SessionData> sessions = WorldFilmLoader.load(sessionIds, FlashbackBridge.replaysById().keySet());
		check(!sessions.isEmpty(), "the recorded sessions can be read");
		// the dev worlds have hardly any building: any building counts as a base, to shoot a timelapse too
		edl = FilmDirector.plan(sessions, new FilmDirector.Options(FILM_SECONDS, worldJson.get("displayName").getAsString(), "Dev",
			new BaseFinder.Rules(1, 1, 2)));
		check(edl.timelapse() != null, "a base timelapse was planned");
		timelapsePictures = (int) RenderPlan.of(edl).jobs().stream().filter(j -> j.item().kind() == EditDecisionList.Kind.TIMELAPSE).count();
		LOGGER.info("Timelapse: {} pictures of the base at {}", timelapsePictures, edl.timelapse());
		check(RenderPlan.of(edl).size() >= 2, "the plan has at least two clips to test stopping");
		LOGGER.info("Test film: {} clips from {} sessions, {} s", RenderPlan.of(edl).size(), sessions.size(), edl.totalSeconds());

		film = gameDir.resolve("survivalcinema/films/test").resolve(FilmFolder.name(LocalDateTime.now()));
		SafeFiles.writeAtomically(FilmFolder.edlFile(film), edl.toJson());
		RenderProgress.fresh(edl, "test", RenderQuality.DRAFT, System.currentTimeMillis()).write(FilmFolder.progressFile(film));
	}

	/** One frame of a shot file as a PNG, to look at. */
	private static void saveStill(Path video, Path png) throws IOException {
		try (org.bytedeco.javacv.FFmpegFrameGrabber grabber = new org.bytedeco.javacv.FFmpegFrameGrabber(video.toFile());
			 org.bytedeco.javacv.Java2DFrameConverter converter = new org.bytedeco.javacv.Java2DFrameConverter()) {
			grabber.start();
			org.bytedeco.javacv.Frame frame = null;
			for (int i = 0; i < 5; i++) {
				org.bytedeco.javacv.Frame next = grabber.grabImage();
				if (next == null) {
					break;
				}
				frame = next;
			}
			check(frame != null, "a picture in " + video);
			Files.createDirectories(png.getParent());
			javax.imageio.ImageIO.write(converter.convert(frame), "png", png.toFile());
		}
	}

	/** Voice tracks of earlier test runs: each run records its own call. */
	private static void deleteVoiceTracks(Path sessions) throws IOException {
		if (!Files.isDirectory(sessions)) {
			return;
		}
		try (Stream<Path> files = Files.walk(sessions)) {
			for (Path file : files.filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals(CallVoiceRecorder.VOICE_FOLDER)).toList()) {
				Files.delete(file);
			}
		}
	}

	/** Starts the beeping "friend" and records it under the first clip of the film. */
	private void startCall(Minecraft mc) throws IOException {
		called = RenderPlan.of(edl).jobs().stream().filter(j -> j.item().kind() == EditDecisionList.Kind.CLIP).findFirst().orElseThrow();
		int seconds = (called.item().endTick() - called.item().startTick()) / 20 + 15;
		call = TestCall.start(mc.gameDirectory.toPath(), seconds);
		LOGGER.info("Test call (pid {}) under clip {}: ticks {}-{} of {}", call.pid(), called.number(), called.item().startTick(),
			called.item().endTick(), called.replayId());
		// starts 3 s before the clip, so the beeps already play when it begins
		CallVoiceRecorder.test = new CallVoiceRecorder.TestRecording(java.util.UUID.fromString(called.replayId()),
			Math.max(0, called.item().startTick() - 60), System.nanoTime(), call.pid());
	}

	/** The screenplay with only the given clips (numbers as in the film, "3,5"); all of them when none are given. */
	private static EditDecisionList onlyClips(EditDecisionList edl, String numbers) {
		if (numbers.isEmpty()) {
			return edl;
		}
		Set<Integer> wanted = new HashSet<>();
		for (String number : numbers.split(",")) {
			wanted.add(Integer.parseInt(number.strip()));
		}
		Set<Integer> kept = new HashSet<>();
		for (RenderPlan.Job job : RenderPlan.of(edl).jobs()) {
			if (wanted.contains(job.number())) {
				kept.add(job.itemIndex());
			}
		}
		List<EditDecisionList.Item> items = new ArrayList<>();
		for (int i = 0; i < edl.items().size(); i++) {
			EditDecisionList.Item item = edl.items().get(i);
			if (item.kind() != EditDecisionList.Kind.CLIP || kept.contains(i)) {
				items.add(item);
			}
		}
		return new EditDecisionList(edl.version(), edl.worldName(), edl.playerName(), edl.targetSeconds(),
			System.currentTimeMillis(), items, edl.stats(), edl.timelapse());
	}

	/** Saves the blocks around every planned camera path next to the clips, for the camera unit tests. */
	private void saveCamera(RenderPlan.Job job, ClipCamera.Piece piece, Trail trail, @Nullable Trail boss, ClipCamera.Keys keys) {
		Minecraft mc = Minecraft.getInstance();
		if (film == null || mc.level == null) {
			return;
		}
		Path file = film.resolve("camera").resolve(String.format(Locale.ROOT, "%03d-%d.scene.gz", job.number(), piece.index() + 1));
		try {
			CameraDump.write(file, job, piece, trail, boss, keys, mc.level);
			LOGGER.info("Camera scene saved: {}", file);
		} catch (IOException e) {
			LOGGER.warn("Could not save the camera scene {}", file, e);
		}
	}

	/** True once the current phase has been on screen for a second (so its screenshot is saved). */
	private boolean settled() {
		RenderPipeline.Phase phase = RenderPipeline.current().phase();
		if (phase != seenPhase) {
			seenPhase = phase;
			phaseTicks = 0;
		}
		return ++phaseTicks > 20;
	}

	private RenderPipeline.Phase seenPhase;
	private int phaseTicks;

	private void over(Minecraft mc) {
		stage = Stage.OVER;
		mc.stop();
	}

	/** The dev world with the most recorded sessions. */
	private static Path recordedWorld(Path devRun) throws IOException {
		Path worlds = devRun.resolve("survivalcinema/worlds");
		if (!Files.isDirectory(worlds)) {
			return null;
		}
		try (Stream<Path> children = Files.list(worlds)) {
			return children.filter(w -> Files.isRegularFile(w.resolve("world.json")))
				.max(Comparator.comparingInt(w -> {
					JsonArray sessions = readJson(w.resolve("world.json")).getAsJsonArray("sessions");
					return sessions == null ? 0 : sessions.size();
				}))
				.orElse(null);
		}
	}

	private static void copyTree(Path from, Path to) throws IOException {
		if (!Files.isDirectory(from)) {
			return;
		}
		try (Stream<Path> files = Files.walk(from)) {
			for (Path file : files.toList()) {
				Path target = to.resolve(from.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(target);
				} else {
					Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private static JsonObject readJson(Path file) {
		try {
			return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("Expected: " + what);
		}
	}
}
