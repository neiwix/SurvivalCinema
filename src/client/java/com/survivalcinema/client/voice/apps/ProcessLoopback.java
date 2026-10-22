package com.survivalcinema.client.voice.apps;

import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The sound one program plays (and its child processes), like OBS's "application audio capture": Windows' process
 * loopback ({@code ActivateAudioInterfaceAsync} on {@code VAD\Process_Loopback}), called through the JNA that ships
 * with Minecraft. Needs Windows 10 build 20348+ / Windows 11. Delivers 48 kHz mono 16-bit.
 * <p>
 * Not thread-safe: open, read and close it on one thread, which must not be the render thread (COM is initialised
 * there as multi-threaded).
 */
public final class ProcessLoopback implements SoundSource {
	public static final int RATE = 48_000;
	private static final int CAPTURE_CHANNELS = 2;

	private static final String DEVICE = "VAD\\Process_Loopback";
	private static final Guid.GUID IID_IUNKNOWN = new Guid.GUID("{00000000-0000-0000-C000-000000000046}");
	private static final Guid.GUID IID_COMPLETION_HANDLER = new Guid.GUID("{41D949AB-9862-444A-80F6-C261334DA5EB}");
	private static final Guid.GUID IID_AGILE_OBJECT = new Guid.GUID("{94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90}");
	private static final Guid.GUID IID_AUDIO_CLIENT = new Guid.GUID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}");
	private static final Guid.GUID IID_AUDIO_CAPTURE_CLIENT = new Guid.GUID("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}");

	private static final int VT_BLOB = 65;
	private static final int ACTIVATION_TYPE_PROCESS_LOOPBACK = 1;
	private static final int LOOPBACK_INCLUDE_TREE = 0;
	private static final int STREAMFLAGS_LOOPBACK = 0x0002_0000;
	private static final int STREAMFLAGS_EVENTCALLBACK = 0x0004_0000;
	private static final int STREAMFLAGS_AUTOCONVERTPCM = 0x8000_0000;
	private static final int STREAMFLAGS_SRC_DEFAULT_QUALITY = 0x0800_0000;
	private static final int BUFFERFLAGS_SILENT = 0x2;
	private static final long BUFFER_100NS = 5_000_000; // half a second: a hiccup of our thread loses nothing
	private static final int E_NOINTERFACE = 0x8000_4002;

	// vtable slots
	private static final int RELEASE = 2;
	private static final int OP_GET_ACTIVATE_RESULT = 3;
	private static final int CLIENT_INITIALIZE = 3;
	private static final int CLIENT_START = 10;
	private static final int CLIENT_STOP = 11;
	private static final int CLIENT_SET_EVENT_HANDLE = 13;
	private static final int CLIENT_GET_SERVICE = 14;
	private static final int CAPTURE_GET_BUFFER = 3;
	private static final int CAPTURE_RELEASE_BUFFER = 4;
	private static final int CAPTURE_GET_NEXT_PACKET_SIZE = 5;

	private Pointer client;
	private Pointer capture;
	private final WinNT.HANDLE event;
	private final boolean comStarted;
	private short[] stereo = new short[RATE / 10 * CAPTURE_CHANNELS];
	private boolean closed;

	/** Why a program's sound could not be captured. */
	public static final class CaptureException extends Exception {
		public CaptureException(String message) {
			super(message);
		}
	}

	private ProcessLoopback(Pointer client, Pointer capture, WinNT.HANDLE event, boolean comStarted) {
		this.client = client;
		this.capture = capture;
		this.event = event;
		this.comStarted = comStarted;
	}

	/** Starts capturing everything process {@code pid} and its children play. */
	public static ProcessLoopback open(long pid) throws CaptureException {
		int co = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED).intValue();
		boolean comStarted = co == 0 || co == 1; // S_OK, S_FALSE (both need CoUninitialize)
		Pointer client = null;
		Pointer capture = null;
		WinNT.HANDLE event = null;
		try {
			client = activate(pid);
			Memory format = new Memory(20);
			format.clear();
			format.setShort(0, (short) 1); // WAVE_FORMAT_PCM
			format.setShort(2, (short) CAPTURE_CHANNELS);
			format.setInt(4, RATE);
			format.setInt(8, RATE * CAPTURE_CHANNELS * 2);
			format.setShort(12, (short) (CAPTURE_CHANNELS * 2));
			format.setShort(14, (short) 16);
			check(call(client, CLIENT_INITIALIZE, 0, STREAMFLAGS_LOOPBACK | STREAMFLAGS_EVENTCALLBACK
				| STREAMFLAGS_AUTOCONVERTPCM | STREAMFLAGS_SRC_DEFAULT_QUALITY, BUFFER_100NS, 0L, format, Pointer.NULL), "Initialize");
			event = Kernel32.INSTANCE.CreateEvent(null, false, false, null);
			if (event == null) {
				throw new CaptureException("CreateEvent failed");
			}
			check(call(client, CLIENT_SET_EVENT_HANDLE, event), "SetEventHandle");
			PointerByReference service = new PointerByReference();
			check(call(client, CLIENT_GET_SERVICE, guid(IID_AUDIO_CAPTURE_CLIENT), service), "GetService");
			capture = service.getValue();
			check(call(client, CLIENT_START), "Start");
			return new ProcessLoopback(client, capture, event, comStarted);
		} catch (CaptureException | RuntimeException | UnsatisfiedLinkError e) {
			if (capture != null) {
				call(capture, RELEASE);
			}
			if (client != null) {
				call(client, RELEASE);
			}
			if (event != null) {
				Kernel32.INSTANCE.CloseHandle(event);
			}
			if (comStarted) {
				Ole32.INSTANCE.CoUninitialize();
			}
			if (e instanceof CaptureException capture1) {
				throw capture1;
			}
			throw new CaptureException(e.toString());
		}
	}

	@Override
	public void read(int timeoutMillis, SampleSink sink) throws CaptureException {
		if (closed) {
			return;
		}
		Kernel32.INSTANCE.WaitForSingleObject(event, timeoutMillis);
		IntByReference packet = new IntByReference();
		PointerByReference data = new PointerByReference();
		IntByReference frames = new IntByReference();
		IntByReference flags = new IntByReference();
		while (true) {
			check(call(capture, CAPTURE_GET_NEXT_PACKET_SIZE, packet), "GetNextPacketSize");
			if (packet.getValue() == 0) {
				return;
			}
			int hr = call(capture, CAPTURE_GET_BUFFER, data, frames, flags, Pointer.NULL, Pointer.NULL);
			if (hr == 0x0889_0001) { // AUDCLNT_S_BUFFER_EMPTY
				return;
			}
			check(hr, "GetBuffer");
			int n = frames.getValue();
			if (stereo.length < n * CAPTURE_CHANNELS) {
				stereo = new short[n * CAPTURE_CHANNELS];
			}
			short[] mono = new short[n];
			if ((flags.getValue() & BUFFERFLAGS_SILENT) == 0 && n > 0) {
				data.getValue().read(0, stereo, 0, n * CAPTURE_CHANNELS);
				for (int i = 0; i < n; i++) {
					mono[i] = (short) ((stereo[i * 2] + stereo[i * 2 + 1]) / 2);
				}
			}
			check(call(capture, CAPTURE_RELEASE_BUFFER, n), "ReleaseBuffer");
			if (n > 0) {
				sink.accept(mono, n);
			}
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			call(client, CLIENT_STOP);
		} catch (RuntimeException ignored) {
		}
		call(capture, RELEASE);
		call(client, RELEASE);
		Kernel32.INSTANCE.CloseHandle(event);
		if (comStarted) {
			Ole32.INSTANCE.CoUninitialize();
		}
	}

	// ---- activation ----

	private interface QueryInterface extends StdCallLibrary.StdCallCallback {
		int invoke(Pointer self, Pointer riid, Pointer out);
	}

	private interface RefCount extends StdCallLibrary.StdCallCallback {
		int invoke(Pointer self);
	}

	private interface Completed extends StdCallLibrary.StdCallCallback {
		int invoke(Pointer self, Pointer operation);
	}

	/**
	 * Our IActivateAudioInterfaceCompletionHandler, a tiny COM object built by hand: Windows calls it on one of its
	 * own threads once the audio client is ready. It must say it is agile (IAgileObject) or activation fails.
	 */
	private static final class Handler {
		final CountDownLatch done = new CountDownLatch(1);
		volatile int result = -1;
		volatile Pointer audioClient;
		final Memory object = new Memory(Native.POINTER_SIZE);
		private final Memory vtable = new Memory(4L * Native.POINTER_SIZE);
		// kept as fields: the native side calls them, they must not be garbage collected
		private final QueryInterface queryInterface;
		private final RefCount addRef = self -> 1;
		private final RefCount release = self -> 1;
		private final Completed completed;

		Handler() {
			queryInterface = (self, riid, out) -> {
				Guid.GUID asked = new Guid.GUID(riid);
				if (asked.equals(IID_IUNKNOWN) || asked.equals(IID_COMPLETION_HANDLER) || asked.equals(IID_AGILE_OBJECT)) {
					out.setPointer(0, self);
					return 0;
				}
				out.setPointer(0, Pointer.NULL);
				return E_NOINTERFACE;
			};
			completed = (self, operation) -> {
				try {
					Memory activateResult = new Memory(4);
					PointerByReference unknown = new PointerByReference();
					int hr = call(operation, OP_GET_ACTIVATE_RESULT, activateResult, unknown);
					result = hr != 0 ? hr : activateResult.getInt(0);
					audioClient = unknown.getValue();
				} catch (RuntimeException e) {
					result = -2;
				} finally {
					done.countDown();
				}
				return 0;
			};
			vtable.setPointer(0, CallbackReference.getFunctionPointer(queryInterface));
			vtable.setPointer(Native.POINTER_SIZE, CallbackReference.getFunctionPointer(addRef));
			vtable.setPointer(2L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(release));
			vtable.setPointer(3L * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(completed));
			object.setPointer(0, vtable);
		}
	}

	private static Pointer activate(long pid) throws CaptureException {
		Memory params = new Memory(12);
		params.setInt(0, ACTIVATION_TYPE_PROCESS_LOOPBACK);
		params.setInt(4, (int) pid);
		params.setInt(8, LOOPBACK_INCLUDE_TREE);
		Memory variant = new Memory(24); // PROPVARIANT holding a BLOB
		variant.clear();
		variant.setShort(0, (short) VT_BLOB);
		variant.setInt(8, 12);
		variant.setPointer(16, params);

		Handler handler = new Handler();
		PointerByReference operation = new PointerByReference();
		Function activate = Function.getFunction("Mmdevapi", "ActivateAudioInterfaceAsync", Function.ALT_CONVENTION);
		check(activate.invokeInt(new Object[]{new WString(DEVICE), guid(IID_AUDIO_CLIENT), variant, handler.object, operation}),
			"ActivateAudioInterfaceAsync");
		try {
			if (!handler.done.await(5, TimeUnit.SECONDS)) {
				throw new CaptureException("activation timed out");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CaptureException("interrupted");
		} finally {
			if (operation.getValue() != null) {
				call(operation.getValue(), RELEASE);
			}
		}
		// keep the handler and the parameters reachable until here
		Pointer audioClient = handler.audioClient;
		check(handler.result, "activation");
		params.clear();
		if (audioClient == null) {
			throw new CaptureException("activation gave no audio client");
		}
		return audioClient;
	}

	private static Pointer guid(Guid.GUID guid) {
		guid.write();
		return guid.getPointer();
	}

	private static int call(Pointer object, int slot, Object... args) {
		Pointer vtable = object.getPointer(0);
		Function function = Function.getFunction(vtable.getPointer((long) slot * Native.POINTER_SIZE), Function.ALT_CONVENTION);
		Object[] all = new Object[args.length + 1];
		all[0] = object;
		System.arraycopy(args, 0, all, 1, args.length);
		return function.invokeInt(all);
	}

	private static void check(int hr, String what) throws CaptureException {
		if (hr < 0) {
			throw new CaptureException(what + " failed: 0x" + Integer.toHexString(hr));
		}
	}
}
