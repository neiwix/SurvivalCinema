package com.survivalcinema.client.play;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.director.play.SoundQueue;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.system.MemoryUtil;

import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The film's sound, played through the game's own OpenAL device with a source of our own (not a game sound, so
 * neither the game's pause nor its music settings touch it). Fed from the render thread a few hundred milliseconds
 * ahead; the sound card's position is the player's clock.
 * <p>
 * The game rebuilds its sound device when headphones are plugged in or out: our source then belongs to a context that
 * is gone, so it is simply made again in the new one.
 */
final class FilmSound implements AutoCloseable {
	private static final int BUFFERS = 8;
	/** About 43 ms per buffer: 8 of them keep a third of a second queued. */
	private static final int FRAMES_PER_BUFFER = 2048;

	private final SoundQueue queue = new SoundQueue(FilmDecoder.RATE);
	private final Deque<Integer> idle = new ArrayDeque<>();
	private final short[] chunk = new short[FRAMES_PER_BUFFER * FilmDecoder.CHANNELS];
	private final ShortBuffer upload = MemoryUtil.memAllocShort(FRAMES_PER_BUFFER * FilmDecoder.CHANNELS);
	private long context;
	private int source;
	private int[] buffers = new int[0];
	private boolean audible;

	private FilmSound() {
	}

	/** Null when the game has no sound device. On the render thread. */
	static @Nullable FilmSound open() {
		FilmSound sound = new FilmSound();
		if (!sound.create()) {
			sound.close();
			return null;
		}
		return sound;
	}

	private boolean create() {
		context = ALC10.alcGetCurrentContext();
		if (context == 0) {
			return false;
		}
		AL10.alGetError();
		source = AL10.alGenSources();
		buffers = new int[BUFFERS];
		AL10.alGenBuffers(buffers);
		if (AL10.alGetError() != AL10.AL_NO_ERROR) {
			SurvivalCinemaClient.LOGGER.warn("Could not make an OpenAL source for the film, it plays without sound");
			context = 0;
			return false;
		}
		// plain stereo straight to the ears: no position, no distance
		AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
		AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
		AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0);
		idle.clear();
		for (int buffer : buffers) {
			idle.add(buffer);
		}
		queue.clear();
		return true;
	}

	/**
	 * Every frame: gives the sound card what was decoded, plays or pauses, and says what is heard now.
	 *
	 * @return film seconds being heard, or NaN when nothing is playing
	 */
	double update(SampleRing ring, boolean play, float gain) {
		long now = ALC10.alcGetCurrentContext();
		if (now == 0) {
			audible = false;
			return Double.NaN;
		}
		if (now != context && !create()) {
			audible = false;
			return Double.NaN;
		}
		int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
		for (int i = 0; i < processed; i++) {
			idle.add(AL10.alSourceUnqueueBuffers(source));
			queue.processed(1);
		}
		if (play) {
			while (!idle.isEmpty()) {
				SampleRing.Read read = ring.read(chunk, chunk.length);
				if (read.count() == 0) {
					break;
				}
				int buffer = idle.poll();
				upload.clear();
				upload.put(chunk, 0, read.count()).flip();
				AL10.alBufferData(buffer, AL10.AL_FORMAT_STEREO16, upload, FilmDecoder.RATE);
				AL10.alSourceQueueBuffers(source, buffer);
				queue.queued(read.time(), read.count() / FilmDecoder.CHANNELS);
			}
		}
		AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0, gain));
		int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
		if (play && state != AL10.AL_PLAYING && queue.size() > 0) {
			AL10.alSourcePlay(source); // starting, going on after a pause, or after running dry for a moment
			state = AL10.AL_PLAYING;
		} else if (!play && state == AL10.AL_PLAYING) {
			AL10.alSourcePause(source);
			state = AL10.AL_PAUSED;
		}
		audible = state == AL10.AL_PLAYING && queue.size() > 0;
		return queue.position(AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET));
	}

	/** The sound card is really playing film sound right now. */
	boolean isAudible() {
		return audible;
	}

	/** Nothing is queued: the sound ran dry (or never started). */
	boolean isEmpty() {
		return queue.size() == 0;
	}

	/** Drops everything queued (a seek). */
	void flush() {
		if (context != 0 && context == ALC10.alcGetCurrentContext()) {
			AL10.alSourceStop(source);
			int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
			for (int i = 0; i < queued; i++) {
				idle.add(AL10.alSourceUnqueueBuffers(source));
			}
		}
		queue.clear();
		audible = false;
	}

	@Override
	public void close() {
		if (context != 0 && context == ALC10.alcGetCurrentContext()) {
			AL10.alSourceStop(source);
			AL10.alSourcei(source, AL10.AL_BUFFER, 0);
			AL10.alDeleteSources(source);
			AL10.alDeleteBuffers(buffers);
			AL10.alGetError();
		}
		context = 0;
		MemoryUtil.memFree(upload);
	}
}
