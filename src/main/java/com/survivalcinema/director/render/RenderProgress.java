package com.survivalcinema.director.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.survivalcinema.director.event.SafeFiles;
import com.survivalcinema.director.story.EditDecisionList;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code render.json} in a film's folder: which clips are already shot. Saved after every finished file, so when the
 * game crashes (or the player stops) in the middle of a film, shooting continues with the next clip instead of
 * starting over.
 */
public final class RenderProgress {
	public static final String FILE_NAME = "render.json";
	public static final int VERSION = 1;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public enum Status {PENDING, DONE, SKIPPED}

	public static final class Clip {
		public int number;
		public Status status = Status.PENDING;
		/** Files already shot for this clip, relative to the film folder, one per piece. */
		public List<String> files = new ArrayList<>();
		/** Replay ticks {from, to} each file shows, in step with {@link #files} (missing in films shot before this was kept). */
		public List<int[]> ticks = new ArrayList<>();
		/** Exports of the current piece that produced no file. */
		public int failures;
		/** Why it was skipped. */
		public @Nullable String note;

		Clip() {
		}

		Clip(int number) {
			this.number = number;
		}
	}

	public int version = VERSION;
	/** {@link EditDecisionList#createdAt()} of the screenplay being shot. */
	public long edlCreatedAt;
	public int clipCount;
	public String worldKey = "";
	public String worldName = "";
	public RenderQuality quality = RenderQuality.DRAFT;
	public long startedAt;
	public long updatedAt;
	/** Every clip is shot (or skipped). */
	public boolean finished;
	/** The edited film, relative to the film folder, once the clips are put together into it. */
	public @Nullable String film;
	/** The base timelapse's camera {x, y, z, yaw, pitch}, chosen at its first picture and kept for all of them. */
	public double @Nullable [] timelapseCamera;
	/** Replay ids of the recordings the film was planned from (missing in films planned before this was kept). */
	public @Nullable List<String> sessions;
	/** The player asked to delete those recordings once the film is edited. */
	public boolean deleteRecordingsWhenDone;
	public List<Clip> clips = new ArrayList<>();

	public static RenderProgress fresh(EditDecisionList edl, String worldKey, RenderQuality quality, long now) {
		RenderProgress progress = new RenderProgress();
		progress.edlCreatedAt = edl.createdAt();
		progress.clipCount = RenderPlan.of(edl).size();
		progress.worldKey = worldKey;
		progress.worldName = edl.worldName();
		progress.quality = quality;
		progress.startedAt = now;
		progress.updatedAt = now;
		return progress;
	}

	/** Whether this progress belongs to that screenplay (and not to an older plan of the same film folder). */
	public boolean matches(EditDecisionList edl) {
		return version == VERSION && edlCreatedAt == edl.createdAt() && clipCount == RenderPlan.of(edl).size();
	}

	public Clip clip(int number) {
		Clip existing = find(number);
		if (existing != null) {
			return existing;
		}
		Clip clip = new Clip(number);
		clips.add(clip);
		clips.sort(Comparator.comparingInt(c -> c.number));
		return clip;
	}

	private @Nullable Clip find(int number) {
		for (Clip clip : clips) {
			if (clip.number == number) {
				return clip;
			}
		}
		return null;
	}

	/** Files shot for the clip so far, in order (empty when none). */
	public List<String> files(int number) {
		Clip clip = find(number);
		return clip == null ? List.of() : List.copyOf(clip.files);
	}

	/** How many pieces of the clip are already shot; shooting continues with the next one. */
	public int piecesDone(int number) {
		Clip clip = find(number);
		return clip == null ? 0 : clip.files.size();
	}

	public void pieceDone(int number, String file) {
		pieceDone(number, file, -1, -1);
	}

	public void pieceDone(int number, String file, int fromTick, int toTick) {
		Clip clip = clip(number);
		while (clip.ticks.size() < clip.files.size()) {
			clip.ticks.add(new int[]{-1, -1});
		}
		clip.files.add(file);
		clip.ticks.add(new int[]{fromTick, toTick});
		clip.failures = 0;
	}

	/** The replay ticks {from, to} a shot file of a clip shows, or null when not known. */
	public int @Nullable [] ticks(int number, String file) {
		Clip clip = find(number);
		if (clip == null) {
			return null;
		}
		int i = clip.files.indexOf(file);
		if (i < 0 || i >= clip.ticks.size() || clip.ticks.get(i) == null || clip.ticks.get(i).length != 2 || clip.ticks.get(i)[0] < 0) {
			return null;
		}
		return clip.ticks.get(i).clone();
	}

	public void clipDone(int number) {
		clip(number).status = Status.DONE;
	}

	public void skip(int number, String reason) {
		Clip clip = clip(number);
		clip.status = Status.SKIPPED;
		clip.note = reason;
	}

	/** Counts a failed export of the clip's current piece; true when it should be given up. */
	public boolean failed(int number, int maxFailures) {
		Clip clip = clip(number);
		clip.failures++;
		return clip.failures >= maxFailures;
	}

	public Status status(int number) {
		Clip clip = find(number);
		return clip == null ? Status.PENDING : clip.status;
	}

	public boolean isSettled(int number) {
		Clip clip = find(number);
		return clip != null && clip.status != Status.PENDING;
	}

	/** The plan's clips still to shoot, in shooting order. */
	public List<RenderPlan.Job> pending(RenderPlan plan) {
		return plan.jobs().stream().filter(job -> !isSettled(job.number())).toList();
	}

	public int count(Status status) {
		return (int) clips.stream().filter(c -> c.status == status).count();
	}

	public boolean isComplete(RenderPlan plan) {
		return pending(plan).isEmpty();
	}

	/** Clips are left to shoot, or they are all shot and still wait to be edited into the film. */
	public boolean needsWork() {
		return !finished || film == null && count(Status.DONE) > 0;
	}

	/**
	 * Clips whose files have disappeared from disk (deleted by hand, lost in a crash) are shot again.
	 *
	 * @return how many clips were reset
	 */
	public int forgetMissingFiles(Path filmFolder) {
		int reset = 0;
		for (Clip clip : clips) {
			boolean missing = clip.files.stream().anyMatch(file -> !Files.isRegularFile(filmFolder.resolve(file)));
			if (missing) {
				clip.files.clear();
				clip.ticks.clear();
				clip.status = Status.PENDING;
				clip.failures = 0;
				clip.note = null;
				finished = false;
				reset++;
			}
		}
		if (reset > 0 || film != null && !Files.isRegularFile(filmFolder.resolve(film))) {
			film = null; // the film is edited again from the clips
		}
		return reset;
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	public static @Nullable RenderProgress fromJson(String json) {
		try {
			RenderProgress progress = GSON.fromJson(json, RenderProgress.class);
			if (progress == null) {
				return null;
			}
			if (progress.clips == null) {
				progress.clips = new ArrayList<>();
			}
			progress.clips.removeIf(c -> c == null);
			for (Clip clip : progress.clips) {
				if (clip.files == null) {
					clip.files = new ArrayList<>();
				}
				if (clip.status == null) {
					clip.status = Status.PENDING;
				}
			}
			if (progress.quality == null) {
				progress.quality = RenderQuality.DRAFT;
			}
			return progress;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Reads the progress file, falling back to its backup if the game died while writing it. */
	public static @Nullable RenderProgress read(Path file) {
		for (Path candidate : List.of(file, SafeFiles.backup(file))) {
			if (!Files.isRegularFile(candidate)) {
				continue;
			}
			try {
				RenderProgress progress = fromJson(Files.readString(candidate, StandardCharsets.UTF_8));
				if (progress != null) {
					return progress;
				}
			} catch (IOException ignored) {
				// try the backup
			}
		}
		return null;
	}

	public void write(Path file) throws IOException {
		SafeFiles.writeAtomically(file, toJson());
	}
}
