package com.survivalcinema.director.edit;

import java.util.List;

/**
 * The closing credits, rolling up like the ones after the End: lines enter from the bottom of the picture, and the
 * roll stops when its last line reaches the middle, then everything fades out.
 * <p>
 * Sizes are in "rows" (the height of a normal text line), so the same roll fits any video size.
 */
public final class CreditsRoll {
	public enum Style {
		/** The film's title. */
		TITLE(3.2),
		HEADING(1.9),
		TEXT(1.35),
		SMALL(1.1),
		GAP(0.9);

		/** Height of the line in rows, spacing included. */
		public final double rows;

		Style(double rows) {
			this.rows = rows;
		}
	}

	public record Line(Style style, String text) {
	}

	/** How fast the roll moves, rows per second. */
	static final double SPEED_ROWS_PER_SECOND = 2.6;
	/** How long the last line stays in the middle before the fade. */
	static final double HOLD_SECONDS = 3;

	private final List<Line> lines;
	private final double screenRows;

	/**
	 * @param screenRows how many rows fit in the picture's height
	 */
	public CreditsRoll(List<Line> lines, double screenRows) {
		this.lines = List.copyOf(lines);
		this.screenRows = screenRows;
	}

	public List<Line> lines() {
		return lines;
	}

	public double heightRows() {
		return lines.stream().mapToDouble(l -> l.style().rows).sum();
	}

	/** Rows the roll travels: from just below the picture until its last line sits in the middle. */
	private double travelRows() {
		return screenRows + heightRows() - screenRows / 2 - lines.getLast().style().rows / 2;
	}

	/** How long the credits need, at least {@code minimumSeconds}. */
	public double seconds(double minimumSeconds) {
		if (lines.isEmpty()) {
			return minimumSeconds;
		}
		return Math.max(minimumSeconds, travelRows() / SPEED_ROWS_PER_SECOND + HOLD_SECONDS);
	}

	/**
	 * Where the top of the roll is at a moment, in rows from the top of the picture (starts at {@code screenRows},
	 * just below it, and decreases).
	 */
	public double topAt(double seconds) {
		if (lines.isEmpty()) {
			return screenRows;
		}
		double travelled = Math.min(travelRows(), Math.max(0, seconds) * SPEED_ROWS_PER_SECOND);
		return screenRows - travelled;
	}
}
