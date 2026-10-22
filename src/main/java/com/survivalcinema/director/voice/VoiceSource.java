package com.survivalcinema.director.voice;

import java.util.Locale;

/**
 * Where the film's voices come from. Simple Voice Chat is recorded by Flashback itself (and by us too, for slow
 * motion, where Flashback's voices stutter); call apps (Discord, Telegram...) and the microphone are recorded by us
 * into voice tracks.
 */
public enum VoiceSource {
	/** Call apps whenever they run; the microphone too when the game's voice chat is not carrying it. */
	AUTO,
	/** Only Simple Voice Chat. */
	VOICE_CHAT,
	/** Call apps and the microphone (the apps never play your own voice back to you). */
	APPS,
	/** No voices at all. */
	OFF;

	/** What to record at the moment. */
	public record Tracks(boolean apps, boolean mic) {
		public static final Tracks NONE = new Tracks(false, false);

		public boolean any() {
			return apps || mic;
		}
	}

	/**
	 * @param voiceAllowed     the player agreed to record voices in this world
	 * @param voiceChatOn      Simple Voice Chat is connected (it carries the player's own voice)
	 * @param appRunning       a call app being captured runs right now
	 * @param micCommentary    "my commentary": record the microphone even without a call
	 */
	public Tracks tracks(boolean voiceAllowed, boolean voiceChatOn, boolean appRunning, boolean micCommentary) {
		if (!voiceAllowed) {
			return Tracks.NONE;
		}
		return switch (this) {
			case AUTO -> new Tracks(true, !voiceChatOn && (appRunning || micCommentary));
			case VOICE_CHAT -> new Tracks(false, !voiceChatOn && micCommentary);
			case APPS -> new Tracks(true, appRunning || micCommentary);
			case OFF -> Tracks.NONE;
		};
	}

	/**
	 * Whether Simple Voice Chat goes into the film (Flashback records it, and we keep our own copy for slow motion):
	 * "Discord and others" and "no voices" mean no voice chat either.
	 */
	public boolean voiceChat() {
		return this == AUTO || this == VOICE_CHAT;
	}

	public static VoiceSource parse(String name) {
		try {
			return valueOf(name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException | NullPointerException e) {
			return AUTO;
		}
	}
}
