package com.survivalcinema.client.render;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.RecordingController;
import com.survivalcinema.client.capture.SessionStore;
import com.survivalcinema.client.capture.WorldFilmLoader;
import com.survivalcinema.client.capture.WorldMemory;
import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.client.edit.EncoderSelector;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.client.flashback.FlashbackCheck;
import com.survivalcinema.director.event.SafeFiles;
import com.survivalcinema.director.render.FilmFolder;
import com.survivalcinema.director.render.RenderPlan;
import com.survivalcinema.director.render.RenderProgress;
import com.survivalcinema.director.render.RenderQuality;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.FilmDirector;
import com.survivalcinema.director.story.FilmLength;
import com.survivalcinema.director.story.SessionData;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Makes a film of the world being played. The "Make a film" button of the pause menu, the offer after the dragon and
 * {@code /cinema render} all start here.
 * <p>
 * The recording running now is finished first (Flashback saves it to the replay folder at once), so the film has what
 * just happened in it, the dragon fight above all. Planning reads hours of recordings, so it runs in the background;
 * then the screenplay and the shooting progress go into a new film folder ({@code films/<world>/<date>/}) and the
 * clips are shot under the cinema screen, then edited into the film. Problems come back as messages in the player's
 * language; when nothing could be started, the recording goes on as before.
 */
public final class FilmMaker {
	/** What happened, always told on the client thread. */
	public interface Listener {
		/** Shooting started under the cinema screen. */
		default void started(RenderPipeline pipeline, int clips, double filmSeconds, RenderQuality quality) {
		}

		void failed(Component reason);
	}

	/** A film of this world that was stopped (or crashed) before it was finished. */
	public record Unfinished(Path film, EditDecisionList edl, RenderProgress progress, int clipsLeft, int clipCount) {
	}

	private static volatile boolean planning;

	private FilmMaker() {
	}

	/** A film is being planned right now (between the button and the cinema screen). */
	public static boolean isPlanning() {
		return planning;
	}

	/** Why no new film can be made right now, or null when one can. */
	public static @Nullable Component cannotStart() {
		Component flashback = FlashbackCheck.problem();
		if (flashback != null) {
			return flashback;
		}
		WorldMemory world = SurvivalCinemaClient.controller().world();
		if (world == null || FlashbackBridge.isInReplay()) {
			return Component.translatable("survivalcinema.film.error.no_world");
		}
		if (RenderPipeline.isRunning() || planning) {
			return Component.translatable("survivalcinema.film.error.running");
		}
		if (EncoderSelector.ready() == null && !EncoderSelector.isChecking()) {
			return Component.translatable("survivalcinema.film.error.encoder");
		}
		if (world.sessions.isEmpty()) {
			return Component.translatable("survivalcinema.film.error.nothing_recorded");
		}
		if (chosenSessions(world).isEmpty()) {
			return Component.translatable("survivalcinema.film.error.none_chosen");
		}
		return null;
	}

	/**
	 * Plans a film of the world being played and starts shooting it. Must be called on the client thread.
	 *
	 * @return false when it could not even start (the listener was told why)
	 */
	public static boolean start(FilmLength length, RenderQuality quality, Listener listener) {
		Component problem = cannotStart();
		if (problem != null) {
			listener.failed(problem);
			return false;
		}
		RecordingController controller = SurvivalCinemaClient.controller();
		WorldMemory world = controller.world();
		Minecraft mc = Minecraft.getInstance();
		boolean wasRecording = FlashbackBridge.isRecording();
		if (wasRecording) {
			controller.stopRecording(); // saves this session's replay now, so it is part of the film
		}
		String player = mc.player == null ? "" : mc.player.getName().getString();
		List<String> sessionIds = chosenSessions(world);
		boolean deleteWhenDone = SurvivalCinemaConfig.get().deleteRecordingsAfterFilm;
		String worldName = world.displayName;
		String worldKey = world.worldKey;
		planning = true;
		SurvivalCinemaClient.LOGGER.info("Planning a {} min film of \"{}\" from {} sessions", length.minutes(), worldName, sessionIds.size());
		// Reading hours of recordings and planning takes a moment: never on the render thread.
		Thread thread = new Thread(() -> {
			try {
				List<SessionData> sessions = WorldFilmLoader.load(sessionIds, FlashbackBridge.replaysById().keySet());
				if (sessions.isEmpty()) {
					fail(mc, listener, Component.translatable("survivalcinema.film.error.nothing_recorded"), world, wasRecording);
					return;
				}
				EditDecisionList edl = FilmDirector.plan(sessions, new FilmDirector.Options(length.seconds(), worldName, player));
				int clips = RenderPlan.of(edl).size();
				if (clips == 0) {
					fail(mc, listener, Component.translatable("survivalcinema.film.error.nothing_to_film"), world, wasRecording);
					return;
				}
				WorldFilmLoader.saveEdl(world, edl);
				Path film = SessionStore.filmsFolder(worldKey).resolve(FilmFolder.name(LocalDateTime.now()));
				SafeFiles.writeAtomically(FilmFolder.edlFile(film), edl.toJson());
				RenderProgress progress = RenderProgress.fresh(edl, worldKey, quality, System.currentTimeMillis());
				progress.sessions = sessionIds;
				progress.deleteRecordingsWhenDone = deleteWhenDone;
				progress.write(FilmFolder.progressFile(film));
				mc.execute(() -> {
					planning = false;
					if (RenderPipeline.isRunning()) {
						listener.failed(Component.translatable("survivalcinema.film.error.running"));
						return;
					}
					RenderPipeline pipeline = RenderPipeline.start(film, edl, progress);
					listener.started(pipeline, clips, edl.totalSeconds(), quality);
				});
			} catch (Throwable t) {
				SurvivalCinemaClient.LOGGER.error("Preparing the film failed", t);
				fail(mc, listener, Component.translatable("survivalcinema.film.error.failed", String.valueOf(t.getMessage())), world, wasRecording);
			}
		}, "SurvivalCinema-Plan");
		thread.setDaemon(true);
		thread.start();
		return true;
	}

	/** The world's recordings that go into the film, oldest first: all but those the player left out. */
	public static List<String> chosenSessions(WorldMemory world) {
		return world.sessions.stream().filter(id -> !world.skippedSessions.contains(id)).toList();
	}

	private static void fail(Minecraft mc, Listener listener, Component reason, WorldMemory world, boolean wasRecording) {
		mc.execute(() -> {
			planning = false;
			RecordingController controller = SurvivalCinemaClient.controller();
			if (wasRecording && controller.world() == world && mc.level != null && !FlashbackBridge.isRecording()) {
				controller.startRecording(world.recordVoiceChat); // nothing was filmed: go on recording as before
			}
			listener.failed(reason);
		});
	}

	/** The newest unfinished film of this world, or null. Reads a few small files. */
	public static @Nullable Unfinished unfinished(WorldMemory world) {
		Path film = FilmFolder.latestUnfinished(SessionStore.filmsFolder(world.worldKey));
		EditDecisionList edl = film == null ? null : FilmFolder.readEdl(film);
		RenderProgress progress = film == null ? null : RenderProgress.read(FilmFolder.progressFile(film));
		if (film == null || edl == null || progress == null || !progress.matches(edl)) {
			return null;
		}
		RenderPlan plan = RenderPlan.of(edl);
		return new Unfinished(film, edl, progress, progress.pending(plan).size(), plan.size());
	}

	/**
	 * Continues the newest unfinished film of this world (after a stop or a crash) with the next clip to shoot, or edits
	 * it when every clip is shot. Clips whose files were deleted are shot again.
	 *
	 * @return the film continued, or null (the listener was told why)
	 */
	public static @Nullable Unfinished resume(Listener listener) {
		WorldMemory world = SurvivalCinemaClient.controller().world();
		Component problem = FlashbackCheck.problem() != null ? FlashbackCheck.problem()
			: world == null || FlashbackBridge.isInReplay() ? Component.translatable("survivalcinema.film.error.no_world")
			: RenderPipeline.isRunning() || planning ? Component.translatable("survivalcinema.film.error.running") : null;
		Unfinished unfinished = problem == null ? unfinished(world) : null;
		if (problem == null && unfinished == null) {
			problem = Component.translatable("survivalcinema.film.error.no_unfinished");
		}
		if (problem != null) {
			listener.failed(problem);
			return null;
		}
		unfinished.progress().forgetMissingFiles(unfinished.film());
		RenderPlan plan = RenderPlan.of(unfinished.edl());
		Unfinished now = new Unfinished(unfinished.film(), unfinished.edl(), unfinished.progress(),
			unfinished.progress().pending(plan).size(), plan.size());
		RenderPipeline pipeline = RenderPipeline.start(now.film(), now.edl(), now.progress());
		listener.started(pipeline, now.clipsLeft(), now.edl().totalSeconds(), now.progress().quality);
		return now;
	}

	/**
	 * Edits the newest shot film of this world again from its clips (after adding your own music to
	 * {@code survivalcinema/music}, or changing the music setting), without shooting anything again.
	 *
	 * @return the film folder, or null (the listener was told why)
	 */
	public static @Nullable Path reedit(Listener listener) {
		WorldMemory world = SurvivalCinemaClient.controller().world();
		if (FlashbackCheck.problem() != null) {
			listener.failed(FlashbackCheck.problem());
			return null;
		}
		if (world == null || FlashbackBridge.isInReplay()) {
			listener.failed(Component.translatable("survivalcinema.film.error.no_world"));
			return null;
		}
		if (RenderPipeline.isRunning() || planning) {
			listener.failed(Component.translatable("survivalcinema.film.error.running"));
			return null;
		}
		Path film = FilmFolder.latestShot(SessionStore.filmsFolder(world.worldKey));
		EditDecisionList edl = film == null ? null : FilmFolder.readEdl(film);
		RenderProgress progress = film == null ? null : RenderProgress.read(FilmFolder.progressFile(film));
		if (film == null || edl == null || progress == null || !progress.matches(edl)) {
			listener.failed(Component.translatable("survivalcinema.film.error.no_shot"));
			return null;
		}
		progress.forgetMissingFiles(film);
		progress.film = null;
		RenderPipeline pipeline = RenderPipeline.start(film, edl, progress);
		listener.started(pipeline, 0, edl.totalSeconds(), progress.quality);
		return film;
	}
}
