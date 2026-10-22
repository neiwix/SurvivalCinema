package com.survivalcinema.client.voice.apps;

import com.survivalcinema.director.voice.VoiceTrackIndex;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/**
 * The player's own microphone (the system's default recording device): call apps never play your own voice back
 * to you, so without it the film would only have your friends' half of the conversation.
 */
public final class MicCapture implements SoundSource {
	private static final AudioFormat FORMAT = new AudioFormat(VoiceTrackIndex.RATE, 16, 1, true, false);

	private final TargetDataLine line;
	private final byte[] bytes = new byte[VoiceTrackIndex.RATE / 5 * 2];

	private MicCapture(TargetDataLine line) {
		this.line = line;
	}

	public static MicCapture open() throws LineUnavailableException {
		TargetDataLine line = AudioSystem.getTargetDataLine(FORMAT);
		line.open(FORMAT, VoiceTrackIndex.RATE / 2 * 2); // half a second of room
		line.start();
		return new MicCapture(line);
	}

	@Override
	public void read(int timeoutMillis, SampleSink sink) throws InterruptedException {
		int available = line.available() & ~1;
		if (available == 0) {
			Thread.sleep(timeoutMillis);
			available = line.available() & ~1;
			if (available == 0) {
				return;
			}
		}
		int n = line.read(bytes, 0, Math.min(available, bytes.length));
		int count = n / 2;
		if (count <= 0) {
			return;
		}
		short[] samples = new short[count];
		for (int i = 0; i < count; i++) {
			samples[i] = (short) ((bytes[i * 2] & 0xFF) | bytes[i * 2 + 1] << 8);
		}
		sink.accept(samples, count);
	}

	@Override
	public void close() {
		line.stop();
		line.close();
	}
}
