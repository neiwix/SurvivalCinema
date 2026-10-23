package com.survivalcinema.client.capture;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.watch.AdvancementWatcher;
import com.survivalcinema.client.capture.watch.BossWatcher;
import com.survivalcinema.client.capture.watch.BuildWatcher;
import com.survivalcinema.client.capture.watch.ChatWatcher;
import com.survivalcinema.client.capture.watch.CombatWatcher;
import com.survivalcinema.client.capture.watch.ItemWatcher;
import com.survivalcinema.client.capture.watch.MovementWatcher;
import com.survivalcinema.client.capture.watch.StructureWatcher;
import com.survivalcinema.client.capture.watch.WoodWatcher;
import com.survivalcinema.client.capture.watch.WorldWatcher;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.client.flashback.FlashbackCheck;
import com.survivalcinema.client.ui.ConsentScreen;
import com.survivalcinema.client.voice.VoiceActivityMeter;
import com.survivalcinema.client.voice.apps.CallVoiceRecorder;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.client.config.SurvivalCinemaConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The recording lifecycle: join a world -> ask (or remember) whether to record -> start Flashback ->
 * notice moments while playing -> finish and save the replay when leaving.
 * <p>
 * Flashback settings we change for a cinema recording (save without asking, record voice chat or not) are only
 * changed while we record and always put back afterwards, because Flashback saves its config to disk.
 */
public final class RecordingController {
	public final AdvancementWatcher advancements = new AdvancementWatcher();
	public final CombatWatcher combat = new CombatWatcher();
	public final BossWatcher bosses = new BossWatcher();
	public final WorldWatcher worldWatcher = new WorldWatcher();
	public final ItemWatcher items = new ItemWatcher();
	public final BuildWatcher builds = new BuildWatcher();
	public final MovementWatcher movement = new MovementWatcher();
	public final ChatWatcher chat = new ChatWatcher();
	public final WoodWatcher wood = new WoodWatcher();
	public final StructureWatcher structures = new StructureWatcher();

	private final TrajectoryRecorder trajectory = new TrajectoryRecorder();
	private final CompanionTracker companions = new CompanionTracker();
	private final AfkDetector afk = new AfkDetector();

	private @Nullable WorldMemory world;
	private @Nullable CinemaSession session;
	private boolean consentPending;
	private boolean wantRecording;
	private boolean wasPaused;
	private @Nullable Boolean previousQuicksave;
	private @Nullable Boolean previousVoiceChat;
	private @Nullable Consumer<WorldMemory> onDragonKilled;
	/** Flashback's settings are put back once the recording it finished on leaving is saved. */
	private boolean restorePending;
	private int restoreWait;
	private static final int RESTORE_DELAY_TICKS = 20;

	public @Nullable CinemaSession session() {
		return session;
	}

	/**
	 * Where watchers report moments: only while our recording is really running. During a pause (AFK, pause menu)
	 * or right after Flashback stopped, the replay tick doesn't move, so moments would land on the wrong footage.
	 */
	public @Nullable EventSink sink() {
		CinemaSession s = session;
		if (s == null || FlashbackBridge.isRecordingPaused() || !s.replayId().equals(FlashbackBridge.recordingReplayId())) {
			return null;
		}
		return s;
	}

	public @Nullable WorldMemory world() {
		return world;
	}

	public boolean isAfkPaused() {
		return afk.isPaused();
	}

	/** Something social happened (voice, chat): the player is not away even if they stand still. */
	public void noteActivity() {
		afk.noteActivity();
	}

	public void onJoin(Minecraft mc) {
		advancements.reset(); // advancement state belongs to the connection, not to one recording
		resetWatchers();
		if (FlashbackBridge.isReplayServer(mc.getSingleplayerServer())) {
			world = null;
			return;
		}
		WorldKeys.WorldId id = WorldKeys.current(mc);
		world = id == null ? null : WorldMemory.load(id.key(), id.displayName());
		consentPending = world != null;
	}

	public void onDisconnect(Minecraft mc) {
		if (wantRecording && FlashbackBridge.finishesRecordingOnDisconnect()) {
			// Flashback finishes the recording itself when the game leaves the world, on the network thread. Finishing it
			// here as well would race with it, and so would putting "save without asking" back: Flashback could read it
			// afterwards and ask the player with its save dialog. Our settings go back once it is done (restoreWhenSaved).
			wantRecording = false;
			closeSession();
			restorePending = true;
			restoreWait = 0;
		} else {
			stopRecording();
		}
		CallVoiceRecorder.get().stopAll();
		world = null;
		consentPending = false;
	}

	public void tick(Minecraft mc) {
		restoreWhenSaved();
		tickRecording(mc);
		// call apps and the microphone follow our recording: written while it runs, nothing while paused
		CinemaSession s = session;
		UUID recording = FlashbackBridge.recordingReplayId();
		boolean ours = s != null && s.replayId().equals(recording);
		CallVoiceRecorder.get().tick(mc, ours ? recording : null, FlashbackBridge.isRecordingPaused(),
			Math.max(0, FlashbackBridge.recordingTick()), world != null && world.recordVoiceChat);
	}

	private void tickRecording(Minecraft mc) {
		if (FlashbackBridge.isInReplay()) {
			if (session != null) {
				closeSession();
			}
			return;
		}

		if (consentPending && world != null && mc.player != null && mc.screen == null) {
			consentPending = false;
			if (FlashbackCheck.problem() != null) {
				return; // this Flashback lacks something we use: no recording (the title screen said why)
			}
			switch (world.consent) {
				case ALWAYS -> startRecording(world.recordVoiceChat);
				case ASK -> mc.setScreen(new ConsentScreen(this, world));
				case NEVER -> {
				}
			}
		}

		UUID recording = FlashbackBridge.recordingReplayId();
		if (wantRecording && recording != null && (session == null || !session.replayId().equals(recording))) {
			openSession(mc, recording);
		} else if (recording == null && session != null) {
			closeSession(); // Flashback's recording ended some other way (its own button, a crash...)
		}

		CinemaSession s = session;
		if (s == null) {
			return;
		}
		afk.tick(mc);
		boolean paused = FlashbackBridge.isRecordingPaused();
		if (paused) {
			wasPaused = true;
			return;
		}
		if (wasPaused) {
			// Voices heard during the pause belong to no footage; start listening afresh.
			VoiceActivityMeter.get().clearPending();
			wasPaused = false;
		}
		trajectory.onClientTick(mc);
		combat.tick(mc, s);
		bosses.tick(mc, s);
		worldWatcher.tick(mc, s);
		items.tick(mc, s);
		builds.tick(mc, s);
		wood.tick(mc, s);
		structures.tick(mc, s);
		movement.tick(mc, s);
		companions.tick(mc, s);
		VoiceActivityMeter.get().tick(mc, s);
	}

	/** Consent screen answer. */
	public void onConsent(boolean record, boolean remember, boolean voiceChat) {
		if (world == null) {
			return;
		}
		world.recordVoiceChat = voiceChat;
		if (remember) {
			world.consent = record ? WorldMemory.Consent.ALWAYS : WorldMemory.Consent.NEVER;
		}
		world.save();
		if (record) {
			startRecording(voiceChat);
		}
	}

	/**
	 * The player changed this world's answer in the settings. Applied at once: "Always" starts recording if it isn't,
	 * "Never" finishes (and keeps) the recording running now; the voices choice also changes the recording running now.
	 */
	public void changeWorldChoice(WorldMemory changed, WorldMemory.Consent consent, boolean voiceChat) {
		changed.consent = consent;
		changed.recordVoiceChat = voiceChat;
		changed.save();
		if (changed != world || FlashbackBridge.isInReplay()) {
			return;
		}
		consentPending = false;
		if (consent == WorldMemory.Consent.NEVER) {
			stopRecording();
		} else if (consent == WorldMemory.Consent.ALWAYS && !wantRecording) {
			startRecording(voiceChat);
		} else if (wantRecording) {
			FlashbackBridge.setVoiceChatRecording(recordsVoiceChat(voiceChat));
		}
	}

	/** The voice settings changed: the recording running now follows them. */
	public void voiceSettingsChanged() {
		if (wantRecording && world != null) {
			FlashbackBridge.setVoiceChatRecording(recordsVoiceChat(world.recordVoiceChat));
		}
	}

	/** The world allows voices, and the voice settings take them from the voice chat (not "only Discord", not "none"). */
	private static boolean recordsVoiceChat(boolean worldAllows) {
		return worldAllows && SurvivalCinemaConfig.get().voiceSource().voiceChat();
	}

	public void startRecording(boolean voiceChat) {
		if (world == null || FlashbackCheck.problem() != null) {
			return;
		}
		wantRecording = true;
		restorePending = false; // back in a world before the last one was put back: keep our settings
		if (previousQuicksave == null) {
			// Leaving the world must save straight away instead of popping Flashback's save dialog.
			previousQuicksave = FlashbackBridge.setQuicksave(true);
		}
		// Set it explicitly both ways: unchecking "record voice chat" (or "no voices" in the voice settings) must really
		// mean no voices.
		Boolean before = FlashbackBridge.setVoiceChatRecording(recordsVoiceChat(voiceChat));
		if (previousVoiceChat == null) {
			previousVoiceChat = before;
		}
		FlashbackBridge.startRecording();
	}

	public void stopRecording() {
		boolean ours = wantRecording;
		wantRecording = false;
		restorePending = false;
		try {
			if (ours && FlashbackBridge.isRecording()) {
				FlashbackBridge.finishRecording();
			}
		} catch (Throwable t) {
			SurvivalCinemaClient.LOGGER.error("Flashback failed to finish the recording", t);
		} finally {
			closeSession();
			if (previousQuicksave != null) {
				FlashbackBridge.setQuicksave(previousQuicksave);
				previousQuicksave = null;
			}
			if (previousVoiceChat != null) {
				FlashbackBridge.setVoiceChatRecording(previousVoiceChat);
				previousVoiceChat = null;
			}
		}
	}

	/** After leaving a world: once Flashback has saved the recording, its own settings go back to what the player had. */
	private void restoreWhenSaved() {
		if (!restorePending || FlashbackBridge.isRecording()) {
			return;
		}
		if (++restoreWait < RESTORE_DELAY_TICKS) {
			return;
		}
		restorePending = false;
		if (previousQuicksave != null) {
			FlashbackBridge.setQuicksave(previousQuicksave);
			previousQuicksave = null;
		}
		if (previousVoiceChat != null) {
			FlashbackBridge.setVoiceChatRecording(previousVoiceChat);
			previousVoiceChat = null;
		}
	}

	private void openSession(Minecraft mc, UUID replayId) {
		closeSession();
		resetWatchers();
		try {
			session = new CinemaSession(replayId, world);
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.error("Could not start the cinema session", e);
			mc.gui.getChat().addMessage(Component.translatable("survivalcinema.error.session"));
			wantRecording = false; // don't retry (and spam the chat) every tick
			return;
		}
		WorldMemory recorded = world;
		session.setOnDragonKilled(() -> {
			if (onDragonKilled != null) {
				onDragonKilled.accept(recorded);
			}
		});
		session.emit(EventType.SESSION_START, "session", world.displayName, null, 0.1, world.sessions.size() == 1, null);
		SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
		if (!config.recordingTipShown) {
			// the very first recording: say where the film is made, the button is easy to miss
			config.recordingTipShown = true;
			config.save();
			mc.getToastManager().addToast(SystemToast.multiline(mc, SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				Component.translatable("survivalcinema.tip.recording.title"), Component.translatable("survivalcinema.tip.recording.body")));
		}
	}

	/** Hears when the Ender Dragon dies in a recorded session of a world (the "make the film?" offer). */
	public void setOnDragonKilled(@Nullable Consumer<WorldMemory> onDragonKilled) {
		this.onDragonKilled = onDragonKilled;
	}

	private void closeSession() {
		if (session != null) {
			session.close();
			session = null;
		}
		afk.reset();
		trajectory.close();
		companions.close();
		wasPaused = false;
	}

	private void resetWatchers() {
		combat.reset();
		bosses.reset();
		worldWatcher.reset();
		items.reset();
		builds.reset();
		wood.reset();
		structures.reset();
		movement.reset();
		VoiceActivityMeter.get().reset();
	}

	public void onClientStopping() {
		stopRecording();
		CallVoiceRecorder.get().stopAll();
	}
}
