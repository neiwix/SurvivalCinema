package com.survivalcinema.director.edit;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FilmDatesTest {
	private static final ZoneId ZONE = ZoneOffset.ofHours(3);
	private static final Locale RU = Locale.forLanguageTag("ru-RU");

	private static long at(int year, int month, int day) {
		return LocalDateTime.of(year, month, day, 21, 30).atZone(ZONE).toInstant().toEpochMilli();
	}

	private static String russian(long from, long to) {
		return FilmDates.range(from, to, ZONE, DateTimeFormatter.ofPattern("d MMMM yyyy", RU),
			DateTimeFormatter.ofPattern("d MMMM", RU), "%s — %s");
	}

	@Test
	void fromTheFirstDayOfPlayToTheDayOfTheFilm() {
		assertEquals("3 октября — 20 октября 2026", russian(at(2026, 10, 3), at(2026, 10, 20)));
		assertEquals("28 декабря 2025 — 3 января 2026", russian(at(2025, 12, 28), at(2026, 1, 3)));
		assertEquals("20 октября 2026", russian(at(2026, 10, 20), at(2026, 10, 20)), "played and filmed on one day");
		assertEquals("20 октября 2026", russian(0, at(2026, 10, 20)), "the start unknown: the day of the film");
		assertEquals("", russian(0, 0));
	}

	@Test
	void inEnglish() {
		String dates = FilmDates.range(at(2026, 9, 28), at(2026, 10, 20), ZONE, DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US),
			DateTimeFormatter.ofPattern("MMMM d", Locale.US), "%s – %s");
		assertEquals("September 28 – October 20, 2026", dates);
	}
}
