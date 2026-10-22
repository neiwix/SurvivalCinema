package com.survivalcinema.director.voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The call programs friends talk in instead of the game's voice chat, and how to find the one process whose sound
 * (with its children's) is the call: Discord, for one, plays voices from a child process of its main window.
 */
public final class CallApps {
	/** @param exeNames lower-case program file names, any of which is this app */
	public record App(String id, String displayName, Set<String> exeNames) {
		public boolean matches(String exeName) {
			return exeNames.contains(exeName.toLowerCase(Locale.ROOT));
		}
	}

	public static final List<App> ALL = List.of(
		new App("discord", "Discord", Set.of("discord.exe", "discordptb.exe", "discordcanary.exe")),
		new App("telegram", "Telegram", Set.of("telegram.exe", "ayugram.exe", "kotatogram.exe")),
		new App("teamspeak", "TeamSpeak", Set.of("ts3client_win64.exe", "ts3client_win32.exe", "teamspeak.exe")),
		new App("zoom", "Zoom", Set.of("zoom.exe")),
		new App("whatsapp", "WhatsApp", Set.of("whatsapp.exe", "whatsapp.root.exe")),
		new App("skype", "Skype", Set.of("skype.exe")));

	/** One running process, as the system lists it. */
	public record Proc(long pid, long parentPid, String exeName) {
	}

	/** A running call app: the process to capture (its whole tree is heard). */
	public record Running(App app, long pid) {
	}

	private CallApps() {
	}

	public static App byId(String id) {
		for (App app : ALL) {
			if (app.id().equals(id)) {
				return app;
			}
		}
		return null;
	}

	/**
	 * Which of the {@code wanted} apps run, each with its top process (the one whose parent is not the same app).
	 * When an app runs twice, the oldest (lowest pid among the tops) is taken.
	 */
	public static List<Running> find(List<Proc> processes, List<App> wanted) {
		Map<Long, Proc> byPid = new HashMap<>();
		for (Proc p : processes) {
			byPid.put(p.pid(), p);
		}
		List<Running> running = new ArrayList<>();
		for (App app : wanted) {
			long best = -1;
			for (Proc p : processes) {
				if (!app.matches(p.exeName())) {
					continue;
				}
				Proc parent = byPid.get(p.parentPid());
				boolean top = parent == null || parent.pid() == p.pid() || !app.matches(parent.exeName());
				if (top && (best < 0 || p.pid() < best)) {
					best = p.pid();
				}
			}
			if (best >= 0) {
				running.add(new Running(app, best));
			}
		}
		return running;
	}

	/** "Discord.exe" from "C:\...\Discord.exe". */
	public static String exeName(String command) {
		int slash = Math.max(command.lastIndexOf('\\'), command.lastIndexOf('/'));
		return command.substring(slash + 1);
	}
}
