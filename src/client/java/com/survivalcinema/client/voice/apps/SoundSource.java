package com.survivalcinema.client.voice.apps;

/** Where a voice track's sound comes from: a program's sound or the microphone, 48 kHz mono 16-bit. */
public interface SoundSource extends AutoCloseable {
	/**
	 * Waits up to {@code timeoutMillis} for sound and hands over everything captured so far.
	 */
	void read(int timeoutMillis, SampleSink sink) throws Exception;

	@Override
	void close();

	interface SampleSink {
		/** {@code samples} holds exactly {@code count} new samples and is not reused. */
		void accept(short[] samples, int count);
	}
}
