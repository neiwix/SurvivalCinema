package com.survivalcinema.client.edit;

import com.moulberry.flashback.combo_options.VideoCodec;
import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.SessionStore;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import net.minecraft.util.Util;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Picks an H.264 encoder that really works on this PC.
 * <p>
 * Flashback's own encoder list only runs a quick open test, so a hardware encoder can pass it and then fail on the
 * real export (which crashes the game, because the export runs inside the main loop). Here every candidate encodes a
 * few frames with the same kind of settings as a real export (yuv420p video + AAC stereo audio) before we trust it.
 */
public final class EncoderSelector {
	private static final String SOFTWARE_FALLBACK = "libopenh264";
	/** Media Foundation needs a multithreaded COM apartment, which Minecraft's render thread (where exports run) is not. */
	private static final Set<String> NEVER_IN_EXPORTS = Set.of("h264_mf");
	/** The check runs once, on these settings (the biggest we export), in the background at startup. */
	private static final int CHECK_WIDTH = 1920;
	private static final int CHECK_HEIGHT = 1080;
	private static final int CHECK_BITRATE = 20_000_000;
	private static @Nullable String cached;
	private static @Nullable String cachedKey;
	private static volatile @Nullable CompletableFuture<String> warmUp;

	private EncoderSelector() {
	}

	/**
	 * Starts checking the encoders on a background thread, so the game never freezes on a slow hardware encoder.
	 */
	public static void startWarmUp() {
		if (warmUp == null) {
			noteCrashedEncoder();
			warmUp = CompletableFuture.supplyAsync(() -> {
				enterRenderThreadComApartment();
				return pick(CHECK_WIDTH, CHECK_HEIGHT, CHECK_BITRATE);
			}, runnable -> {
				Thread thread = new Thread(runnable, "SurvivalCinema-EncoderCheck");
				thread.setDaemon(true);
				thread.start();
			});
		}
	}

	/** The verified encoder, or null while the check is still running (or found nothing). Never blocks. */
	public static @Nullable String ready() {
		CompletableFuture<String> future = warmUp;
		return future != null && future.isDone() && !future.isCompletedExceptionally() ? future.join() : null;
	}

	public static boolean isChecking() {
		CompletableFuture<String> future = warmUp;
		return future != null && !future.isDone();
	}

	/** Blocking check; only call from a background thread. */
	public static synchronized @Nullable String pick(int width, int height, int bitrate) {
		String key = width + "x" + height + "@" + bitrate;
		if (cached != null && key.equals(cachedKey)) {
			return cached;
		}
		for (String encoder : candidates()) {
			if (works(encoder, width, height, bitrate)) {
				SurvivalCinemaClient.LOGGER.info("Using H.264 encoder {}", encoder);
				cached = encoder;
				cachedKey = key;
				return encoder;
			}
		}
		SurvivalCinemaClient.LOGGER.error("No working H.264 encoder found");
		return null;
	}

	public static List<String> candidates() {
		Set<String> ordered = new LinkedHashSet<>(List.of(VideoCodec.H264.getEncoders()));
		ordered.add(SOFTWARE_FALLBACK);
		ordered.removeAll(NEVER_IN_EXPORTS);
		ordered.removeAll(blocked());
		return new ArrayList<>(ordered);
	}

	// ---- encoders that took the game down -----------------------------------------------------------------------
	/*
	 * A hardware encoder can pass the check above and still crash the game later in a long export (an old driver, the
	 * graphics card out of memory). That crash happens inside FFmpeg and cannot be caught. So while a clip is being
	 * encoded its encoder's name is written down, and removed when the clip is done. Found at the next start, it means
	 * the game died encoding: that encoder is never used again, and the film continues with the next one (in the end
	 * the software encoder, which is never blocked).
	 */
	private static final String IN_USE_FILE = "encoder-in-use.txt";
	private static final String BLOCKED_FILE = "blocked-encoders.txt";
	private static volatile @Nullable String crashed;

	private static Path folder() {
		return SessionStore.root();
	}

	/** Called just before Flashback starts encoding a clip with this encoder. */
	public static void encoding(String encoder) {
		try {
			Files.createDirectories(folder());
			Files.writeString(folder().resolve(IN_USE_FILE), encoder, StandardCharsets.UTF_8);
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.warn("Could not note the encoder in use", e);
		}
	}

	/** Called when the clip's export is over, however it went. */
	public static void encodingDone() {
		try {
			Files.deleteIfExists(folder().resolve(IN_USE_FILE));
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.warn("Could not clear the encoder in use", e);
		}
	}

	/** The encoder the game crashed with last time, once (to tell the player), or null. */
	public static @Nullable String takeCrashed() {
		String name = crashed;
		crashed = null;
		return name;
	}

	private static void noteCrashedEncoder() {
		Path inUse = folder().resolve(IN_USE_FILE);
		try {
			if (!Files.exists(inUse)) {
				return;
			}
			String encoder = Files.readString(inUse, StandardCharsets.UTF_8).strip();
			Files.deleteIfExists(inUse);
			if (encoder.isEmpty() || encoder.equals(SOFTWARE_FALLBACK)) {
				return; // the last resort stays: the crash was most likely something else
			}
			Set<String> blocked = new LinkedHashSet<>(blocked());
			if (blocked.add(encoder)) {
				Files.writeString(folder().resolve(BLOCKED_FILE), String.join("\n", blocked) + "\n", StandardCharsets.UTF_8);
			}
			crashed = encoder;
			SurvivalCinemaClient.LOGGER.warn("The game stopped while encoding with {}: it is not used any more (delete {} to try it again)",
				encoder, folder().resolve(BLOCKED_FILE));
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.warn("Could not read the encoder in use", e);
		}
	}

	private static Set<String> blocked() {
		try {
			Path file = folder().resolve(BLOCKED_FILE);
			if (!Files.exists(file)) {
				return Set.of();
			}
			Set<String> names = new LinkedHashSet<>();
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (!line.isBlank()) {
					names.add(line.strip());
				}
			}
			return names;
		} catch (IOException e) {
			return Set.of();
		}
	}

	/** Puts a thread that encodes video in the same COM apartment the encoders were checked in. */
	public static void prepareEncodingThread() {
		enterRenderThreadComApartment();
	}

	/**
	 * Flashback encodes on the render thread, which Windows runs in a single-threaded COM apartment (STA). Encoders
	 * built on COM behave differently in other threads: Windows' Media Foundation encoder passed the check here in
	 * the background and then crashed the game on the real export ("COM must not be in STA mode"). So the checking
	 * thread joins an STA too, and sees the encoders the way the export will.
	 */
	private static void enterRenderThreadComApartment() {
		if (Util.getPlatform() != Util.OS.WINDOWS) {
			return;
		}
		try {
			int result = Native.load("ole32", Ole32.class).CoInitializeEx(Pointer.NULL, COINIT_APARTMENTTHREADED);
			SurvivalCinemaClient.LOGGER.debug("CoInitializeEx for the encoder check returned {}", result);
		} catch (Throwable t) {
			SurvivalCinemaClient.LOGGER.warn("Could not set up COM for the encoder check", t);
		}
	}

	private static final int COINIT_APARTMENTTHREADED = 0x2;

	private interface Ole32 extends Library {
		int CoInitializeEx(Pointer reserved, int coInit);
	}

	private static boolean works(String encoder, int width, int height, int bitrate) {
		Path temp = null;
		try {
			temp = Files.createTempFile("survivalcinema-encoder-", ".mp4");
			try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(temp.toFile(), width, height, 2)) {
				recorder.setFormat("mp4");
				recorder.setVideoCodecName(encoder);
				recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
				recorder.setFrameRate(30);
				recorder.setVideoBitrate(bitrate);
				recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
				recorder.setSampleRate(48000);
				recorder.setAudioChannels(2);
				recorder.start();

				Frame frame = new Frame(width, height, Frame.DEPTH_UBYTE, 3);
				ByteBuffer pixels = (ByteBuffer) frame.image[0];
				for (int i = 0; i < 5; i++) {
					pixels.clear();
					while (pixels.hasRemaining()) {
						pixels.put((byte) (i * 40));
					}
					pixels.flip();
					recorder.record(frame, avutil.AV_PIX_FMT_BGR24);
				}
				recorder.stop();
			}
			return Files.size(temp) > 0;
		} catch (Throwable t) {
			SurvivalCinemaClient.LOGGER.info("H.264 encoder {} does not work here: {}", encoder, t.toString());
			return false;
		} finally {
			if (temp != null) {
				try {
					Files.deleteIfExists(temp);
				} catch (Exception ignored) {
				}
			}
		}
	}
}
