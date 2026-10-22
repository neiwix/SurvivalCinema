package com.survivalcinema.client.render;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * What the cinema screen shows at a moment of the shooting.
 *
 * @param clip        the clip being worked on, counted in shooting order (1 = first)
 * @param shotClips   clips finished so far
 * @param progress    share of the film already shot, 0..1 (while editing: share of the film edited)
 * @param minutesLeft rough minutes until the last clip is shot (or the film is edited)
 * @param folder      where the clips are (or the film, once edited), relative to the game folder
 * @param editing     the clips are being edited into the film (also while asking whether to stop that)
 * @param hasFilm     the edited film exists
 * @param filmSeconds how long the edited film is, 0 before it is made
 * @param gameMusic   the film uses the game's soundtrack
 */
public record CinemaStatus(RenderPipeline.Phase phase, String worldName, int filmMinutes,
						   int clip, int clipCount, int shotClips, int skippedClips,
						   int session, int sessionCount, float progress, int minutesLeft,
						   boolean stopAfterClip, @Nullable Component problem, String folder,
						   boolean editing, boolean hasFilm, double filmSeconds, boolean gameMusic) {
}
