package com.survivalcinema.client.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.survivalcinema.client.SurvivalCinemaClient;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.Reader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Minecraft font, drawn without the game: reads the bitmap glyph sheets the game itself uses
 * ({@code font/default.json} → {@code ascii.png}, {@code accented.png}, {@code nonlatin_european.png}, including
 * resource packs) and draws text with them in Java2D, so title cards and credits look like {@code /title} and the
 * End credits. Latin and Cyrillic are in the game's sheets; anything else falls back to a system font.
 * <p>
 * Metrics follow the game: a glyph is as wide as its rightmost lit column, plus one pixel of spacing.
 */
public final class McFont {
	/** Size of the line box in font pixels: room for accents above (3) and the shadow below (1). */
	private static final int LINE_TOP = -3;
	private static final int LINE_BOTTOM = 9;

	private record Glyph(BufferedImage sheet, int x, int y, int width, int height, float scale, int ascent, int advance) {
	}

	private final Map<Integer, Glyph> glyphs = new HashMap<>();
	private final Map<Integer, Float> spaces = new HashMap<>();
	private final Font fallback = new Font(Font.SANS_SERIF, Font.PLAIN, 9);

	private McFont() {
	}

	/** Reads the default font; needs the client's resources (call on the client thread). */
	public static McFont load(ResourceManager resources) {
		McFont font = new McFont();
		try {
			font.readFont(resources, Identifier.withDefaultNamespace("default"), new HashSet<>());
		} catch (RuntimeException e) {
			SurvivalCinemaClient.LOGGER.warn("Could not read the Minecraft font, film titles use a system font", e);
		}
		if (!font.spaces.containsKey((int) ' ')) {
			font.spaces.put((int) ' ', 4f);
		}
		return font;
	}

	private void readFont(ResourceManager resources, Identifier id, Set<Identifier> seen) {
		if (!seen.add(id)) {
			return;
		}
		Identifier file = Identifier.fromNamespaceAndPath(id.getNamespace(), "font/" + id.getPath() + ".json");
		// every pack can add providers; the game reads them from the bottom pack up, the first glyph found wins
		List<Resource> stack = resources.getResourceStack(file);
		for (int i = stack.size() - 1; i >= 0; i--) {
			try (Reader reader = stack.get(i).openAsReader()) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				for (JsonElement element : json.getAsJsonArray("providers")) {
					readProvider(resources, element.getAsJsonObject(), seen);
				}
			} catch (Exception e) {
				SurvivalCinemaClient.LOGGER.warn("Skipping broken font file {}", file, e);
			}
		}
	}

	private void readProvider(ResourceManager resources, JsonObject provider, Set<Identifier> seen) throws Exception {
		if (provider.has("filter") && provider.getAsJsonObject("filter").has("uniform")
			&& provider.getAsJsonObject("filter").get("uniform").getAsBoolean()) {
			return; // only for the "force uniform font" option
		}
		switch (provider.get("type").getAsString()) {
			case "reference" -> readFont(resources, Identifier.parse(provider.get("id").getAsString()), seen);
			case "space" -> {
				for (Map.Entry<String, JsonElement> entry : provider.getAsJsonObject("advances").entrySet()) {
					int codepoint = entry.getKey().codePointAt(0);
					if (!spaces.containsKey(codepoint) && !glyphs.containsKey(codepoint)) {
						spaces.put(codepoint, entry.getValue().getAsFloat());
					}
				}
			}
			case "bitmap" -> readBitmap(resources, provider);
			default -> {
				// unihex (unifont) and ttf are left to the fallback font
			}
		}
	}

	private void readBitmap(ResourceManager resources, JsonObject provider) throws Exception {
		Identifier file = Identifier.parse(provider.get("file").getAsString());
		Identifier texture = Identifier.fromNamespaceAndPath(file.getNamespace(), "textures/" + file.getPath());
		@Nullable Resource resource = resources.getResource(texture).orElse(null);
		if (resource == null) {
			return;
		}
		BufferedImage sheet;
		try (InputStream in = resource.open()) {
			sheet = ImageIO.read(in);
		}
		if (sheet == null) {
			return;
		}
		JsonArray rows = provider.getAsJsonArray("chars");
		int height = provider.has("height") ? provider.get("height").getAsInt() : 8;
		int ascent = provider.get("ascent").getAsInt();
		int rowCount = rows.size();
		int columnCount = rows.get(0).getAsString().codePointCount(0, rows.get(0).getAsString().length());
		int cellWidth = sheet.getWidth() / columnCount;
		int cellHeight = sheet.getHeight() / rowCount;
		float scale = height / (float) cellHeight;
		for (int row = 0; row < rowCount; row++) {
			int[] codepoints = rows.get(row).getAsString().codePoints().toArray();
			for (int column = 0; column < codepoints.length; column++) {
				int codepoint = codepoints[column];
				if (codepoint == 0 || codepoint == ' ' || glyphs.containsKey(codepoint) || spaces.containsKey(codepoint)) {
					continue;
				}
				int x = column * cellWidth;
				int y = row * cellHeight;
				int width = litWidth(sheet, x, y, cellWidth, cellHeight);
				int advance = (int) (0.5f + width * scale) + 1;
				glyphs.put(codepoint, new Glyph(sheet, x, y, width, cellHeight, scale, ascent, advance));
			}
		}
	}

	/** Width up to the rightmost column with a visible pixel, like the game measures glyphs. */
	private static int litWidth(BufferedImage sheet, int x, int y, int width, int height) {
		for (int column = width - 1; column >= 0; column--) {
			for (int row = 0; row < height; row++) {
				if ((sheet.getRGB(x + column, y + row) >>> 24) != 0) {
					return column + 1;
				}
			}
		}
		return 0;
	}

	/** Width of a line in font pixels (multiply by the scale for real pixels). */
	public int width(String text) {
		float width = 0;
		for (int codepoint : text.codePoints().toArray()) {
			width += advance(codepoint);
		}
		return Math.round(width);
	}

	private float advance(int codepoint) {
		Float space = spaces.get(codepoint);
		if (space != null) {
			return space;
		}
		Glyph glyph = glyphs.get(codepoint);
		if (glyph != null) {
			return glyph.advance;
		}
		return fallbackAdvance(codepoint);
	}

	private float fallbackAdvance(int codepoint) {
		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = probe.createGraphics();
		try {
			return g.getFontMetrics(fallback).stringWidth(new String(Character.toChars(codepoint))) + 1;
		} finally {
			g.dispose();
		}
	}

	/** Height of a rendered line in font pixels. */
	public static int lineHeight() {
		return LINE_BOTTOM - LINE_TOP;
	}

	/** How far below the top of a rendered line the text's top sits, in font pixels. */
	public static int textTop() {
		return -LINE_TOP;
	}

	/**
	 * Draws one line of text into a transparent image, each font pixel as a {@code scale}×{@code scale} square,
	 * with the game's text shadow (the colour at a quarter brightness, one font pixel down and right).
	 */
	public BufferedImage render(String text, int scale, int rgb, boolean shadow) {
		int width = Math.max(1, (width(text) + 1) * scale);
		int height = lineHeight() * scale;
		BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = mask.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			float x = 0;
			for (int codepoint : text.codePoints().toArray()) {
				Glyph glyph = glyphs.get(codepoint);
				if (glyph != null && glyph.width > 0) {
					int left = Math.round(x * scale);
					int top = Math.round((-LINE_TOP + 7 - glyph.ascent) * scale);
					int drawWidth = Math.round(glyph.width * glyph.scale * scale);
					int drawHeight = Math.round(glyph.height * glyph.scale * scale);
					g.drawImage(glyph.sheet, left, top, left + drawWidth, top + drawHeight,
						glyph.x, glyph.y, glyph.x + glyph.width, glyph.y + glyph.height, null);
				} else if (glyph == null && !spaces.containsKey(codepoint)) {
					g.setFont(fallback.deriveFont(fallback.getSize2D() * scale));
					g.setColor(java.awt.Color.WHITE);
					g.drawString(new String(Character.toChars(codepoint)), Math.round(x * scale), (-LINE_TOP + 8) * scale);
				}
				x += advance(codepoint);
			}
		} finally {
			g.dispose();
		}
		return tint(mask, scale, rgb, shadow);
	}

	/** Colours the white glyph mask and puts the shadow under it. */
	private static BufferedImage tint(BufferedImage mask, int scale, int rgb, boolean shadow) {
		int width = mask.getWidth();
		int height = mask.getHeight();
		int[] alpha = mask.getRGB(0, 0, width, height, null, 0, width);
		int[] out = new int[alpha.length];
		int shadowRgb = (rgb & 0xFCFCFC) >> 2;
		if (shadow) {
			for (int y = scale; y < height; y++) {
				for (int x = scale; x < width; x++) {
					int a = alpha[(y - scale) * width + (x - scale)] >>> 24;
					if (a != 0) {
						out[y * width + x] = a << 24 | shadowRgb;
					}
				}
			}
		}
		for (int i = 0; i < alpha.length; i++) {
			int a = alpha[i] >>> 24;
			if (a == 0) {
				continue;
			}
			int below = out[i];
			if (below == 0 || a == 255) {
				out[i] = a << 24 | rgb & 0xFFFFFF;
			} else {
				out[i] = blend(below, a << 24 | rgb & 0xFFFFFF);
			}
		}
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, width, height, out, 0, width);
		return image;
	}

	private static int blend(int below, int above) {
		float a = (above >>> 24) / 255f;
		int r = Math.round(((above >> 16) & 255) * a + ((below >> 16) & 255) * (1 - a));
		int g = Math.round(((above >> 8) & 255) * a + ((below >> 8) & 255) * (1 - a));
		int b = Math.round((above & 255) * a + (below & 255) * (1 - a));
		int alpha = Math.round(255 * (a + ((below >>> 24) / 255f) * (1 - a)));
		return alpha << 24 | r << 16 | g << 8 | b;
	}
}
