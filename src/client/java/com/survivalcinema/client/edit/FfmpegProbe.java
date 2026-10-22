package com.survivalcinema.client.edit;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase 0 check: can we encode our own video (title cards) with the FFmpeg that ships inside Flashback?
 * Renders a 3 second fading title card to an MP4.
 */
public final class FfmpegProbe {
	private FfmpegProbe() {
	}

	public static void renderTitleCard(Path output, String title, String subtitle) throws Exception {
		Files.createDirectories(output.getParent());
		int width = 1280;
		int height = 720;
		int fps = 30;
		int frames = fps * 3;

		try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output.toFile(), width, height, 0);
			 Java2DFrameConverter converter = new Java2DFrameConverter()) {
			recorder.setFormat("mp4");
			String encoder = EncoderSelector.pick(width, height, 4_000_000);
			if (encoder != null) {
				recorder.setVideoCodecName(encoder);
			} else {
				recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
			}
			recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
			recorder.setFrameRate(fps);
			recorder.setVideoBitrate(4_000_000);
			recorder.start();

			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
			for (int i = 0; i < frames; i++) {
				float t = i / (float) (frames - 1);
				float alpha = Math.min(1f, Math.min(t / 0.25f, (1f - t) / 0.25f));
				drawCard(image, title, subtitle, alpha);
				recorder.record(converter.convert(image));
			}
			recorder.stop();
		}
	}

	private static void drawCard(BufferedImage image, String title, String subtitle, float alpha) {
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());

			int a = Math.round(alpha * 255);
			g.setColor(new Color(255, 255, 255, a));
			g.setFont(new Font(Font.SERIF, Font.BOLD, 72));
			drawCentered(g, title, image.getWidth(), image.getHeight() / 2);

			g.setColor(new Color(200, 200, 200, a));
			g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 32));
			drawCentered(g, subtitle, image.getWidth(), image.getHeight() / 2 + 70);
		} finally {
			g.dispose();
		}
	}

	private static void drawCentered(Graphics2D g, String text, int width, int baselineY) {
		FontMetrics metrics = g.getFontMetrics();
		g.drawString(text, (width - metrics.stringWidth(text)) / 2, baselineY);
	}
}
