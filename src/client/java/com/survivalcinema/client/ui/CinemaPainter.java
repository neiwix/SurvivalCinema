package com.survivalcinema.client.ui;

import com.survivalcinema.client.render.CinemaStatus;
import com.survivalcinema.client.render.RenderPipeline;
import net.minecraft.network.chat.Component;

/**
 * Draws the "shooting the film" picture: black like a cinema hall, Minecraft font, a vanilla-style progress bar.
 * Everything is placed around the middle of the screen in GUI units, so the GUI and the export window match.
 */
final class CinemaPainter {
	/** Where the cinema screen puts its buttons, below the middle of the screen. */
	static final int BUTTONS_Y = 40;

	private static final int BLACK = 0xFF000000;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int LIGHT = 0xFFE0E0E0;
	private static final int GREY = 0xFFA0A0A0;
	private static final int DIM = 0xFF707070;
	private static final int FAINT = 0xFF454545;
	private static final int BAR_FRAME = 0xFFFFFFFF;
	private static final int BAR_FILL = 0xFFFFFFFF;
	private static final long HOLD_TO_CANCEL_MILLIS = 3000;

	private CinemaPainter() {
	}

	/**
	 * @param escHeldMillis how long Esc has been held during an export (Flashback cancels at three seconds), or 0
	 * @param exporting     a clip is being rendered right now
	 */
	static void paint(CinemaCanvas canvas, CinemaStatus status, long escHeldMillis, boolean exporting) {
		int width = canvas.width();
		int height = canvas.height();
		int cx = width / 2;
		int cy = height / 2;
		canvas.fill(0, 0, width, height, BLACK);

		RenderPipeline.Phase phase = status.phase();
		boolean working = !phase.isFinal() && phase != RenderPipeline.Phase.CONFIRM_STOP;

		boolean allShot = status.shotClips() + status.skippedClips() >= status.clipCount();
		String title = tr(switch (phase) {
			case CONFIRM_STOP -> status.editing() ? "survivalcinema.cinema.confirm.edit_title" : "survivalcinema.cinema.confirm.title";
			case DONE -> status.hasFilm() ? "survivalcinema.cinema.done.film_title" : "survivalcinema.cinema.done.title";
			case STOPPED -> allShot ? "survivalcinema.cinema.stopped.edit_title" : "survivalcinema.cinema.stopped.title";
			case EDITING -> "survivalcinema.cinema.edit_title";
			case FAILED -> "survivalcinema.cinema.failed.title";
			default -> "survivalcinema.cinema.title";
		});
		int titleWidth = canvas.textWidth(title) * 2;
		canvas.text(title, cx - titleWidth / 2, cy - 52, WHITE, 2);
		if (working) {
			// "..." that slowly fills up, without moving the title
			String dots = ".".repeat((int) (System.currentTimeMillis() / 500 % 3) + 1);
			canvas.text(dots, cx + titleWidth / 2, cy - 52, WHITE, 2);
		}
		// the length asked for while shooting; once the film is made its real length is shown below instead
		boolean made = phase == RenderPipeline.Phase.DONE && status.hasFilm();
		centered(canvas, made ? tr("survivalcinema.player.title", status.worldName())
			: tr("survivalcinema.cinema.subtitle", status.worldName(), status.filmMinutes()), cx, cy - 30, GREY);

		if (phase != RenderPipeline.Phase.FAILED) {
			progressBar(canvas, cx, cy - 14, Math.min(width - 40, 200), phase == RenderPipeline.Phase.DONE ? 1f : status.progress());
		}

		switch (phase) {
			case CONFIRM_STOP -> {
				centered(canvas, tr(status.editing() ? "survivalcinema.cinema.confirm.edit_body" : "survivalcinema.cinema.confirm.body"), cx, cy, LIGHT);
				centered(canvas, tr("survivalcinema.cinema.stopped.body", status.shotClips(), status.clipCount()), cx, cy + 12, GREY);
			}
			case DONE -> {
				if (status.hasFilm()) {
					centered(canvas, tr("survivalcinema.cinema.done.film", duration(status.filmSeconds()), status.shotClips(), status.clipCount()), cx, cy, LIGHT);
					if (status.gameMusic()) {
						centered(canvas, tr("survivalcinema.cinema.done.music_note"), cx, cy + 12, GREY);
					}
				} else {
					centered(canvas, tr("survivalcinema.cinema.done.body", status.shotClips(), status.clipCount()), cx, cy, LIGHT);
				}
				if (!status.hasFilm() && status.skippedClips() > 0) {
					centered(canvas, tr("survivalcinema.cinema.done.skipped", status.skippedClips()), cx, cy + 12, GREY);
				}
				if (!status.hasFilm()) {
					centered(canvas, fit(canvas, tr("survivalcinema.cinema.folder", status.folder()), width - 20), cx, cy + 24, DIM);
				}
			}
			case STOPPED -> {
				centered(canvas, allShot ? tr("survivalcinema.cinema.stopped.edit_body")
					: tr("survivalcinema.cinema.stopped.body", status.shotClips(), status.clipCount()), cx, cy, LIGHT);
				centered(canvas, tr(allShot ? "survivalcinema.cinema.stopped.edit_resume" : "survivalcinema.cinema.stopped.resume"), cx, cy + 12, GREY);
				centered(canvas, fit(canvas, tr("survivalcinema.cinema.folder", status.folder()), width - 20), cx, cy + 24, DIM);
			}
			case FAILED -> {
				Component problem = status.problem();
				centered(canvas, problem == null ? "" : problem.getString(), cx, cy - 8, LIGHT);
				centered(canvas, tr("survivalcinema.cinema.stopped.body", status.shotClips(), status.clipCount()), cx, cy + 6, GREY);
				if (allShot && status.shotClips() > 0) {
					centered(canvas, tr("survivalcinema.cinema.stopped.edit_resume"), cx, cy + 18, DIM);
				}
			}
			default -> {
				centered(canvas, stageLine(status), cx, cy, LIGHT);
				centered(canvas, tr("survivalcinema.cinema.eta", status.minutesLeft()), cx, cy + 12, GREY);
			}
		}

		if (working) {
			centered(canvas, hint(status, escHeldMillis, exporting), cx, height - 28, DIM);
		}
		centered(canvas, tr("survivalcinema.cinema.credit"), cx, height - 14, FAINT);
	}

	private static String stageLine(CinemaStatus status) {
		return switch (status.phase()) {
			case OPENING -> tr("survivalcinema.cinema.opening", status.session(), status.sessionCount());
			case SETTING_UP -> tr("survivalcinema.cinema.setting_up", status.clip(), status.clipCount());
			case SHOOTING -> tr("survivalcinema.cinema.shooting", status.clip(), status.clipCount());
			case EDITING -> tr("survivalcinema.cinema.editing", Math.round(status.progress() * 100));
			default -> tr("survivalcinema.cinema.preparing");
		};
	}

	private static String hint(CinemaStatus status, long escHeldMillis, boolean exporting) {
		if (exporting && escHeldMillis > 0) {
			long seconds = Math.max(1, (HOLD_TO_CANCEL_MILLIS - escHeldMillis + 999) / 1000);
			return tr("survivalcinema.cinema.hint_holding", seconds);
		}
		if (status.stopAfterClip()) {
			return tr("survivalcinema.cinema.hint_stop_after");
		}
		if (status.editing()) {
			return tr("survivalcinema.cinema.hint_edit");
		}
		return tr(exporting ? "survivalcinema.cinema.hint_export" : "survivalcinema.cinema.hint");
	}

	/** Like the bar of Minecraft's loading screen: a white frame filling up with white. */
	private static void progressBar(CinemaCanvas canvas, int cx, int y, int width, float progress) {
		int left = cx - width / 2;
		int barHeight = 6;
		canvas.fill(left, y, width, barHeight, BAR_FRAME);
		canvas.fill(left + 1, y + 1, width - 2, barHeight - 2, BLACK);
		int filled = Math.round((width - 4) * Math.max(0, Math.min(1, progress)));
		if (filled > 0) {
			canvas.fill(left + 2, y + 2, filled, barHeight - 4, BAR_FILL);
		}
	}

	/** "4:35" */
	private static String duration(double seconds) {
		long total = Math.round(seconds);
		return total / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", total % 60);
	}

	/** A line that fits the width: a long folder path loses its beginning ("...films/World/2026-10-20"). */
	private static String fit(CinemaCanvas canvas, String text, int maxWidth) {
		if (canvas.textWidth(text) <= maxWidth) {
			return text;
		}
		String cut = text;
		while (cut.length() > 1 && canvas.textWidth("..." + cut) > maxWidth) {
			cut = cut.substring(1);
		}
		return "..." + cut;
	}

	private static void centered(CinemaCanvas canvas, String text, int cx, int y, int argb) {
		if (!text.isEmpty()) {
			canvas.text(text, cx - canvas.textWidth(text) / 2, y, argb, 1);
		}
	}

	private static String tr(String key, Object... args) {
		return Component.translatable(key, args).getString();
	}
}
