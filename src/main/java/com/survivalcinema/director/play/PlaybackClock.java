package com.survivalcinema.director.play;

/**
 * Where the in-game player is in the film, in seconds. It runs on the wall clock and follows the sound card while
 * there is sound, so the picture stays with the voices: small differences are closed gently (no visible jump),
 * big ones (after a hiccup of the game) at once.
 */
public final class PlaybackClock {
	/** Further apart than this, the clock jumps to the sound instead of gliding towards it. */
	static final double SNAP_SECONDS = 0.2;
	/** Share of the difference to the sound closed at each update (updates come every frame). */
	static final double GLIDE = 0.1;

	private boolean running;
	private double anchor;
	private long anchorNanos;
	private double speed = 1;

	/** Film seconds at the given {@link System#nanoTime()}. */
	public double now(long nanos) {
		return running ? anchor + (nanos - anchorNanos) / 1e9 * speed : anchor;
	}

	/** Film seconds per second: 2 plays twice as fast. Takes effect from the given moment on. */
	public void setSpeed(double speed, long nanos) {
		anchor = now(nanos);
		anchorNanos = nanos;
		this.speed = speed;
	}

	public double speed() {
		return speed;
	}

	public boolean isRunning() {
		return running;
	}

	public void start(long nanos) {
		if (!running) {
			anchorNanos = nanos;
			running = true;
		}
	}

	public void stop(long nanos) {
		if (running) {
			anchor = now(nanos);
			anchorNanos = nanos;
			running = false;
		}
	}

	/** Jumps to a place in the film (a seek), running or not. */
	public void set(double seconds, long nanos) {
		anchor = seconds;
		anchorNanos = nanos;
	}

	/** The sound card says it is playing this moment of the film. */
	public void follow(double soundSeconds, long nanos) {
		double current = now(nanos);
		double difference = soundSeconds - current;
		anchor = Math.abs(difference) > SNAP_SECONDS ? soundSeconds : current + difference * GLIDE;
		anchorNanos = nanos;
	}
}
