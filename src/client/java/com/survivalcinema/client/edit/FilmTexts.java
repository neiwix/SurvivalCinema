package com.survivalcinema.client.edit;

import com.survivalcinema.client.ui.ChapterTitles;
import com.survivalcinema.director.edit.CreditsRoll;
import com.survivalcinema.director.edit.CreditsRoll.Line;
import com.survivalcinema.director.edit.CreditsRoll.Style;
import com.survivalcinema.director.edit.FilmDates;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.FilmStats;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words on the film's title cards and in its credits, in the player's language.
 */
final class FilmTexts {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

	private FilmTexts() {
	}

	/** The intro: the world's name, what this is, who is in it and when it was played. */
	static List<FilmPainter.CardLine> intro(FilmPainter painter, EditDecisionList edl) {
		List<FilmPainter.CardLine> lines = new ArrayList<>();
		lines.add(new FilmPainter.CardLine(edl.worldName(), painter.fit(edl.worldName(), painter.px(10)), FilmPainter.WHITE));
		String kind = tr("survivalcinema.film.intro.subtitle");
		lines.add(new FilmPainter.CardLine(kind, painter.fit(kind, painter.px(4)), FilmPainter.LIGHT));
		String cast = String.join(", ", cast(edl));
		if (!cast.isEmpty()) {
			lines.add(new FilmPainter.CardLine(cast, painter.fit(cast, painter.px(3)), FilmPainter.GREY));
		}
		String dates = introDates(edl);
		if (!dates.isEmpty()) {
			lines.add(new FilmPainter.CardLine(dates, painter.fit(dates, painter.px(4)), FilmPainter.LIGHT));
		}
		return lines;
	}

	/** A chapter card like {@code /title}: "Day 3" or "The Nether", and its best moment (or the day) under it. */
	static List<FilmPainter.CardLine> chapter(FilmPainter painter, EditDecisionList.Item chapter) {
		List<FilmPainter.CardLine> lines = new ArrayList<>();
		String title = ChapterTitles.title(chapter).getString();
		lines.add(new FilmPainter.CardLine(title, painter.fit(title, painter.px(12)), FilmPainter.WHITE));
		String subtitle;
		if (chapter.dimension() != null) {
			subtitle = chapter.dayTo() > chapter.dayFrom()
				? tr("survivalcinema.chapter.days", chapter.dayFrom(), chapter.dayTo())
				: tr("survivalcinema.chapter.day", chapter.dayFrom());
		} else {
			subtitle = chapter.subtitle();
		}
		if (subtitle != null && !subtitle.isBlank() && !subtitle.equals(title)) {
			lines.add(new FilmPainter.CardLine(subtitle, painter.fit(subtitle, painter.px(6)), FilmPainter.LIGHT));
		}
		return lines;
	}

	/** The rolling credits: title, cast, the world in numbers, music, and who made the film. */
	static List<Line> credits(EditDecisionList edl, List<String> music) {
		List<Line> lines = new ArrayList<>();
		lines.add(new Line(Style.TITLE, edl.worldName()));
		lines.add(new Line(Style.SMALL, tr("survivalcinema.film.intro.subtitle")));
		lines.add(new Line(Style.GAP, ""));

		lines.add(new Line(Style.HEADING, tr("survivalcinema.film.credits.cast")));
		for (String name : cast(edl)) {
			lines.add(new Line(Style.TEXT, name));
		}

		FilmStats stats = edl.stats();
		if (stats != null) {
			lines.add(new Line(Style.GAP, ""));
			lines.add(new Line(Style.HEADING, tr("survivalcinema.film.credits.numbers")));
			stat(lines, "days", stats.days());
			if (stats.playTicks() >= 20 * 60) {
				lines.add(new Line(Style.TEXT, tr("survivalcinema.film.credits.played", playTime(stats.playTicks()))));
			}
			if (stats.distanceBlocks() >= 100) {
				lines.add(new Line(Style.TEXT, tr("survivalcinema.film.credits.distance",
					String.format(Locale.ROOT, "%.1f", stats.distanceBlocks() / 1000.0))));
			}
			stat(lines, "blocks", stats.blocksPlaced());
			stat(lines, "kills", stats.kills());
			stat(lines, "diamonds", stats.diamonds());
			stat(lines, "advancements", stats.advancements());
			stat(lines, "deaths", stats.deaths());
			for (FilmStats.Friend friend : stats.friends()) {
				if (friend.deaths() > 0) {
					lines.add(new Line(Style.TEXT, tr("survivalcinema.film.credits.friend_deaths", friend.name(), friend.deaths())));
				}
			}
			if (stats.visited("minecraft:the_nether")) {
				lines.add(new Line(Style.SMALL, tr("survivalcinema.film.credits.nether")));
			}
			if (stats.dragonKilled()) {
				lines.add(new Line(Style.SMALL, tr("survivalcinema.film.credits.dragon")));
			}
		}

		if (!music.isEmpty()) {
			lines.add(new Line(Style.GAP, ""));
			lines.add(new Line(Style.HEADING, tr("survivalcinema.film.credits.music")));
			for (String title : music) {
				lines.add(new Line(Style.TEXT, title));
			}
		}

		lines.add(new Line(Style.GAP, ""));
		lines.add(new Line(Style.HEADING, tr("survivalcinema.film.credits.made_with")));
		lines.add(new Line(Style.TEXT, "Survival Cinema"));
		lines.add(new Line(Style.SMALL, tr("survivalcinema.film.credits.flashback")));
		String dates = dates(stats);
		if (!dates.isEmpty()) {
			lines.add(new Line(Style.SMALL, dates));
		}
		return lines;
	}

	private static void stat(List<Line> lines, String key, long value) {
		if (value > 0) {
			lines.add(new Line(Style.TEXT, tr("survivalcinema.film.credits." + key, value)));
		}
	}

	private static List<String> cast(EditDecisionList edl) {
		List<String> names = new ArrayList<>();
		if (edl.playerName() != null && !edl.playerName().isBlank()) {
			names.add(edl.playerName());
		}
		if (edl.stats() != null) {
			for (FilmStats.Friend friend : edl.stats().friends()) {
				if (!names.contains(friend.name())) {
					names.add(friend.name());
				}
			}
		}
		return names;
	}

	private static String playTime(long ticks) {
		long minutes = ticks / 20 / 60;
		long hours = minutes / 60;
		return hours > 0
			? tr("survivalcinema.film.credits.hours", hours, minutes % 60)
			: tr("survivalcinema.film.credits.minutes", minutes);
	}

	/** When the friends played: "17 October – 19 October 2026", one date when it was a single day, or nothing when unknown. */
	static String dates(FilmStats stats) {
		if (stats == null || stats.firstPlayed() <= 0) {
			return "";
		}
		return range(stats.firstPlayed(), Math.max(stats.firstPlayed(), stats.lastPlayed()));
	}

	/** From the first day of play to the day the film was made, in the player's language. */
	static String introDates(EditDecisionList edl) {
		long first = edl.stats() == null ? 0 : edl.stats().firstPlayed();
		long made = edl.createdAt() > 0 ? edl.createdAt() : edl.stats() == null ? 0 : edl.stats().lastPlayed();
		return range(first, made);
	}

	private static String range(long from, long to) {
		Locale locale = locale();
		DateTimeFormatter full;
		DateTimeFormatter noYear;
		if (ownDateWords(locale)) {
			full = DateTimeFormatter.ofPattern(raw("survivalcinema.film.date", "d MMMM yyyy"), locale);
			noYear = DateTimeFormatter.ofPattern(raw("survivalcinema.film.date.no_year", "d MMMM"), locale);
		} else {
			full = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale);
			noYear = full;
		}
		try {
			return FilmDates.range(from, to, ZoneId.systemDefault(), full, noYear, raw("survivalcinema.film.dates", "%s – %s"));
		} catch (RuntimeException e) {
			return FilmDates.range(from, to, ZoneId.systemDefault(), DATE, DATE, "%s – %s"); // a broken pattern in a resource pack
		}
	}

	/** The game's language as a locale: month names come out in it. */
	private static Locale locale() {
		String code = Minecraft.getInstance().options.languageCode;
		return code == null || code.isBlank() ? Locale.ROOT : Locale.forLanguageTag(code.replace('_', '-'));
	}

	/** Languages our own date patterns are written for; the rest get Java's long date of their locale. */
	private static boolean ownDateWords(Locale locale) {
		return locale.getLanguage().equals("ru") || locale.getLanguage().equals("en");
	}

	private static String raw(String key, String fallback) {
		return Language.getInstance().getOrDefault(key, fallback);
	}

	static CreditsRoll roll(EditDecisionList edl, List<String> music, FilmPainter painter) {
		return new CreditsRoll(credits(edl, music), painter.height() / painter.creditsRowPixels());
	}

	/** "Day 12" in the player's language. */
	static String day(int day) {
		return day <= 0 ? "" : tr("survivalcinema.chapter.day", day);
	}

	private static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}
}
