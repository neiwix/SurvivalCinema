package com.survivalcinema.client.voice.apps;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.voice.VoiceActivityMeter;
import com.survivalcinema.director.voice.TrackClock;
import com.survivalcinema.director.voice.VoiceTrackIndex;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * One voice track being captured on its own thread: opens its sound source when asked (a program by pid, or the
 * microphone), measures how loud it is, and while the recording runs writes it with silence filling the moments
 * the source gave nothing, marking the replay tick as it goes. The client thread only sets the volatile fields.
 */
final class CapturedTrack {
	/** Opens the source; {@code target} is the program's pid (unused for the microphone). */
	interface Opener {
		SoundSource open(long target) throws Exception;
	}

	private static final int READ_MILLIS = 20;
	private static final long RETRY_NANOS = 10_000_000_000L;

	final String name;
	private final Opener opener;
	private final Thread thread;
	private final TrackClock clock = new TrackClock(VoiceTrackIndex.RATE);

	/** What to capture: a pid (or 0 for the microphone), or -1 for nothing right now. */
	volatile long target = -1;
	/** Where the recording's tracks go, or null when nothing is recorded (just measuring). */
	volatile @Nullable Path folder;
	/** The recording runs (not paused): sound is written. */
	volatile boolean active;
	volatile int tick;
	/** Who speaks in this track, for noticing voices; null: don't tell the voice meter. */
	volatile @Nullable UUID speaker;
	/** Loudest RMS (0..1) of the last tenth of a second. */
	volatile float level;
	/** The last thing that went wrong (shown in the voice settings), or null. */
	volatile @Nullable String problem;
	/** A source is open and delivers. */
	volatile boolean live;
	private volatile boolean stopping;

	private @Nullable SoundSource source;
	private long openedTarget = -1;
	private long retryAt;
	private @Nullable VoiceTrackWriter writer;
	private @Nullable Path writerFolder;
	private @Nullable Path brokenFolder;
	private boolean forceMark;
	private float windowPeak;
	private long windowStart;

	CapturedTrack(String name, Opener opener) {
		this.name = name;
		this.opener = opener;
		this.thread = new Thread(this::run, "SurvivalCinema-Voice-" + name);
		thread.setDaemon(true);
	}

	void start() {
		thread.start();
	}

	/** Ends the thread, closing the track file properly (waits a moment for it). */
	void stop() {
		stopping = true;
		try {
			thread.join(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void run() {
		try {
			while (!stopping) {
				followSource();
				followFolder();
				if (source != null) {
					try {
						source.read(READ_MILLIS, this::onSamples);
					} catch (Exception e) {
						SurvivalCinemaClient.LOGGER.warn("Voice track {} stopped capturing: {}", name, e.toString());
						problem = e.toString();
						closeSource();
						retryAt = System.nanoTime() + RETRY_NANOS;
					}
				} else {
					Thread.sleep(READ_MILLIS);
				}
				onSamples(new short[0], 0); // keeps time while the source gives nothing
			}
		} catch (InterruptedException ignored) {
		} catch (Throwable t) {
			SurvivalCinemaClient.LOGGER.error("Voice track {} failed", name, t);
			problem = t.toString();
		} finally {
			closeSource();
			closeWriter();
			live = false;
		}
	}

	private void followSource() {
		long want = target;
		if (source != null && openedTarget != want) {
			closeSource();
		}
		if (source == null && want >= 0 && System.nanoTime() >= retryAt) {
			try {
				source = opener.open(want);
				openedTarget = want;
				problem = null;
				live = true;
				SurvivalCinemaClient.LOGGER.info("Capturing voice track {}", name);
			} catch (Exception e) {
				problem = e.getMessage() == null ? e.toString() : e.getMessage();
				SurvivalCinemaClient.LOGGER.warn("Could not capture voice track {}: {}", name, problem);
				retryAt = System.nanoTime() + RETRY_NANOS;
			}
		}
	}

	private void followFolder() {
		Path wanted = folder;
		if (writer != null && !Objects.equals(writerFolder, wanted)) {
			closeWriter();
		}
		if (writer == null && wanted != null && active && !wanted.equals(brokenFolder)) {
			try {
				writer = new VoiceTrackWriter(wanted, name);
				writerFolder = wanted;
				clock.stop();
			} catch (IOException | RuntimeException e) {
				SurvivalCinemaClient.LOGGER.error("Could not write voice track {} in {}", name, wanted, e);
				problem = e.toString();
				brokenFolder = wanted;
			}
		}
	}

	private void onSamples(short[] samples, int count) {
		long now = System.nanoTime();
		measure(samples, count, now);
		UUID who = speaker;
		if (who != null && count > 0) {
			VoiceActivityMeter.get().onAudio(who, samples);
		}
		VoiceTrackWriter w = writer;
		if (w == null || !active) {
			clock.stop();
			return;
		}
		try {
			if (!clock.isRunning()) {
				clock.restart(now, w.written());
				forceMark = true;
			}
			w.writeSilence(clock.silenceBefore(now, w.written(), count));
			w.write(samples, count);
			w.mark(tick, forceMark);
			forceMark = false;
		} catch (IOException | RuntimeException e) {
			SurvivalCinemaClient.LOGGER.error("Voice track {} could not be written", name, e);
			problem = e.toString();
			brokenFolder = writerFolder;
			closeWriter();
		}
	}

	private void measure(short[] samples, int count, long now) {
		if (count > 0) {
			double sum = 0;
			for (int i = 0; i < count; i++) {
				sum += (double) samples[i] * samples[i];
			}
			windowPeak = Math.max(windowPeak, (float) (Math.sqrt(sum / count) / 32768));
		}
		if (now - windowStart >= 100_000_000L) {
			level = live ? windowPeak : 0;
			windowPeak = 0;
			windowStart = now;
		}
	}

	private void closeSource() {
		if (source != null) {
			try {
				source.close();
			} catch (RuntimeException e) {
				SurvivalCinemaClient.LOGGER.warn("Could not close voice source {}", name, e);
			}
		}
		source = null;
		openedTarget = -1;
		live = false;
	}

	private void closeWriter() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException | RuntimeException e) {
				SurvivalCinemaClient.LOGGER.warn("Could not close voice track {}", name, e);
			}
		}
		writer = null;
		writerFolder = null;
		clock.stop();
	}
}
