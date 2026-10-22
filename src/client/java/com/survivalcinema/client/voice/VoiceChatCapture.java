package com.survivalcinema.client.voice;

import com.survivalcinema.client.voice.apps.SoundSource;
import com.survivalcinema.director.voice.VoiceMix;

import java.util.UUID;

/**
 * Our own copy of the voice chat, everyone mixed into one track, for slow motion: Flashback records the voices too,
 * but plays them back at the replay's speed, so in slow motion they stutter. Under the slowed picture of the big
 * moment ("we finally killed it!") this copy plays at normal speed instead. Fed by {@link CinemaVoicechatPlugin}.
 */
public final class VoiceChatCapture implements SoundSource {
	private static final VoiceMix MIX = new VoiceMix();
	private static final long START = System.nanoTime();

	private VoiceChatCapture() {
	}

	/** A packet of someone's voice (ours too), from a voice chat thread. */
	public static void onAudio(UUID speaker, short[] pcm) {
		MIX.add(speaker, pcm, now());
	}

	public static SoundSource open() {
		MIX.reset();
		return new VoiceChatCapture();
	}

	/** Samples (48 kHz) since the game started. */
	private static long now() {
		return (System.nanoTime() - START) / 1000 * VoiceMix.RATE / 1_000_000;
	}

	@Override
	public void read(int timeoutMillis, SampleSink sink) throws InterruptedException {
		Thread.sleep(timeoutMillis);
		short[] samples = MIX.take(now());
		if (samples.length > 0) {
			sink.accept(samples, samples.length);
		}
	}

	@Override
	public void close() {
	}
}
