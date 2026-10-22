package com.survivalcinema.client.flashback;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.combo_options.AudioCodec;
import com.moulberry.flashback.combo_options.VideoCodec;
import com.moulberry.flashback.combo_options.VideoContainer;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.exporting.ExportJobQueue;
import com.moulberry.flashback.exporting.ExportSettings;
import com.moulberry.flashback.combo_options.WeatherOverride;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeType;
import com.moulberry.flashback.keyframe.impl.CameraKeyframe;
import com.moulberry.flashback.keyframe.impl.TickrateKeyframe;
import com.moulberry.flashback.keyframe.interpolation.InterpolationType;
import com.moulberry.flashback.keyframe.types.CameraKeyframeType;
import com.moulberry.flashback.keyframe.types.SpeedKeyframeType;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.record.Recorder;
import com.moulberry.flashback.record.ReplayMarker;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.KeyframeTrack;
import com.survivalcinema.client.mixin.MinecraftServerAccessor;
import com.survivalcinema.client.mixin.flashback.ExportDoneWindowInvoker;
import com.survivalcinema.client.mixin.flashback.RecorderAccessor;
import com.survivalcinema.director.camera.CameraKey;
import com.survivalcinema.director.camera.Vec;
import com.survivalcinema.director.render.ClipCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The only place in the mod that touches Flashback internals. Flashback has no official API, so when
 * a Flashback update breaks something, this class (and nothing else) needs fixing.
 * All methods must be called on the client thread.
 * <p>
 * Besides this class, only the mixins into Flashback's own classes know its internals: {@code RecorderAccessor},
 * {@code ExportDoneWindowInvoker}, {@code ReplayUIMixin} (hides the editor while we film) and {@code ExportJobMixin}
 * (our picture on the window instead of Flashback's export screen).
 */
public final class FlashbackBridge {
	private FlashbackBridge() {
	}

	// ---- recording ----

	public static boolean isRecording() {
		return Flashback.RECORDER != null;
	}

	public static boolean isRecordingPaused() {
		Recorder recorder = Flashback.RECORDER;
		return recorder != null && recorder.isPaused();
	}

	public static void startRecording() {
		if (!isRecording()) {
			Flashback.startRecordingReplay();
		}
	}

	public static void setRecordingPaused(boolean paused) {
		if (isRecording()) {
			Flashback.pauseRecordingReplay(paused);
		}
	}

	/**
	 * Finishes the recording and saves it straight to the replay folder, skipping Flashback's save prompt.
	 */
	public static void finishRecording() {
		if (!isRecording()) {
			return;
		}
		FlashbackConfigV1 config = Flashback.getConfig();
		boolean previousQuicksave = config.recordingControls.quicksave;
		config.recordingControls.quicksave = true;
		try {
			Flashback.finishRecordingReplay();
		} finally {
			config.recordingControls.quicksave = previousQuicksave;
		}
	}

	public static boolean addMarker(String description, int colour, @Nullable Vec3 position, @Nullable String dimension) {
		Recorder recorder = Flashback.RECORDER;
		if (recorder == null) {
			return false;
		}
		ReplayMarker.MarkerPosition markerPosition = null;
		if (position != null && dimension != null) {
			markerPosition = new ReplayMarker.MarkerPosition(new Vector3f((float) position.x, (float) position.y, (float) position.z), dimension);
		}
		recorder.addMarker(new ReplayMarker(colour, markerPosition, description));
		return true;
	}

	/** Flashback finishes (and saves) the running recording by itself when the game leaves a world or server. */
	public static boolean finishesRecordingOnDisconnect() {
		return Flashback.getConfig().recordingControls.automaticallyFinish;
	}

	public static boolean isVoiceChatRecordingEnabled() {
		return Flashback.getConfig().recording.recordVoiceChat;
	}

	/** Sets Flashback's "save without asking" option (in memory only) and returns the old value. */
	public static boolean setQuicksave(boolean quicksave) {
		FlashbackConfigV1 config = Flashback.getConfig();
		boolean previous = config.recordingControls.quicksave;
		config.recordingControls.quicksave = quicksave;
		return previous;
	}

	/** True for the integrated server Flashback uses to play replays, which we must never record. */
	public static boolean isReplayServer(@Nullable Object server) {
		return server instanceof ReplayServer;
	}

	/**
	 * Sets whether Flashback records Simple Voice Chat (read by Flashback for every voice packet) and returns the
	 * old value, which the caller must put back: Flashback saves its config to disk, e.g. when a replay is opened.
	 */
	public static boolean setVoiceChatRecording(boolean record) {
		FlashbackConfigV1 config = Flashback.getConfig();
		boolean previous = config.recording.recordVoiceChat;
		config.recording.recordVoiceChat = record;
		return previous;
	}

	/** The replay tick Flashback will write next, or -1 when not recording. */
	public static int recordingTick() {
		Recorder recorder = Flashback.RECORDER;
		return recorder == null ? -1 : ((RecorderAccessor) recorder).survivalcinema$writtenTicks();
	}

	/** Id of the replay being recorded; the saved replay zip keeps the same id in its metadata. */
	public static @Nullable UUID recordingReplayId() {
		Recorder recorder = Flashback.RECORDER;
		return recorder == null ? null : ((RecorderAccessor) recorder).survivalcinema$metadata().replayIdentifier;
	}

	// ---- replays on disk ----

	public static Path replayFolder() {
		return Flashback.getReplayFolder();
	}

	/** Replay zips, newest first. */
	public static List<Path> listReplays() {
		Path folder = replayFolder();
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(p -> p.getFileName().toString().endsWith(".zip"))
				.sorted(Comparator.comparingLong(FlashbackBridge::lastModified).reversed())
				.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** Replay id (from each zip's metadata) -> replay file, for every saved replay. Reads only the metadata entries. */
	public static Map<UUID, Path> replaysById() {
		Map<UUID, Path> result = new LinkedHashMap<>();
		replayFiles().forEach((id, file) -> result.put(id, file.zip()));
		return result;
	}

	/** A saved replay: its zip and how many ticks it holds. */
	public record ReplayFile(Path zip, int ticks) {
	}

	/** Every saved replay by id, newest first. Reads only the metadata entries; safe on any thread. */
	public static Map<UUID, ReplayFile> replayFiles() {
		Map<UUID, ReplayFile> result = new LinkedHashMap<>();
		for (Path zip : listReplays()) {
			try (ZipFile file = new ZipFile(zip.toFile())) {
				ZipEntry entry = file.getEntry("metadata.json");
				if (entry == null) {
					continue;
				}
				try (Reader reader = new InputStreamReader(file.getInputStream(entry), StandardCharsets.UTF_8)) {
					var metadata = JsonParser.parseReader(reader).getAsJsonObject();
					JsonElement uuid = metadata.get("uuid");
					JsonElement ticks = metadata.get("total_ticks");
					if (uuid != null) {
						result.putIfAbsent(UUID.fromString(uuid.getAsString()),
							new ReplayFile(zip, ticks == null ? 0 : ticks.getAsInt()));
					}
				}
			} catch (IOException | RuntimeException e) {
				// not a readable Flashback replay
			}
		}
		return result;
	}

	private static long lastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	// ---- playback ----

	public static void openReplay(Path replayZip) {
		if (isInReplay()) {
			// the next session's replay: leave this one our way first, or Flashback waits for its sleeping server
			closeReplay();
		}
		Flashback.openReplayWorld(replayZip);
	}

	public static boolean isInReplay() {
		return Flashback.isInReplay();
	}

	public static boolean isReplayReady() {
		ReplayServer server = Flashback.getReplayServer();
		return server != null && server.isReady();
	}

	/** Id of the replay that is open, matching {@link #recordingReplayId()} at recording time. */
	public static @Nullable UUID openReplayId() {
		ReplayServer server = Flashback.getReplayServer();
		return server == null ? null : server.getMetadata().replayIdentifier;
	}

	public static int replayTick() {
		ReplayServer server = Flashback.getReplayServer();
		return server == null ? -1 : server.getReplayTick();
	}

	public static int totalReplayTicks() {
		ReplayServer server = Flashback.getReplayServer();
		return server == null ? -1 : server.getTotalReplayTicks();
	}

	public static void goToReplayTick(int tick) {
		ReplayServer server = Flashback.getReplayServer();
		if (server != null) {
			server.goToReplayTick(tick);
		}
	}

	/** Stops (or resumes) the replay playing by itself, so it waits at the tick it was sent to. */
	public static void setReplayPaused(boolean paused) {
		ReplayServer server = Flashback.getReplayServer();
		if (server != null) {
			server.replayPaused = paused;
		}
	}

	/** Leaves the open replay for the title screen, the way Flashback itself leaves a world before opening a replay. */
	public static void closeReplay() {
		Minecraft mc = Minecraft.getInstance();
		if (!isInReplay() && mc.level == null) {
			return;
		}
		// After an export the replay server can sleep until a tick scheduled up to half a minute ahead, and only then
		// notices the viewer leaving, the game waiting all along: stop it, and wake it up to do so now.
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null && isInReplay()) {
			server.halt(false);
			server.execute(() -> ((MinecraftServerAccessor) server).survivalcinema$setNextTickTimeNanos(Util.getNanos()));
		}
		if (mc.level != null) {
			mc.level.disconnect(Component.empty());
		}
		mc.disconnectWithProgressScreen();
		mc.setScreen(new TitleScreen());
	}

	// ---- exporting ----

	public static boolean isExporting() {
		return Flashback.isExporting();
	}

	public static int queuedExports() {
		return ExportJobQueue.count();
	}

	/** Drops exports that were queued but never started (their replay is gone). */
	public static void cancelQueuedExports() {
		ExportJobQueue.queuedJobs.clear();
		ExportJobQueue.drainingQueue = false;
	}

	/** How far the running export is, from 0 to 1, or -1 when nothing is exporting. */
	public static float exportProgress() {
		ExportJob job = Flashback.EXPORT_JOB;
		if (job == null || job.progressOutOf <= 0) {
			return -1;
		}
		return Math.min(1f, job.progressCount / (float) job.progressOutOf);
	}

	/** Flashback lists every finished export for its "export done" popup; ours must not pile up there. */
	public static void forgetFinishedExports() {
		ExportDoneWindowInvoker.survivalcinema$clear();
	}

	/** Queues a clip whose camera follows the given keyframes (smoothly interpolated by Flashback). */
	public static void queueCameraClip(ClipRequest request, List<CameraKey> cameraKeys) {
		queueCameraClip(request, cameraKeys, List.of());
	}

	/**
	 * Queues a clip whose camera follows the given keyframes (smoothly interpolated by Flashback) and whose playback
	 * speed follows the speed keys (slow motion).
	 */
	public static void queueCameraClip(ClipRequest request, List<CameraKey> cameraKeys, List<ClipCamera.SpeedKey> speedKeys) {
		Map<Integer, Keyframe> byTick = new TreeMap<>();
		double eyes = viewerEyeHeight();
		for (CameraKey key : cameraKeys) {
			Vec p = key.position();
			byTick.put(key.tick(), new CameraKeyframe(new Vector3d(p.x(), p.y() - eyes, p.z()), key.yaw(), key.pitch(), 0f,
				InterpolationType.SMOOTH));
		}
		Map<KeyframeType<?>, Map<Integer, Keyframe>> tracks = new LinkedHashMap<>();
		tracks.put(CameraKeyframeType.INSTANCE, byTick);
		if (!speedKeys.isEmpty()) {
			Map<Integer, Keyframe> speed = new TreeMap<>();
			for (ClipCamera.SpeedKey key : speedKeys) {
				// Flashback's speed is a tick rate: 20 ticks per second is normal speed.
				speed.put(key.tick(), new TickrateKeyframe(20f * key.speed(), InterpolationType.LINEAR));
			}
			tracks.put(SpeedKeyframeType.INSTANCE, speed);
		}
		queueClip(request, tracks);
	}

	/**
	 * Queues one clip for export from the replay that is currently open and starts draining the queue.
	 * Flashback's own main-loop hook then runs the jobs one after another.
	 *
	 * @param keyframes keyframe type -> (replay tick -> keyframe); every entry becomes one track
	 */
	public static void queueClip(ClipRequest request, Map<KeyframeType<?>, Map<Integer, Keyframe>> keyframes) {
		ReplayServer server = Flashback.getReplayServer();
		if (server == null) {
			throw new IllegalStateException("No replay is open");
		}
		int total = server.getTotalReplayTicks();
		int start = Math.max(0, request.startTick());
		int end = Math.min(total, request.endTick());

		EditorState exportState = server.getEditorState().copyWithoutKeyframes();
		long stamp = exportState.acquireWrite();
		try {
			EditorScene scene = exportState.getCurrentScene(stamp);
			scene.keyframeTracks.clear();
			keyframes.forEach((type, byTick) -> {
				KeyframeTrack track = new KeyframeTrack(type);
				track.keyframesByTick.putAll(byTick);
				scene.keyframeTracks.add(track);
			});
			scene.setExportTicks(start, end, total);
			if (request.noonClearSky()) {
				// the base timelapse: the same light in every picture, whatever the time and weather were
				exportState.replayVisuals.overrideTimeOfDay = NOON;
				exportState.replayVisuals.overrideWeatherMode = WeatherOverride.CLEAR;
			}
		} finally {
			exportState.release(stamp);
		}

		ExportSettings settings = new ExportSettings(
			request.name(),
			exportState,
			request.initialCameraPosition().subtract(0, viewerEyeHeight(), 0), request.initialYaw(), request.initialPitch(),
			request.width(), request.height(),
			start, end,
			request.framerate(),
			false, // resetRng
			VideoContainer.MP4, VideoCodec.H264, request.encoder(),
			request.bitrate(),
			false, // transparent
			false, // ssaa
			true, // noGui
			true, // recordAudio
			true, // stereoAudio
			AudioCodec.AAC,
			request.output(),
			null);

		ExportJobQueue.queuedJobs.add(settings);
		ExportJobQueue.drainingQueue = true;
	}

	/** Minecraft time of day at noon. */
	private static final long NOON = 6000;

	/** Eye height of a standing player, for when there is no viewer to ask. */
	private static final double STANDING_EYE_HEIGHT = 1.62;

	/**
	 * Flashback's camera keyframes (and an export's starting position) say where the replay viewer stands; the picture
	 * is taken from the viewer's eyes, this much higher. Our camera keys say where the lens is, so they are lowered
	 * by it, or every shot would be filmed from 1.6 blocks above where it was planned (and kept clear of leaves).
	 */
	private static double viewerEyeHeight() {
		LocalPlayer viewer = Minecraft.getInstance().player;
		// standing, whatever pose the viewer is in right now: while we film it always stands (ViewerPoseMixin)
		return viewer == null ? STANDING_EYE_HEIGHT : viewer.getEyeHeight(net.minecraft.world.entity.Pose.STANDING);
	}

	/**
	 * @param initialCameraPosition where the lens is at the first frame (like the camera keys)
	 * @param encoder               an FFmpeg H.264 encoder name that was verified on this PC (see EncoderSelector)
	 * @param noonClearSky          shoot it at noon in clear weather (the pictures of the base timelapse)
	 */
	public record ClipRequest(String name, int startTick, int endTick, int width, int height, double framerate, int bitrate,
							  String encoder, Vec3 initialCameraPosition, float initialYaw, float initialPitch, Path output,
							  boolean noonClearSky) {
		public ClipRequest(String name, int startTick, int endTick, int width, int height, double framerate, int bitrate,
						   String encoder, Vec3 initialCameraPosition, float initialYaw, float initialPitch, Path output) {
			this(name, startTick, endTick, width, height, framerate, bitrate, encoder, initialCameraPosition, initialYaw,
				initialPitch, output, false);
		}
	}
}
