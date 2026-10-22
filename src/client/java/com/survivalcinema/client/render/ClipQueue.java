package com.survivalcinema.client.render;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.director.camera.CameraKey;
import com.survivalcinema.director.render.ClipCamera;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Hands clips to Flashback one at a time, with a pause in between.
 * <p>
 * Queuing several exports at once made the game freeze at the end of the second one: between back-to-back exports
 * Flashback swaps the OpenAL device, and voice chat audio threads were caught mid-swap. Letting the client run a
 * couple of seconds between exports gives everything time to settle.
 * <p>
 * Every clip gets a {@link Ticket} that tells when its export is over. Note that client ticks keep running inside an
 * export (Flashback ticks the client for every frame it renders), so nothing here may start work while one runs.
 */
public final class ClipQueue {
	private static final int PAUSE_TICKS = 40;

	/** The fate of one queued clip. */
	public static final class Ticket {
		public enum State {WAITING, EXPORTING, DONE, DROPPED}

		private State state = State.WAITING;

		public State state() {
			return state;
		}

		public boolean isOver() {
			return state == State.DONE || state == State.DROPPED;
		}
	}

	private record Pending(FlashbackBridge.ClipRequest request, List<CameraKey> keys, List<ClipCamera.SpeedKey> speed,
						   Ticket ticket) {
	}

	private static final Deque<Pending> PENDING = new ArrayDeque<>();
	private static @Nullable Ticket handedOver;
	private static int idleTicks;

	private ClipQueue() {
	}

	public static Ticket add(FlashbackBridge.ClipRequest request, List<CameraKey> keys) {
		return add(request, keys, List.of());
	}

	public static Ticket add(FlashbackBridge.ClipRequest request, List<CameraKey> keys, List<ClipCamera.SpeedKey> speed) {
		Ticket ticket = new Ticket();
		PENDING.addLast(new Pending(request, keys, speed, ticket));
		return ticket;
	}

	public static int size() {
		return PENDING.size();
	}

	public static void clear() {
		for (Pending pending : PENDING) {
			pending.ticket().state = Ticket.State.DROPPED;
		}
		PENDING.clear();
	}

	/** Every client tick, including the ones Flashback runs inside an export. */
	public static void tick() {
		if (FlashbackBridge.isExporting()) {
			idleTicks = 0;
			return;
		}
		Ticket exporting = handedOver;
		if (exporting != null) {
			if (FlashbackBridge.queuedExports() > 0 && FlashbackBridge.isReplayReady()) {
				idleTicks = 0;
				return; // handed over, Flashback has not started it yet
			}
			// Started and finished (or the replay closed under it): Flashback's queue is empty again.
			exporting.state = FlashbackBridge.queuedExports() > 0 ? Ticket.State.DROPPED : Ticket.State.DONE;
			if (exporting.state == Ticket.State.DROPPED) {
				FlashbackBridge.cancelQueuedExports();
			}
			handedOver = null;
			idleTicks = 0;
		}
		if (PENDING.isEmpty()) {
			idleTicks = 0;
			return;
		}
		if (!FlashbackBridge.isReplayReady()) {
			SurvivalCinemaClient.LOGGER.warn("Replay closed, dropping {} queued clips", PENDING.size());
			clear();
			return;
		}
		if (FlashbackBridge.queuedExports() > 0) {
			idleTicks = 0;
			return; // the player's own exports from Flashback's editor go first
		}
		if (++idleTicks < PAUSE_TICKS) {
			return;
		}
		idleTicks = 0;
		Pending next = PENDING.removeFirst();
		next.ticket().state = Ticket.State.EXPORTING;
		handedOver = next.ticket();
		FlashbackBridge.queueCameraClip(next.request(), next.keys(), next.speed());
	}
}
