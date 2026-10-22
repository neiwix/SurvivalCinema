package com.survivalcinema.client.debug;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.SessionStore;

import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * When the game freezes, Windows usually makes the player kill it before anyone can look. This watchdog notices
 * when the render thread has been stuck on exactly the same stack for a while and writes every thread's stack
 * (with locks) to {@code survivalcinema/hangs/}, so the freeze can be diagnosed afterwards.
 * <p>
 * Client ticks don't run during a Flashback export, so "alive" means the render thread's stack keeps changing.
 */
public final class HangWatchdog {
	private static final long CHECK_EVERY_MS = 5_000;
	private static final int STUCK_CHECKS = 3;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private final Thread renderThread;
	private volatile long lastHeartbeat = System.currentTimeMillis();

	private HangWatchdog(Thread renderThread) {
		this.renderThread = renderThread;
	}

	/** Call from the render thread. */
	public static HangWatchdog start() {
		HangWatchdog watchdog = new HangWatchdog(Thread.currentThread());
		Thread thread = new Thread(watchdog::run, "SurvivalCinema-HangWatchdog");
		thread.setDaemon(true);
		thread.start();
		return watchdog;
	}

	/** Every client tick. */
	public void heartbeat() {
		lastHeartbeat = System.currentTimeMillis();
	}

	private void run() {
		StackTraceElement[] previous = null;
		int stuck = 0;
		boolean dumped = false;
		while (true) {
			try {
				Thread.sleep(CHECK_EVERY_MS);
			} catch (InterruptedException e) {
				return;
			}
			StackTraceElement[] stack = renderThread.getStackTrace();
			boolean ticking = System.currentTimeMillis() - lastHeartbeat < CHECK_EVERY_MS * 2;
			boolean sameStack = previous != null && Arrays.equals(previous, stack);
			previous = stack;
			if (ticking || !sameStack) {
				stuck = 0;
				dumped = false;
				continue;
			}
			if (++stuck >= STUCK_CHECKS && !dumped) {
				dumped = true;
				dump(stuck * CHECK_EVERY_MS / 1000);
			}
		}
	}

	private void dump(long stuckSeconds) {
		StringBuilder out = new StringBuilder();
		out.append("Survival Cinema hang dump, render thread stuck for ~").append(stuckSeconds).append(" s at ")
			.append(LocalDateTime.now()).append("\n\n");
		for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
			out.append('"').append(info.getThreadName()).append("\" ").append(info.getThreadState());
			LockInfo lock = info.getLockInfo();
			if (lock != null) {
				out.append(" waiting on ").append(lock);
				if (info.getLockOwnerName() != null) {
					out.append(" held by \"").append(info.getLockOwnerName()).append('"');
				}
			}
			out.append('\n');
			StackTraceElement[] frames = info.getStackTrace();
			MonitorInfo[] monitors = info.getLockedMonitors();
			for (int i = 0; i < frames.length; i++) {
				out.append("\tat ").append(frames[i]).append('\n');
				for (MonitorInfo monitor : monitors) {
					if (monitor.getLockedStackDepth() == i) {
						out.append("\t- locked ").append(monitor).append('\n');
					}
				}
			}
			for (LockInfo sync : info.getLockedSynchronizers()) {
				out.append("\t- holds ").append(sync).append('\n');
			}
			out.append('\n');
		}
		try {
			Path file = SessionStore.root().resolve("hangs").resolve("hang-" + LocalDateTime.now().format(STAMP) + ".txt");
			Files.createDirectories(file.getParent());
			Files.writeString(file, out, StandardCharsets.UTF_8);
			SurvivalCinemaClient.LOGGER.error("The game seems frozen; wrote thread dump to {}", file);
		} catch (IOException e) {
			SurvivalCinemaClient.LOGGER.error("The game seems frozen; thread dump follows\n{}", out);
		}
	}
}
