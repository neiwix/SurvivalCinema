package com.survivalcinema.director.camera;

import com.google.gson.Gson;
import com.survivalcinema.director.story.EditDecisionList;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A clip's real surroundings, saved by the end-to-end test ({@code CameraDump}): the blocks around the player's path,
 * the path, and the camera keys the game planned. Works as a {@link BlockProbe} that casts rays through the saved
 * collision boxes the way the game's raycast does, so camera changes can be tried on real terrain in unit tests.
 */
final class CameraScene implements BlockProbe {
	private static final byte AIR = 0;
	private static final byte FULL = 1;
	private static final byte PARTIAL = 2;

	final EditDecisionList.Item item;
	final int fromTick;
	final int toTick;
	final Trail trail;
	/** The camera the game planned when the scene was saved. */
	final List<CameraKey> savedKeys;
	/** Where the boss of a boss scene was, when the game saw it; null otherwise. */
	final Trail boss;

	private int ox, oy, oz, sx, sy, sz;
	private byte[] cells;
	private final Map<Integer, List<double[]>> boxes = new HashMap<>();

	private CameraScene(EditDecisionList.Item item, int fromTick, int toTick, Trail trail, List<CameraKey> savedKeys,
						Trail boss) {
		this.item = item;
		this.fromTick = fromTick;
		this.toTick = toTick;
		this.trail = trail;
		this.savedKeys = savedKeys;
		this.boss = boss;
	}

	static CameraScene read(InputStream gzipped) throws IOException {
		EditDecisionList.Item item = null;
		int from = 0, to = 0;
		int[] origin = null;
		List<TrailSample> samples = new ArrayList<>();
		List<CameraKey> keys = new ArrayList<>();
		List<TrailSample> boss = new ArrayList<>();
		List<String[]> blocks = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(gzipped), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.startsWith("item ")) {
					item = new Gson().fromJson(line.substring(5), EditDecisionList.Item.class);
					continue;
				}
				String[] f = line.split(" ");
				switch (f[0]) {
					case "piece" -> {
						from = Integer.parseInt(f[1]);
						to = Integer.parseInt(f[2]);
					}
					case "origin" -> origin = new int[]{i(f[1]), i(f[2]), i(f[3]), i(f[4]), i(f[5]), i(f[6])};
					case "trail" -> samples.add(new TrailSample(i(f[1]), d(f[2]), d(f[3]), d(f[4]), (float) d(f[5]),
						(float) d(f[6]), i(f[7])));
					case "key" -> keys.add(new CameraKey(i(f[1]), new Vec(d(f[2]), d(f[3]), d(f[4])), (float) d(f[5]), (float) d(f[6])));
					case "boss" -> boss.add(new TrailSample(i(f[1]), d(f[2]), d(f[3]), d(f[4]), 0, 0, 0));
					case "full", "box" -> blocks.add(f);
					default -> throw new IOException("Unknown line in a camera scene: " + line);
				}
			}
		}
		if (item == null || origin == null) {
			throw new IOException("Not a camera scene");
		}
		CameraScene scene = new CameraScene(item, from, to, new Trail(samples), keys,
			boss.isEmpty() ? null : new Trail(boss));
		scene.ox = origin[0];
		scene.oy = origin[1];
		scene.oz = origin[2];
		scene.sx = origin[3];
		scene.sy = origin[4];
		scene.sz = origin[5];
		scene.cells = new byte[scene.sx * scene.sy * scene.sz];
		for (String[] f : blocks) {
			int x = i(f[1]), y = i(f[2]), z = i(f[3]);
			if (f[0].equals("full")) {
				for (int k = 0; k < i(f[4]); k++) {
					scene.cells[scene.index(x + k, y, z)] = FULL;
				}
			} else {
				int index = scene.index(x, y, z);
				scene.cells[index] = PARTIAL;
				scene.boxes.computeIfAbsent(index, k -> new ArrayList<>())
					.add(new double[]{d(f[4]), d(f[5]), d(f[6]), d(f[7]), d(f[8]), d(f[9])});
			}
		}
		return scene;
	}

	/** Blocks with a collision box in the scene. */
	int blockCount() {
		int count = 0;
		for (byte cell : cells) {
			if (cell != AIR) {
				count++;
			}
		}
		return count;
	}

	private static int i(String s) {
		return Integer.parseInt(s);
	}

	private static double d(String s) {
		return Double.parseDouble(s);
	}

	private int index(int x, int y, int z) {
		return (y * sz + z) * sx + x;
	}

	private byte cell(int x, int y, int z) {
		int lx = x - ox, ly = y - oy, lz = z - oz;
		if (lx < 0 || ly < 0 || lz < 0 || lx >= sx || ly >= sy || lz >= sz) {
			return AIR;
		}
		return cells[index(lx, ly, lz)];
	}

	/** Walks the blocks along the line (Amanatides-Woo) and returns where the first collision box is hit. */
	@Override
	public double clearFraction(Vec from, Vec to) {
		double dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
		if (dx * dx + dy * dy + dz * dz < 1e-12) {
			return 1;
		}
		int x = (int) Math.floor(from.x()), y = (int) Math.floor(from.y()), z = (int) Math.floor(from.z());
		int stepX = (int) Math.signum(dx), stepY = (int) Math.signum(dy), stepZ = (int) Math.signum(dz);
		double tDeltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
		double tDeltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
		double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
		double tMaxX = stepX == 0 ? Double.MAX_VALUE : ((stepX > 0 ? x + 1 - from.x() : from.x() - x) * tDeltaX);
		double tMaxY = stepY == 0 ? Double.MAX_VALUE : ((stepY > 0 ? y + 1 - from.y() : from.y() - y) * tDeltaY);
		double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : ((stepZ > 0 ? z + 1 - from.z() : from.z() - z) * tDeltaZ);
		double tEnter = 0;
		while (tEnter <= 1) {
			byte cell = cell(x, y, z);
			if (cell == FULL) {
				return tEnter;
			}
			if (cell == PARTIAL) {
				double hit = Double.MAX_VALUE;
				for (double[] b : boxes.get(index(x - ox, y - oy, z - oz))) {
					hit = Math.min(hit, slab(from, dx, dy, dz, x + b[0], y + b[1], z + b[2], x + b[3], y + b[4], z + b[5]));
				}
				if (hit <= 1) {
					return Math.max(0, hit);
				}
			}
			if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
				tEnter = tMaxX;
				tMaxX += tDeltaX;
				x += stepX;
			} else if (tMaxY <= tMaxZ) {
				tEnter = tMaxY;
				tMaxY += tDeltaY;
				y += stepY;
			} else {
				tEnter = tMaxZ;
				tMaxZ += tDeltaZ;
				z += stepZ;
			}
		}
		return 1;
	}

	/** Where the line {@code from + t * d} enters the box, or MAX_VALUE when it misses it (0 when it starts inside). */
	private static double slab(Vec from, double dx, double dy, double dz, double x0, double y0, double z0, double x1,
							   double y1, double z1) {
		double tMin = 0, tMax = 1;
		double[] o = {from.x(), from.y(), from.z()};
		double[] d = {dx, dy, dz};
		double[] lo = {x0, y0, z0};
		double[] hi = {x1, y1, z1};
		for (int axis = 0; axis < 3; axis++) {
			if (Math.abs(d[axis]) < 1e-12) {
				if (o[axis] < lo[axis] || o[axis] > hi[axis]) {
					return Double.MAX_VALUE;
				}
				continue;
			}
			double t0 = (lo[axis] - o[axis]) / d[axis];
			double t1 = (hi[axis] - o[axis]) / d[axis];
			if (t0 > t1) {
				double t = t0;
				t0 = t1;
				t1 = t;
			}
			tMin = Math.max(tMin, t0);
			tMax = Math.min(tMax, t1);
			if (tMin > tMax) {
				return Double.MAX_VALUE;
			}
		}
		return tMin;
	}
}
