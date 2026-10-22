package com.survivalcinema.client.render;

import com.survivalcinema.director.edit.VoiceCut;
import org.jetbrains.annotations.Nullable;

/**
 * The little the mixins need to know about a film being shot. Kept apart from {@link RenderPipeline} so the mixins
 * into Flashback and Minecraft stay tiny.
 */
public final class CinemaMode {
	private static volatile boolean filming;
	private static volatile boolean exportCancelled;
	private static volatile int guiScale = 2;
	/** The piece being exported: {from, to, slow motion from} replay ticks, or null. */
	private static volatile int @Nullable [] clip;

	private CinemaMode() {
	}

	/** True while our cinema screen covers the game: Flashback's editor stays hidden and its export screen is ours. */
	public static boolean isFilming() {
		return filming;
	}

	static void setFilming(boolean value) {
		filming = value;
		exportCancelled = false;
	}

	/** Flashback cancels an export when Esc is held for three seconds. */
	public static void noteExportCancelled() {
		if (filming) {
			exportCancelled = true;
		}
	}

	static boolean consumeExportCancelled() {
		boolean cancelled = exportCancelled;
		exportCancelled = false;
		return cancelled;
	}

	/**
	 * The player's GUI scale, remembered while the normal GUI is drawn: during an export Flashback changes the window
	 * size and GUI scale, and the export picture must look exactly like the cinema screen around it.
	 */
	public static int guiScale() {
		return guiScale;
	}

	public static void rememberGuiScale(int scale) {
		if (scale > 0) {
			guiScale = scale;
		}
	}

	/** The piece Flashback is exporting now (null when none): its replay ticks and where its slow motion starts. */
	static void setClip(int @Nullable [] fromToSlow) {
		clip = fromToSlow;
	}

	/**
	 * Flashback plays the recorded voice chat at the replay's speed, so in slow motion it stutters. There the clip stays
	 * without it, and our own copy of the voices is laid under the slowed picture at normal speed instead.
	 */
	public static boolean muteVoiceChatAt(int replayTick) {
		int[] piece = clip;
		return filming && piece != null && VoiceCut.isSlow(piece[0], piece[1], piece[2], replayTick);
	}
}
