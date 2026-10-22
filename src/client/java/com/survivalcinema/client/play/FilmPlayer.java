package com.survivalcinema.client.play;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.director.play.FramePicker;
import com.survivalcinema.director.play.PlaybackClock;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Plays a film file on a texture with its sound in sync: the picture follows the sound card's clock. Used by the
 * in-game cinema ({@code FilmPlayerScreen}); everything here runs on the render thread except the decoding.
 */
public final class FilmPlayer implements AutoCloseable {
	public enum State {OPENING, PLAYING, PAUSED, ENDED, FAILED}

	/** Sound decoded ahead before playing starts (or goes on after running dry). */
	private static final double READY_SOUND_SECONDS = 0.2;
	/** With this many pictures waiting the decoder's images are nearly all used: start even with less sound. */
	private static final int READY_PICTURES = 6;
	private static int textures;

	private final Path file;
	private final FilmDecoder decoder;
	private final PlaybackClock clock = new PlaybackClock();
	private final Deque<FilmDecoder.Picture> waiting = new ArrayDeque<>();
	private @Nullable FilmSound sound;
	private @Nullable VideoTexture texture;
	private @Nullable Identifier textureId;
	private State state = State.OPENING;
	private boolean wantPlay = true;
	private boolean buffering = true;
	private boolean showNext = true;
	private boolean shownAny;
	private int shownPictures;
	private @Nullable String problem;

	/** @param screenWidth window size in pixels (the film is decoded no bigger) */
	public FilmPlayer(Path file, int screenWidth, int screenHeight) {
		this.file = file;
		this.decoder = new FilmDecoder(file, screenWidth, screenHeight);
		decoder.start();
	}

	public Path file() {
		return file;
	}

	public State state() {
		return state;
	}

	public @Nullable String problem() {
		return problem;
	}

	/** The texture to draw, once the first picture is on it. */
	public @Nullable Identifier texture() {
		return shownAny ? textureId : null;
	}

	public int videoWidth() {
		return decoder.width();
	}

	public int videoHeight() {
		return decoder.height();
	}

	public double duration() {
		return decoder.duration();
	}

	public boolean hasSound() {
		return sound != null;
	}

	/** Waiting for the first pictures or sound (after opening or a seek). */
	public boolean isBuffering() {
		return state == State.PLAYING && buffering;
	}

	public boolean isSoundPlaying() {
		return sound != null && sound.isAudible();
	}

	public int shownPictures() {
		return shownPictures;
	}

	public double position() {
		double duration = duration();
		double now = clock.now(System.nanoTime());
		return duration > 0 ? Math.max(0, Math.min(duration, now)) : Math.max(0, now);
	}

	/** Every frame, before drawing. @param gain the sound's volume, 0..1 */
	public void update(float gain) {
		long nanos = System.nanoTime();
		if (state == State.FAILED) {
			return;
		}
		if (decoder.problem() != null) {
			problem = decoder.problem();
			state = State.FAILED;
			return;
		}
		if (!decoder.isOpen()) {
			return;
		}
		if (texture == null) {
			Minecraft mc = Minecraft.getInstance();
			texture = new VideoTexture(decoder.width(), decoder.height());
			textureId = Identifier.fromNamespaceAndPath(SurvivalCinemaClient.MOD_ID, "film_player/" + textures++);
			mc.getTextureManager().register(textureId, texture);
			sound = decoder.hasSound() ? FilmSound.open() : null;
			state = wantPlay ? State.PLAYING : State.PAUSED;
		}
		FilmDecoder.Picture picture;
		while ((picture = decoder.poll()) != null) {
			waiting.add(picture);
		}

		boolean playing = state == State.PLAYING;
		if (playing && buffering) {
			boolean pictures = !waiting.isEmpty() || decoder.ended();
			boolean enoughSound = sound == null || decoder.sound().seconds() >= READY_SOUND_SECONDS || decoder.ended()
				|| !sound.isEmpty() || waiting.size() >= READY_PICTURES;
			if (pictures && enoughSound) {
				buffering = false;
				clock.start(nanos);
			}
		}
		boolean running = playing && !buffering;
		decoder.setPlaying(running);
		if (sound != null) {
			double heard = sound.update(decoder.sound(), running, gain);
			if (running) {
				if (sound.isAudible() && !Double.isNaN(heard)) {
					clock.follow(heard, nanos);
				} else if (sound.isEmpty() && !decoder.ended()) {
					buffering = true; // the sound ran dry: wait for it instead of running off
					clock.stop(nanos);
				}
			}
		}

		double now = clock.now(nanos);
		if (showNext && !waiting.isEmpty()) {
			show(waiting.poll()); // right after opening or a seek, even when paused
			showNext = false;
		} else {
			List<Double> times = new ArrayList<>(waiting.size());
			for (FilmDecoder.Picture next : waiting) {
				times.add(next.time());
			}
			int chosen = FramePicker.pick(times, now);
			for (int i = 0; i < chosen; i++) {
				decoder.recycle(waiting.poll().image()); // late: skipped
			}
			if (chosen >= 0) {
				show(waiting.poll());
			}
		}

		if (running && decoder.ended() && waiting.isEmpty() && (sound == null || sound.isEmpty() && decoder.sound().seconds() <= 0)) {
			state = State.ENDED;
			clock.stop(nanos);
			if (duration() > 0) {
				clock.set(duration(), nanos);
			}
		}
	}

	private void show(FilmDecoder.Picture picture) {
		try {
			if (texture != null) {
				texture.show(picture.image());
				shownAny = true;
				shownPictures++;
			}
		} finally {
			decoder.recycle(picture.image());
		}
	}

	public void pause() {
		wantPlay = false;
		if (state == State.PLAYING) {
			state = State.PAUSED;
			clock.stop(System.nanoTime());
		}
	}

	public void play() {
		wantPlay = true;
		switch (state) {
			case PAUSED -> {
				state = State.PLAYING;
				if (!buffering) {
					clock.start(System.nanoTime());
				}
			}
			case ENDED -> {
				seek(0);
				wantPlay = true;
				state = State.PLAYING;
			}
			default -> {
			}
		}
	}

	public void togglePause() {
		if (state == State.PLAYING) {
			pause();
		} else {
			play();
		}
	}

	/** Jumps to a moment of the film; playing goes on there (or it stays paused). */
	public void seek(double seconds) {
		if (texture == null || state == State.FAILED) {
			return;
		}
		double duration = duration();
		double target = Math.max(0, duration > 0 ? Math.min(seconds, duration - 0.05) : seconds);
		long nanos = System.nanoTime();
		decoder.seek(target);
		FilmDecoder.Picture stale;
		while ((stale = waiting.poll()) != null) {
			decoder.recycle(stale.image());
		}
		if (sound != null) {
			sound.flush();
		}
		clock.stop(nanos);
		clock.set(target, nanos);
		buffering = true;
		showNext = true;
		if (state == State.ENDED) {
			state = State.PAUSED;
			wantPlay = false;
		}
	}

	@Override
	public void close() {
		FilmDecoder.Picture stale;
		while ((stale = waiting.poll()) != null) {
			decoder.recycle(stale.image());
		}
		if (sound != null) {
			sound.close();
			sound = null;
		}
		decoder.close();
		if (textureId != null) {
			Minecraft.getInstance().getTextureManager().release(textureId);
			textureId = null;
			texture = null;
		}
	}
}
