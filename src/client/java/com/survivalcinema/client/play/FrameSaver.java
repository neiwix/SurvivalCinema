package com.survivalcinema.client.play;

import com.mojang.blaze3d.platform.NativeImage;
import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.director.play.PlayTime;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Saves a moment of the film as a picture, at the film's own size (the cinema decodes it no bigger than the window), into
 * the game's screenshots folder. Read from the file again on a thread of its own, so the film plays on meanwhile.
 */
public final class FrameSaver {
	private FrameSaver() {
	}

	/** @param done gets the saved file, or null when it could not be saved (on the saving thread) */
	public static void save(Path film, double seconds, Path folder, Consumer<Path> done) {
		Thread thread = new Thread(() -> done.accept(write(film, seconds, folder)), "SurvivalCinema-Frame");
		thread.setDaemon(true);
		thread.start();
	}

	private static Path write(Path film, double seconds, Path folder) {
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(film.toFile())) {
			grabber.setPixelFormat(avutil.AV_PIX_FMT_RGBA);
			grabber.start();
			grabber.setTimestamp(Math.round(seconds * 1e6));
			Frame frame = grabber.grabImage();
			if (frame == null || frame.image == null || frame.image[0] == null) {
				return null;
			}
			int width = frame.imageWidth;
			int height = frame.imageHeight;
			try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false)) {
				ByteBuffer pixels = (ByteBuffer) frame.image[0];
				long from = MemoryUtil.memAddress0(pixels);
				int row = width * 4;
				int stride = frame.imageStride > 0 ? frame.imageStride : row;
				for (int y = 0; y < height; y++) {
					MemoryUtil.memCopy(from + (long) y * stride, image.getPointer() + (long) y * row, row);
				}
				Files.createDirectories(folder);
				String name = film.getFileName().toString().replaceFirst("\\.[^.]+$", "");
				Path file = folder.resolve(name + " " + PlayTime.format(seconds).replace(':', '-') + ".png");
				for (int i = 2; Files.exists(file); i++) {
					file = folder.resolve(name + " " + PlayTime.format(seconds).replace(':', '-') + " (" + i + ").png");
				}
				image.writeToFile(file);
				return file;
			}
		} catch (Exception e) {
			SurvivalCinemaClient.LOGGER.warn("Could not save a frame of {} at {} s", film, seconds, e);
			return null;
		}
	}
}
