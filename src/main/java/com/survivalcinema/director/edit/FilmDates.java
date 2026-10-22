package com.survivalcinema.director.edit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Dates on the film's title: when the playthrough began and when the film was made, written the way the player's
 * language writes dates ("3 октября – 20 октября 2026", "October 3 – October 20, 2026").
 */
public final class FilmDates {
	private FilmDates() {
	}

	/**
	 * @param full   a whole date
	 * @param noYear a date without its year, for the first date of a range within one year
	 * @param range  joins two dates, e.g. {@code "%s – %s"}
	 * @return one date when both are the same day, or "" when the first is unknown
	 */
	public static String range(long fromMillis, long toMillis, ZoneId zone, DateTimeFormatter full, DateTimeFormatter noYear,
							   String range) {
		if (fromMillis <= 0) {
			return toMillis > 0 ? full.format(day(toMillis, zone)) : "";
		}
		LocalDate from = day(fromMillis, zone);
		LocalDate to = day(Math.max(fromMillis, toMillis), zone);
		if (from.equals(to)) {
			return full.format(from);
		}
		String first = from.getYear() == to.getYear() ? noYear.format(from) : full.format(from);
		return String.format(range, first, full.format(to));
	}

	private static LocalDate day(long millis, ZoneId zone) {
		return LocalDate.ofInstant(Instant.ofEpochMilli(millis), zone);
	}
}
