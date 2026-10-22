package com.survivalcinema.gametest;

import com.google.gson.Gson;
import com.survivalcinema.director.camera.CameraKey;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.camera.TrailSample;
import com.survivalcinema.director.camera.Vec;
import com.survivalcinema.director.render.ClipCamera;
import com.survivalcinema.director.render.RenderPlan;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * Saves what a clip's camera had to work with: the blocks around the player's path (their collision boxes, as the
 * camera's raycasts see them), the path itself and the camera keys that were planned. The unit tests read it back
 * ({@code CameraScene}) to try camera changes on real terrain without starting the game.
 * <p>
 * Gzipped text: {@code item <json>}, {@code piece <from> <to>}, {@code origin <x> <y> <z> <sizeX> <sizeY> <sizeZ>},
 * {@code trail <tick> <x> <y> <z> <yaw> <pitch> <flags>}, {@code key <tick> <x> <y> <z> <yaw> <pitch>},
 * {@code boss <tick> <x> <y> <z>} (where the boss of a boss scene was, the middle of its body),
 * {@code full <dx> <dy> <dz> <run>} (a run of whole blocks along x from the origin) and
 * {@code box <dx> <dy> <dz> <x0> <y0> <z0> <x1> <y1> <z1>} (part of a block, in block coordinates).
 */
final class CameraDump {
	/** Blocks kept around the player's path: farther than any camera stands. */
	private static final int AROUND = 18;
	private static final int BELOW = 4;
	private static final int ABOVE = 18;
	/** Path kept before and after the piece, for the smoothing at its edges. */
	private static final int TRAIL_MARGIN_TICKS = 40;
	private static final Gson GSON = new Gson();

	private CameraDump() {
	}

	static void write(Path file, RenderPlan.Job job, ClipCamera.Piece piece, Trail trail, @Nullable Trail boss,
					  ClipCamera.Keys keys, ClientLevel level) throws IOException {
		List<TrailSample> samples = trail.samples().stream()
			.filter(s -> s.tick() >= piece.fromTick() - TRAIL_MARGIN_TICKS && s.tick() <= piece.toTick() + TRAIL_MARGIN_TICKS)
			.toList();
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (TrailSample s : samples) {
			if (s.tick() < piece.fromTick() || s.tick() > piece.toTick()) {
				continue;
			}
			minX = Math.min(minX, s.x());
			minY = Math.min(minY, s.y());
			minZ = Math.min(minZ, s.z());
			maxX = Math.max(maxX, s.x());
			maxY = Math.max(maxY, s.y());
			maxZ = Math.max(maxZ, s.z());
		}
		for (CameraKey key : keys.camera()) {
			Vec p = key.position();
			maxY = Math.max(maxY, p.y() - ABOVE + 2);
		}
		int x0 = (int) Math.floor(minX) - AROUND;
		int y0 = (int) Math.floor(minY) - BELOW;
		int z0 = (int) Math.floor(minZ) - AROUND;
		int x1 = (int) Math.floor(maxX) + AROUND;
		int y1 = (int) Math.floor(maxY) + ABOVE;
		int z1 = (int) Math.floor(maxZ) + AROUND;

		Files.createDirectories(file.getParent());
		try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)),
			StandardCharsets.UTF_8))) {
			out.println("item " + GSON.toJson(job.item()));
			out.println("piece " + piece.fromTick() + " " + piece.toTick());
			out.println("origin " + x0 + " " + y0 + " " + z0 + " " + (x1 - x0 + 1) + " " + (y1 - y0 + 1) + " " + (z1 - z0 + 1));
			for (TrailSample s : samples) {
				out.println(String.format(Locale.ROOT, "trail %d %.4f %.4f %.4f %.2f %.2f %d", s.tick(), s.x(), s.y(), s.z(),
					s.yaw(), s.pitch(), s.flags()));
			}
			for (TrailSample s : boss == null ? List.<TrailSample>of() : boss.samples()) {
				out.println(String.format(Locale.ROOT, "boss %d %.4f %.4f %.4f", s.tick(), s.x(), s.y(), s.z()));
			}
			for (CameraKey key : keys.camera()) {
				Vec p = key.position();
				out.println(String.format(Locale.ROOT, "key %d %.4f %.4f %.4f %.2f %.2f", key.tick(), p.x(), p.y(), p.z(),
					key.yaw(), key.pitch()));
			}
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					boolean inRun = false;
					int runStart = 0;
					for (int x = x0; x <= x1 + 1; x++) {
						VoxelShape shape = null;
						if (x <= x1) {
							pos.set(x, y, z);
							BlockState state = level.getBlockState(pos);
							shape = state.getCollisionShape(level, pos);
						}
						boolean full = shape != null && !shape.isEmpty() && Block.isShapeFullBlock(shape);
						if (full) {
							if (!inRun) {
								inRun = true;
								runStart = x;
							}
							continue;
						}
						if (inRun) {
							out.println("full " + (runStart - x0) + " " + (y - y0) + " " + (z - z0) + " " + (x - runStart));
							inRun = false;
						}
						if (shape != null && !shape.isEmpty()) {
							for (AABB box : shape.toAabbs()) {
								out.println(String.format(Locale.ROOT, "box %d %d %d %.4f %.4f %.4f %.4f %.4f %.4f", x - x0, y - y0,
									z - z0, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
							}
						}
					}
				}
			}
		}
	}
}
