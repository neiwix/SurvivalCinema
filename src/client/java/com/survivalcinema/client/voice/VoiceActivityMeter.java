package com.survivalcinema.client.voice;

import com.survivalcinema.client.capture.EventSink;
import com.survivalcinema.client.voice.apps.CallVoiceRecorder;
import com.survivalcinema.director.event.Details;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.voice.CallApps;
import com.survivalcinema.director.voice.ConversationDetector;
import com.survivalcinema.director.voice.VoiceReactionDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns voice chat audio into highlight moments: someone screaming or laughing, or several excited people at once.
 * Audio arrives on Simple Voice Chat threads; we only keep each speaker's loudest level per 100 ms window there,
 * and {@link VoiceReactionDetector} decides on the client tick.
 */
public final class VoiceActivityMeter {
	private static final VoiceActivityMeter INSTANCE = new VoiceActivityMeter();

	private final Map<UUID, Double> windowPeakDb = new ConcurrentHashMap<>();
	private static final double NEAR_BLOCKS = 24;

	/** Voices from call apps (one track per app, everyone in the call together), named after the app. */
	private static final Map<UUID, String> CALL_APPS = new HashMap<>();

	static {
		for (CallApps.App app : CallApps.ALL) {
			CALL_APPS.put(CallVoiceRecorder.speakerOf(app), app.displayName());
		}
	}

	private volatile boolean voiceChatConnected;
	private final VoiceReactionDetector detector = new VoiceReactionDetector();
	private final ConversationDetector conversations = new ConversationDetector();
	private int ticks;

	public static VoiceActivityMeter get() {
		return INSTANCE;
	}

	/** Simple Voice Chat is connected to the server (it then carries the player's own voice). */
	public boolean isVoiceChatConnected() {
		return voiceChatConnected;
	}

	public void setVoiceChatConnected(boolean connected) {
		voiceChatConnected = connected;
	}

	/** Called from voice chat threads with 16-bit PCM. */
	public void onAudio(UUID speakerId, short[] pcm) {
		if (pcm == null || pcm.length == 0) {
			return;
		}
		double sum = 0;
		for (short s : pcm) {
			sum += (double) s * s;
		}
		double rms = Math.sqrt(sum / pcm.length);
		double db = rms <= 0 ? -120 : 20 * Math.log10(rms / 32768.0);
		windowPeakDb.merge(speakerId, db, Math::max);
		if (db >= ConversationDetector.SPEECH_FLOOR_DB && activityListener != null) {
			activityListener.run(); // someone is talking: the player isn't away
		}
	}

	private volatile Runnable activityListener;

	public void setActivityListener(Runnable listener) {
		this.activityListener = listener;
	}

	public void reset() {
		windowPeakDb.clear();
		detector.reset();
		conversations.reset();
	}

	/** Drops audio heard while the recording was paused, keeping what we learned about everyone's voice. */
	public void clearPending() {
		windowPeakDb.clear();
		conversations.reset();
	}

	/** Every client tick; one detector window per 2 ticks (100 ms). */
	public void tick(Minecraft mc, EventSink sink) {
		if (++ticks % 2 != 0) {
			return;
		}
		Map<UUID, Double> window = new HashMap<>();
		for (UUID id : List.copyOf(windowPeakDb.keySet())) {
			Double db = windowPeakDb.remove(id);
			if (db != null && isPlayer(mc, id)) {
				window.put(id, db);
			}
		}
		int tick = sink.currentTick();
		for (ConversationDetector.Conversation talk : conversations.window(window)) {
			emitConversation(mc, sink, tick, talk);
		}
		for (VoiceReactionDetector.Reaction reaction : detector.window(window)) {
			String names = names(mc, reaction.speakers());
			int startTick = Math.max(0, tick - reaction.windowsAgo() * 2);
			if (reaction.kind() == VoiceReactionDetector.Kind.PEAK) {
				sink.emit(EventType.VOICE_PEAK, "voice", Component.translatable("survivalcinema.event.voice_peak", names).getString(),
					null, reaction.intensity(), false, names, startTick);
			} else {
				sink.emit(EventType.VOICE_CROWD, "voice", Component.translatable("survivalcinema.event.voice_crowd", reaction.speakers().size()).getString(),
					null, reaction.intensity(), false, names, startTick);
			}
		}
	}

	/** Who talked, and which of them were standing near us (so the camera can film them together). */
	private static void emitConversation(Minecraft mc, EventSink sink, int tick, ConversationDetector.Conversation talk) {
		List<String> speakers = new ArrayList<>();
		List<String> near = new ArrayList<>();
		for (UUID id : talk.speakers()) {
			speakers.add(names(mc, List.of(id)));
			if (mc.player != null && mc.level != null && !id.equals(mc.player.getUUID())) {
				Player other = mc.level.getPlayerByUUID(id);
				if (other != null && other.distanceTo(mc.player) <= NEAR_BLOCKS) {
					near.add(other.getName().getString());
				}
			}
		}
		int startTick = Math.max(0, tick - talk.windowsAgo() * 2);
		Map<String, String> detail = new LinkedHashMap<>();
		detail.put("ticks", String.valueOf(talk.durationWindows() * 2));
		detail.put("speakers", String.join(",", speakers));
		detail.put("near", String.join(",", near));
		detail.put("turns", String.valueOf(talk.turns()));
		boolean first = sink.markSeen("conversation", "any");
		sink.emit(EventType.CONVERSATION, "conversation", Component.translatable("survivalcinema.event.conversation",
			String.join(", ", speakers)).getString(), null, talk.intensity(), first, Details.of(detail), startTick);
	}

	/**
	 * Only people count: voice chat add-ons also play music discs, horns and other sounds through audio channels,
	 * which must not look like someone screaming.
	 */
	private static boolean isPlayer(Minecraft mc, UUID id) {
		if (CALL_APPS.containsKey(id)) {
			return true;
		}
		if (mc.player != null && id.equals(mc.player.getUUID())) {
			return true;
		}
		return mc.getConnection() != null && mc.getConnection().getPlayerInfo(id) != null;
	}

	private static String names(Minecraft mc, List<UUID> ids) {
		List<String> names = new ArrayList<>();
		for (UUID id : ids) {
			String app = CALL_APPS.get(id);
			if (app != null) {
				names.add(app);
				continue;
			}
			if (mc.player != null && id.equals(mc.player.getUUID())) {
				names.add(mc.player.getName().getString());
				continue;
			}
			PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(id);
			names.add(info == null ? "?" : info.getProfile().name());
		}
		return String.join(", ", names);
	}
}
