package com.survivalcinema.client.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.CinemaSession;
import com.survivalcinema.client.capture.SessionStore;
import com.survivalcinema.client.capture.WorldFilmLoader;
import com.survivalcinema.client.capture.WorldMemory;
import com.survivalcinema.client.ui.ChapterTitles;
import com.survivalcinema.client.ui.DragonOfferScreen;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.FilmDirector;
import com.survivalcinema.director.story.FilmLength;
import com.survivalcinema.director.story.SessionData;
import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.client.edit.EncoderSelector;
import com.survivalcinema.client.edit.FfmpegProbe;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.director.camera.CameraKey;
import com.survivalcinema.client.render.ClientBlockProbe;
import com.survivalcinema.client.render.ClipQueue;
import com.survivalcinema.client.render.FilmMaker;
import com.survivalcinema.client.render.RenderPipeline;
import com.survivalcinema.director.render.FilmFolder;
import com.survivalcinema.director.render.RenderQuality;
import com.survivalcinema.director.camera.CameraPathBuilder;
import com.survivalcinema.director.camera.OcclusionSolver;
import com.survivalcinema.director.camera.ShotType;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailIO;
import com.survivalcinema.director.event.GameEvent;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Developer commands used to verify the Flashback integration by hand (Phase 0).
 */
public final class DebugCommands {
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private DebugCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(literal("cinema")
			.then(literal("status").executes(DebugCommands::status))
			.then(literal("rec")
				.then(literal("start").executes(ctx -> run(ctx, () -> SurvivalCinemaClient.controller().startRecording(true), "Recording started (voice chat recording on)")))
				.then(literal("pause").executes(ctx -> run(ctx, () -> FlashbackBridge.setRecordingPaused(true), "Recording paused")))
				.then(literal("resume").executes(ctx -> run(ctx, () -> FlashbackBridge.setRecordingPaused(false), "Recording resumed")))
				.then(literal("stop").executes(ctx -> run(ctx, () -> SurvivalCinemaClient.controller().stopRecording(), "Recording finished and saved"))))
			.then(literal("events").executes(DebugCommands::events))
			.then(literal("plan")
				.executes(ctx -> plan(ctx, SurvivalCinemaConfig.get().filmLength()))
				.then(argument("minutes", IntegerArgumentType.integer(1, 60))
					.executes(ctx -> plan(ctx, FilmLength.ofMinutes(IntegerArgumentType.getInteger(ctx, "minutes"))))))
			.then(literal("render")
				.executes(ctx -> render(ctx, SurvivalCinemaConfig.get().filmLength()))
				.then(literal("resume").executes(DebugCommands::resume))
				.then(literal("reedit").executes(DebugCommands::reedit))
				.then(argument("minutes", IntegerArgumentType.integer(1, 60))
					.executes(ctx -> render(ctx, FilmLength.ofMinutes(IntegerArgumentType.getInteger(ctx, "minutes"))))))
			.then(literal("offer").executes(ctx -> {
				// the "make the film?" question of the dragon, without killing one
				Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new DragonOfferScreen()));
				return 1;
			}))
			.then(literal("debugchat").executes(ctx -> {
				SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
				config.debugChat = !config.debugChat;
				config.save();
				ctx.getSource().sendFeedback(Component.literal("Event chat messages " + (config.debugChat ? "on" : "off")));
				return 1;
			}))
			.then(literal("consent").then(literal("reset").executes(ctx -> {
				WorldMemory world = SurvivalCinemaClient.controller().world();
				if (world == null) {
					ctx.getSource().sendError(Component.literal("Not in a world"));
					return 0;
				}
				world.consent = WorldMemory.Consent.ASK;
				world.save();
				ctx.getSource().sendFeedback(Component.literal("The recording question will be asked again next time you join"));
				return 1;
			})))
			.then(literal("marker")
				.then(argument("text", StringArgumentType.greedyString()).executes(DebugCommands::marker)))
			.then(literal("replays").executes(DebugCommands::replays))
			.then(literal("open")
				.then(argument("index", IntegerArgumentType.integer(0)).executes(DebugCommands::open)))
			.then(literal("clip")
				.then(argument("shot", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						for (ShotType shot : ShotType.values()) {
							builder.suggest(shot.name().toLowerCase(Locale.ROOT));
						}
						return builder.buildFuture();
					})
					.then(argument("seconds", IntegerArgumentType.integer(1, 600)).executes(DebugCommands::clip))))
			.then(literal("ffmpeg").executes(DebugCommands::ffmpeg)));
	}

	private static int run(CommandContext<FabricClientCommandSource> ctx, Runnable action, String message) {
		action.run();
		ctx.getSource().sendFeedback(Component.literal(message));
		return 1;
	}

	private static int status(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource source = ctx.getSource();
		source.sendFeedback(Component.literal("recording=" + FlashbackBridge.isRecording()
			+ " paused=" + FlashbackBridge.isRecordingPaused()
			+ " tick=" + FlashbackBridge.recordingTick()
			+ " voiceChatRecording=" + FlashbackBridge.isVoiceChatRecordingEnabled()));
		source.sendFeedback(Component.literal("inReplay=" + FlashbackBridge.isInReplay()
			+ " ready=" + FlashbackBridge.isReplayReady()
			+ " tick=" + FlashbackBridge.replayTick() + "/" + FlashbackBridge.totalReplayTicks()
			+ " replayId=" + FlashbackBridge.openReplayId()));
		source.sendFeedback(Component.literal("exporting=" + FlashbackBridge.isExporting()
			+ " queued=" + FlashbackBridge.queuedExports()
			+ " h264Candidates=" + String.join(",", EncoderSelector.candidates())));
		return 1;
	}

	private static int events(CommandContext<FabricClientCommandSource> ctx) {
		CinemaSession session = SurvivalCinemaClient.controller().session();
		if (session == null) {
			ctx.getSource().sendError(Component.literal("No cinema recording running"));
			return 0;
		}
		List<GameEvent> recent = session.recentEvents();
		ctx.getSource().sendFeedback(Component.literal(recent.size() + " recent events (replay " + session.replayId() + "):"));
		for (GameEvent event : recent.subList(Math.max(0, recent.size() - 15), recent.size())) {
			ctx.getSource().sendFeedback(Component.literal("§7t" + event.tick() + " d" + event.day() + " §f" + event.type()
				+ (event.first() ? " §e★" : "") + " §7" + event.label()));
		}
		return 1;
	}

	/** Plans the film of this world and prints the screenplay, without rendering anything. */
	private static int plan(CommandContext<FabricClientCommandSource> ctx, FilmLength length) {
		FabricClientCommandSource source = ctx.getSource();
		WorldMemory world = SurvivalCinemaClient.controller().world();
		if (world == null) {
			source.sendError(Component.literal("Join the world you want a film of first"));
			return 0;
		}
		Minecraft mc = Minecraft.getInstance();
		String player = mc.player == null ? "" : mc.player.getName().getString();
		List<String> sessionIds = List.copyOf(world.sessions);
		String worldName = world.displayName;
		source.sendFeedback(Component.literal("§7Planning the film..."));
		// Reading hours of recordings and planning takes a moment: never on the render thread.
		Thread thread = new Thread(() -> {
			try {
				List<SessionData> sessions = WorldFilmLoader.load(sessionIds, FlashbackBridge.replaysById().keySet());
				if (sessions.isEmpty()) {
					mc.execute(() -> source.sendError(Component.literal("Nothing recorded in this world yet")));
					return;
				}
				EditDecisionList edl = FilmDirector.plan(sessions, new FilmDirector.Options(length.seconds(), worldName, player));
				Path file = WorldFilmLoader.saveEdl(world, edl);
				mc.execute(() -> printPlan(source, worldName, length, sessions.size(), edl, file));
			} catch (Throwable t) {
				SurvivalCinemaClient.LOGGER.error("Planning the film failed", t);
				mc.execute(() -> source.sendError(Component.literal("Planning the film failed: " + t)));
			}
		}, "SurvivalCinema-Plan");
		thread.setDaemon(true);
		thread.start();
		return 1;
	}

	private static void printPlan(FabricClientCommandSource source, String worldName, FilmLength length, int sessionCount,
								  EditDecisionList edl, Path file) {
		List<EditDecisionList.Item> clips = edl.clips();
		source.sendFeedback(Component.literal(String.format(Locale.ROOT, "§eFilm of %s: %.0f s (asked for %d min), %d sessions, %d shots",
			worldName, edl.totalSeconds(), length.minutes(), sessionCount, clips.size())));
		for (EditDecisionList.Item item : edl.items()) {
			switch (item.kind()) {
				case CHAPTER -> source.sendFeedback(Component.literal("§6■ ").append(ChapterTitles.title(item))
					.append(Component.literal(item.subtitle() == null ? "" : " §7— " + item.subtitle())));
				case CLIP -> source.sendFeedback(Component.literal(String.format(Locale.ROOT, "  §7s%d %.1fs §f%s §7%s %s",
					item.sessionIndex() + 1, item.filmSeconds(), item.label(), item.shot().name().toLowerCase(Locale.ROOT),
					item.slowMotionFromTick() >= 0 ? "§b(slow-mo)" : "")));
				default -> {
				}
			}
		}
		source.sendFeedback(Component.literal("§7Saved to " + file));
	}

	/**
	 * Plans the film of this world and shoots all its clips under the cinema screen. The clips land in
	 * {@code survivalcinema/films/<world>/<date>/clips/}, then edits them into the film next to them.
	 */
	private static int render(CommandContext<FabricClientCommandSource> ctx, FilmLength length) {
		FabricClientCommandSource source = ctx.getSource();
		WorldMemory world = SurvivalCinemaClient.controller().world();
		if (world != null && FilmFolder.latestUnfinished(SessionStore.filmsFolder(world.worldKey)) != null) {
			source.sendFeedback(Component.literal("§7(An unfinished film of this world exists: /cinema render resume continues it)"));
		}
		boolean started = FilmMaker.start(length, SurvivalCinemaConfig.get().renderQuality(), new FilmMaker.Listener() {
			@Override
			public void started(RenderPipeline pipeline, int clips, double filmSeconds, RenderQuality quality) {
				source.sendFeedback(Component.literal(String.format(Locale.ROOT, "§eShooting %d clips (%.0f s of film, %s)...",
					clips, filmSeconds, quality.name().toLowerCase(Locale.ROOT))));
			}

			@Override
			public void failed(Component reason) {
				source.sendError(reason);
			}
		});
		if (started) {
			source.sendFeedback(Component.literal("§7Planning the film..."));
		}
		return started ? 1 : 0;
	}

	/** Continues the newest unfinished film of this world (after a stop or a crash) with the next clip to shoot. */
	private static int resume(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource source = ctx.getSource();
		FilmMaker.Unfinished film = FilmMaker.resume(reason -> source.sendError(reason));
		if (film == null) {
			return 0;
		}
		source.sendFeedback(Component.literal(film.clipsLeft() == 0
			? String.format(Locale.ROOT, "§eEditing %s: every clip is shot", film.film().getFileName())
			: String.format(Locale.ROOT, "§eContinuing %s: %d of %d clips left", film.film().getFileName(), film.clipsLeft(),
			film.clipCount())));
		return 1;
	}

	/**
	 * Edits the newest film of this world again from its clips (after adding your own music to
	 * {@code survivalcinema/music}, or changing the music setting), without shooting anything again.
	 */
	private static int reedit(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource source = ctx.getSource();
		Path film = FilmMaker.reedit(reason -> source.sendError(reason));
		if (film == null) {
			return 0;
		}
		source.sendFeedback(Component.literal("§eEditing " + film.getFileName() + " again"));
		return 1;
	}

	private static int marker(CommandContext<FabricClientCommandSource> ctx) {
		Minecraft mc = Minecraft.getInstance();
		String text = StringArgumentType.getString(ctx, "text");
		Vec3 pos = mc.player == null ? null : mc.player.position();
		String dim = mc.level == null ? null : mc.level.dimension().identifier().toString();
		boolean ok = FlashbackBridge.addMarker(text, 0xFFD24A, pos, dim);
		ctx.getSource().sendFeedback(Component.literal(ok ? "Marker added: " + text : "Not recording"));
		return 1;
	}

	private static int replays(CommandContext<FabricClientCommandSource> ctx) {
		List<Path> replays = FlashbackBridge.listReplays();
		ctx.getSource().sendFeedback(Component.literal(replays.size() + " replays in " + FlashbackBridge.replayFolder()));
		for (int i = 0; i < Math.min(10, replays.size()); i++) {
			ctx.getSource().sendFeedback(Component.literal(i + ": " + replays.get(i).getFileName()));
		}
		return 1;
	}

	private static int open(CommandContext<FabricClientCommandSource> ctx) {
		int index = IntegerArgumentType.getInteger(ctx, "index");
		List<Path> replays = FlashbackBridge.listReplays();
		if (index >= replays.size()) {
			ctx.getSource().sendError(Component.literal("No replay #" + index));
			return 0;
		}
		Path replay = replays.get(index);
		Minecraft.getInstance().execute(() -> FlashbackBridge.openReplay(replay));
		ctx.getSource().sendFeedback(Component.literal("Opening " + replay.getFileName()));
		return 1;
	}

	/**
	 * Queues a test clip starting at the current replay tick, with the camera built from the recorded trail.
	 */
	private static int clip(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource source = ctx.getSource();
		if (!FlashbackBridge.isReplayReady()) {
			source.sendError(Component.literal("Open a replay first (/cinema open 0)"));
			return 0;
		}
		ShotType shot;
		try {
			shot = ShotType.valueOf(StringArgumentType.getString(ctx, "shot").toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			source.sendError(Component.literal("Unknown shot. Use chase, orbit, side, static_lookat or pullback"));
			return 0;
		}
		UUID replayId = FlashbackBridge.openReplayId();
		Trail trail;
		try {
			trail = replayId == null ? null : new Trail(TrailIO.read(SessionStore.trailFile(replayId)));
		} catch (IOException e) {
			source.sendError(Component.literal("Could not read the trail: " + e.getMessage()));
			return 0;
		}
		if (trail == null || trail.isEmpty()) {
			source.sendError(Component.literal("This replay has no recorded trail. Record a new one with /cinema rec start"));
			return 0;
		}

		int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
		int start = Math.max(trail.firstTick(), FlashbackBridge.replayTick());
		int end = Math.min(trail.lastTick(), start + seconds * 20);
		List<int[]> ranges = trail.continuousRanges(start, end, 20);
		if (ranges.isEmpty()) {
			source.sendError(Component.literal("Not enough recorded trail after this point, scrub the timeline back"));
			return 0;
		}

		int bitrate = 8_000_000;
		String encoder = EncoderSelector.ready();
		if (encoder == null) {
			source.sendError(Component.literal(EncoderSelector.isChecking()
				? "Still checking which video encoder works on this PC, try again in a few seconds"
				: "No working H.264 encoder on this PC, see the log"));
			return 0;
		}
		Minecraft mc = Minecraft.getInstance();
		ClientBlockProbe probe = new ClientBlockProbe(mc.level);
		String name = shot.name().toLowerCase(Locale.ROOT);
		String stamp = LocalDateTime.now().format(STAMP);
		for (int i = 0; i < ranges.size(); i++) {
			int from = ranges.get(i)[0];
			int to = ranges.get(i)[1];
			// One clip per continuous piece: a respawn or portal becomes a cut instead of a flight across the map.
			Trail piece = trail.slice(from, to);
			List<CameraKey> planned = CameraPathBuilder.build(piece, shot, from, to, CameraPathBuilder.Params.defaults(shot));
			List<CameraKey> keys = OcclusionSolver.solve(piece, planned, probe);
			int adjusted = 0;
			for (int k = 0; k < keys.size(); k++) {
				if (keys.get(k).position().distance(planned.get(k).position()) > 0.05) {
					adjusted++;
				}
			}
			String suffix = ranges.size() > 1 ? "-part" + (i + 1) : "";
			Path output = outputFolder(mc).resolve("clip-" + name + "-" + stamp + suffix + ".mp4");
			CameraKey first = keys.getFirst();
			Vec3 firstPos = new Vec3(first.position().x(), first.position().y(), first.position().z());
			ClipQueue.add(new FlashbackBridge.ClipRequest("survivalcinema-" + name + suffix, from, to, 1280, 720, 30.0,
				bitrate, encoder, firstPos, first.yaw(), first.pitch(), output), keys);
			source.sendFeedback(Component.literal("Queued " + name + suffix + " ticks " + from + "-" + to + " (" + keys.size()
				+ " keyframes, " + adjusted + " moved around obstacles) with " + encoder));
		}
		source.sendFeedback(Component.literal(ClipQueue.size() + " clips waiting; they render one by one with a short pause"));
		return 1;
	}

	private static int ffmpeg(CommandContext<FabricClientCommandSource> ctx) {
		Minecraft mc = Minecraft.getInstance();
		Path output = outputFolder(mc).resolve("titlecard-" + LocalDateTime.now().format(STAMP) + ".mp4");
		ctx.getSource().sendFeedback(Component.literal("Rendering title card..."));
		Thread thread = new Thread(() -> {
			try {
				FfmpegProbe.renderTitleCard(output, "День 1", "Survival Cinema · проверка FFmpeg");
				mc.execute(() -> mc.gui.getChat().addMessage(Component.literal("Title card OK: " + output)));
			} catch (Throwable t) {
				SurvivalCinemaClient.LOGGER.error("Title card test failed", t);
				mc.execute(() -> mc.gui.getChat().addMessage(Component.literal("Title card FAILED: " + t)));
			}
		}, "SurvivalCinema-FfmpegProbe");
		thread.setDaemon(true);
		thread.start();
		return 1;
	}

	private static Path outputFolder(Minecraft mc) {
		return mc.gameDirectory.toPath().resolve("survivalcinema").resolve("test");
	}
}
