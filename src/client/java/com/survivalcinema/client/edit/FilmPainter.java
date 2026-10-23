package com.survivalcinema.client.edit;

import com.survivalcinema.director.edit.CreditsRoll;
import org.jetbrains.annotations.Nullable;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the film's own pictures: the intro, chapter cards in the style of {@code /title}, and the rolling credits,
 * in the Minecraft font over a blurred, darkened still of the footage they belong to.
 * <p>
 * A chapter card shows the first frame of its chapter out of focus; at its end the picture pulls focus into that
 * very frame, so the cut into the clip is invisible.
 */
final class FilmPainter {
	static final int WHITE = 0xFFFFFF;
	static final int LIGHT = 0xE0E0E0;
	static final int GREY = 0xA8A8A8;
	static final int YELLOW = 0xFFFF55;

	static final double TEXT_OUT_BEFORE_END = 1.0;
	static final double TEXT_OUT_SECONDS = 0.5;
	static final double FOCUS_PULL_SECONDS = 0.6;
	/** How much of the still is left on a card, and under the credits. */
	static final float CARD_BACKDROP = 0.45f;
	static final float CREDITS_BACKDROP = 0.22f;

	/** A line of a title card: text, its size in font pixels (scale) and colour. */
	record CardLine(String text, int scale, int rgb) {
	}

	private final McFont font;
	private final int width;
	private final int height;
	private final BufferedImage frame;
	private final Map<String, BufferedImage> rendered = new HashMap<>();

	FilmPainter(McFont font, int width, int height) {
		this.font = font;
		this.width = width;
		this.height = height;
		this.frame = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
	}

	int width() {
		return width;
	}

	int height() {
		return height;
	}

	/** A size in pixels of a 720p picture, for this picture. */
	int px(double at720p) {
		return Math.max(1, (int) Math.round(at720p * height / 720.0));
	}

	/** Font scale for a line that must fit the picture's width. */
	int fit(String text, int scale) {
		int maxWidth = (int) (width * 0.9);
		int s = scale;
		while (s > 1 && (font.width(text) + 1) * s > maxWidth) {
			s--;
		}
		return s;
	}

	/** The backdrop is blurred at this fraction of the picture's width, then smoothly enlarged. */
	static final int BLUR_SHRINK = 16;
	/** The blur's sigma at that size, in its pixels (about 40 pixels of a 720p picture). */
	static final double BLUR_SIGMA = 2.2;
	/** A dark still (night, a cave) is darkened less, so the card does not sit on a black smudge. */
	static final double BACKDROP_TARGET_LUMA = 0.2;
	static final float MAX_BACKDROP_KEEP = 0.85f;
	/** Noise against banding in the smooth dark gradients (levels of 255, either way). */
	static final double DITHER = 1.5;

	/**
	 * A still blurred like an out-of-focus lens and darkened, or plain black without a still. The blur is a real
	 * Gaussian on a small copy, enlarged smoothly (a tiny copy blown up showed its pixels as soft squares), with a
	 * little noise so the gradients do not break into bands once encoded.
	 *
	 * @param keep how bright the still stays at least, 0..1; dark stills keep more of their light
	 */
	BufferedImage backdrop(@Nullable BufferedImage still, float keep) {
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		if (still == null) {
			return out; // black
		}
		int w = Math.max(8, width / BLUR_SHRINK);
		int h = Math.max(4, w * height / width);
		BufferedImage small = still;
		int sw = still.getWidth();
		int sh = still.getHeight();
		while (sw / 2 >= w * 2) { // halving averages every pixel, unlike one big step down
			sw /= 2;
			sh = Math.max(1, sh / 2);
			small = scaled(small, sw, sh);
		}
		small = scaled(small, w, h);
		float[][] channels = blur(small, BLUR_SIGMA);
		double luma = 0;
		for (int i = 0; i < w * h; i++) {
			luma += (0.299 * channels[0][i] + 0.587 * channels[1][i] + 0.114 * channels[2][i]) / 255;
		}
		luma /= w * h;
		// as bright as the card's backdrop would be on an average picture, within limits
		double target = BACKDROP_TARGET_LUMA * keep / CARD_BACKDROP;
		double light = luma <= 0 ? keep : Math.max(keep, Math.min(Math.min(MAX_BACKDROP_KEEP, keep * 1.9), target / luma));
		BufferedImage blurred = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < w * h; i++) {
			int r = (int) Math.round(Math.min(255, channels[0][i] * light));
			int gr = (int) Math.round(Math.min(255, channels[1][i] * light));
			int b = (int) Math.round(Math.min(255, channels[2][i] * light));
			blurred.setRGB(i % w, i / w, r << 16 | gr << 8 | b);
		}
		Graphics2D g = out.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(blurred, 0, 0, width, height, null);
		} finally {
			g.dispose();
		}
		dither(out);
		return out;
	}

	/** A separable Gaussian blur of an image's colour channels, the edges repeated outwards. */
	private static float[][] blur(BufferedImage image, double sigma) {
		int w = image.getWidth();
		int h = image.getHeight();
		int radius = (int) Math.ceil(sigma * 3);
		float[] kernel = new float[radius * 2 + 1];
		float sum = 0;
		for (int i = -radius; i <= radius; i++) {
			kernel[i + radius] = (float) Math.exp(-0.5 * i * i / (sigma * sigma));
			sum += kernel[i + radius];
		}
		for (int i = 0; i < kernel.length; i++) {
			kernel[i] /= sum;
		}
		int[] rgb = image.getRGB(0, 0, w, h, null, 0, w);
		float[][] channels = new float[3][w * h];
		for (int i = 0; i < w * h; i++) {
			channels[0][i] = rgb[i] >> 16 & 0xFF;
			channels[1][i] = rgb[i] >> 8 & 0xFF;
			channels[2][i] = rgb[i] & 0xFF;
		}
		float[] tmp = new float[w * h];
		for (float[] channel : channels) {
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					float v = 0;
					for (int k = -radius; k <= radius; k++) {
						v += kernel[k + radius] * channel[y * w + Math.max(0, Math.min(w - 1, x + k))];
					}
					tmp[y * w + x] = v;
				}
			}
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					float v = 0;
					for (int k = -radius; k <= radius; k++) {
						v += kernel[k + radius] * tmp[Math.max(0, Math.min(h - 1, y + k)) * w + x];
					}
					channel[y * w + x] = v;
				}
			}
		}
		return channels;
	}

	/** Fine noise over the picture, the same every time (the backdrop stands still, so does its grain). */
	private static void dither(BufferedImage image) {
		int w = image.getWidth();
		int h = image.getHeight();
		int[] rgb = image.getRGB(0, 0, w, h, null, 0, w);
		java.util.Random random = new java.util.Random(42);
		for (int i = 0; i < rgb.length; i++) {
			int noise = (int) Math.round((random.nextDouble() * 2 - 1) * DITHER);
			int r = Math.max(0, Math.min(255, (rgb[i] >> 16 & 0xFF) + noise));
			int g = Math.max(0, Math.min(255, (rgb[i] >> 8 & 0xFF) + noise));
			int b = Math.max(0, Math.min(255, (rgb[i] & 0xFF) + noise));
			rgb[i] = r << 16 | g << 8 | b;
		}
		image.setRGB(0, 0, w, h, rgb, 0, w);
	}

	/** The still at the film's size, sharp (for the focus pull). */
	BufferedImage sharp(BufferedImage still) {
		return still.getWidth() == width && still.getHeight() == height ? still : scaled(still, width, height);
	}

	private static BufferedImage scaled(BufferedImage source, int w, int h) {
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(source, 0, 0, w, h, null);
		} finally {
			g.dispose();
		}
		return out;
	}

	/** The intro starts typing after this long, with the cursor already blinking where the title will be. */
	static final double TYPE_FROM = 0.35;
	/** Typing is over by this share of the card at the latest; the words then stay a moment before they fade. */
	static final double TYPE_BY = 0.6;
	/** Seconds per letter at most (long texts type faster to fit), the title's letters this many times slower. */
	static final double TYPE_LETTER_SECONDS = 0.05;
	static final double TITLE_LETTER_WEIGHT = 1.8;
	static final double TYPE_LINE_PAUSE = 0.2;
	static final double CURSOR_BLINK_SECONDS = 0.5;

	/**
	 * One frame of the intro or a chapter card, typed out letter by letter like a chat message, with Minecraft's
	 * blinking "_" cursor: the title slowly, the lines under it quicker, then everything fades (into the next clip's
	 * first frame, {@code focus}, when there is one).
	 */
	BufferedImage typed(BufferedImage backdrop, @Nullable BufferedImage focus, List<CardLine> lines, double centerY, double t,
						double seconds, double brightness) {
		Graphics2D g = begin(backdrop, brightness);
		try {
			if (focus != null) {
				double pull = ease(clamp((t - (seconds - FOCUS_PULL_SECONDS)) / FOCUS_PULL_SECONDS));
				if (pull > 0) {
					g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (pull * brightness)));
					g.drawImage(focus, 0, 0, null);
				}
			}
			double text = 1 - ease(clamp((t - (seconds - TEXT_OUT_BEFORE_END)) / TEXT_OUT_SECONDS));
			if (text <= 0 || lines.isEmpty()) {
				return frame;
			}
			int n = lines.size();
			int[] lengths = new int[n];
			double units = 0;
			for (int i = 0; i < n; i++) {
				lengths[i] = (int) lines.get(i).text().codePoints().count();
				units += lengths[i] * (i == 0 ? TITLE_LETTER_WEIGHT : 1);
			}
			double budget = seconds * TYPE_BY - TYPE_FROM - TYPE_LINE_PAUSE * (n - 1);
			double perUnit = Math.min(TYPE_LETTER_SECONDS, Math.max(0.005, budget / Math.max(1, units)));
			int[] shown = new int[n];
			int cursorLine = 0;
			double clock = t - TYPE_FROM;
			boolean typing = false;
			for (int i = 0; i < n && clock > 0; i++) {
				double letter = perUnit * (i == 0 ? TITLE_LETTER_WEIGHT : 1);
				shown[i] = Math.min(lengths[i], (int) Math.ceil(clock / letter));
				cursorLine = i;
				typing = shown[i] < lengths[i];
				clock -= lengths[i] * letter + TYPE_LINE_PAUSE;
			}
			// the cursor stands still while letters appear and blinks while it waits
			boolean cursor = typing || (t % (2 * CURSOR_BLINK_SECONDS)) < CURSOR_BLINK_SECONDS;

			int gap = px(10);
			int total = gap * (n - 1);
			for (CardLine line : lines) {
				total += line(line.text(), line.scale(), line.rgb()).getHeight();
			}
			int y = (int) Math.round(centerY * height - total / 2.0);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) clamp(text * brightness)));
			for (int i = 0; i < n; i++) {
				CardLine line = lines.get(i);
				BufferedImage full = line(line.text(), line.scale(), line.rgb());
				int x = (width - full.getWidth()) / 2;
				String typedText = line.text().codePoints().limit(shown[i])
					.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
				if (!typedText.isEmpty()) {
					g.drawImage(line(typedText, line.scale(), line.rgb()), x, y, null);
				}
				if (cursor && i == cursorLine) {
					g.drawImage(line("_", line.scale(), line.rgb()), x + font.width(typedText) * line.scale(), y, null);
				}
				y += full.getHeight() + gap;
			}
		} finally {
			g.dispose();
		}
		return frame;
	}

	/** Lines centred on the picture around {@code centerY} (0..1 of the height), with small gaps between them. */
	private void drawLines(Graphics2D g, List<CardLine> lines, double centerY, float alpha) {
		List<BufferedImage> images = new ArrayList<>();
		int total = 0;
		int gap = px(10);
		for (CardLine line : lines) {
			BufferedImage image = line(line.text(), line.scale(), line.rgb());
			images.add(image);
			total += image.getHeight();
		}
		total += gap * Math.max(0, lines.size() - 1);
		int y = (int) Math.round(centerY * height - total / 2.0);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0, Math.min(1, alpha))));
		for (BufferedImage image : images) {
			g.drawImage(image, (width - image.getWidth()) / 2, y, null);
			y += image.getHeight() + gap;
		}
	}

	/**
	 * One frame of the credits.
	 *
	 * @param t seconds since the credits started
	 */
	BufferedImage credits(BufferedImage backdrop, CreditsRoll roll, double rowPixels, double t, double brightness) {
		Graphics2D g = begin(backdrop, brightness);
		try {
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) brightness));
			double rowTop = roll.topAt(t);
			for (CreditsRoll.Line line : roll.lines()) {
				double lineRows = line.style().rows;
				if (line.style() != CreditsRoll.Style.GAP && !line.text().isEmpty()) {
					BufferedImage image = line(line.text(), creditsScale(line), creditsColour(line.style()));
					double middle = (rowTop + lineRows / 2) * rowPixels;
					int y = (int) Math.round(middle - (McFont.textTop() + 4) * image.getHeight() / (double) McFont.lineHeight());
					if (y + image.getHeight() >= 0 && y < height) {
						g.drawImage(image, (width - image.getWidth()) / 2, y, null);
					}
				}
				rowTop += lineRows;
			}
		} finally {
			g.dispose();
		}
		return frame;
	}

	/**
	 * One frame of the base timelapse: a picture of the base, dissolving into the next one, and the day in the corner.
	 *
	 * @param blend how far into the next picture, 0..1
	 */
	BufferedImage timelapse(BufferedImage picture, @Nullable BufferedImage next, double blend, String caption, double brightness) {
		Graphics2D g = begin(sharp(picture), brightness);
		try {
			if (next != null && blend > 0) {
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (ease(clamp(blend)) * brightness)));
				g.drawImage(sharp(next), 0, 0, null);
			}
			if (!caption.isEmpty()) {
				BufferedImage text = line(caption, Math.max(2, px(4)), WHITE);
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) (0.9 * brightness)));
				g.drawImage(text, px(28), height - px(28) - text.getHeight(), null);
			}
		} finally {
			g.dispose();
		}
		return frame;
	}

	/** Text scale of the normal credits line; a row of the roll is this line's height. */
	int creditsTextScale() {
		return Math.max(2, (int) Math.round(height / 180.0));
	}

	double creditsRowPixels() {
		return creditsTextScale() * McFont.lineHeight();
	}

	private int creditsScale(CreditsRoll.Line line) {
		int text = creditsTextScale();
		int scale = switch (line.style()) {
			case TITLE -> text * 3;
			case HEADING -> (int) Math.round(text * 1.5);
			case TEXT, GAP -> text;
			case SMALL -> Math.max(1, text - 1);
		};
		return fit(line.text(), scale);
	}

	private static int creditsColour(CreditsRoll.Style style) {
		return switch (style) {
			case TITLE -> WHITE;
			case HEADING -> YELLOW;
			case TEXT, GAP -> LIGHT;
			case SMALL -> GREY;
		};
	}

	private Graphics2D begin(BufferedImage backdrop, double brightness) {
		Graphics2D g = frame.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.setComposite(AlphaComposite.Src);
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, width, height);
		if (brightness > 0) {
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, brightness)));
			g.drawImage(backdrop, 0, 0, null);
		}
		return g;
	}

	private BufferedImage line(String text, int scale, int rgb) {
		return rendered.computeIfAbsent(scale + "|" + rgb + "|" + text, key -> font.render(text, scale, rgb, true));
	}

	private static double clamp(double v) {
		return Math.max(0, Math.min(1, v));
	}

	/** Smoothstep: starts and ends gently. */
	private static double ease(double v) {
		return v * v * (3 - 2 * v);
	}
}
