package com.survivalcinema.client.render;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.Recordings;
import com.survivalcinema.client.capture.SessionStore;
import com.survivalcinema.client.edit.EncoderSelector;
import com.survivalcinema.client.edit.FilmAssembler;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.client.ui.CinemaScreen;
import com.survivalcinema.director.camera.BlockProbe;
import com.survivalcinema.director.camera.CameraKey;
import com.survivalcinema.director.camera.CompanionIO;
import com.survivalcinema.director.camera.ShotType;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailIO;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.camera.Vec;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.render.ClipCamera;
import com.survivalcinema.director.render.FilmFolder;
import com.survivalcinema.director.render.RenderEta;
import com.survivalcinema.director.render.RenderPlan;
import com.survivalcinema.director.render.RenderProgress;
import com.survivalcinema.director.render.RenderQuality;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.timelapse.TimelapsePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shoots every clip of a screenplay ({@code edl.json}), one replay after another, under our cinema screen.
 * <p>
 * For each session: open its replay, then for each clip (cut into pieces at teleports) jump to its first tick, let
 * the world around the player load, build the camera from the recorded path (kept clear of walls by raycasts in the
 * replay world; a boss scene is first stepped through to see where the boss goes), and hand the clip to Flashback
 * through {@link ClipQueue}. After every finished file the progress is
 * saved, so after a crash or a stop {@code /cinema render resume} continues with the next clip. When every clip is
 * shot, the replay is closed and {@link FilmAssembler} edits them into the film in the background, still under the
 * cinema screen; "Done" then takes the player back into the world they started from.
 * <p>
 * Runs from the client tick. Flashback ticks the client inside its exports too, so nothing happens while one runs.
 */
public final class RenderPipeline {
	private static final Logger LOGGER = SurvivalCinemaClient.LOGGER;

	public enum Phase {
		PREPARING, OPENING, SETTING_UP, SHOOTING, EDITING, CONFIRM_STOP, DONE, STOPPED, FAILED;

		public boolean isFinal() {
			return this == DONE || this == STOPPED || this == FAILED;
		}
	}

	private enum Step {
		START, PREPARE, NEXT_SESSION, OPEN_REPLAY, WAIT_REPLAY, WAIT_TRACKS, NEXT_CLIP, SEEK, WAIT_SEEK, WAIT_CHUNKS,
		READ_BOSS, READ_END, BUILD, WAIT_EXPORT, EDIT, WAIT_EDIT, CONFIRM, STOP, FINISH, IDLE
	}

	private record Tracks(Trail trail, Map<String, Trail> companions) {
	}

	/** Where the player was when shooting started, to take them back there afterwards. */
	private record ReturnTo(@Nullable String levelId, @Nullable ServerData server) {
		static @Nullable ReturnTo here(Minecraft mc) {
			if (mc.level == null || FlashbackBridge.isInReplay()) {
				return null;
			}
			if (mc.getSingleplayerServer() != null) {
				Path world = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
				return world.getFileName() == null ? null : new ReturnTo(world.getFileName().toString(), null);
			}
			return mc.getCurrentServer() == null ? null : new ReturnTo(null, mc.getCurrentServer());
		}

		void go(Minecraft mc) {
			if (levelId != null) {
				mc.createWorldOpenFlows().openWorld(levelId, () -> mc.setScreen(new TitleScreen()));
			} else if (server != null) {
				ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(server.ip), server, false, null);
			}
		}
	}

	/** Exports of one piece that may come back without a video before the clip is skipped. */
	private static final int MAX_EXPORT_FAILURES = 2;
	/** A recording that was being saved needs a moment before replays are opened. */
	private static final int MIN_PREPARE_TICKS = 20;
	private static final int ENCODER_TIMEOUT_TICKS = 20 * 120;
	private static final int OPEN_TIMEOUT_TICKS = 20 * 120;
	/** Opening failed outright when no replay server appeared within this time. */
	private static final int OPEN_FAILED_TICKS = 20 * 10;
	private static final int SEEK_TIMEOUT_TICKS = 20 * 10;
	private static final int MIN_SETTLE_TICKS = 10;
	private static final int MAX_SETTLE_TICKS = 20 * 5;
	/** How often along a piece we check that the world around the player has arrived. */
	private static final int CHUNK_CHECK_STEP_TICKS = 40;
	/** How often (in ticks) along a boss scene the replay is asked where the boss is. */
	private static final int BOSS_STEP_TICKS = 10;
	/** The entities of a jump in the replay reach the client a moment after the replay arrives at its tick. */
	private static final int BOSS_SETTLE_TICKS = 2;
	/** How far round the player's path the blocks at a piece's end are kept, in blocks. */
	private static final int END_WORLD_MARGIN = 20;
	/** Still the wrong world this long after arriving: the replay is moved a tick on (at most this many ticks). */
	private static final int DIMENSION_NUDGE_TICKS = 10;
	private static final int MAX_DIMENSION_NUDGE = 5;
	/** How far past a jump's tick the replay plays before its world is looked at. */
	private static final int REFRESH_TICKS = 2;

	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "SurvivalCinema-Render");
		thread.setDaemon(true);
		return thread;
	});

	private static @Nullable RenderPipeline current;
	private static boolean ticking;

	/** Hears about every clip's camera path once it is built (the end-to-end test saves the blocks around it). */
	public interface CameraListener {
		/** @param boss where the boss of a boss scene was, if it was seen */
		void built(RenderPlan.Job job, ClipCamera.Piece piece, Trail trail, @Nullable Trail boss, ClipCamera.Keys keys);
	}

	private static @Nullable CameraListener cameraListener;

	public static void listenToCameras(@Nullable CameraListener listener) {
		cameraListener = listener;
	}

	private final Path film;
	private final EditDecisionList edl;
	private final RenderPlan plan;
	private final RenderProgress progress;
	private final RenderQuality quality;
	private final String worldName;
	private final int filmMinutes;
	private final RenderEta eta = new RenderEta();
	private final CinemaScreen screen;

	private Phase phase = Phase.PREPARING;
	private Phase phaseBeforeConfirm = Phase.PREPARING;
	private Step step = Step.START;
	private Step resumeStep = Step.NEXT_CLIP;
	private int waitTicks;
	private boolean stopAfterClip;
	private boolean leftWorld;
	private @Nullable Component problem;

	private @Nullable CompletableFuture<Map<UUID, Path>> replayIndex;
	private Map<UUID, Path> replays = Map.of();
	private @Nullable String encoder;

	private final Deque<List<RenderPlan.Job>> sessions = new ArrayDeque<>();
	private int sessionCount;
	private int sessionNumber;
	private @Nullable UUID replayId;
	private @Nullable Path replayFile;
	private @Nullable CompletableFuture<Tracks> tracksLoading;
	private Tracks tracks = new Tracks(new Trail(List.of()), Map.of());

	private final Deque<RenderPlan.Job> clips = new ArrayDeque<>();
	private @Nullable RenderPlan.Job job;
	private List<ClipCamera.Piece> pieces = List.of();
	private int pieceIndex;
	private @Nullable ClipQueue.Ticket ticket;
	private @Nullable Path output;
	/** Where the boss of a boss scene was during the piece {@link #bossReadFor} ({@link #pieceKey}), or null if unseen. */
	private @Nullable Trail boss;
	private int bossReadFor = -1;
	/** The last camera key of the shot planned last, and its recording: the next shot of the same moment cuts away from it. */
	private @Nullable CameraKey lastCamera;
	private @Nullable String lastCameraReplay;
	/** The blocks around the piece {@link #endReadFor} as they were at its last tick, or null (too big a stretch). */
	private @Nullable BlockSnapshot endWorld;
	private int endReadFor = -1;
	private final List<TrailSample> bossSamples = new ArrayList<>();
	private int bossTick;
	/**
	 * Ticks past the piece's start the replay is moved to ({@link #waitForSeek}): first a little before it, then on to
	 * it, or past it to the piece's world. The piece is shot from there.
	 */
	private int seekNudge;
	/** The paused replay has played a tick since the jump, so it shows the world as it really was ({@link #waitForSeek}). */
	private boolean refreshed;
	/** The tick the piece being shot starts at in its video (its start, or a tick or two on: {@link #build}). */
	private int shotFrom;
	/** {@link #readEnd}: 0 while going to just before the piece's end, 1 at its end. */
	private int endStage;
	private int endBefore;
	private long openStartedAt;
	private long pieceStartedAt;

	private @Nullable ReturnTo returnTo;
	private @Nullable FilmAssembler assembler;
	private @Nullable CompletableFuture<FilmAssembler.Result> editing;
	private @Nullable FilmAssembler.Result edited;
	private long editStartedAt;
	private boolean autoPlay;

	private RenderPipeline(Path film, EditDecisionList edl, RenderProgress progress) {
		this.film = film;
		this.edl = edl;
		this.plan = RenderPlan.of(edl);
		this.progress = progress;
		this.quality = progress.quality;
		this.worldName = edl.worldName();
		this.filmMinutes = Math.max(1, Math.round(edl.targetSeconds() / 60f));
		this.screen = new CinemaScreen(this);
	}

	public static @Nullable RenderPipeline current() {
		return current;
	}

	/** A film is being shot (or waits for the player to answer "stop?"). */
	public static boolean isRunning() {
		RenderPipeline pipeline = current;
		return pipeline != null && !pipeline.phase.isFinal();
	}

	/** Starts shooting under the cinema screen. Must be called on the client thread. */
	public static RenderPipeline start(Path film, EditDecisionList edl, RenderProgress progress) {
		if (isRunning()) {
			throw new IllegalStateException("A film is already being shot");
		}
		RenderPipeline pipeline = new RenderPipeline(film, edl, progress);
		Minecraft mc = Minecraft.getInstance();
		pipeline.returnTo = ReturnTo.here(mc);
		current = pipeline;
		CinemaMode.setFilming(true);
		mc.setScreen(pipeline.screen);
		LOGGER.info("Shooting {} clips of \"{}\" into {}", pipeline.plan.size(), pipeline.worldName, film);
		return pipeline;
	}

	/** Every client tick. */
	public static void tickCurrent(Minecraft mc) {
		RenderPipeline pipeline = current;
		if (pipeline == null || ticking || FlashbackBridge.isExporting()) {
			return;
		}
		ticking = true; // opening a replay runs nested client ticks; they must not step the pipeline again
		try {
			pipeline.tick(mc);
		} catch (Throwable t) {
			LOGGER.error("Shooting the film failed", t);
			pipeline.fail(Component.translatable("survivalcinema.cinema.failed.error", String.valueOf(t.getMessage())));
		} finally {
			ticking = false;
		}
	}

	public Phase phase() {
		return phase;
	}

	public Path film() {
		return film;
	}

	public String worldName() {
		return worldName;
	}

	/** True once after the film was edited in this run: the cinema screen then starts playing it by itself. */
	public boolean consumeAutoPlay() {
		boolean play = autoPlay && phase == Phase.DONE && filmFile() != null;
		autoPlay = false;
		return play;
	}

	public boolean hasShotClips() {
		return progress.count(RenderProgress.Status.DONE) > 0 || job != null && progress.piecesDone(job.number()) > 0;
	}

	/** The edited film, once it is made. */
	public @Nullable Path filmFile() {
		Path file = progress.film == null ? null : film.resolve(progress.film);
		return file != null && Files.isRegularFile(file) ? file : null;
	}

	// ---- the player's choices (from the cinema screen) ----

	/** Esc: ask whether to stop. While a clip is being rendered, the question waits until it is done. */
	public void requestStop() {
		if (!phase.isFinal() && phase != Phase.CONFIRM_STOP) {
			stopAfterClip = true;
		}
	}

	public void continueShooting() {
		if (phase == Phase.CONFIRM_STOP) {
			phase = phaseBeforeConfirm;
			go(resumeStep);
		}
	}

	/** Stops at the next tick: leaving the replay from inside a button click would disconnect mid-event. */
	public void stop() {
		if (phase == Phase.CONFIRM_STOP) {
			go(Step.STOP);
		}
	}

	/**
	 * Closes the finished cinema screen: back to the game if we never left it, else back into the world (or server)
	 * shooting started from, else to the title screen.
	 */
	public void dismiss() {
		if (!phase.isFinal()) {
			return;
		}
		if (current == this) {
			current = null;
		}
		Minecraft mc = Minecraft.getInstance();
		if (!leftWorld && mc.level != null) {
			mc.setScreen(null);
		} else if (returnTo != null && mc.level == null) {
			LOGGER.info("Back to {}", returnTo.levelId() != null ? returnTo.levelId() : returnTo.server().ip);
			mc.setScreen(new TitleScreen());
			returnTo.go(mc);
		} else {
			mc.setScreen(new TitleScreen());
		}
	}

	// ---- the state machine ----

	private void go(Step next) {
		step = next;
		waitTicks = 0;
	}

	private void tick(Minecraft mc) {
		if (phase.isFinal()) {
			return;
		}
		keepScreen(mc);
		waitTicks++;
		if (stopAfterClip) {
			Step resume = safeResumeStep();
			if (resume != null) {
				confirm(resume);
				return;
			}
		}
		switch (step) {
			case START -> start();
			case PREPARE -> prepare();
			case NEXT_SESSION -> nextSession();
			case OPEN_REPLAY -> openReplay();
			case WAIT_REPLAY -> waitForReplay(mc);
			case WAIT_TRACKS -> waitForTracks();
			case NEXT_CLIP -> nextClip();
			case SEEK -> seek();
			case WAIT_SEEK -> waitForSeek(mc);
			case WAIT_CHUNKS -> waitForChunks(mc);
			case READ_BOSS -> readBoss(mc);
			case READ_END -> readEnd(mc);
			case BUILD -> build(mc);
			case WAIT_EXPORT -> waitForExport();
			case FINISH -> {
				progress.finished = progress.isComplete(plan);
				save();
				if (progress.finished && progress.count(RenderProgress.Status.DONE) > 0) {
					go(Step.EDIT);
				} else {
					finish(Phase.DONE);
				}
			}
			case EDIT -> edit(mc);
			case WAIT_EDIT -> waitForEdit();
			case STOP -> finish(Phase.STOPPED);
			case CONFIRM, IDLE -> {
			}
		}
	}

	/** Where to pick up after a "stop?" question asked now, or null when now is not a good moment to ask. */
	private @Nullable Step safeResumeStep() {
		return switch (step) {
			case SEEK, WAIT_SEEK, WAIT_CHUNKS, READ_BOSS, READ_END, BUILD -> Step.SEEK;
			case NEXT_SESSION, OPEN_REPLAY, WAIT_TRACKS, NEXT_CLIP -> step;
			case EDIT, WAIT_EDIT -> step; // editing goes on in the background while the player decides
			case WAIT_EXPORT -> {
				if (ticket != null && ticket.state() == ClipQueue.Ticket.State.WAITING) {
					ClipQueue.clear(); // not handed to Flashback yet: take it back, shoot it again later
					ticket = null;
					EncoderSelector.encodingDone();
					yield Step.SEEK;
				}
				yield null;
			}
			default -> null;
		};
	}

	private void confirm(Step resume) {
		stopAfterClip = false;
		resumeStep = resume;
		phaseBeforeConfirm = phase;
		phase = Phase.CONFIRM_STOP;
		FlashbackBridge.setReplayPaused(true);
		go(Step.CONFIRM);
	}

	/** Our screen stays on top; vanilla's loading screen is left alone (it closes itself when the world is there). */
	private void keepScreen(Minecraft mc) {
		if (mc.screen != screen && !(mc.screen instanceof LevelLoadingScreen) && mc.getOverlay() == null) {
			mc.setScreen(screen);
		}
	}

	private void start() {
		if (FlashbackBridge.isRecording()) {
			SurvivalCinemaClient.controller().stopRecording();
		}
		replayIndex = CompletableFuture.supplyAsync(FlashbackBridge::replaysById, IO);
		go(Step.PREPARE);
	}

	private void prepare() {
		if (waitTicks < MIN_PREPARE_TICKS || replayIndex == null || !replayIndex.isDone()) {
			return;
		}
		encoder = EncoderSelector.ready();
		if (encoder == null) {
			if (EncoderSelector.isChecking() && waitTicks < ENCODER_TIMEOUT_TICKS) {
				return;
			}
			fail(Component.translatable("survivalcinema.cinema.failed.encoder"));
			return;
		}
		try {
			replays = replayIndex.join();
		} catch (RuntimeException e) {
			LOGGER.warn("Could not list the replays", e);
			replays = Map.of();
		}
		List<RenderPlan.Job> pending = progress.pending(plan);
		List<RenderPlan.Job> group = new ArrayList<>();
		for (RenderPlan.Job next : pending) {
			if (!group.isEmpty() && !group.getFirst().replayId().equals(next.replayId())) {
				sessions.add(group);
				group = new ArrayList<>();
			}
			group.add(next);
		}
		if (!group.isEmpty()) {
			sessions.add(group);
		}
		sessionCount = sessions.size();
		LOGGER.info("{} clips left to shoot from {} replays with {} at {}x{}", pending.size(), sessionCount, encoder,
			quality.width, quality.height);
		go(Step.NEXT_SESSION);
	}

	private void nextSession() {
		List<RenderPlan.Job> group = sessions.poll();
		if (group == null) {
			go(Step.FINISH);
			return;
		}
		sessionNumber++;
		clips.clear();
		clips.addAll(group);
		replayId = parseUuid(group.getFirst().replayId());
		replayFile = replayId == null ? null : replays.get(replayId);
		if (replayFile == null) {
			skipAll("its replay file is gone");
			return;
		}
		UUID id = replayId;
		tracksLoading = CompletableFuture.supplyAsync(() -> loadTracks(id), IO);
		go(Step.OPEN_REPLAY);
	}

	private void openReplay() {
		if (replayId != null && replayId.equals(FlashbackBridge.openReplayId()) && FlashbackBridge.isReplayReady()) {
			go(Step.WAIT_TRACKS);
			return;
		}
		phase = Phase.OPENING;
		openStartedAt = System.currentTimeMillis();
		leftWorld = true;
		LOGGER.info("Opening replay {} ({} of {})", replayFile, sessionNumber, sessionCount);
		try {
			FlashbackBridge.openReplay(replayFile);
		} catch (RuntimeException e) {
			LOGGER.error("Could not open replay {}", replayFile, e);
			skipAll("its replay could not be opened");
			go(Step.NEXT_SESSION);
			return;
		}
		go(Step.WAIT_REPLAY);
	}

	private void waitForReplay(Minecraft mc) {
		boolean ready = FlashbackBridge.isReplayReady() && replayId != null && replayId.equals(FlashbackBridge.openReplayId())
			&& mc.level != null && mc.player != null && !(mc.screen instanceof LevelLoadingScreen);
		if (ready) {
			eta.replayOpened((System.currentTimeMillis() - openStartedAt) / 1000.0);
			FlashbackBridge.setReplayPaused(true);
			go(Step.WAIT_TRACKS);
		} else if (waitTicks > OPEN_TIMEOUT_TICKS || waitTicks > OPEN_FAILED_TICKS && mc.getSingleplayerServer() == null) {
			LOGGER.warn("Replay {} did not open", replayFile);
			skipAll("its replay did not open");
			go(Step.NEXT_SESSION);
		}
	}

	private void waitForTracks() {
		if (tracksLoading == null || !tracksLoading.isDone()) {
			return;
		}
		tracks = tracksLoading.join();
		if (tracks.trail().isEmpty()) {
			skipAll("no camera path was recorded in that session");
			go(Step.NEXT_SESSION);
			return;
		}
		go(Step.NEXT_CLIP);
	}

	private void nextClip() {
		job = clips.poll();
		if (job == null) {
			go(Step.NEXT_SESSION);
			return;
		}
		pieces = isTimelapse(job)
			? List.of(new ClipCamera.Piece(0, job.item().startTick(), job.item().endTick())) // a fixed camera, no cuts
			: ClipCamera.pieces(job.item(), tracks.trail());
		if (pieces.isEmpty()) {
			skip(job, "no camera path was recorded during it");
			return;
		}
		pieceIndex = progress.piecesDone(job.number());
		if (pieceIndex >= pieces.size()) {
			progress.clipDone(job.number());
			save();
			return;
		}
		go(Step.SEEK);
	}

	/** How long a piece's video is: slow motion stretches it, a stretch of the base timelapse is sped up. */
	private static double shotSeconds(EditDecisionList.Item item, ClipCamera.Piece piece) {
		double seconds = RenderPlan.filmSeconds(piece.fromTick(), piece.toTick(), item.slowMotionFromTick());
		return item.kind() == EditDecisionList.Kind.TIMELAPSE ? seconds / item.timelapseSpeed() : seconds;
	}

	private ClipCamera.Piece piece() {
		return pieces.get(pieceIndex);
	}

	private static boolean isTimelapse(@Nullable RenderPlan.Job job) {
		return job != null && job.item().kind() == EditDecisionList.Kind.TIMELAPSE;
	}

	/** Where the replay's viewer stands while a piece is prepared: the player, or for the timelapse the base. */
	private Vec viewerSpot() {
		TimelapsePlanner.View view = edl.timelapse();
		if (isTimelapse(job) && view != null) {
			return new Vec(view.x(), view.maxY(), view.z());
		}
		return tracks.trail().positionAt(piece().fromTick());
	}

	/**
	 * The timelapse's fixed camera: chosen once, at its first picture, where the replay world can be asked what is in
	 * the way (a hill, trees), then kept for every picture so the base stays put on screen.
	 */
	private ClipCamera.@Nullable Keys timelapseKeys(Minecraft mc, ClipCamera.Piece piece) {
		TimelapsePlanner.View view = edl.timelapse();
		if (view == null) {
			return null;
		}
		double[] camera = progress.timelapseCamera;
		if (camera == null || camera.length != 5) {
			TimelapsePlanner.Camera chosen = view.camera(new ClientBlockProbe(mc.level));
			camera = new double[]{chosen.position().x(), chosen.position().y(), chosen.position().z(), chosen.yaw(), chosen.pitch()};
			progress.timelapseCamera = camera;
			save();
			LOGGER.info("Timelapse camera at {} looking {}/{} at the base around {}, {}, {}", chosen.position(), chosen.yaw(),
				chosen.pitch(), Math.round(view.x()), Math.round(view.y()), Math.round(view.z()));
		}
		Vec position = new Vec(camera[0], camera[1], camera[2]);
		List<CameraKey> keys = List.of(new CameraKey(piece.fromTick(), position, (float) camera[3], (float) camera[4]),
			new CameraKey(piece.toTick(), position, (float) camera[3], (float) camera[4]));
		// a stretch of building plays sped up (the blocks appear one after another), a still at normal speed
		double speed = job == null ? 1 : job.item().timelapseSpeed();
		List<ClipCamera.SpeedKey> fast = speed > 1.05 ? List.of(new ClipCamera.SpeedKey(piece.fromTick(), (float) speed)) : List.of();
		return new ClipCamera.Keys(ShotType.STATIC_LOOKAT, keys, fast);
	}

	private void seek() {
		phase = Phase.SETTING_UP;
		pieceStartedAt = System.currentTimeMillis();
		FlashbackBridge.setReplayPaused(true);
		seekNudge = -Math.min(REFRESH_TICKS, piece().fromTick());
		refreshed = seekNudge == 0; // the very start of the replay: nothing to play on from
		FlashbackBridge.goToReplayTick(piece().fromTick() + seekNudge);
		go(Step.WAIT_SEEK);
	}

	private void waitForSeek(Minecraft mc) {
		boolean arrived = Math.abs(FlashbackBridge.replayTick() - (piece().fromTick() + seekNudge)) <= 1;
		// Right after a jump the paused replay still shows the world of the snapshot it jumped from: the blocks placed
		// since (the top row of a wall the player has just finished) are missing until it plays on. It jumps to just
		// before the piece and plays on to its start: the camera is planned against the walls it will really film, and
		// the replay waits exactly where the piece starts. Flashback then shoots from there as it is; anywhere else it
		// goes back two seconds first and plays them unseen, and the first pictures show the world still loading (all
		// of it after a portal, the new dimension coming in as sky).
		if (arrived && !refreshed && waitTicks >= MIN_SETTLE_TICKS) {
			refreshed = true;
			seekNudge = 0;
			FlashbackBridge.goToReplayTick(piece().fromTick());
			go(Step.WAIT_SEEK);
			return;
		}
		if (!refreshed && waitTicks < SEEK_TIMEOUT_TICKS) {
			return;
		}
		// A piece that starts on a portal's tick: the paused replay still shows the world left behind, and raycasts there
		// would see its blocks at the clip's coordinates (the camera of the dragon finale dodged the overworld base's
		// walls). A tick on, it switches; the clip itself is still shot from its first tick.
		if (arrived && !inPieceDimension(mc) && waitTicks >= DIMENSION_NUDGE_TICKS && seekNudge < MAX_DIMENSION_NUDGE) {
			seekNudge++;
			FlashbackBridge.goToReplayTick(piece().fromTick() + seekNudge);
			go(Step.WAIT_SEEK);
			return;
		}
		if ((!arrived || !inPieceDimension(mc)) && waitTicks < SEEK_TIMEOUT_TICKS) {
			return;
		}
		if (!inPieceDimension(mc)) {
			LOGGER.warn("The replay did not switch to {} for clip {} piece {}", pieceDimension(),
				job == null ? -1 : job.number(), piece().index() + 1);
		}
		// The replay sends the world around the viewer: stand where the clip happens so raycasts see real blocks.
		if (mc.player != null) {
			Vec at = viewerSpot();
			mc.player.snapTo(at.x(), at.y() + 2, at.z(), mc.player.getYRot(), mc.player.getXRot());
		}
		mc.getSoundManager().stop(); // jumping through the replay can fire off its sounds behind our screen
		go(Step.WAIT_CHUNKS);
	}

	private void waitForChunks(Minecraft mc) {
		if (waitTicks < MIN_SETTLE_TICKS) {
			return;
		}
		ChunkPos unready = unreadyChunk(mc);
		if (unready != null) {
			if (waitTicks < MAX_SETTLE_TICKS) {
				return;
			}
			LOGGER.info("Clip {} piece {}: not all of the world around it is there to draw after {} s, shooting it anyway"
					+ " (chunk {}: {}, {} chunks from the viewer; {})", job == null ? -1 : job.number(), piece().index() + 1,
				MAX_SETTLE_TICKS / 20, unready, mc.level == null || !mc.level.hasChunk(unready.x, unready.z) ? "missing" : "unlit",
				mc.player == null ? -1 : Math.max(Math.abs(mc.player.chunkPosition().x - unready.x), Math.abs(mc.player.chunkPosition().z - unready.z)),
				mc.level == null ? "" : mc.level.getChunkSource().gatherStats());
		}
		if (job != null && !isTimelapse(job) && ClipCamera.isBossScene(job.item()) && bossReadFor != pieceKey()) {
			bossSamples.clear();
			bossTick = piece().fromTick() + seekNudge;
			go(Step.READ_BOSS);
			return;
		}
		if (job != null && !isTimelapse(job) && endReadFor != pieceKey()) {
			// what the world looks like when the piece ends: walls built while it plays block the camera too
			endWorld = null;
			endStage = 0;
			FlashbackBridge.goToReplayTick(piece().toTick() - REFRESH_TICKS);
			go(Step.READ_END);
			return;
		}
		go(Step.BUILD);
	}

	/** Keeps the blocks around the piece as they are at its end, then goes back to its start to plan and film it. */
	private void readEnd(Minecraft mc) {
		int target = endStage == 0 ? piece().toTick() - REFRESH_TICKS : piece().toTick();
		boolean arrived = Math.abs(FlashbackBridge.replayTick() - target) <= (endStage == 0 ? 1 : 0);
		if (waitTicks < MIN_SETTLE_TICKS || !arrived && waitTicks < SEEK_TIMEOUT_TICKS) {
			return;
		}
		ClipCamera.Piece piece = piece();
		if (endStage == 0) {
			// as at the start: the replay plays on to the end before the world is kept (see waitForSeek)
			BlockSnapshot stale = arrived && mc.level != null ? snapshotAround(mc, piece) : null;
			endBefore = stale == null ? -1 : stale.size();
			endStage = 1;
			FlashbackBridge.goToReplayTick(piece.toTick());
			go(Step.READ_END);
			return;
		}
		if (arrived && mc.level != null && inPieceDimension(mc)) {
			endWorld = snapshotAround(mc, piece);
		}
		endReadFor = pieceKey();
		if (endWorld != null) {
			LOGGER.info("Clip {} piece {}: {} blocks kept as they are at its end ({} right after the jump)", job == null ? -1 : job.number(),
				piece.index() + 1, endWorld.size(), endBefore);
		}
		go(Step.SEEK); // back to the start of the piece, to plan and film it
	}

	/** The blocks around the player's path through the piece, as the replay shows them now. */
	private @Nullable BlockSnapshot snapshotAround(Minecraft mc, ClipCamera.Piece piece) {
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (TrailSample sample : tracks.trail().slice(piece.fromTick(), piece.toTick()).samples()) {
			minX = Math.min(minX, sample.x());
			minY = Math.min(minY, sample.y());
			minZ = Math.min(minZ, sample.z());
			maxX = Math.max(maxX, sample.x());
			maxY = Math.max(maxY, sample.y());
			maxZ = Math.max(maxZ, sample.z());
		}
		if (minX > maxX) {
			return null;
		}
		return BlockSnapshot.capture(mc.level, (int) Math.floor(minX) - END_WORLD_MARGIN, (int) Math.floor(minY) - 6,
			(int) Math.floor(minZ) - END_WORLD_MARGIN, (int) Math.floor(maxX) + END_WORLD_MARGIN, (int) Math.floor(maxY) + 18,
			(int) Math.floor(maxZ) + END_WORLD_MARGIN);
	}

	/**
	 * The dimension the piece happens in ({@code "other"} for one of another mod, not checked): the camera is planned
	 * there, and a picture of the base taken while the replay still shows the End would be empty sky.
	 */
	private @Nullable String pieceDimension() {
		if (job == null) {
			return null;
		}
		return isTimelapse(job) ? job.item().dimension() : TimelapsePlanner.dimension(tracks.trail().sampleAt(piece().fromTick()));
	}

	/** The replay shows the world the piece happens in (and is not still loading it). */
	private boolean inPieceDimension(Minecraft mc) {
		if (mc.level == null || mc.screen instanceof LevelLoadingScreen) {
			return false;
		}
		String wanted = pieceDimension();
		return wanted == null || wanted.equals("other") || wanted.equals(mc.level.dimension().identifier().toString());
	}

	private int pieceKey() {
		return job == null ? -1 : job.number() * 1000 + pieceIndex;
	}

	/**
	 * Before a boss scene is filmed, the replay jumps through it in small steps to see where the boss goes (a dying
	 * dragon flies to the portal and rises while it bursts), then goes back to the piece's start to film it.
	 */
	private void readBoss(Minecraft mc) {
		RenderPlan.Job active = job;
		if (active == null) {
			go(Step.NEXT_CLIP);
			return;
		}
		boolean arrived = Math.abs(FlashbackBridge.replayTick() - bossTick) <= 1;
		if (waitTicks < BOSS_SETTLE_TICKS || !arrived && waitTicks < SEEK_TIMEOUT_TICKS) {
			return;
		}
		Entity found = arrived && mc.level != null ? findBoss(mc, active.item(), tracks.trail().positionAt(bossTick)) : null;
		if (found != null) {
			// the replay paused: the client has not moved the boss yet, it only knows where to
			Vec3 at = found.getInterpolation() != null ? found.getInterpolation().position() : found.position();
			bossSamples.add(new TrailSample(bossTick, at.x, at.y + found.getBbHeight() / 2, at.z, 0, 0, 0));
		}
		ClipCamera.Piece piece = piece();
		if (bossTick < piece.toTick()) {
			bossTick = Math.min(piece.toTick(), bossTick + BOSS_STEP_TICKS);
			FlashbackBridge.goToReplayTick(bossTick);
			go(Step.READ_BOSS);
			return;
		}
		boss = bossSamples.isEmpty() ? null : new Trail(bossSamples);
		bossReadFor = pieceKey();
		if (boss == null) {
			LOGGER.info("No boss in sight in clip {} piece {}", active.number(), piece.index() + 1);
		} else {
			LOGGER.info("Boss of clip {} piece {} seen {} times, from {} to {}", active.number(), piece.index() + 1,
				bossSamples.size(), boss.samples().getFirst().position(), boss.samples().getLast().position());
		}
		go(Step.SEEK); // back to the start of the piece, to film it
	}

	/**
	 * The boss of a boss scene in the replay world, nearest to the player: one named like the scene's boss bar (the
	 * Wither, modded bosses), else the dragon or the Wither (the bar's name may be in another language).
	 */
	private static @Nullable Entity findBoss(Minecraft mc, EditDecisionList.Item item, Vec near) {
		Entity named = null;
		Entity known = null;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity) || entity instanceof Player || entity.isRemoved()) {
				continue;
			}
			boolean dragon = entity instanceof EnderDragon;
			if (item.eventType() == EventType.DRAGON_KILLED && !dragon) {
				continue;
			}
			if (item.label() != null && item.label().equals(entity.getName().getString())) {
				named = nearer(named, entity, near);
			} else if (dragon || entity instanceof WitherBoss) {
				known = nearer(known, entity, near);
			}
		}
		return named != null ? named : known;
	}

	private static Entity nearer(@Nullable Entity best, Entity entity, Vec near) {
		return best == null || entity.distanceToSqr(near.x(), near.y(), near.z()) < best.distanceToSqr(near.x(), near.y(), near.z())
			? entity : best;
	}

	/** A chunk on the piece's way the game would not draw yet, or null when all of them are ready. */
	private @Nullable ChunkPos unreadyChunk(Minecraft mc) {
		if (mc.level == null) {
			return ChunkPos.ZERO;
		}
		ClipCamera.Piece piece = piece();
		if (isTimelapse(job)) {
			// the base and the camera looking at it, wherever the player was
			Vec base = viewerSpot();
			double[] camera = progress.timelapseCamera;
			ChunkPos unready = unreadyAround(mc.level, base.x(), base.z());
			return unready != null || camera == null ? unready : unreadyAround(mc.level, camera[0], camera[2]);
		}
		for (int tick = piece.fromTick(); ; tick = Math.min(piece.toTick(), tick + CHUNK_CHECK_STEP_TICKS)) {
			Vec at = tracks.trail().positionAt(tick);
			ChunkPos unready = unreadyAround(mc.level, at.x(), at.z());
			if (unready != null || tick >= piece.toTick()) {
				return unready;
			}
		}
	}

	/**
	 * The game draws a chunk's blocks only once it and the eight round it are there with their light (the light comes
	 * a few frames after the blocks): a chunk merely there can still be sky in the picture.
	 */
	private static @Nullable ChunkPos unreadyAround(ClientLevel level, double x, double z) {
		int chunkX = SectionPos.blockToSectionCoord(x);
		int chunkZ = SectionPos.blockToSectionCoord(z);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (!level.hasChunk(chunkX + dx, chunkZ + dz)
					|| !level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(chunkX + dx, chunkZ + dz))) {
					return new ChunkPos(chunkX + dx, chunkZ + dz);
				}
			}
		}
		return null;
	}

	private void build(Minecraft mc) {
		RenderPlan.Job active = job;
		if (active == null || mc.level == null) {
			go(Step.NEXT_CLIP);
			return;
		}
		ClipCamera.Piece piece = piece();
		long planningStarted = System.nanoTime();
		ClipCamera.Keys keys;
		boolean timelapse = isTimelapse(active);
		if (timelapse) {
			keys = timelapseKeys(mc, piece);
			if (keys == null) {
				skip(active, "the film has no base to look at");
				go(Step.NEXT_CLIP);
				return;
			}
		} else {
			String partnerName = active.item().partner();
			Trail partner = partnerName == null ? null : tracks.companions().get(partnerName);
			Trail seenBoss = bossReadFor == pieceKey() ? boss : null;
			CameraKey cutFrom = java.util.Objects.equals(lastCameraReplay, active.item().replayId()) ? lastCamera : null;
			BlockProbe probe = new ClientBlockProbe(mc.level);
			if (endWorld != null && endReadFor == pieceKey()) {
				probe = BlockProbe.both(probe, endWorld);
			}
			keys = ClipCamera.build(active.item(), piece, tracks.trail(), partner, seenBoss, probe, cutFrom,
				endWorld != null && endReadFor == pieceKey() ? endWorld : null);
		}
		lastCamera = timelapse || keys.camera().isEmpty() ? null : keys.camera().getLast();
		lastCameraReplay = active.item().replayId();
		long planningMillis = (System.nanoTime() - planningStarted) / 1_000_000;
		if (keys.camera().isEmpty()) {
			skip(active, "no camera path could be built");
			go(Step.NEXT_CLIP);
			return;
		}
		if (cameraListener != null && !timelapse) {
			cameraListener.built(active, piece, tracks.trail(), bossReadFor == pieceKey() ? boss : null, keys);
		}
		output = film.resolve(FilmFolder.clipPath(active.number(), piece.index()));
		try {
			Files.createDirectories(output.getParent());
			Files.deleteIfExists(output); // left over from a crash in the middle of this clip
		} catch (IOException e) {
			fail(Component.translatable("survivalcinema.cinema.failed.error", e.getMessage()));
			return;
		}
		CameraKey first = keys.camera().getFirst();
		Vec3 firstPosition = new Vec3(first.position().x(), first.position().y(), first.position().z());
		String name = "survivalcinema-" + RenderPlan.clipFileName(active.number(), piece.index());
		// from where the replay waits (see waitForSeek): its start, or a tick or two on in the piece's world
		int at = FlashbackBridge.replayTick();
		shotFrom = at >= piece.fromTick() && at < piece.toTick() - 1 ? at : piece.fromTick();
		EncoderSelector.encoding(encoder); // a crash while it encodes keeps this encoder from being used again
		ticket = ClipQueue.add(new FlashbackBridge.ClipRequest(name, shotFrom, piece.toTick(), quality.width, quality.height,
			quality.framerate, quality.bitrate, encoder, firstPosition, first.yaw(), first.pitch(), output, timelapse), keys.camera(), keys.speed());
		CinemaMode.setClip(new int[]{shotFrom, piece.toTick(), timelapse ? -1 : active.item().slowMotionFromTick()});
		phase = Phase.SHOOTING;
		LOGGER.info("Shooting clip {} piece {} ({}, ticks {}-{}, {} shot, camera planned in {} ms)", active.number(),
			piece.index() + 1, active.item().label(), shotFrom, piece.toTick(), keys.shot(), planningMillis);
		go(Step.WAIT_EXPORT);
	}

	private void waitForExport() {
		RenderPlan.Job active = job;
		if (ticket == null || active == null) {
			go(Step.NEXT_CLIP);
			return;
		}
		if (!ticket.isOver()) {
			return;
		}
		boolean exported = ticket.state() == ClipQueue.Ticket.State.DONE;
		ticket = null;
		EncoderSelector.encodingDone();
		CinemaMode.setClip(null);
		FlashbackBridge.forgetFinishedExports();
		if (CinemaMode.consumeExportCancelled()) {
			// Esc was held: Flashback stopped this clip half-way. Shoot it again if the player wants to go on.
			deleteQuietly(output);
			confirm(Step.SEEK);
			return;
		}
		ClipCamera.Piece piece = piece();
		if (exported && isVideo(output)) {
			progress.pieceDone(active.number(), FilmFolder.clipPath(active.number(), piece.index()), shotFrom, piece.toTick());
			eta.pieceDone((System.currentTimeMillis() - pieceStartedAt) / 1000.0, shotSeconds(active.item(), piece));
			pieceIndex++;
			if (pieceIndex >= pieces.size()) {
				progress.clipDone(active.number());
			}
			save();
			go(pieceIndex < pieces.size() ? Step.SEEK : Step.NEXT_CLIP);
			return;
		}
		LOGGER.warn("Clip {} piece {} came back without a video ({})", active.number(), piece.index() + 1, output);
		if (progress.failed(active.number(), MAX_EXPORT_FAILURES)) {
			skip(active, "its export produced no video");
			go(Step.NEXT_CLIP);
			return;
		}
		save();
		if (!FlashbackBridge.isReplayReady()) {
			clips.addFirst(active); // the replay went away under us: open it again and retry
			go(Step.OPEN_REPLAY);
		} else {
			go(Step.SEEK);
		}
	}

	/** Every clip is shot: leave the replay and edit the film in the background, still under the cinema screen. */
	private void edit(Minecraft mc) {
		phase = Phase.EDITING;
		if (FlashbackBridge.isInReplay() || mc.level != null && leftWorld) {
			ClipQueue.clear();
			FlashbackBridge.closeReplay();
			return; // the next tick starts editing, with the replay world gone
		}
		if (encoder == null) {
			encoder = EncoderSelector.ready();
		}
		if (encoder == null) {
			fail(Component.translatable("survivalcinema.cinema.failed.encoder"));
			return;
		}
		assembler = new FilmAssembler(film, edl, progress, encoder, FilmAssembler.Ingredients.gather(mc));
		editStartedAt = System.currentTimeMillis();
		editing = assembler.start();
		go(Step.WAIT_EDIT);
	}

	private void waitForEdit() {
		CompletableFuture<FilmAssembler.Result> running = editing;
		if (running == null) {
			go(Step.EDIT);
			return;
		}
		if (!running.isDone()) {
			return;
		}
		editing = null;
		try {
			edited = running.join();
			progress.film = film.relativize(edited.file()).toString();
			save();
			LOGGER.info("Film edited in {} s: {} ({} s long)", (System.currentTimeMillis() - editStartedAt) / 1000,
				edited.file(), Math.round(edited.seconds()));
			autoPlay = true;
			finish(Phase.DONE);
			deleteRecordingsIfAsked();
		} catch (CompletionException | CancellationException e) {
			Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
			if (cause instanceof CancellationException) {
				finish(Phase.STOPPED);
				return;
			}
			LOGGER.error("Editing the film failed", cause);
			fail(Component.translatable("survivalcinema.cinema.failed.edit", String.valueOf(cause.getMessage())));
		}
	}

	/**
	 * The player asked (in the film setup) to free the disk once the film is made: the recordings it was planned from
	 * go, in the background. The film and its clips stay, so it can still be watched and edited again.
	 */
	private void deleteRecordingsIfAsked() {
		List<String> used = progress.sessions;
		if (!progress.deleteRecordingsWhenDone || used == null || used.isEmpty()) {
			return;
		}
		String worldKey = progress.worldKey;
		UUID recording = FlashbackBridge.recordingReplayId();
		UUID open = FlashbackBridge.openReplayId();
		Minecraft mc = Minecraft.getInstance();
		CompletableFuture.supplyAsync(() -> Recordings.delete(used, recording, open))
			.thenAcceptAsync(gone -> Recordings.forget(worldKey, worldName, gone), mc)
			.exceptionally(t -> {
				LOGGER.warn("Could not delete the recordings of the film", t);
				return null;
			});
	}

	private void finish(Phase result) {
		ClipQueue.clear();
		if (assembler != null && editing != null && !editing.isDone()) {
			assembler.cancel(); // stopped while editing: the half-made film is thrown away
		}
		progress.updatedAt = System.currentTimeMillis();
		save();
		phase = result; // the curtain shows the result while the replay closes
		go(Step.IDLE);
		if (FlashbackBridge.isInReplay()) {
			FlashbackBridge.closeReplay(); // still under the curtain
		}
		CinemaMode.setFilming(false);
		Minecraft.getInstance().setScreen(screen);
		LOGGER.info("Film \"{}\" {}: {} of {} clips shot, {} skipped, clips in {}", worldName, result,
			progress.count(RenderProgress.Status.DONE), plan.size(), progress.count(RenderProgress.Status.SKIPPED),
			film.resolve(FilmFolder.CLIPS));
	}

	private void fail(Component reason) {
		problem = reason;
		finish(Phase.FAILED);
	}

	private void skip(RenderPlan.Job skipped, String reason) {
		LOGGER.warn("Skipping clip {} ({}): {}", skipped.number(), skipped.item().label(), reason);
		progress.skip(skipped.number(), reason);
		save();
	}

	private void skipAll(String reason) {
		for (RenderPlan.Job skipped : clips) {
			skip(skipped, reason);
		}
		clips.clear();
	}

	private void save() {
		try {
			progress.updatedAt = System.currentTimeMillis();
			progress.write(FilmFolder.progressFile(film));
		} catch (IOException e) {
			LOGGER.warn("Could not save the shooting progress of {}", film, e);
		}
	}

	// ---- what the screen shows ----

	/** @param exportProgress how far the clip being rendered is, 0..1, or negative when none is */
	public CinemaStatus status(float exportProgress) {
		double total = Math.max(1e-6, plan.totalFilmSeconds());
		double done = 0;
		int shot = 0;
		int skipped = 0;
		for (RenderPlan.Job planned : plan.jobs()) {
			RenderProgress.Status status = progress.status(planned.number());
			if (status == RenderProgress.Status.DONE) {
				shot++;
				done += planned.filmSeconds();
			} else if (status == RenderProgress.Status.SKIPPED) {
				skipped++;
				done += planned.filmSeconds();
			}
		}
		RenderPlan.Job active = job;
		if (active != null && !progress.isSettled(active.number()) && !pieces.isEmpty()) {
			for (int i = 0; i < Math.min(pieceIndex, pieces.size()); i++) {
				done += shotSeconds(active.item(), pieces.get(i));
			}
			if (exportProgress > 0 && pieceIndex < pieces.size()) {
				done += exportProgress * shotSeconds(active.item(), pieces.get(pieceIndex));
			}
		}
		done = Math.min(done, total);
		int replaysToOpen = phase == Phase.PREPARING ? sessionCountGuess() : sessions.size();
		double secondsLeft = eta.secondsLeft(total - done, replaysToOpen);
		float shown = (float) (done / total);
		Phase showing = phase == Phase.CONFIRM_STOP ? phaseBeforeConfirm : phase;
		if (showing == Phase.EDITING) {
			// editing has its own bar: from nothing to the finished film
			double edit = assembler == null ? 0 : assembler.progress();
			double elapsed = (System.currentTimeMillis() - editStartedAt) / 1000.0;
			secondsLeft = edit > 0.03 ? elapsed * (1 - edit) / edit : total * 0.5;
			shown = (float) edit;
		}
		Path base = Minecraft.getInstance().gameDirectory.toPath();
		Path filmFile = filmFile();
		Path shownFolder = filmFile != null ? film : film.resolve(FilmFolder.CLIPS);
		String folder = shownFolder.startsWith(base) ? base.relativize(shownFolder).toString() : shownFolder.toString();
		int clip = Math.min(plan.size(), shot + skipped + 1);
		double filmSeconds = edited != null ? edited.seconds() : 0;
		return new CinemaStatus(phase, worldName, filmMinutes, clip, plan.size(), shot, skipped,
			Math.max(1, sessionNumber), Math.max(1, sessionCount), shown,
			RenderEta.minutes(secondsLeft), stopAfterClip, problem, folder, showing == Phase.EDITING,
			filmFile != null, filmSeconds, edited != null && edited.gameMusic());
	}

	private int sessionCountGuess() {
		return (int) plan.jobs().stream().map(RenderPlan.Job::replayId).distinct().count();
	}

	// ---- helpers ----

	private static Tracks loadTracks(UUID replayId) {
		Path folder = SessionStore.sessionFolder(replayId);
		Trail trail;
		try {
			trail = new Trail(TrailIO.read(folder.resolve(SessionStore.TRAIL_FILE)));
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read the camera path of session {}", replayId, e);
			trail = new Trail(List.of());
		}
		Map<String, Trail> companions;
		try {
			companions = CompanionIO.read(folder.resolve(CompanionIO.FILE_NAME));
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read who was around in session {}", replayId, e);
			companions = Map.of();
		}
		return new Tracks(trail, companions);
	}

	private static @Nullable UUID parseUuid(@Nullable String id) {
		try {
			return id == null ? null : UUID.fromString(id);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static boolean isVideo(@Nullable Path file) {
		try {
			return file != null && Files.isRegularFile(file) && Files.size(file) > 0;
		} catch (IOException e) {
			return false;
		}
	}

	private static void deleteQuietly(@Nullable Path file) {
		if (file == null) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			LOGGER.warn("Could not delete the unfinished clip {}", file, e);
		}
	}
}
