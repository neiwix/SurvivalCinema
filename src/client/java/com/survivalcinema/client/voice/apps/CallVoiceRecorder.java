package com.survivalcinema.client.voice.apps;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.SessionStore;
import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.client.voice.VoiceActivityMeter;
import com.survivalcinema.client.voice.VoiceChatCapture;
import com.survivalcinema.director.voice.CallApps;
import com.survivalcinema.director.voice.VoiceSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Voices the game's voice chat doesn't carry: friends in a Discord, Telegram... call (each app its own track) and
 * the player's microphone, recorded next to the Flashback replay into {@code sessions/<replay>/voice/}. Decides
 * every few seconds what to capture ({@link VoiceSource}), looks for the call apps in the background, and keeps the
 * tracks' threads in step with the recording (running, paused, which tick). Also runs without a recording while
 * the voice settings are open, only measuring, so the player can see that their call is heard.
 */
public final class CallVoiceRecorder {
	public static final String VOICE_FOLDER = "voice";
	public static final String MIC_TRACK = "mic";
	/** Our copy of the voice chat, used only under slow motion ({@link VoiceChatCapture}). */
	public static final String VOICE_CHAT_TRACK = "voicechat";
	private static final CallVoiceRecorder INSTANCE = new CallVoiceRecorder();
	private static final int SCAN_EVERY_TICKS = 100;
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

	private final Map<String, CapturedTrack> apps = new LinkedHashMap<>();
	private @Nullable CapturedTrack mic;
	private @Nullable CapturedTrack voiceChat;
	private volatile List<CallApps.Running> running = List.of();
	private @Nullable CompletableFuture<List<CallApps.Running>> scanning;
	private int ticksToScan;
	private long previewUntil;
	private @Nullable UUID remindedFor;
	private final Set<String> reminded = new HashSet<>();

	/**
	 * Dev only (the film test): records program {@code pid} as a call app named "test" into recording
	 * {@code replayId} from tick {@code startTick} on, counting 20 ticks a second, without a world. When it is the
	 * recording running now (the playthrough test), it follows that recording's own ticks and pauses instead.
	 */
	public record TestRecording(UUID replayId, int startTick, long startNanos, long pid) {
		int tickNow() {
			return startTick + (int) ((System.nanoTime() - startNanos) / 50_000_000L);
		}
	}

	public static final CallApps.App TEST_APP = new CallApps.App("test", "Test", Set.of());
	public static volatile @Nullable TestRecording test;

	public static CallVoiceRecorder get() {
		return INSTANCE;
	}

	/** Capturing program sound works only on Windows. */
	public static boolean appsSupported() {
		return WINDOWS;
	}

	public static UUID speakerOf(CallApps.App app) {
		return UUID.nameUUIDFromBytes(("survivalcinema:call-app:" + app.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/** The voice settings are open: measure for a moment even without a recording. */
	public void preview() {
		previewUntil = System.currentTimeMillis() + 1500;
	}

	/** The call apps found running in the last look. */
	public List<CallApps.Running> running() {
		return running;
	}

	/** How loud a track is right now (0..1), or -1 when it isn't being captured. */
	public float level(String trackName) {
		CapturedTrack track = MIC_TRACK.equals(trackName) ? mic : apps.get(trackName);
		return track == null || !track.live ? -1 : track.level;
	}

	public @Nullable String problem(String trackName) {
		CapturedTrack track = MIC_TRACK.equals(trackName) ? mic : apps.get(trackName);
		return track == null ? null : track.problem;
	}

	/**
	 * Every client tick.
	 *
	 * @param replayId     the recording running now (also while paused), or null
	 * @param paused       the recording is paused (AFK, pause menu): nothing is written
	 * @param voiceAllowed the player agreed to record voices in this world
	 */
	public void tick(Minecraft mc, @Nullable UUID replayId, boolean paused, int replayTick, boolean voiceAllowed) {
		TestRecording testing = test;
		if (testing != null) {
			if (!testing.replayId().equals(replayId)) {
				// a recording made without a world (the film test): its ticks are counted by the clock
				replayId = testing.replayId();
				paused = false;
				replayTick = testing.tickNow();
			}
			voiceAllowed = true;
			running = List.of(new CallApps.Running(TEST_APP, testing.pid()));
		}
		boolean previewing = System.currentTimeMillis() < previewUntil;
		if (replayId == null && !previewing) {
			stopAll();
			return;
		}
		SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
		if (testing == null) {
			lookForApps(config);
		}

		boolean allowed = replayId == null || voiceAllowed;
		boolean anyApp = !running.isEmpty();
		VoiceSource.Tracks tracks = config.voiceSource().tracks(allowed, VoiceActivityMeter.get().isVoiceChatConnected(),
			anyApp, config.micCommentary);
		if (testing != null) {
			tracks = new VoiceSource.Tracks(true, false); // never the real microphone in a test
		}
		Path folder = replayId == null ? null : SessionStore.sessionFolder(replayId).resolve(VOICE_FOLDER);
		boolean active = replayId != null && !paused;

		Set<String> seen = new HashSet<>();
		if (tracks.apps() && WINDOWS) {
			for (CallApps.Running app : running) {
				seen.add(app.app().id());
				CapturedTrack track = apps.computeIfAbsent(app.app().id(), id -> started(new CapturedTrack(id, ProcessLoopback::open)));
				track.target = app.pid();
				if (active && testing == null) {
					remind(mc, replayId, app.app());
				}
			}
		}
		for (Map.Entry<String, CapturedTrack> entry : apps.entrySet()) {
			CapturedTrack track = entry.getValue();
			if (!seen.contains(entry.getKey())) {
				track.target = -1; // the app quit: its track goes on as silence
			}
			CallApps.App app = CallApps.byId(entry.getKey());
			if (testing != null) {
				app = null; // no voice meter outside a world
			}
			follow(track, tracks.apps() ? folder : null, active, replayTick, replayId != null && app != null ? speakerOf(app) : null);
		}

		// the voice chat copy for slow motion: whenever Flashback records the voice chat for the film
		boolean chat = replayId != null && voiceAllowed && config.voiceSource().voiceChat()
			&& VoiceActivityMeter.get().isVoiceChatConnected();
		if (chat && voiceChat == null) {
			voiceChat = started(new CapturedTrack(VOICE_CHAT_TRACK, target -> VoiceChatCapture.open()));
		}
		if (voiceChat != null) {
			voiceChat.target = chat ? 0 : -1;
			follow(voiceChat, chat ? folder : null, active, replayTick, null);
		}

		if (tracks.mic() && mic == null) {
			mic = started(new CapturedTrack(MIC_TRACK, target -> MicCapture.open()));
		}
		if (mic != null) {
			mic.target = tracks.mic() ? 0 : -1;
			follow(mic, tracks.mic() ? folder : null, active, replayTick,
				replayId != null && mc.player != null ? mc.player.getUUID() : null);
		}
	}

	private static void follow(CapturedTrack track, @Nullable Path folder, boolean active, int tick, @Nullable UUID speaker) {
		track.tick = tick;
		track.folder = folder;
		track.active = active && folder != null;
		track.speaker = speaker;
	}

	private static CapturedTrack started(CapturedTrack track) {
		track.start();
		return track;
	}

	/** Friends in a call app see no "recording" sign: remind the player to tell them, once per recording and app. */
	private void remind(Minecraft mc, UUID replayId, CallApps.App app) {
		if (!replayId.equals(remindedFor)) {
			remindedFor = replayId;
			reminded.clear();
		}
		if (reminded.add(app.id())) {
			mc.getToastManager().addToast(SystemToast.multiline(mc, SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				Component.translatable("survivalcinema.voice.reminder.title", app.displayName()),
				Component.translatable("survivalcinema.voice.reminder.body", app.displayName())));
		}
	}

	private void lookForApps(SurvivalCinemaConfig config) {
		CompletableFuture<List<CallApps.Running>> pending = scanning;
		if (pending != null && pending.isDone()) {
			running = pending.getNow(List.of());
			scanning = null;
		}
		if (!WINDOWS || scanning != null || --ticksToScan > 0) {
			return;
		}
		ticksToScan = SCAN_EVERY_TICKS;
		List<CallApps.App> wanted = new ArrayList<>();
		for (CallApps.App app : CallApps.ALL) {
			if (!config.voiceAppsOff.contains(app.id())) {
				wanted.add(app);
			}
		}
		scanning = CompletableFuture.supplyAsync(() -> CallApps.find(processes(), wanted), Util.backgroundExecutor())
			.exceptionally(t -> {
				SurvivalCinemaClient.LOGGER.warn("Could not list the running programs", t);
				return List.of();
			});
	}

	/** Looks again straight away (the settings changed). */
	public void rescan() {
		ticksToScan = 0;
	}

	private static List<CallApps.Proc> processes() {
		List<CallApps.Proc> list = new ArrayList<>();
		ProcessHandle.allProcesses().forEach(p -> {
			String command = p.info().command().orElse(null);
			if (command != null) {
				list.add(new CallApps.Proc(p.pid(), p.parent().map(ProcessHandle::pid).orElse(-1L), CallApps.exeName(command)));
			}
		});
		return list;
	}

	/** Stops every track and closes its file (leaving the world, the game closing). */
	public void stopAll() {
		for (CapturedTrack track : apps.values()) {
			track.stop();
		}
		apps.clear();
		if (mic != null) {
			mic.stop();
			mic = null;
		}
		if (voiceChat != null) {
			voiceChat.stop();
			voiceChat = null;
		}
		running = List.of();
		ticksToScan = 0;
	}
}
