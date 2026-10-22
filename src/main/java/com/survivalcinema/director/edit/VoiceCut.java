package com.survivalcinema.director.edit;

import com.survivalcinema.director.render.ClipCamera;
import com.survivalcinema.director.voice.VoiceTrackIndex;

import java.util.List;

/**
 * Which piece of a voice track goes under each frame of a shot clip. The clip shows replay ticks
 * {@code [fromTick, toTick]}, slowed down from {@code slowFromTick} on; the track says where each tick is.
 * Voices play at normal speed: a small drift from lag is let run and caught up in one jump when it grows. Under slow
 * motion they go on at their own speed over the slowed picture ("we finally killed it!" while the dragon slowly bursts),
 * and catch up with the picture again when it is back to normal speed.
 */
public final class VoiceCut {
	/** Drift between the voice and the picture that is caught up at once. */
	static final double RESYNC_SECONDS = 0.15;
	/** Slower than this counts as slow motion. */
	static final float NORMAL_SPEED = 0.97f;

	/** Track sample under the start of each frame (-1: no sound there) and how loud it plays (0..1). */
	private final long[] starts;
	private final float[] gains;
	private final int samplesPerFrame;

	private VoiceCut(long[] starts, float[] gains, int samplesPerFrame) {
		this.starts = starts;
		this.gains = gains;
		this.samplesPerFrame = samplesPerFrame;
	}

	public int frames() {
		return starts.length;
	}

	public long start(int frame) {
		return starts[frame];
	}

	public float gain(int frame) {
		return gains[frame];
	}

	public int samplesPerFrame() {
		return samplesPerFrame;
	}

	/** First and last track sample the clip needs, or null when it needs none. */
	public long[] range() {
		long lo = Long.MAX_VALUE;
		long hi = -1;
		for (int i = 0; i < starts.length; i++) {
			if (starts[i] >= 0 && gains[i] > 0) {
				lo = Math.min(lo, starts[i]);
				hi = Math.max(hi, starts[i] + samplesPerFrame);
			}
		}
		return hi < 0 ? null : new long[]{lo, hi};
	}

	public boolean isSilent() {
		return range() == null;
	}

	public static VoiceCut plan(VoiceTrackIndex index, int fromTick, int toTick, int slowFromTick, int fps, int frames) {
		return plan(index, fromTick, toTick, slowFromTick, fps, frames, false);
	}

	/**
	 * @param slowMotionOnly only under slow motion: our copy of the voice chat, which the clip itself carries (as
	 *                       Flashback recorded it) everywhere else
	 */
	public static VoiceCut plan(VoiceTrackIndex index, int fromTick, int toTick, int slowFromTick, int fps, int frames,
								boolean slowMotionOnly) {
		int samplesPerFrame = VoiceTrackIndex.RATE / fps;
		long[] starts = new long[Math.max(0, frames)];
		float[] gains = new float[starts.length];
		if (frames <= 0 || toTick <= fromTick) {
			return new VoiceCut(starts, gains, samplesPerFrame);
		}
		double[] ticks = frameTicks(fromTick, toTick, slowFromTick, fps, frames);
		List<ClipCamera.SpeedKey> keys = ClipCamera.speedKeys(fromTick, toTick, slowFromTick);
		double cursor = -1;
		double resync = RESYNC_SECONDS * VoiceTrackIndex.RATE;
		for (int f = 0; f < frames; f++) {
			double target = index.sampleAt(ticks[f]);
			boolean normal = speedAt(keys, ticks[f]) >= NORMAL_SPEED;
			if (normal && slowMotionOnly) {
				starts[f] = -1;
				gains[f] = 0;
				cursor = -1;
				continue;
			}
			if (normal || cursor < 0) {
				if (target < 0) {
					starts[f] = -1;
					gains[f] = 0;
					cursor = -1;
					continue;
				}
				if (cursor < 0 || Math.abs(cursor - target) > resync) {
					cursor = target;
				}
			}
			// in slow motion the cursor just runs on: the voices keep their own speed
			starts[f] = Math.round(cursor);
			gains[f] = 1;
			cursor += samplesPerFrame;
		}
		return new VoiceCut(starts, gains, samplesPerFrame);
	}

	/**
	 * The replay tick at the start of each frame, following the speed keys and stretched so the clip's last frame
	 * ends on {@code toTick} (the file's real length decides).
	 */
	static double[] frameTicks(int fromTick, int toTick, int slowFromTick, int fps, int frames) {
		List<ClipCamera.SpeedKey> keys = ClipCamera.speedKeys(fromTick, toTick, slowFromTick);
		double[] raw = new double[frames + 1];
		double tick = fromTick;
		double perFrame = 20.0 / fps;
		for (int f = 0; f <= frames; f++) {
			raw[f] = tick;
			// midpoint step: the speed changes smoothly along the ramp
			double half = tick + speedAt(keys, tick) * perFrame / 2;
			tick += speedAt(keys, half) * perFrame;
		}
		double scale = raw[frames] > fromTick ? (toTick - fromTick) / (raw[frames] - fromTick) : 1;
		double[] ticks = new double[frames];
		for (int f = 0; f < frames; f++) {
			ticks[f] = fromTick + (raw[f] - fromTick) * scale;
		}
		return ticks;
	}

	/** Whether the clip is slowed down at that tick (where voices no longer keep time with the picture). */
	public static boolean isSlow(int fromTick, int toTick, int slowFromTick, double tick) {
		return slowFromTick >= 0 && speedAt(ClipCamera.speedKeys(fromTick, toTick, slowFromTick), tick) < NORMAL_SPEED;
	}

	static float speedAt(List<ClipCamera.SpeedKey> keys, double tick) {
		if (keys.isEmpty() || tick <= keys.getFirst().tick()) {
			return keys.isEmpty() ? 1f : keys.getFirst().speed();
		}
		for (int i = 0; i + 1 < keys.size(); i++) {
			ClipCamera.SpeedKey a = keys.get(i);
			ClipCamera.SpeedKey b = keys.get(i + 1);
			if (tick < b.tick()) {
				double t = (tick - a.tick()) / (double) (b.tick() - a.tick());
				return (float) (a.speed() + t * (b.speed() - a.speed()));
			}
		}
		return keys.getLast().speed();
	}
}
