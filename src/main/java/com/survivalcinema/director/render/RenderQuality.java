package com.survivalcinema.director.render;

/**
 * Video settings of the clips. A draft renders several times faster, for checking the film before the real thing.
 */
public enum RenderQuality {
	DRAFT(1280, 720, 30, 8_000_000),
	CINEMA(1920, 1080, 60, 20_000_000);

	public final int width;
	public final int height;
	public final int framerate;
	public final int bitrate;

	RenderQuality(int width, int height, int framerate, int bitrate) {
		this.width = width;
		this.height = height;
		this.framerate = framerate;
		this.bitrate = bitrate;
	}

	/** Lenient parsing for config files: anything unknown is a draft. */
	public static RenderQuality parse(String name) {
		if (name != null) {
			for (RenderQuality quality : values()) {
				if (quality.name().equalsIgnoreCase(name.trim())) {
					return quality;
				}
			}
		}
		return DRAFT;
	}
}
