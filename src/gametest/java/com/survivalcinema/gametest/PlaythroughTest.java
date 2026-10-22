package com.survivalcinema.gametest;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.CinemaSession;
import com.survivalcinema.client.capture.SessionStore;
import com.survivalcinema.client.capture.WorldMemory;
import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.client.flashback.FlashbackCheck;
import com.survivalcinema.client.play.FilmPlayer;
import com.survivalcinema.client.render.RenderPipeline;
import com.survivalcinema.client.ui.CinemaScreen;
import com.survivalcinema.client.ui.ConsentScreen;
import com.survivalcinema.client.ui.DragonOfferScreen;
import com.survivalcinema.client.ui.FilmPlayerScreen;
import com.survivalcinema.client.ui.FilmSetupScreen;
import com.survivalcinema.client.ui.SessionsScreen;
import com.survivalcinema.client.ui.SettingsScreen;
import com.survivalcinema.client.ui.StorageScreen;
import com.survivalcinema.client.voice.apps.CallVoiceRecorder;
import com.survivalcinema.director.camera.Trail;
import com.survivalcinema.director.event.EventLog;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;
import com.survivalcinema.director.render.ClipCamera;
import com.survivalcinema.director.render.FilmFolder;
import com.survivalcinema.director.render.RenderPlan;
import com.survivalcinema.director.render.RenderProgress;
import com.survivalcinema.director.story.EditDecisionList;
import com.survivalcinema.director.story.FilmStats;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@code gradlew runPlayTest} (and the same jar in a copy of a real modpack): a bot plays a short survival the way a
 * player would, and then makes the film the way a player would. It creates a world, answers the recording question,
 * walks, builds a house block by block over two sessions (leaving and coming back), fights zombies at night, sleeps,
 * goes out in the morning (chops a tree, digs a staircase mine down to diamonds, pillars up to look around, falls,
 * nearly dies, eats, pillars up higher and falls to death), respawns, takes off with an elytra and rockets, lights a
 * Nether portal and walks through it, fights and mines there, walks back, fills an End portal with eyes, bridges
 * from the End platform to the island, kills the Ender Dragon with its sword, walks into the exit portal, sees the
 * credits, and presses "Make the film" in the offer that follows. The film is shot, edited, played in the in-game
 * cinema and closed, and the game is back in the world, recording again.
 * <p>
 * The player is never moved by a command: it walks along paths it finds itself ({@link BotPath}), jumps, breaks and
 * places blocks with the same calls a mouse click makes. Commands only set the world up (items, the time of day, the
 * zombies, a solid rock around the mine with diamonds in it, the portal frames) and help with the dragon (it is
 * called down to the portal, and after the bot's sword hits the killing blow is a command). If walking ever fails,
 * the bot is put down at the next point of its way and that is noted in the result.
 * <p>
 * Along the way it checks what the mod noticed (the events of each session), the screenplay (chapters, the base
 * timelapse, the dragon), the film file (length, the "friend in a call" beeping in it) and that "delete the recordings
 * after the film" freed them. Every Survival Cinema error in the log fails the test. Screenshots of every screen land
 * in {@code screenshots/play-*.png}. The result is logged as "PLAYTHROUGH PASSED" or "PLAYTHROUGH FAILED" and written
 * to {@code playtest-result.txt}.
 */
public class PlaythroughTest implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("survivalcinema-playtest");
	private static final String WORLD_NAME = "Cinema Playtest";
	private static final int STEP_TIMEOUT = 20 * 90;
	private static final int FILM_TIMEOUT = 20 * 60 * 25;
	/** Steps of the staircase mine. */
	private static final int STAIRS = 10;
	/** What the bot builds up and bridges with: what it mined first, the planks of the house last. */
	private static final Item[] RUBBLE = {Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.NETHERRACK, Items.OAK_PLANKS};

	/** One step of the play: returns true once done. {@code ticks} counts from the step's start. */
	private interface Step {
		boolean tick(Minecraft mc, int ticks) throws Exception;
	}

	private record Named(String name, Step step, int timeout) {
	}

	private final Deque<Named> steps = new ArrayDeque<>();
	private @Nullable Named current;
	private int stepTicks;
	private int totalTicks;
	private boolean over;
	private final List<String> errors = new ArrayList<>();
	private final List<String> notes = new ArrayList<>();

	// what the play found out on the way
	private String worldFolder;
	private BlockPos base;
	private UUID session1;
	private UUID session2;
	private Path film;
	private @Nullable Process call;
	private int placed;
	private int teleports;
	// the outing on the second morning
	private BlockPos mineSite;
	private Direction mineDir;
	private @Nullable BlockPos treeTrunk;
	private @Nullable BlockPos treeStand;
	private BlockPos pillar1;
	private BlockPos landing1;
	private BlockPos pillar2;
	private BlockPos landing2;
	// the flight, the Nether and the End
	private Vec3 flightGoal;
	private BlockPos takeOff;
	private BlockPos netherPortal;
	private BlockPos netherSpot;
	private Direction netherWall;
	private final List<BlockPos> piglinSpots = new ArrayList<>();
	private BlockPos endFrame;
	private Direction toIsland;
	private int islandLevel;
	private @Nullable BlockPos exitRim;

	@Override
	public void onInitializeClient() {
		if (!Boolean.getBoolean("survivalcinema.playtest")) {
			return;
		}
		watchErrors();
		script();
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		RenderPipeline.listenToCameras(PlaythroughTest::saveCamera);
	}

	/**
	 * Saves the blocks around every planned camera path next to the film's clips ({@code camera/NNN-P.scene.gz}), so a
	 * shot that looks wrong (the dragon finale) can be tried again in the camera unit tests.
	 */
	private static void saveCamera(RenderPlan.Job job, ClipCamera.Piece piece, Trail trail, @Nullable Trail boss, ClipCamera.Keys keys) {
		Minecraft mc = Minecraft.getInstance();
		RenderPipeline pipeline = RenderPipeline.current();
		if (pipeline == null || mc.level == null) {
			return;
		}
		Path file = pipeline.film().resolve("camera").resolve(String.format(Locale.ROOT, "%03d-%d.scene.gz", job.number(), piece.index() + 1));
		try {
			CameraDump.write(file, job, piece, trail, boss, keys, mc.level);
		} catch (IOException e) {
			LOGGER.warn("Could not save the camera scene {}", file, e);
		}
	}

	// ------------------------------------------------------------------ the play

	private void script() {
		step("wait for the title screen", (mc, t) -> mc.level == null && mc.screen != null && mc.getOverlay() == null && t > 60);
		step("start clean", (mc, t) -> {
			cleanGameFolder(mc);
			mc.options.pauseOnLostFocus = false; // the bot plays on when the window is in the background
			SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
			config.recordingTipShown = false;
			config.deleteRecordingsAfterFilm = false;
			config.filmMinutes = 5;
			config.renderQuality = "DRAFT";
			config.save();
			check(FlashbackCheck.result().ok(), "Flashback " + FlashbackCheck.result().version() + " has everything we use: "
				+ FlashbackCheck.result().missing());
			return true;
		});
		step("create a survival world", (mc, t) -> {
			if (t == 1) {
				CreateWorldScreen.openFresh(mc, () -> mc.setScreen(new TitleScreen()));
			}
			if (!(mc.screen instanceof CreateWorldScreen create)) {
				return false;
			}
			WorldCreationUiState world = create.getUiState();
			world.setName(WORLD_NAME);
			world.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
			world.setDifficulty(Difficulty.EASY);
			world.setAllowCommands(true);
			world.setSeed("survivalcinema");
			worldFolder = world.getTargetFolder();
			press(mc, "selectWorld.create");
			return true;
		});
		step("the recording question comes", (mc, t) -> {
			if (!(mc.screen instanceof ConsentScreen)) {
				return false;
			}
			if (t < 30) {
				return false;
			}
			shot(mc, "play-consent");
			press(mc, "survivalcinema.consent.record_always");
			return true;
		});
		step("recording starts", (mc, t) -> {
			CinemaSession session = SurvivalCinemaClient.controller().session();
			if (session == null || mc.player == null) {
				return false;
			}
			session1 = session.replayId();
			LOGGER.info("Recording started: quicksave {}", com.moulberry.flashback.Flashback.getConfig().recordingControls.quicksave);
			check(SurvivalCinemaConfig.get().recordingTipShown, "the first recording tells where the film is made");
			return true;
		});
		wait(40);
		commands("time set 1000", "weather clear");
		step("walk around", (mc, t) -> {
			LocalPlayer player = mc.player;
			player.setYRot(-90 + t * 2);
			mc.options.keyUp.setDown(true);
			mc.options.keyJump.setDown(player.horizontalCollision || player.isInWater());
			// on dry land, not down by the water: the house goes four blocks east of here
			BlockPos site = ground(mc.level, player.getBlockX() + 4, player.getBlockZ());
			boolean dry = player.onGround() && !player.isInWater() && player.getBlockY() > mc.level.getSeaLevel()
				&& site != null && Math.abs(site.getY() - player.getBlockY()) <= 1;
			if (t >= 80 && dry || t >= 20 * 40) {
				mc.options.keyUp.setDown(false);
				mc.options.keyJump.setDown(false);
				return true;
			}
			return false;
		}, 20 * 45);
		step("clear a place for the base", (mc, t) -> {
			LocalPlayer player = mc.player;
			BlockPos feet = player.blockPosition();
			base = new BlockPos(feet.getX() + 4, feet.getY(), feet.getZ());
			int x = base.getX();
			int y = base.getY();
			int z = base.getZ();
			// the sea or a river next to the spot would run in: dry land around it first
			command(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d dirt replace water", x - 16, y - 4, z - 16, x + 16, y + 2, z + 16));
			command(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d air", x - 8, y, z - 8, x + 8, y + 12, z + 8));
			command(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d dirt", x - 8, y - 3, z - 8, x + 8, y - 2, z + 8));
			command(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d grass_block", x - 8, y - 1, z - 8, x + 8, y - 1, z + 8));
			command(mc, "kill @e[type=!minecraft:player,distance=..16]"); // sheep and chickens wander off the building site
			LOGGER.info("Base at {}", base);
			return true;
		});
		step("the building site is ready", (mc, t) -> {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					if (!mc.level.getBlockState(base.offset(dx, -1, dz)).is(Blocks.GRASS_BLOCK)
						|| !mc.level.getBlockState(base.offset(dx, 0, dz)).isAir()) {
						return false;
					}
				}
			}
			return true;
		}, 20 * 15);
		step("walk to the middle of the building site", (mc, t) -> walkTo(mc, base, 0, t));
		giveBuildingKit();
		wait(10);
		// the floor: 7 x 7 on the grass, the middle (where the bot stands) comes later
		step("lay the floor", (mc, t) -> buildEvery(mc, t, floor()));
		step("place the bed and the chest", (mc, t) -> {
			if (t == 1) {
				aim(-90, 30); // facing east: the bed lies east-west
				return false;
			}
			if (t == 10) {
				check(Math.abs(Mth.wrapDegrees(mc.player.getYRot() + 90)) < 30, "the bot faces east, got " + mc.player.getYRot());
				place(mc, base.offset(-2, 1, 1), Items.WHITE_BED);
			}
			if (t == 18) {
				place(mc, base.offset(2, 1, -2), Items.CHEST);
			}
			if (t == 24) {
				check(mc.level.getBlockState(base.offset(-2, 1, 1)).getBlock() instanceof BedBlock, "the bed is placed");
				check(mc.level.getBlockState(base.offset(2, 1, -2)).getBlock() instanceof ChestBlock, "the chest is placed");
				return true;
			}
			return false;
		});
		// nightfall and a fight on the unfinished floor
		step("the sun goes down", this::dusk, 20 * 20);
		wait(40);
		step("zombies come", (mc, t) -> {
			int x = base.getX();
			int y = base.getY();
			int z = base.getZ();
			for (int[] at : new int[][]{{7, 0}, {0, 7}, {-7, 0}}) {
				command(mc, "summon minecraft:zombie " + middle(x + at[0], y, z + at[1]) + " {PersistenceRequired:1b}");
			}
			return true;
		});
		step("fight the zombies", (mc, t) -> fight(mc, t, EntityType.ZOMBIE, Vec3.atCenterOf(base), 16), 20 * 60);
		commands("difficulty peaceful");
		step("back to the middle", (mc, t) -> walkTo(mc, base, 0, t));
		step("raise the walls", (mc, t) -> buildEvery(mc, t, walls()));
		step("hang the door", (mc, t) -> {
			if (t == 1) {
				aim(180, 20); // facing north, out through the doorway
				return false;
			}
			if (t == 10) {
				place(mc, base.offset(0, 1, -3), Items.OAK_DOOR);
			}
			if (t == 16) {
				check(mc.level.getBlockState(base.offset(0, 1, -3)).getBlock() instanceof DoorBlock, "the door is placed");
				return true;
			}
			return false;
		});
		step("go to bed", (mc, t) -> {
			BlockPos bed = base.offset(-2, 1, 1);
			look(mc.player, Vec3.atCenterOf(bed));
			if (t % 20 == 8) {
				mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(bed).add(0, 0.06, 0), Direction.UP, bed, false));
			}
			return mc.player.isSleeping();
		});
		step("sleep through the night", (mc, t) -> t > 40 && !mc.player.isSleeping() && mc.level.getDayTime() % 24_000 < 12_000, 20 * 30);
		wait(30);

		// ---- the second morning: out for wood, a mine, a look from up high... and a fall too many
		step("look around in the morning", (mc, t) -> lookAround(mc, t, 50));
		commands("item replace entity @s hotbar.3 with minecraft:iron_pickaxe", "item replace entity @s hotbar.4 with minecraft:iron_axe",
			"item replace entity @s hotbar.5 with minecraft:torch 16", "item replace entity @s hotbar.7 with minecraft:golden_apple");
		step("go out through the door", (mc, t) -> walkTo(mc, base.offset(0, 0, -5), 0, t), 20 * 30);
		step("pick a place for a mine", (mc, t) -> planOuting(mc));
		step("walk to a tree", (mc, t) -> treeStand == null || walkTo(mc, treeStand, 0, t), 20 * 60);
		step("chop the tree", this::chop, 20 * 40);
		step("walk to the place for the mine", (mc, t) -> walkTo(mc, mineSite, 0, t), 20 * 60);
		step("look around there", (mc, t) -> lookAround(mc, t, 40));
		step("the rock under the mine", this::prepareMine);
		step("dig a staircase mine", this::digStairs, 20 * 120);
		step("diamonds", (mc, t) -> {
			int diamonds = count(mc.player, Items.DIAMOND);
			if (diamonds >= 3 || t > 20 * 5) {
				LOGGER.info("Diamonds from the mine: {}", diamonds);
				check(diamonds > 0, "the bot mined diamonds");
				return true;
			}
			return false;
		});
		step("climb out of the mine", (mc, t) -> walkTo(mc, mineSite, 0, t), 20 * 40);
		step("pick places to look around from", (mc, t) -> planPillars(mc));
		step("walk to a place to look around from", (mc, t) -> walkTo(mc, pillar1, 0, t), 20 * 30);
		// a fall from as many blocks as the bot has health leaves it with two hearts or less
		step("build up to look around", (mc, t) -> pillar(mc, t, Math.max(16, Mth.ceil(mc.player.getHealth()))), 20 * 60);
		step("look around from up there", (mc, t) -> lookAround(mc, t, 50));
		step("step off and fall", (mc, t) -> stepOff(mc, t, landing1), 20 * 15);
		step("nearly dead from the fall", (mc, t) -> {
			if (t == 2) {
				LOGGER.info("After the fall: {} health", mc.player.getHealth());
				if (mc.player.getHealth() > 4) {
					notes.add("the fall left the bot with " + mc.player.getHealth() + " health");
				}
			}
			return t > 10;
		});
		step("eat a golden apple", (mc, t) -> eat(mc, t, Items.GOLDEN_APPLE), 20 * 10);
		step("get better", (mc, t) -> t > 20 && mc.player.getHealth() > 6, 20 * 40);
		step("walk to another place", (mc, t) -> walkTo(mc, pillar2, 0, t), 20 * 30);
		step("build up even higher", (mc, t) -> pillar(mc, t, 32), 20 * 80);
		step("fall to death", (mc, t) -> {
			if (mc.screen instanceof DeathScreen) {
				stop(mc);
				return true;
			}
			if (stepOff(mc, t, landing2)) {
				check(mc.player.isDeadOrDying() || mc.player.getY() < stepOffFrom - 25, "a deadly fall, but the bot landed alive at "
					+ mc.player.position() + " with " + mc.player.getHealth() + " health");
			}
			return false;
		}, 20 * 15);
		step("respawn", (mc, t) -> {
			if (t == 40) {
				shot(mc, "play-death");
				press(mc, "deathScreen.respawn");
			}
			return t > 40 && mc.screen == null && mc.player != null && mc.player.isAlive();
		});
		wait(60);
		step("save and quit to the title screen", (mc, t) -> {
			if (t == 1) {
				var controls = com.moulberry.flashback.Flashback.getConfig().recordingControls;
				LOGGER.info("Before quitting: recording {}, quicksave {}, automaticallyFinish {}", FlashbackBridge.isRecording(),
					controls.quicksave, controls.automaticallyFinish);
				mc.pauseGame(false);
			}
			if (t == 20) {
				check(mc.screen instanceof PauseScreen, "the pause menu is open, got " + mc.screen);
				shot(mc, "play-pause-menu");
				press(mc, "menu.returnToMenu");
			}
			return t > 20 && mc.level == null && mc.screen instanceof TitleScreen;
		});
		step("the first session is saved", (mc, t) -> {
			if (t < 40) {
				return false;
			}
			check(FlashbackBridge.replaysById().containsKey(session1), "the first session's replay is saved");
			return true;
		});

		// ---- the second session: back in the same world
		step("come back to the world", (mc, t) -> {
			if (t == 1) {
				mc.createWorldOpenFlows().openWorld(worldFolder, () -> mc.setScreen(new TitleScreen()));
			}
			CinemaSession session = SurvivalCinemaClient.controller().session();
			check(!(mc.screen instanceof ConsentScreen), "'always in this world' does not ask again");
			if (session == null || mc.player == null || session.replayId().equals(session1)) {
				return false;
			}
			session2 = session.replayId();
			return true;
		});
		wait(60);
		commands("item replace entity @s hotbar.0 with minecraft:oak_planks 64", "item replace entity @s hotbar.1 with minecraft:oak_planks 64",
			"item replace entity @s hotbar.2 with minecraft:diamond_sword", "item replace entity @s hotbar.3 with minecraft:elytra",
			"item replace entity @s hotbar.4 with minecraft:firework_rocket 4", "item replace entity @s hotbar.5 with minecraft:diamond_pickaxe",
			"item replace entity @s hotbar.6 with minecraft:flint_and_steel", "item replace entity @s hotbar.7 with minecraft:cobblestone 32",
			"item replace entity @s hotbar.8 with minecraft:bread 16");
		wait(10);
		step("go to the middle of the house", (mc, t) -> walkTo(mc, base.offset(1, 1, 0), 0, t));
		step("finish the floor and put on the roof", (mc, t) -> buildEvery(mc, t, roof()));
		wait(40);
		step("go out through the door", (mc, t) -> walkTo(mc, base.offset(0, 0, -5), 0, t), 20 * 30);
		step("put on the elytra", (mc, t) -> {
			if (t == 1) {
				bring(mc, Items.ELYTRA);
			}
			if (t == 5) {
				mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			}
			return t > 10 && mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.ELYTRA);
		}, 20 * 5);
		step("pick a place to fly to", (mc, t) -> planFlight(mc));
		step("walk to the take-off place", (mc, t) -> walkTo(mc, takeOff, 0, t), 20 * 30);
		// a hard landing on the trees should not end the test (no particles: nothing to see in the film)
		commands("effect give @s minecraft:resistance 60 4 true");
		step("fly with an elytra", this::fly, 20 * 60);
		step("swim ashore and walk home", (mc, t) -> walkTo(mc, base.offset(0, 0, 5), 1, t, new BotPath.Rules(12, true)), 20 * 120);
		// a friend in a call from here to the dragon: the beeping "voice" must be heard in the film (the arrival in the
		// Nether plays at normal speed; the dragon's death animation is in slow motion, with the voices at normal speed)
		step("a friend calls", (mc, t) -> {
			call = TestCall.start(mc.gameDirectory.toPath(), 1200);
			CallVoiceRecorder.test = new CallVoiceRecorder.TestRecording(session2, Math.max(0, FlashbackBridge.recordingTick()),
				System.nanoTime(), call.pid());
			return true;
		});
		step("build a Nether portal", (mc, t) -> {
			int x = base.getX();
			int y = base.getY();
			int z = base.getZ() + 7;
			command(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:obsidian", x - 1, y - 1, z, x + 2, y + 3, z));
			command(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x, y, z, x + 1, y + 2, z));
			return true;
		});
		wait(20);
		step("walk up to the portal", (mc, t) -> walkTo(mc, base.offset(0, 0, 5), 0, t));
		step("light the portal", (mc, t) -> {
			BlockPos bottom = base.offset(0, -1, 7);
			Vec3 hit = Vec3.atCenterOf(bottom).add(0, 0.5, 0);
			look(mc.player, hit);
			if (t == 8) {
				bring(mc, Items.FLINT_AND_STEEL);
				mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, bottom, false));
				mc.player.swing(InteractionHand.MAIN_HAND);
			}
			return t > 15 && mc.level.getBlockState(bottom.above()).is(Blocks.NETHER_PORTAL);
		}, 20 * 5);
		step("walk into the portal", (mc, t) -> {
			if (mc.level.dimension() == Level.NETHER) {
				stop(mc);
				return true;
			}
			walkStraight(mc, new Vec3(base.getX() + 1.0, base.getY(), base.getZ() + 7.5));
			return false;
		}, 20 * 30);
		step("look around the Nether", (mc, t) -> lookAround(mc, t, 60));
		// a little adventure there, so the film has moments from the Nether and not only the arrival
		// a gang of angry piglins should not end the test either (no particles: nothing to see in the film)
		commands("effect give @s minecraft:fire_resistance 300 0 true", "effect give @s minecraft:resistance 120 3 true", "difficulty easy");
		step("pick a way through the Nether", (mc, t) -> planNether(mc));
		step("walk through the Nether", (mc, t) -> walkTo(mc, netherSpot, 0, t), 20 * 40);
		step("zombified piglins", (mc, t) -> {
			for (BlockPos spot : piglinSpots) {
				command(mc, "summon minecraft:zombified_piglin " + middle(spot.getX(), spot.getY(), spot.getZ()) + " {PersistenceRequired:1b}");
			}
			return true;
		});
		step("a fight in the Nether", (mc, t) -> {
			if (t == 20 * 25) {
				notes.add("the zombified piglins were out of reach, killed with /kill");
				command(mc, "kill @e[type=minecraft:zombified_piglin]");
			}
			return fight(mc, t, EntityType.ZOMBIFIED_PIGLIN, Vec3.atCenterOf(netherSpot), 20);
		}, 20 * 30);
		commands("difficulty peaceful");
		step("back to the wall", (mc, t) -> walkTo(mc, netherSpot, 0, t), 20 * 30);
		step("ancient debris in the wall", (mc, t) -> {
			BlockPos wall = netherSpot.above().relative(netherWall);
			command(mc, "setblock " + wall.getX() + " " + wall.getY() + " " + wall.getZ() + " minecraft:ancient_debris");
			return true;
		});
		step("mine the ancient debris", (mc, t) -> {
			BlockPos wall = netherSpot.above().relative(netherWall);
			if (t < 10) {
				look(mc.player, Vec3.atCenterOf(wall));
				return false;
			}
			if (!dig(mc, wall)) {
				return false;
			}
			return count(mc.player, Items.ANCIENT_DEBRIS) > 0 || t > 20 * 15;
		}, 20 * 20);
		// meanwhile at home: the End portal of a stronghold, all but three eyes in, as if found and carried over
		step("an End portal at home", this::setUpEndPortal);
		step("walk back to the portal", (mc, t) -> walkTo(mc, netherPortal, 1.5, t), 20 * 40);
		step("step into the portal", (mc, t) -> {
			if (mc.level.dimension() == Level.OVERWORLD) {
				stop(mc);
				return true;
			}
			if (mc.level.getBlockState(mc.player.blockPosition()).is(Blocks.NETHER_PORTAL)) {
				stop(mc); // and wait for it to take the bot
				return false;
			}
			walkStraight(mc, Vec3.atBottomCenterOf(netherPortal));
			return false;
		}, 20 * 30);
		step("back in the overworld", (mc, t) -> mc.level.dimension() == Level.OVERWORLD && t > 40);

		// ---- the End (the friend is still in the call)
		commands("item replace entity @s hotbar.3 with minecraft:ender_eye 3");
		step("walk to the End portal", (mc, t) -> walkTo(mc, endFrame.offset(3, 0, 0), 0, t), 20 * 40);
		step("put in the last eyes", this::fillEndPortal, 20 * 10);
		step("jump into the End portal", (mc, t) -> {
			if (mc.level.dimension() == Level.END) {
				stop(mc);
				return true;
			}
			walkStraight(mc, Vec3.atBottomCenterOf(endFrame));
			return false;
		}, 20 * 30);
		step("the dragon appears", (mc, t) -> mc.level.dimension() == Level.END && dragon(mc) != null && t > 20, 20 * 40);
		// the dragon's charges and fireballs should not end the test (no particles: nothing to see in the film)
		commands("effect give @s minecraft:resistance 600 4 true");
		step("look around the End", (mc, t) -> lookAround(mc, t, 40));
		step("climb up to the island", this::climbToIsland, 20 * 30);
		step("bridge over to the island", this::bridge, 20 * 40);
		step("walk to the middle of the island", (mc, t) -> {
			BlockPos middle = ground(mc.level, 0, 9);
			check(middle != null, "ground in the middle of the island");
			return walkTo(mc, middle, 1, t);
		}, 20 * 90);
		step("kill the dragon", this::killDragon, 20 * 100);
		step("the call ends", (mc, t) -> {
			if (t == 20 * 3) {
				CallVoiceRecorder.test = null;
				call.destroyForcibly();
			}
			return t > 20 * 3;
		});
		// up onto its rim first (the ground round it may be a block lower, with the rim at head height), then in
		step("walk up to the exit portal", (mc, t) -> {
			if (t == 1) {
				check(!exitPortalBlocks(mc).isEmpty(), "the exit portal is open");
				exitRim = exitPortalRim(mc);
				check(exitRim != null, "a way up onto the exit portal's rim");
			}
			return walkTo(mc, exitRim, 0, t);
		}, 20 * 30);
		step("walk into the exit portal", (mc, t) -> {
			if (mc.screen instanceof WinScreen) {
				stop(mc);
				return true;
			}
			// stuck on the rim (the pillar or the egg in the way): a jump, and another block of the portal to aim at
			List<BlockPos> portal = exitPortalBlocks(mc);
			if (!portal.isEmpty()) {
				BlockPos target = portal.get((t / 40) % portal.size());
				walkStraight(mc, Vec3.atBottomCenterOf(target));
				if (t % 40 > 30) {
					mc.options.keyJump.setDown(mc.player.onGround());
				}
				if (t % 40 == 0) {
					LOGGER.info("Into the exit portal: the bot at {}, aiming at {}", mc.player.position(), target);
				}
			}
			return false;
		}, 20 * 40);
		step("watch the credits for a while", (mc, t) -> {
			if (t == 20 * 4) {
				shot(mc, "play-credits");
				mc.screen.onClose(); // like pressing Esc in the credits
			}
			return t > 20 * 4 && mc.level != null && mc.level.dimension() == Level.OVERWORLD;
		}, 20 * 30);
		step("the film is offered", (mc, t) -> mc.screen instanceof DragonOfferScreen, 20 * 30);
		step("say yes to the film", (mc, t) -> {
			if (t == 20) {
				shot(mc, "play-offer");
				// the player turned on "delete recordings after the film" in the settings earlier
				SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
				config.deleteRecordingsAfterFilm = true;
				config.save();
				press(mc, "survivalcinema.offer.make");
			}
			return t > 20 && RenderPipeline.isRunning();
		}, 20 * 60);
		step("what the bot did was noticed", (mc, t) -> {
			saveEvidence(mc);
			checkEvents(mc);
			film = RenderPipeline.current().film();
			return true;
		});
		step("the film is shot and edited", (mc, t) -> {
			RenderPipeline pipeline = RenderPipeline.current();
			screenshotPhase(mc, pipeline);
			return pipeline.phase().isFinal() && t % 20 == 0;
		}, FILM_TIMEOUT);
		step("the film is done", (mc, t) -> {
			RenderPipeline pipeline = RenderPipeline.current();
			check(pipeline.phase() == RenderPipeline.Phase.DONE, "the film is finished, got " + pipeline.phase());
			checkFilm(mc, pipeline);
			return true;
		});
		step("the film plays by itself", (mc, t) -> mc.screen instanceof FilmPlayerScreen, 20 * 30);
		step("watch the film", (mc, t) -> {
			if (t < 20 * 6) {
				return false;
			}
			FilmPlayerScreen player = (FilmPlayerScreen) mc.screen;
			FilmPlayerScreen.Status status = player.status();
			LOGGER.info("Cinema after 6 s: {}", status);
			check(status.state() == FilmPlayer.State.PLAYING, "the film plays, got " + status.state());
			check(status.position() > 3, "the film moves on, at " + status.position());
			check(status.hasSound(), "the film has sound");
			shot(mc, "play-cinema");
			player.onClose();
			return true;
		});
		step("close the cinema and go back to the world", (mc, t) -> {
			if (t == 10) {
				check(mc.screen instanceof CinemaScreen, "back on the finished film screen, got " + mc.screen);
				shot(mc, "play-film-ready");
				press(mc, "survivalcinema.cinema.close");
			}
			CinemaSession session = SurvivalCinemaClient.controller().session();
			return t > 10 && session != null && mc.player != null && !session.replayId().equals(session2);
		}, 20 * 90);
		step("the recordings were deleted after the film", (mc, t) -> {
			if (t < 20 * 5) {
				return false;
			}
			checkDeleted(mc);
			return true;
		});
		step("look at the settings screens", this::lookAtScreens, 20 * 60);
		step("done", (mc, t) -> {
			finish(mc, null);
			return true;
		});
	}

	// ---- building

	/** 7 x 7 floor around the base, all but the middle. */
	private List<BlockPos> floor() {
		List<BlockPos> blocks = new ArrayList<>();
		for (int ring = 1; ring <= 3; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
						blocks.add(base.offset(dx, 0, dz));
					}
				}
			}
		}
		return blocks;
	}

	/** Three layers of wall on the floor's edge, with a doorway in the north wall; the lintel last in its layer. */
	private List<BlockPos> walls() {
		List<BlockPos> blocks = new ArrayList<>();
		for (int dy = 1; dy <= 3; dy++) {
			BlockPos lintel = null;
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != 3) {
						continue;
					}
					if (dx == 0 && dz == -3) {
						if (dy == 3) {
							lintel = base.offset(dx, dy, dz);
						}
						continue; // the doorway
					}
					blocks.add(base.offset(dx, dy, dz));
				}
			}
			if (lintel != null) {
				blocks.add(lintel);
			}
		}
		return blocks;
	}

	/** The last floor block in the middle, then the roof from the walls inwards. */
	private List<BlockPos> roof() {
		List<BlockPos> blocks = new ArrayList<>();
		blocks.add(base);
		for (int ring = 3; ring >= 0; ring--) {
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
						blocks.add(base.offset(dx, 4, dz));
					}
				}
			}
		}
		return blocks;
	}

	/**
	 * Places a block every third tick, the first of the list that is not there yet (a click that got lost is made
	 * again before the block above it needs it); done when all are placed (and checked).
	 */
	private boolean buildEvery(Minecraft mc, int t, List<BlockPos> blocks) {
		int index = (t - 1) / 3;
		if ((t - 1) % 3 == 0 && index < blocks.size()) {
			for (BlockPos block : blocks.subList(0, index + 1)) {
				if (!mc.level.getBlockState(block).is(Blocks.OAK_PLANKS)) {
					place(mc, block, Items.OAK_PLANKS);
					break;
				}
			}
		}
		if (index < blocks.size() + 4) {
			return false;
		}
		List<BlockPos> missing = blocks.stream().filter(block -> !mc.level.getBlockState(block).is(Blocks.OAK_PLANKS)).toList();
		if (missing.isEmpty()) {
			return true;
		}
		// a block that did not go in (a mob stood there, a click got lost): a player just clicks again
		int extra = t - (blocks.size() + 4) * 3;
		if (extra < 20 * 6) {
			if (extra % 6 == 0) {
				LOGGER.info("Placing {} again", missing.getFirst());
				place(mc, missing.getFirst(), Items.OAK_PLANKS);
			}
			return false;
		}
		BlockPos first = missing.getFirst();
		List<String> near = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity.position().distanceToSqr(Vec3.atCenterOf(first)) < 4) {
				near.add(entity.getType().toShortString() + " at " + entity.position());
			}
		}
		throw new AssertionError("Expected: " + missing.size() + " of " + blocks.size() + " blocks placed, missing " + missing + " (there: "
			+ mc.level.getBlockState(first) + ", below: " + mc.level.getBlockState(first.below()) + ", near it: " + near + ")");
	}

	private void giveBuildingKit() {
		commands("item replace entity @s hotbar.0 with minecraft:oak_planks 64",
			"item replace entity @s hotbar.1 with minecraft:oak_planks 64",
			"item replace entity @s hotbar.2 with minecraft:oak_planks 64",
			"item replace entity @s hotbar.3 with minecraft:white_bed",
			"item replace entity @s hotbar.4 with minecraft:chest",
			"item replace entity @s hotbar.5 with minecraft:oak_door",
			"item replace entity @s hotbar.6 with minecraft:diamond_sword",
			"item replace entity @s hotbar.8 with minecraft:bread 16");
	}

	/** Places a block the way a player does: looking at a face of a block next to it and clicking with the item. */
	private void place(Minecraft mc, BlockPos target, Item item) {
		LocalPlayer player = mc.player;
		bring(mc, item);
		BlockPos support = null;
		Direction face = null;
		if (solid(mc, target.below())) {
			support = target.below();
			face = Direction.UP;
		} else {
			for (Direction side : Direction.Plane.HORIZONTAL) {
				if (solid(mc, target.relative(side))) {
					support = target.relative(side);
					face = side.getOpposite();
					break;
				}
			}
		}
		check(support != null, "something to build " + target + " on (below: " + mc.level.getBlockState(target.below()) + ", there: "
			+ mc.level.getBlockState(target) + ", bot at " + player.position() + ")");
		placeOn(mc, support, face);
	}

	/** Clicks a face of a block with what is in the hand: the new block goes next to that face. */
	private void placeOn(Minecraft mc, BlockPos support, Direction face) {
		LocalPlayer player = mc.player;
		Vec3 hit = Vec3.atCenterOf(support).add(face.getUnitVec3().scale(0.5));
		look(player, hit);
		mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
		player.swing(InteractionHand.MAIN_HAND);
		placed++;
	}

	/** A full block to build on that does nothing when clicked (not a door, bed or chest). */
	private static boolean solid(Minecraft mc, BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		return !state.isAir() && state.isCollisionShapeFullBlock(mc.level, pos) && !(state.getBlock() instanceof DoorBlock)
			&& !(state.getBlock() instanceof BedBlock) && !(state.getBlock() instanceof ChestBlock);
	}

	/**
	 * Takes the first of these items the bot has into its hand: from the hotbar, or swapped in from the rest of the
	 * inventory the way a player presses a number key over it.
	 */
	private static void bring(Minecraft mc, Item... items) {
		Inventory inventory = mc.player.getInventory();
		for (Item item : items) {
			for (int slot = 0; slot < 9; slot++) {
				if (inventory.getItem(slot).is(item)) {
					inventory.setSelectedSlot(slot);
					return;
				}
			}
		}
		for (Item item : items) {
			for (int slot = 9; slot < 36; slot++) {
				if (inventory.getItem(slot).is(item)) {
					int hotbar = 0;
					for (int free = 8; free >= 0; free--) {
						if (inventory.getItem(free).isEmpty()) {
							hotbar = free;
						}
					}
					mc.gameMode.handleInventoryMouseClick(mc.player.inventoryMenu.containerId, slot, hotbar, ClickType.SWAP, mc.player);
					inventory.setSelectedSlot(hotbar);
					return;
				}
			}
		}
		throw new AssertionError("Expected: one of " + Arrays.toString(items) + " with the bot");
	}

	private static int count(LocalPlayer player, Item item) {
		Inventory inventory = player.getInventory();
		int count = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	// ---- looking: the head turns a bit every tick, like a mouse, never snaps

	private @Nullable Float aimYaw;
	private float aimPitch;

	/** Turns the head toward a point over the next ticks. */
	private void look(LocalPlayer player, Vec3 at) {
		Vec3 d = at.subtract(player.getEyePosition());
		double flat = Math.sqrt(d.x * d.x + d.z * d.z);
		aim((float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) Math.toDegrees(-Math.atan2(d.y, flat)));
	}

	private void aim(float yaw, float pitch) {
		aimYaw = yaw;
		aimPitch = Mth.clamp(pitch, -90, 90);
	}

	/** Every tick, after the step: the head moves a share of the way to where the bot wants to look. */
	private void turnHead(LocalPlayer player) {
		if (aimYaw == null) {
			return;
		}
		player.setYRot(player.getYRot() + ease(Mth.wrapDegrees(aimYaw - player.getYRot()), 30));
		player.setXRot(Mth.clamp(player.getXRot() + ease(aimPitch - player.getXRot(), 20), -90, 90));
	}

	private static float ease(float difference, float most) {
		float step = difference * 0.4f;
		if (Math.abs(step) < 2) {
			step = Math.abs(difference) < 2 ? difference : Math.signum(difference) * 2;
		}
		return Mth.clamp(step, -most, most);
	}

	/** Whether the head is about where the bot wants to look. */
	private boolean aimed(LocalPlayer player, float within) {
		return aimYaw == null || Math.abs(Mth.wrapDegrees(aimYaw - player.getYRot())) < within && Math.abs(aimPitch - player.getXRot()) < within;
	}

	/** Turns straight away, at most so many degrees a tick (flying: the step steers the head itself). */
	private void steer(LocalPlayer player, float yaw, float pitch, float yawStep, float pitchStep) {
		aimYaw = null;
		player.setYRot(player.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - player.getYRot()), -yawStep, yawStep));
		player.setXRot(Mth.clamp(player.getXRot() + Mth.clamp(pitch - player.getXRot(), -pitchStep, pitchStep), -90, 90));
	}

	private float lookAroundFrom;

	/** Looks left and right, up a little and down, for a while. */
	private boolean lookAround(Minecraft mc, int t, int ticks) {
		stop(mc);
		if (t == 1) {
			lookAroundFrom = mc.player.getYRot();
		}
		double turn = Math.sin(t * Math.PI * 2 / ticks);
		aim(lookAroundFrom + (float) (75 * turn), (float) (-4 + 12 * Math.sin(t * Math.PI * 4 / ticks)));
		return t >= ticks;
	}

	// ---- walking

	private @Nullable List<BlockPos> route;
	private int routeIndex;
	private int routeTries;
	private double routeBest;
	private int routeBestAt;
	private int doorUsedAt = -100;

	private boolean walkTo(Minecraft mc, BlockPos goal, double reach, int t) {
		return walkTo(mc, goal, reach, t, BotPath.Rules.WALK);
	}

	/**
	 * Walks with the movement keys to the feet block {@code goal} (or within {@code reach} of it) along a way a player
	 * would take: around trees, jumping up steps, opening doors, swimming when the rules allow. Stuck, it jumps and
	 * looks for another way; only when nothing works the bot is put down at the next point of its way (noted).
	 */
	private boolean walkTo(Minecraft mc, BlockPos goal, double reach, int t, BotPath.Rules rules) {
		if (t == 1 || route == null) {
			if (t == 1) {
				routeTries = 0;
			}
			plan(mc, goal, reach, rules, t);
		}
		return follow(mc, goal, reach, t, rules);
	}

	/** Walks along {@link #route}; stuck, it plans again (with {@code rules}), or with no rules leaves the planning to the caller. */
	private boolean follow(Minecraft mc, BlockPos goal, double reach, int t, @Nullable BotPath.Rules rules) {
		LocalPlayer player = mc.player;
		Vec3 p = player.position();
		boolean settled = player.onGround() || player.isInWater();
		double toGoal = horizontal(p, Vec3.atBottomCenterOf(goal));
		// to a block itself: right in its middle (the bot builds around itself and must not stand in the next block)
		if (settled && Math.abs(p.y - goal.getY()) < 1.5 && (reach > 0 ? toGoal <= reach + 0.3 : toGoal < 0.15)) {
			stop(mc);
			route = null;
			return true;
		}
		while (routeIndex < route.size() - 1) {
			BlockPos at = route.get(routeIndex);
			if (horizontal(p, Vec3.atBottomCenterOf(at)) < 0.45 && (Math.abs(p.y - at.getY()) < 1.2 || at.getY() < p.y)) {
				routeIndex++;
			} else {
				break;
			}
		}
		BlockPos next = route.get(routeIndex);
		Vec3 point = Vec3.atBottomCenterOf(next);
		double dx = point.x - p.x;
		double dz = point.z - p.z;
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (openDoor(mc, next, distance, t) || openDoor(mc, next.above(), distance, t)) {
			return false;
		}
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		aim(yaw, 12);
		float off = Math.abs(Mth.wrapDegrees(yaw - player.getYRot()));
		mc.options.keyUp.setDown(off < 60);
		mc.options.keySprint.setDown(route.size() - routeIndex > 6 && off < 15 && !player.isInWater());
		// the last steps to a block's middle are small ones
		mc.options.keyShift.setDown(reach == 0 && routeIndex == route.size() - 1 && distance < 0.8 && player.onGround()
			&& Math.abs(next.getY() - p.y) < 0.5);
		boolean climb = next.getY() > p.y + 0.5 && distance < 1.6;
		mc.options.keyJump.setDown(player.isInWater() || player.onGround() && (climb || player.horizontalCollision && off < 30));
		// stuck: no nearer to the end of the way for a while
		double left = route.size() - 1 - routeIndex + distance;
		if (left < routeBest - 0.5) {
			routeBest = left;
			routeBestAt = t;
		} else if (t - routeBestAt > 50) {
			if (rules == null) {
				route = null;
				return false;
			}
			routeTries++;
			LOGGER.info("Walk: stuck at {} on the way to {} (try {})", p, next, routeTries);
			if (routeTries > 3) {
				stepOver(mc, next, "stuck at " + BlockPos.containing(p) + " on the way to " + goal);
				routeTries = 0;
				return false;
			}
			plan(mc, goal, reach, rules, t);
		}
		return false;
	}

	private void plan(Minecraft mc, BlockPos goal, double reach, BotPath.Rules rules, int t) {
		BlockPos from = mc.player.blockPosition();
		List<BlockPos> path = BotPath.find(mc.level, from, goal, reach, rules);
		if (path == null && !rules.swim()) {
			path = BotPath.find(mc.level, from, goal, reach, new BotPath.Rules(rules.maxDrop(), true));
		}
		if (path == null) {
			LOGGER.info("Walk: no way known from {} to {}, straight on", from, goal);
			path = List.of(from, goal);
		} else {
			LOGGER.info("Walk: {} steps from {} to {}", path.size(), from, goal);
		}
		take(path, t);
	}

	private void take(List<BlockPos> path, int t) {
		route = path;
		routeIndex = path.size() > 1 ? 1 : 0;
		routeBest = Double.MAX_VALUE;
		routeBestAt = t;
	}

	private @Nullable BlockPos chaseGoal;
	private int chaseAgainAt;

	/**
	 * Goes after a mob along a way a player can walk, planned again as the mob moves. With no such way (the mob on a
	 * ledge) the bot waits for it to come down instead of running off the edge.
	 */
	private void chase(Minecraft mc, int t, Entity target) {
		BlockPos at = target.blockPosition();
		boolean moved = chaseGoal == null || horizontal(at, chaseGoal) > 2 || Math.abs(at.getY() - chaseGoal.getY()) > 1;
		if ((route == null || moved) && t >= chaseAgainAt) {
			chaseGoal = at;
			chaseAgainAt = t + 10;
			List<BlockPos> path = BotPath.find(mc.level, mc.player.blockPosition(), at, 2, BotPath.Rules.WALK, 4000);
			if (path == null) {
				route = null;
				chaseAgainAt = t + 20;
			} else {
				take(path, t);
			}
		}
		if (route == null) {
			stop(mc);
			look(mc.player, target.getEyePosition());
			return;
		}
		if (follow(mc, chaseGoal, 2, t, null)) {
			route = null;
		}
	}

	/** A closed door on the way is opened like a player does: stop, look at it, click it. */
	private boolean openDoor(Minecraft mc, BlockPos pos, double distance, int t) {
		BlockState state = mc.level.getBlockState(pos);
		if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.OPEN) || distance > 2.2) {
			return false;
		}
		LocalPlayer player = mc.player;
		stop(mc);
		Vec3 middle = Vec3.atCenterOf(pos);
		look(player, middle);
		if (aimed(player, 15) && t - doorUsedAt > 10) {
			Direction face = Direction.getApproximateNearest(player.getEyePosition().subtract(middle));
			mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(middle, face, pos, false));
			player.swing(InteractionHand.MAIN_HAND);
			doorUsedAt = t;
		}
		return true;
	}

	/**
	 * The last resort when walking does not get there: the bot is put down at the next point of its way. It shows in
	 * the film as a jump, so it is noted in the result.
	 */
	private void stepOver(Minecraft mc, BlockPos to, String why) {
		LocalPlayer player = mc.player;
		Vec3 from = player.position();
		stop(mc);
		player.setPos(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
		teleports++;
		notes.add("walking failed (" + why + "): the bot was put from " + BlockPos.containing(from) + " to " + to);
		LOGGER.warn("Walk: {}, put from {} to {}", why, from, to);
		route = null;
	}

	/** Walks straight at a point (the last steps into a portal), jumping over what is in the way. */
	private boolean walkStraight(Minecraft mc, Vec3 to) {
		LocalPlayer player = mc.player;
		double dx = to.x - player.getX();
		double dz = to.z - player.getZ();
		if (dx * dx + dz * dz < 0.09) {
			stop(mc);
			return true;
		}
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		aim(yaw, 15);
		mc.options.keyUp.setDown(Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) < 50);
		mc.options.keyJump.setDown(player.isInWater() || player.horizontalCollision && player.onGround());
		return false;
	}

	/** Lets go of every key. */
	private static void stop(Minecraft mc) {
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		mc.options.keyJump.setDown(false);
		mc.options.keySprint.setDown(false);
		mc.options.keyShift.setDown(false);
		mc.options.keyUse.setDown(false);
	}

	private static double horizontal(Vec3 a, Vec3 b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static double horizontal(BlockPos a, BlockPos b) {
		return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
	}

	/** Where a player stands in this column: the highest place with ground under it, below the treetops; null over water. */
	private static @Nullable BlockPos ground(Level level, int x, int z) {
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		for (int y = top; y > top - 48 && y > level.getMinY(); y--) {
			BlockPos pos = new BlockPos(x, y, z);
			if (!level.getFluidState(pos).isEmpty()) {
				return null;
			}
			BlockState below = level.getBlockState(pos.below());
			if (!below.is(BlockTags.LEAVES) && !below.is(BlockTags.LOGS) && BotPath.standable(level, pos, BotPath.Rules.WALK)) {
				return pos;
			}
		}
		return null;
	}

	/** Nothing above: no tree, no overhang. */
	private static boolean openSky(Level level, BlockPos pos) {
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) <= pos.getY();
	}

	// ---- hands: digging, eating

	private int digTicks;
	private @Nullable BlockPos digTarget;

	/** Holds the attack button on a block with the best tool in the hotbar, until it breaks; true once it is gone. */
	private boolean dig(Minecraft mc, BlockPos pos) {
		BlockState state = mc.level.getBlockState(pos);
		if (state.getCollisionShape(mc.level, pos).isEmpty()) {
			digTarget = null;
			return true;
		}
		LocalPlayer player = mc.player;
		if (!pos.equals(digTarget)) {
			digTarget = pos;
			digTicks = 0;
			bringTool(mc, state);
		}
		look(player, Vec3.atCenterOf(pos));
		if (!aimed(player, 25)) {
			return false;
		}
		if (++digTicks > 20 * 12) {
			throw new AssertionError("Expected: " + state + " at " + pos + " broken, the bot at " + player.position());
		}
		Direction face = Direction.getApproximateNearest(player.getEyePosition().subtract(Vec3.atCenterOf(pos)));
		mc.gameMode.continueDestroyBlock(pos, face);
		player.swing(InteractionHand.MAIN_HAND);
		return false;
	}

	/** The hotbar item that breaks this block fastest. */
	private static void bringTool(Minecraft mc, BlockState state) {
		Inventory inventory = mc.player.getInventory();
		int best = inventory.getSelectedSlot();
		float speed = inventory.getItem(best).getDestroySpeed(state);
		for (int slot = 0; slot < 9; slot++) {
			float s = inventory.getItem(slot).getDestroySpeed(state);
			if (s > speed) {
				speed = s;
				best = slot;
			}
		}
		inventory.setSelectedSlot(best);
	}

	private int eatenFrom;

	/** Holds the use button with food in the hand until one is eaten. */
	private boolean eat(Minecraft mc, int t, Item food) {
		LocalPlayer player = mc.player;
		if (t == 1) {
			eatenFrom = count(player, food);
			bring(mc, food);
		}
		aim(player.getYRot(), -10);
		mc.options.keyUse.setDown(true);
		if (t == 4 && !player.isUsingItem()) {
			mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		}
		if (count(player, food) < eatenFrom) {
			mc.options.keyUse.setDown(false);
			return true;
		}
		return false;
	}

	// ---- the night

	/** Evening comes in a few seconds: the sun goes down quickly instead of the sky turning black at once. */
	private boolean dusk(Minecraft mc, int t) {
		long time = mc.level.getDayTime() % 24_000;
		if (time >= 13_000) {
			return true;
		}
		if (t % 2 == 0) {
			mc.player.connection.sendCommand("time add " + Math.min(240, 13_050 - time));
		}
		return false;
	}

	// ---- the outing: a tree, a mine, a look from up high

	/** Finds a flat, open place for the mine 46-62 blocks from the base (farther than its building reaches), and a tree on the way. */
	private boolean planOuting(Minecraft mc) {
		Level level = mc.level;
		record Site(BlockPos pos, Direction dir, double score) {
		}
		List<Site> sites = new ArrayList<>();
		for (int r = 46; r <= 62; r += 4) {
			for (int a = 0; a < 360; a += 12) {
				int x = base.getX() + (int) Math.round(r * Math.cos(Math.toRadians(a)));
				int z = base.getZ() + (int) Math.round(r * Math.sin(Math.toRadians(a)));
				BlockPos site = ground(level, x, z);
				if (site == null) {
					continue;
				}
				double around = surroundings(level, site);
				if (around < 0) {
					continue;
				}
				for (Direction dir : Direction.Plane.HORIZONTAL) {
					if (solidAhead(level, site, dir)) {
						sites.add(new Site(site, dir, around - (r - 46) * 0.3));
					}
				}
			}
		}
		sites.sort(Comparator.comparingDouble(Site::score).reversed());
		// only where the bot can walk to (not across the lake)
		BotPath.Explored walkable = BotPath.explore(level, mc.player.blockPosition(), 70, BotPath.Rules.WALK, 120_000);
		LOGGER.info("Outing: {} places to look at, {} within walking reach", sites.size(), walkable.costs().size());
		for (Site site : sites) {
			if (walkable.costs().containsKey(site.pos().asLong())) {
				mineSite = site.pos();
				mineDir = site.dir();
				break;
			}
		}
		check(mineSite != null, "a flat open place for a mine 46-62 blocks from the base (" + sites.size() + " looked at)");
		// a tree a little off the way there
		Vec3 way = Vec3.atCenterOf(base).lerp(Vec3.atCenterOf(mineSite), 0.4);
		double best = Double.MAX_VALUE;
		for (int dx = -14; dx <= 14; dx++) {
			for (int dz = -14; dz <= 14; dz++) {
				int x = (int) Math.floor(way.x) + dx;
				int z = (int) Math.floor(way.z) + dz;
				for (int y = (int) way.y + 10; y > way.y - 10; y--) {
					BlockPos log = new BlockPos(x, y, z);
					if (!level.getBlockState(log).is(BlockTags.LOGS) || !level.getBlockState(log.below()).is(BlockTags.DIRT)
						|| !level.getBlockState(log.above(2)).is(BlockTags.LOGS)) {
						continue;
					}
					for (Direction side : Direction.Plane.HORIZONTAL) {
						BlockPos stand = log.relative(side);
						double d = Vec3.atCenterOf(stand).distanceTo(way);
						if (d < best && BotPath.standable(level, stand, BotPath.Rules.WALK)) {
							best = d;
							treeTrunk = log;
							treeStand = stand;
						}
					}
					break;
				}
			}
		}
		if (treeTrunk == null) {
			notes.add("no tree on the way to the mine");
		}
		LOGGER.info("Outing: mine at {} going {}, tree {} (stand at {})", mineSite, mineDir, treeTrunk, treeStand);
		return true;
	}

	/** How flat and open the place around a mine is (the falls happen there, and the camera needs room); below 0 is no good. */
	private static double surroundings(Level level, BlockPos site) {
		int open = 0;
		int rough = 0;
		for (int dx = -6; dx <= 6; dx += 2) {
			for (int dz = -6; dz <= 6; dz += 2) {
				int x = site.getX() + dx;
				int z = site.getZ() + dz;
				int y = surface(level, x, z);
				if (y == Integer.MIN_VALUE) {
					rough += 4;
					continue;
				}
				rough += Math.min(4, Math.abs(y - site.getY()));
				if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) <= y) {
					open++;
				}
			}
		}
		return rough > 40 || open < 4 ? -1 : open * 2 - rough;
	}

	/** The staircase goes under ground no lower than its entrance, and not under water. */
	private static boolean solidAhead(Level level, BlockPos site, Direction dir) {
		Direction side = dir.getClockWise();
		for (int k = 1; k <= STAIRS + 2; k++) {
			for (int s = -2; s <= 2; s++) {
				BlockPos column = site.relative(dir, k).relative(side, s);
				int y = surface(level, column.getX(), column.getZ());
				if (y < site.getY() || y > site.getY() + 4) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * The feet level on top of the ground of a column, looking through trees (a trunk stands on the ground too);
	 * {@link Integer#MIN_VALUE} over water or lava.
	 */
	private static int surface(Level level, int x, int z) {
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		for (int y = top - 1; y > top - 48 && y > level.getMinY(); y--) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState state = level.getBlockState(pos);
			if (!state.getFluidState().isEmpty()) {
				return Integer.MIN_VALUE;
			}
			if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.getCollisionShape(level, pos).isEmpty()) {
				continue;
			}
			return y + 1;
		}
		return Integer.MIN_VALUE;
	}

	private int chopping;

	/** Chops the trunk from as high as it reaches down to the ground, then steps where it stood for the logs. */
	private boolean chop(Minecraft mc, int t) {
		if (treeTrunk == null) {
			return true;
		}
		if (t == 1) {
			chopping = 4;
		}
		while (chopping >= 0 && !mc.level.getBlockState(treeTrunk.above(chopping)).is(BlockTags.LOGS)) {
			chopping--;
		}
		if (chopping >= 0) {
			if (dig(mc, treeTrunk.above(chopping))) {
				chopping--;
			}
			return false;
		}
		return walkTo(mc, treeTrunk, 0, t) && t > 20;
	}

	/**
	 * The world under the mine is made solid rock (no cave, water or lava to dig into, which a test cannot plan for),
	 * with a few diamond ores on the way down.
	 */
	private boolean prepareMine(Minecraft mc, int t) {
		int g = mineSite.getY();
		Direction side = mineDir.getClockWise();
		BlockPos a = mineSite.relative(mineDir, 1).relative(side, 2).atY(g - 14);
		BlockPos b = mineSite.relative(mineDir, STAIRS + 2).relative(side, -2).atY(g - 2);
		for (String what : List.of("air", "cave_air", "water", "lava", "gravel", "sand")) {
			command(mc, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone replace minecraft:%s",
				a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ(), what));
		}
		BlockPos c7 = mineSite.relative(mineDir, 7);
		BlockPos c8 = mineSite.relative(mineDir, 8);
		BlockPos c9 = mineSite.relative(mineDir, 9);
		for (BlockPos ore : List.of(c7.atY(g - 7), c8.atY(g - 6), c9.atY(g - 8), c9.relative(mineDir.getCounterClockWise()).atY(g - 9))) {
			command(mc, "setblock " + ore.getX() + " " + ore.getY() + " " + ore.getZ() + " minecraft:diamond_ore");
		}
		return true;
	}

	private int stairStep;
	private int stairPart;
	private int stairWait;

	/**
	 * Digs down like a player: three blocks ahead (head, feet, below), a step down into the hole, again. A torch on the
	 * wall every third step, and the diamond in the wall at the bottom.
	 */
	private boolean digStairs(Minecraft mc, int t) {
		LocalPlayer player = mc.player;
		if (t == 1) {
			stairStep = 0;
			stairPart = 0;
		}
		if (stairStep >= STAIRS) {
			stop(mc);
			return true;
		}
		int feet = mineSite.getY() - stairStep;
		BlockPos next = mineSite.relative(mineDir, stairStep + 1);
		int k = stairStep + 1;
		switch (stairPart) {
			case 0, 1, 2 -> {
				if (dig(mc, next.atY(feet + 1 - stairPart))) {
					stairPart++;
				}
			}
			case 3 -> {
				if (player.getBlockX() == next.getX() && player.getBlockZ() == next.getZ() && player.onGround() && player.getY() < feet - 0.5) {
					stop(mc);
					stairPart = 4;
					stairWait = 0;
				} else {
					walkStraight(mc, Vec3.atBottomCenterOf(next.atY(feet - 1)));
				}
			}
			case 4 -> {
				Direction right = mineDir.getClockWise();
				if (k % 3 != 0) {
					stairPart = 5;
				} else if (++stairWait == 6) {
					bring(mc, Items.TORCH);
					placeOn(mc, next.relative(right).atY(feet), right.getOpposite());
				} else if (stairWait > 12) {
					stairPart = 5;
				}
			}
			case 5 -> {
				BlockPos ore = next.relative(mineDir.getCounterClockWise()).atY(feet - 1);
				if (k != 9 || dig(mc, ore)) {
					stairPart = 6;
				}
			}
			default -> {
				stairStep++;
				stairPart = 0;
			}
		}
		return false;
	}

	/** Two open places near the mine to pillar up from, each with free ground next to it to fall on. */
	private boolean planPillars(Minecraft mc) {
		BlockPos[] first = pillarSpot(mc.level, mineSite, null);
		check(first != null, "an open place near the mine at " + mineSite + " to build up from");
		pillar1 = first[0];
		landing1 = first[1];
		BlockPos[] second = pillarSpot(mc.level, landing1, pillar1);
		if (second == null) {
			second = pillarSpot(mc.level, mineSite, pillar1);
		}
		check(second != null, "another open place near " + landing1 + " to build up from");
		pillar2 = second[0];
		landing2 = second[1];
		LOGGER.info("Pillars at {} (fall onto {}) and {} (fall onto {})", pillar1, landing1, pillar2, landing2);
		return true;
	}

	/** An open place 3-14 blocks from {@code near} to build a pillar on, and free ground at the same level next to it. */
	private BlockPos @Nullable [] pillarSpot(Level level, BlockPos near, @Nullable BlockPos avoid) {
		for (int r = 3; r <= 14; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					BlockPos p = ground(level, near.getX() + dx, near.getZ() + dz);
					if (p == null || nearMine(p) || !openSky(level, p) || avoid != null && horizontal(p, avoid) < 5) {
						continue;
					}
					for (Direction d : Direction.Plane.HORIZONTAL) {
						BlockPos l = ground(level, p.getX() + d.getStepX(), p.getZ() + d.getStepZ());
						if (l != null && l.getY() == p.getY() && clearAround(level, l, p, avoid)) {
							return new BlockPos[]{p, l};
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * Room to come down on: the landing place and the ground around it (a fall drifts a little) open to the sky, about
	 * as high as the foot of the pillar, away from the mine and from the other pillar.
	 */
	private boolean clearAround(Level level, BlockPos landing, BlockPos pillar, @Nullable BlockPos avoid) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				int x = landing.getX() + dx;
				int z = landing.getZ() + dz;
				if (x == pillar.getX() && z == pillar.getZ()) {
					continue;
				}
				BlockPos g = ground(level, x, z);
				if (g == null || Math.abs(g.getY() - pillar.getY()) > 1 || !openSky(level, g) || nearMine(g)
					|| avoid != null && horizontal(g, avoid) < 2) {
					return false;
				}
			}
		}
		return true;
	}

	/** On the staircase or right next to it. */
	private boolean nearMine(BlockPos p) {
		for (int k = -1; k <= STAIRS + 1; k++) {
			BlockPos column = mineSite.relative(mineDir, k);
			if (Math.abs(p.getX() - column.getX()) <= 1 && Math.abs(p.getZ() - column.getZ()) <= 1) {
				return true;
			}
		}
		return false;
	}

	private BlockPos pillarBase;
	private boolean placedThisJump;

	/** Jumps and puts a block under itself at the top of each jump, {@code blocks} times; done standing on the top. */
	private boolean pillar(Minecraft mc, int t, int blocks) {
		LocalPlayer player = mc.player;
		if (t == 1) {
			pillarBase = player.blockPosition();
			LOGGER.info("Pillar of {} at {}", blocks, pillarBase);
		}
		mc.options.keyUp.setDown(false);
		aim(player.getYRot(), 90);
		int built = 0;
		while (built < blocks && BotPath.floor(mc.level, pillarBase.above(built))) {
			built++;
		}
		BlockState there = mc.level.getBlockState(pillarBase.above(built));
		if (!there.isAir() && !there.canBeReplaced() && player.onGround()) {
			// flowers a block cannot simply go into: picked first, with a click
			mc.gameMode.startDestroyBlock(pillarBase.above(built), Direction.UP);
			player.swing(InteractionHand.MAIN_HAND);
			return false;
		}
		if (built >= blocks) {
			mc.options.keyJump.setDown(false);
			return player.onGround() && player.getY() >= pillarBase.getY() + blocks - 0.1;
		}
		Vec3 middle = Vec3.atBottomCenterOf(pillarBase);
		if (built == 0 && player.onGround() && horizontal(player.position(), middle) > 0.15) {
			// first into the middle of the block: a jump straight up needs room over this block, not the next one
			aim((float) Math.toDegrees(Math.atan2(-(middle.x - player.getX()), middle.z - player.getZ())), 60);
			mc.options.keyShift.setDown(true);
			mc.options.keyUp.setDown(aimed(player, 20));
			mc.options.keyJump.setDown(false);
			return false;
		}
		mc.options.keyShift.setDown(false);
		if (player.onGround()) {
			placedThisJump = false;
		}
		mc.options.keyJump.setDown(player.onGround() && player.getXRot() > 70);
		BlockPos target = pillarBase.above(built);
		if (!player.onGround() && !placedThisJump && player.getY() >= target.getY() + 1.02) {
			bring(mc, RUBBLE);
			placeOn(mc, target.below(), Direction.UP);
			placedThisJump = true;
		}
		return false;
	}

	private double stepOffFrom;
	private boolean landed;
	private int atEdge;
	private Vec3 edgeLast;

	/**
	 * Sneaks to the edge of the top toward {@code landing}, lets go and steps off, holding back in the air so it comes
	 * down next to the pillar and not wherever the step carries it; done back on the ground (or dead).
	 */
	private boolean stepOff(Minecraft mc, int t, BlockPos landing) {
		LocalPlayer player = mc.player;
		if (t == 1) {
			stepOffFrom = player.getY();
			landed = false;
			atEdge = 0;
			edgeLast = player.position();
		}
		if (landed || player.isDeadOrDying()) {
			stop(mc);
			return true;
		}
		Vec3 to = Vec3.atBottomCenterOf(landing);
		float yaw = (float) Math.toDegrees(Math.atan2(-(to.x - player.getX()), to.z - player.getZ()));
		boolean falling = player.getY() < stepOffFrom - 0.5;
		if (!falling) {
			aim(yaw, 35);
			if (t <= 8) {
				return false;
			}
			// creep out until sneaking holds the bot at the edge, then one step without it
			atEdge = player.position().distanceToSqr(edgeLast) < 1e-4 ? atEdge + 1 : 0;
			edgeLast = player.position();
			boolean edge = atEdge >= 4;
			mc.options.keyShift.setDown(!edge);
			mc.options.keyUp.setDown(true);
			return false;
		}
		mc.options.keyShift.setDown(false);
		// in the air: forward or back so that it comes down on the landing place (where it would be in 8 ticks)
		Vec3 motion = player.getDeltaMovement();
		double facing = Math.toRadians(player.getYRot());
		double forwardX = -Math.sin(facing);
		double forwardZ = Math.cos(facing);
		double ahead = (to.x - player.getX()) * forwardX + (to.z - player.getZ()) * forwardZ;
		double going = motion.x * forwardX + motion.z * forwardZ;
		double miss = ahead - going * 8;
		mc.options.keyUp.setDown(miss > 0.2);
		mc.options.keyDown.setDown(miss < -0.2);
		if (player.onGround()) {
			stop(mc);
			landed = true;
			LOGGER.info("Fell from {} to {} at {}, landing place {}", stepOffFrom, player.getY(), player.blockPosition(), landing);
			return true;
		}
		return false;
	}

	// ---- the flight

	/** The open water (a lake, the sea) 20-55 blocks from the base to land in, and the side of the clearing to take off from. */
	private boolean planFlight(Minecraft mc) {
		Level level = mc.level;
		BlockPos best = null;
		int bestCount = 0;
		for (int dx = -56; dx <= 56; dx += 4) {
			for (int dz = -56; dz <= 56; dz += 4) {
				double d = Math.hypot(dx, dz);
				int x = base.getX() + dx;
				int z = base.getZ() + dz;
				if (d < 20 || d > 55 || !deepWater(level, x, z)) {
					continue;
				}
				int around = 0;
				for (int ox = -8; ox <= 8; ox += 2) {
					for (int oz = -8; oz <= 8; oz += 2) {
						if (deepWater(level, x + ox, z + oz)) {
							around++;
						}
					}
				}
				if (around > bestCount) {
					bestCount = around;
					best = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
				}
			}
		}
		if (best == null) {
			notes.add("no water to land in near the base: the elytra lands on the ground");
			best = base.offset(35, 0, 0);
		}
		flightGoal = Vec3.atBottomCenterOf(best);
		Vec3 away = flightGoal.subtract(Vec3.atBottomCenterOf(base)).multiply(1, 0, 1).normalize();
		takeOff = base.offset((int) Math.round(away.x * 6), 0, (int) Math.round(away.z * 6));
		if (!BotPath.standable(level, takeOff, BotPath.Rules.WALK)) {
			takeOff = base.offset(0, 0, -6);
		}
		LOGGER.info("Flight: take off at {}, land at {} ({} water around)", takeOff, best, bestCount);
		return true;
	}

	private static boolean deepWater(Level level, int x, int z) {
		BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
		return level.getBlockState(top).is(Blocks.WATER) && level.getBlockState(top.below()).is(Blocks.WATER);
	}

	private int wingTicks;

	/**
	 * Takes off like a player: a jump, the jump key again at the top of it opens the wings, a rocket steeply up over the
	 * trees, then a glide that circles down onto the water.
	 */
	private boolean fly(Minecraft mc, int t) {
		LocalPlayer player = mc.player;
		if (t == 1) {
			wingTicks = 0;
		}
		Vec3 p = player.position();
		double dx = flightGoal.x - p.x;
		double dz = flightGoal.z - p.z;
		float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
		if (wingTicks > 0 && !player.isFallFlying()) {
			stop(mc);
			LOGGER.info("Landed after {} ticks with the wings open, at {} (in water: {})", wingTicks, p, player.isInWater());
			return true;
		}
		if (!player.isFallFlying()) {
			steer(player, bearing, -30, 10, 5);
			int phase = (t - 20) % 30;
			mc.options.keyJump.setDown(t >= 20 && (phase == 0 || phase == 6));
			return false;
		}
		mc.options.keyJump.setDown(false);
		wingTicks++;
		if (wingTicks == 1) {
			bring(mc, Items.FIREWORK_ROCKET);
			player.setXRot(-65);
			mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		}
		if (wingTicks < 22) {
			steer(player, bearing, -65, 2, 10);
		} else if (wingTicks < 40) {
			steer(player, bearing, 0, 3, 4); // level out, keep the speed
		} else {
			double d = Math.hypot(dx, dz);
			double h = p.y - flightGoal.y;
			double slope = Math.toDegrees(Math.atan2(h, Math.max(1, d)));
			if (slope > 40) {
				steer(player, bearing + 80, 32, 4, 3); // still too high: spiral down round the landing place
			} else {
				steer(player, bearing, (float) Mth.clamp(slope + 3, 3, 40), 4, 3);
			}
		}
		return false;
	}

	// ---- the Nether

	/** A place 6-14 blocks from the portal with a wall next to it (for the ancient debris), and spots for two piglins. */
	private boolean planNether(Minecraft mc) {
		Level level = mc.level;
		netherPortal = mc.player.blockPosition();
		BotPath.Explored around = BotPath.explore(level, netherPortal, 16, BotPath.Rules.WALK);
		double best = -1;
		for (BlockPos place : around.places()) {
			double d = horizontal(place, netherPortal);
			// about level with the portal: a walk, not a climb down a cliff
			if (d < 6 || d > 14 || d <= best || Math.abs(place.getY() - netherPortal.getY()) > 3) {
				continue;
			}
			for (Direction side : Direction.Plane.HORIZONTAL) {
				BlockPos wall = place.above().relative(side);
				BlockState state = level.getBlockState(wall);
				if (state.isCollisionShapeFullBlock(level, wall) && !state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)
					&& level.getFluidState(wall.above()).isEmpty()) {
					best = d;
					netherSpot = place;
					netherWall = side;
					break;
				}
			}
		}
		if (netherSpot == null) {
			notes.add("no place with a wall near the Nether portal: the bot stays by it");
			netherSpot = around.places().stream().max(Comparator.comparingDouble(p -> horizontal(p, netherPortal))).orElse(netherPortal);
			netherWall = Direction.NORTH;
		}
		// the piglins a few steps away on the same ground, where the bot can walk up to them
		BotPath.Explored there = BotPath.explore(level, netherSpot, 8, BotPath.Rules.WALK);
		for (BlockPos place : there.places()) {
			double d = horizontal(place, netherSpot);
			if (d >= 3 && d <= 6 && Math.abs(place.getY() - netherSpot.getY()) <= 1 && there.costs().get(place.asLong()) < d + 3
				&& piglinSpots.size() < 2 && (piglinSpots.isEmpty() || horizontal(place, piglinSpots.getFirst()) >= 2)) {
				piglinSpots.add(place);
			}
		}
		for (BlockPos place : there.places()) {
			if (piglinSpots.size() < 2 && !piglinSpots.contains(place) && horizontal(place, netherSpot) >= 2
				&& Math.abs(place.getY() - netherSpot.getY()) <= 2) {
				piglinSpots.add(place);
			}
		}
		LOGGER.info("Nether: portal at {}, {} places around, going to {} (wall {}), piglins at {}", netherPortal, around.places().size(),
			netherSpot, netherWall, piglinSpots);
		return true;
	}

	/**
	 * The End portal of a stronghold, set up next to the base while the bot is away (as if found and carried over): a
	 * flat clearing, the twelve frame blocks, nine of them with an eye. The last three are the bot's to put in.
	 */
	private boolean setUpEndPortal(Minecraft mc, int t) {
		endFrame = base.offset(-12, 0, 0);
		int x = endFrame.getX();
		int y = endFrame.getY();
		int z = endFrame.getZ();
		if (t == 1) {
			command(mc, overworld(String.format(Locale.ROOT, "forceload add %d %d %d %d", x - 8, z - 8, x + 8, z + 8)));
		}
		if (t == 40) {
			command(mc, overworld(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 6, y, z - 6, x + 6, y + 16, z + 6)));
			command(mc, overworld(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:grass_block", x - 6, y - 1, z - 6, x + 6, y - 1, z + 6)));
			command(mc, overworld(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:dirt", x - 6, y - 3, z - 6, x + 6, y - 2, z + 6)));
			for (int i = -1; i <= 1; i++) {
				// facing the middle: the game looks for the ring from the frame the last eye goes into toward its far
				// corner, and only finds it that way round
				frame(mc, x + i, y, z - 2, "south", true);
				frame(mc, x + i, y, z + 2, "north", true);
				frame(mc, x - 2, y, z + i, "east", true);
				frame(mc, x + 2, y, z + i, "west", false);
			}
		}
		if (t == 50) {
			command(mc, overworld(String.format(Locale.ROOT, "forceload remove %d %d %d %d", x - 8, z - 8, x + 8, z + 8)));
			return true;
		}
		return false;
	}

	private static void frame(Minecraft mc, int x, int y, int z, String facing, boolean eye) {
		command(mc, overworld(String.format(Locale.ROOT, "setblock %d %d %d minecraft:end_portal_frame[facing=%s,eye=%s]", x, y, z, facing, eye)));
	}

	private static String overworld(String command) {
		return "execute in minecraft:overworld run " + command;
	}

	/** Puts an eye of ender into each of the three empty frames, one after another; done when the portal opens. */
	private boolean fillEndPortal(Minecraft mc, int t) {
		LocalPlayer player = mc.player;
		int eye = Math.min(2, (t - 1) / 15);
		BlockPos frame = endFrame.offset(2, 0, eye - 1);
		Vec3 top = new Vec3(frame.getX() + 0.5, frame.getY() + 0.8125, frame.getZ() + 0.5);
		look(player, top);
		if ((t - 1) % 15 == 10 && (t - 1) / 15 < 3) {
			bring(mc, Items.ENDER_EYE);
			mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(top, Direction.UP, frame, false));
			player.swing(InteractionHand.MAIN_HAND);
		}
		if (t > 60) {
			check(mc.level.getBlockState(endFrame).is(Blocks.END_PORTAL), "the End portal opened, got " + mc.level.getBlockState(endFrame));
			return true;
		}
		return false;
	}

	// ---- the End

	/**
	 * The spawn platform is below the island's edge: the bot pillars up until it is level with the top of the edge
	 * (the highest ground in the first blocks toward the middle).
	 */
	private boolean climbToIsland(Minecraft mc, int t) {
		LocalPlayer player = mc.player;
		if (t == 1) {
			BlockPos feet = player.blockPosition();
			climbFrom = feet.getY();
			toIsland = Math.abs(feet.getX()) > Math.abs(feet.getZ()) ? (feet.getX() > 0 ? Direction.WEST : Direction.EAST)
				: (feet.getZ() > 0 ? Direction.NORTH : Direction.SOUTH);
			islandLevel = feet.getY() - 1;
			for (int k = 1; k <= 6; k++) {
				BlockPos column = feet.relative(toIsland, k);
				for (int y = feet.getY() + 12; y >= feet.getY() - 4; y--) {
					if (!mc.level.getBlockState(column.atY(y)).getCollisionShape(mc.level, column.atY(y)).isEmpty()) {
						islandLevel = Math.max(islandLevel, y);
						break;
					}
				}
			}
			LOGGER.info("End: on the platform at {}, the island's edge is {} at {}", feet, toIsland, islandLevel);
		}
		int up = islandLevel + 1 - climbFrom;
		return up <= 0 || pillar(mc, t, up);
	}

	private int climbFrom;
	private int bridged;
	private BlockPos bridgeTip;
	private @Nullable BlockPos bridgeLast;

	/**
	 * Sneaks out toward the island at the island's level, each time putting a block against the end of the bridge,
	 * until the island itself is next.
	 */
	private boolean bridge(Minecraft mc, int t) {
		LocalPlayer player = mc.player;
		if (t == 1) {
			bridged = 0;
			bridgeTip = new BlockPos(player.getBlockX(), islandLevel, player.getBlockZ());
		}
		BlockPos next = bridgeTip.relative(toIsland);
		if (BotPath.floor(mc.level, next)) {
			if (next.equals(bridgeLast)) {
				bridgeTip = next; // the block just put down
				return false;
			}
			stop(mc);
			LOGGER.info("End: bridged {} blocks, the island's edge is next at {}, the bot at {}", bridged, next, player.position());
			return true;
		}
		mc.options.keyShift.setDown(true);
		Vec3 tip = Vec3.atBottomCenterOf(bridgeTip.above());
		if (horizontal(player.position(), tip) > 0.9) {
			// out to the end of the bridge, sneaking: no falling off the edge
			aim(toIsland.toYRot(), 60);
			mc.options.keyUp.setDown(aimed(player, 20));
			return false;
		}
		mc.options.keyUp.setDown(false);
		look(player, Vec3.atCenterOf(bridgeTip).add(toIsland.getUnitVec3().scale(0.5)));
		if (t % 8 == 0 && aimed(player, 20)) {
			check(++bridged <= 16, "the island within 16 blocks of " + bridgeTip);
			bring(mc, RUBBLE);
			placeOn(mc, bridgeTip, toIsland);
			bridgeLast = next;
		}
		return false;
	}

	private int landingCalled;
	private int sittingSince;
	private boolean finished;

	/**
	 * The dragon is called down onto the portal (a command: a real fight is long and its charges throw the bot about),
	 * the bot runs up to it and hits it with its sword for a few seconds, and the killing blow is a command in the
	 * bot's name (so it dies the way a dragon does: it rises and bursts).
	 */
	private boolean killDragon(Minecraft mc, int t) {
		LocalPlayer player = mc.player;
		if (t == 1) {
			command(mc, "data merge entity @e[type=minecraft:ender_dragon,limit=1] {DragonPhase:2}");
			landingCalled = t;
			sittingSince = -1;
			finished = false;
		}
		if (dragon(mc) instanceof EnderDragon dragon && !dragon.isDeadOrDying()) {
			if (t % 40 == 0) {
				LOGGER.info("Dragon: {} at {}, health {}, the bot at {}", dragon.getPhaseManager().getCurrentPhase().getPhase(),
					dragon.blockPosition(), dragon.getHealth(), player.blockPosition());
			}
			if (dragon.getPhaseManager().getCurrentPhase().isSitting()) {
				if (sittingSince < 0) {
					sittingSince = t;
				}
				// the part of it nearest to the bot: up to it, and hit whenever the sword is ready
				Vec3 eye = player.getEyePosition();
				Entity part = dragon;
				double reach = Double.MAX_VALUE;
				for (Entity p : dragon.getSubEntities()) {
					AABB box = p.getBoundingBox();
					Vec3 nearest = new Vec3(Mth.clamp(eye.x, box.minX, box.maxX), Mth.clamp(eye.y, box.minY, box.maxY),
						Mth.clamp(eye.z, box.minZ, box.maxZ));
					if (eye.distanceTo(nearest) < reach) {
						reach = eye.distanceTo(nearest);
						part = p;
					}
				}
				bring(mc, Items.DIAMOND_SWORD);
				Vec3 at = part.getBoundingBox().getCenter();
				if (reach > 2.6) {
					walkStraight(mc, new Vec3(at.x, player.getY(), at.z));
				} else {
					stop(mc);
					if (player.getAttackStrengthScale(0.5f) > 0.95f) {
						mc.gameMode.attack(player, part);
						player.swing(InteractionHand.MAIN_HAND);
					}
				}
				look(player, at);
				if (t - sittingSince == 20 * 6 && !finished) {
					// a real fight is long (the crystals heal it): after a few hits the last one is a command, as if a hard one
					notes.add("the bot hit the dragon with its sword on the portal; the killing blow was /damage by the bot");
					command(mc, "damage @e[type=minecraft:ender_dragon,limit=1] 1000 minecraft:player_attack by @s");
					finished = true;
				}
			} else {
				stop(mc);
				look(player, dragon.position());
				// asked again only when it went back to circling (asking restarts its way down from far off)
				var phase = dragon.getPhaseManager().getCurrentPhase().getPhase();
				if (t - landingCalled > 20 * 5 && phase != EnderDragonPhase.LANDING_APPROACH && phase != EnderDragonPhase.LANDING) {
					command(mc, "data merge entity @e[type=minecraft:ender_dragon,limit=1] {DragonPhase:2}");
					landingCalled = t;
				}
			}
			if (t == 20 * 40 && !finished) {
				// as if the bot had hit it hard: it flies to the portal, rises and bursts (and counts as killed by the bot)
				notes.add("the dragon did not die from the bot's sword in 40 s, finished with /damage by the bot");
				command(mc, "damage @e[type=minecraft:ender_dragon,limit=1] 1000 minecraft:player_attack by @s");
			}
			if (t == 20 * 70) {
				notes.add("the dragon did not die from /damage either, killed with /kill");
				command(mc, "kill @e[type=minecraft:ender_dragon]");
			}
		} else {
			stop(mc);
		}
		// a dying dragon rises and bursts (10 s); then the exit portal opens
		return t > 40 && exitPortal(mc) != null;
	}

	// ---- fighting

	/** Runs at the nearest mob of this kind near {@code around} and hits it whenever the sword is ready. */
	private boolean fight(Minecraft mc, int t, EntityType<?> type, Vec3 around, double radius) {
		LocalPlayer player = mc.player;
		Entity nearest = null;
		double best = Double.MAX_VALUE;
		for (Entity entity : mc.level.entitiesForRendering()) {
			// only the summoned ones: the night also spawns zombies in caves nearby
			if (entity.getType() == type && entity.isAlive() && entity.position().distanceToSqr(around) < radius * radius) {
				double distance = entity.distanceToSqr(player);
				if (distance < best) {
					best = distance;
					nearest = entity;
				}
			}
		}
		if (nearest == null) {
			stop(mc);
			return t > 40;
		}
		if (t % 40 == 0) {
			LOGGER.info("Fight: bot at {}, nearest {} at {} ({} blocks), on ground {}, collision {}", player.position(), type.toShortString(),
				nearest.position(), Math.round(Math.sqrt(best) * 10) / 10.0, player.onGround(), player.horizontalCollision);
		}
		bring(mc, Items.DIAMOND_SWORD);
		if (best > 2.5 * 2.5) {
			chase(mc, t, nearest);
		} else {
			stop(mc);
			route = null;
			look(player, nearest.getEyePosition().add(0, -0.4, 0));
			if (player.getAttackStrengthScale(0.5f) > 0.95f) {
				mc.gameMode.attack(player, nearest);
				player.swing(InteractionHand.MAIN_HAND);
			}
		}
		return false;
	}

	private static @Nullable Entity dragon(Minecraft mc) {
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (entity.getType() == EntityType.ENDER_DRAGON) {
				return entity;
			}
		}
		return null;
	}

	/** An end portal block of the exit portal, once it is open. */
	private static @Nullable BlockPos exitPortal(Minecraft mc) {
		for (int y = 40; y < 100; y++) {
			for (int x = -4; x <= 4; x++) {
				for (int z = -4; z <= 4; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (mc.level.getBlockState(pos).is(Blocks.END_PORTAL) && mc.level.getBlockState(pos.north()).is(Blocks.END_PORTAL)
						&& mc.level.getBlockState(pos.south()).is(Blocks.END_PORTAL)) {
						return pos;
					}
				}
			}
		}
		return null;
	}

	/** The blocks of the open exit portal, the nearest to the bot first. */
	private static List<BlockPos> exitPortalBlocks(Minecraft mc) {
		List<BlockPos> blocks = new ArrayList<>();
		for (int y = 40; y < 100; y++) {
			for (int x = -4; x <= 4; x++) {
				for (int z = -4; z <= 4; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (mc.level.getBlockState(pos).is(Blocks.END_PORTAL)) {
						blocks.add(pos);
					}
				}
			}
		}
		blocks.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(mc.player.position())));
		return blocks;
	}

	/** A place on the exit portal's rim, right by the portal, the bot can walk to in the fewest steps. */
	private static @Nullable BlockPos exitPortalRim(Minecraft mc) {
		BlockPos best = null;
		int fewest = Integer.MAX_VALUE;
		for (BlockPos portal : exitPortalBlocks(mc)) {
			for (Direction side : Direction.Plane.HORIZONTAL) {
				BlockPos feet = portal.relative(side).above();
				if (!BotPath.standable(mc.level, feet, BotPath.Rules.WALK)) {
					continue;
				}
				List<BlockPos> way = BotPath.find(mc.level, mc.player.blockPosition(), feet, 0, BotPath.Rules.WALK, 4000);
				if (way != null && way.size() < fewest) {
					best = feet;
					fewest = way.size();
				}
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ checks

	/**
	 * A copy of what was recorded besides the replays (events, camera paths, the world's memory) in
	 * {@code playtest-evidence/}: "delete the recordings after the film" removes them, and a surprising screenplay can be
	 * planned again from it in a unit test.
	 */
	private void saveEvidence(Minecraft mc) throws IOException {
		Path evidence = mc.gameDirectory.toPath().resolve("playtest-evidence");
		deleteTree(evidence);
		for (UUID session : List.of(session1, session2)) {
			Path from = SessionStore.sessionFolder(session);
			try (Stream<Path> files = Files.walk(from)) {
				for (Path file : files.filter(Files::isRegularFile).toList()) {
					Path to = evidence.resolve(session.toString()).resolve(from.relativize(file).toString());
					Files.createDirectories(to.getParent());
					Files.copy(file, to);
				}
			}
		}
		WorldMemory world = SurvivalCinemaClient.controller().world();
		Files.writeString(evidence.resolve("sessions.txt"), String.join(System.lineSeparator(), session1.toString(), session2.toString(),
			world == null ? "" : world.worldKey));
		// laid out like a game folder, to shoot clips of this film again without playing: `gradlew runFilmTest
		// -Pfrom=build/run/playTest/playtest-evidence/game -Preshoot=playtest/film -Pclips=7,9`
		Path game = evidence.resolve("game");
		Map<UUID, Path> replays = FlashbackBridge.replaysById();
		for (UUID session : List.of(session1, session2)) {
			Path replay = replays.get(session);
			Path to = game.resolve("flashback/replays").resolve(replay.getFileName().toString());
			Files.createDirectories(to.getParent());
			Files.copy(replay, to);
			Path from = SessionStore.sessionFolder(session);
			try (Stream<Path> files = Files.walk(from)) {
				for (Path file : files.filter(Files::isRegularFile).toList()) {
					Path copy = game.resolve("survivalcinema/sessions").resolve(session.toString()).resolve(from.relativize(file).toString());
					Files.createDirectories(copy.getParent());
					Files.copy(file, copy);
				}
			}
		}
		Path edl = FilmFolder.edlFile(RenderPipeline.current().film());
		Path to = FilmFolder.edlFile(game.resolve("survivalcinema/films/playtest/film"));
		Files.createDirectories(to.getParent());
		Files.copy(edl, to);
	}

	/** What each session's events say the mod noticed. */
	private void checkEvents(Minecraft mc) throws IOException {
		Map<EventType, Integer> first = countEvents(session1);
		Map<EventType, Integer> second = countEvents(session2);
		LOGGER.info("Noticed in session 1: {}", first);
		LOGGER.info("Noticed in session 2: {}", second);
		notes.add("session 1: " + first);
		notes.add("session 2: " + second);
		expect(first, EventType.SESSION_START, 1);
		expect(first, EventType.BUILD_ACTIVITY, 1);
		expect(first, EventType.NIGHT_FALL, 1);
		expect(first, EventType.KILL, 3);
		expect(first, EventType.KILL_STREAK, 1);
		expect(first, EventType.SLEEP, 1);
		expect(first, EventType.NEW_DAY, 1);
		expect(first, EventType.NEAR_DEATH, 1);
		expect(first, EventType.BIG_DAMAGE, 1);
		expect(first, EventType.RARE_ITEM, 2); // the diamond sword and the diamonds mined
		expect(first, EventType.ADVANCEMENT, 2); // Stone Age and Diamonds! at least, both earned in the mine
		expect(first, EventType.BIG_FALL, 1);
		expect(first, EventType.DEATH, 1);
		expect(second, EventType.SESSION_START, 1);
		expect(second, EventType.BUILD_ACTIVITY, 1);
		expect(second, EventType.ELYTRA_FLIGHT, 1);
		expect(second, EventType.DIMENSION_CHANGE, 4); // Nether, back, End, back
		expect(second, EventType.KILL, 3); // two zombified piglins in the Nether, the dragon
		expect(second, EventType.BOSS_FIGHT_START, 1);
		expect(second, EventType.DRAGON_KILLED, 1);
		if (second.getOrDefault(EventType.VOICE_PEAK, 0) + second.getOrDefault(EventType.CONVERSATION, 0)
			+ second.getOrDefault(EventType.VOICE_CROWD, 0) == 0) {
			notes.add("the friend's beeping call made no voice moment (fine: it is a tone, not speech)");
		}
	}

	private static Map<EventType, Integer> countEvents(UUID session) throws IOException {
		List<GameEvent> events = EventLog.read(SessionStore.sessionFolder(session).resolve(EventLog.FILE_NAME));
		Map<EventType, Integer> counts = new EnumMap<>(EventType.class);
		for (GameEvent event : events) {
			counts.merge(event.type(), 1, Integer::sum);
		}
		return counts;
	}

	private void expect(Map<EventType, Integer> counts, EventType type, int atLeast) {
		int count = counts.getOrDefault(type, 0);
		if (count < atLeast) {
			errors.add("expected " + atLeast + "x " + type + ", noticed " + count);
		}
	}

	/** The screenplay and the film file. */
	private void checkFilm(Minecraft mc, RenderPipeline pipeline) throws IOException {
		EditDecisionList edl = FilmFolder.readEdl(film);
		check(edl != null, "the screenplay is in " + film);
		RenderProgress done = RenderProgress.read(FilmFolder.progressFile(film));
		check(done != null && done.finished && done.film != null, "the film is finished and edited");
		List<String> chapters = new ArrayList<>();
		int clips = 0;
		boolean dragon = false;
		boolean death = false;
		for (EditDecisionList.Item item : edl.items()) {
			if (item.kind() == EditDecisionList.Kind.CHAPTER) {
				chapters.add(String.valueOf(item.dimension()));
			}
			if (item.kind() == EditDecisionList.Kind.CLIP) {
				clips++;
				dragon |= item.eventType() == EventType.DRAGON_KILLED || item.eventType() == EventType.BOSS_FIGHT_START
					|| item.eventType() == EventType.BOSS_DEFEATED;
				death |= item.eventType() == EventType.DEATH;
			}
		}
		FilmStats stats = edl.stats();
		LOGGER.info("Screenplay: {} items, {} clips, chapters {}, timelapse {}, stats {}", edl.items().size(), clips, chapters,
			edl.timelapse(), stats);
		notes.add("screenplay: " + clips + " clips, chapters " + chapters + ", " + Math.round(edl.totalSeconds()) + " s");
		check(clips >= 5, "the film has clips: " + clips);
		check(edl.timelapse() != null, "the house built over two sessions is found as the base and gets a timelapse");
		check(chapters.contains("minecraft:the_nether"), "a Nether chapter: " + chapters);
		check(chapters.contains("minecraft:the_end"), "an End chapter: " + chapters);
		int nether = 0;
		boolean wayIn = false;
		for (RenderPlan.Job job : RenderPlan.of(edl).jobs()) {
			if (job.item().kind() == EditDecisionList.Kind.CLIP && "minecraft:the_nether".equals(job.item().dimension())) {
				nether++;
				// shot in two pieces: walking into the portal in the Overworld, then the arrival
				wayIn |= job.item().eventType() == EventType.DIMENSION_CHANGE && done.files(job.number()).size() >= 2;
			}
		}
		notes.add("Nether clips: " + nether);
		check(wayIn, "the arrival in the Nether shows the way into the portal");
		check(dragon, "the dragon fight is in the film");
		if (!death) {
			notes.add("the death did not make it into the film");
		}
		check(stats != null && stats.dragonKilled(), "the credits know the dragon is dead: " + stats);
		check(stats.blocksPlaced() >= 150, "the credits count the blocks of the house: " + stats.blocksPlaced());
		check(stats.deaths() >= 1, "the credits count the death: " + stats.deaths());
		Path edited = film.resolve(done.film);
		check(Files.size(edited) > 100_000, "the film " + edited + " has something in it");
		check(edited.equals(pipeline.filmFile()), "the cinema screen offers this film");
		int beeps = TestCall.toneWindows(edited);
		notes.add("the friend's call is heard for " + beeps / 10.0 + " s in the film");
		check(beeps > 5, "the call is heard in the film: " + beeps + " tenths of a second");
		notes.add("film: " + edited.getFileName() + ", " + Files.size(edited) / 1024 + " KB, " + done.count(RenderProgress.Status.DONE)
			+ " of " + RenderPlan.of(edl).size() + " clips shot, " + done.count(RenderProgress.Status.SKIPPED) + " skipped");
	}

	/** "Delete the recordings after the film" was on: the two sessions it was made of are gone, the film is not. */
	private void checkDeleted(Minecraft mc) {
		var replays = FlashbackBridge.replaysById();
		check(!replays.containsKey(session1) && !replays.containsKey(session2), "the film's replays were deleted");
		check(!Files.exists(SessionStore.sessionFolder(session1)) && !Files.exists(SessionStore.sessionFolder(session2)),
			"the film's session data was deleted");
		WorldMemory world = SurvivalCinemaClient.controller().world();
		check(world != null && !world.sessions.contains(session1.toString()) && !world.sessions.contains(session2.toString()),
			"the world forgot the deleted sessions: " + (world == null ? null : world.sessions));
		check(world.sessions.size() == 1, "only the recording running now is left: " + world.sessions);
		RenderProgress done = RenderProgress.read(FilmFolder.progressFile(film));
		check(Files.isRegularFile(film.resolve(done.film)), "the film itself stays");
	}

	private int screensShown;

	/** The settings (from Mod Menu when it is there), the recordings list, the disk space and the film setup. */
	private boolean lookAtScreens(Minecraft mc, int t) {
		if (t == 1) {
			Screen settings = modMenuSettings();
			if (settings == null) {
				settings = new SettingsScreen(null);
			}
			if (FabricLoader.getInstance().isModLoaded("modmenu")) {
				check(settings instanceof SettingsScreen, "Mod Menu opens our settings");
			}
			mc.setScreen(settings);
		}
		WorldMemory world = SurvivalCinemaClient.controller().world();
		if (t == 30) {
			shot(mc, "play-settings");
			mc.setScreen(new SessionsScreen(null, world));
		}
		if (t == 90) {
			shot(mc, "play-sessions");
			mc.setScreen(new StorageScreen(null, world));
		}
		if (t == 150) {
			shot(mc, "play-storage");
			mc.setScreen(new FilmSetupScreen(null));
		}
		if (t == 210) {
			shot(mc, "play-setup");
			mc.setScreen(null);
			return true;
		}
		return false;
	}

	/** What Mod Menu's "Configure" button of Survival Cinema opens, or null without Mod Menu. */
	private static @Nullable Screen modMenuSettings() {
		if (!FabricLoader.getInstance().isModLoaded("modmenu")) {
			return null;
		}
		try {
			Class<?> api = Class.forName("com.terraformersmc.modmenu.api.ModMenuApi");
			Class<?> factoryType = Class.forName("com.terraformersmc.modmenu.api.ConfigScreenFactory");
			for (var container : FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class)) {
				if (container.getProvider().getMetadata().getId().equals(SurvivalCinemaClient.MOD_ID)) {
					Object factory = api.getMethod("getModConfigScreenFactory").invoke(container.getEntrypoint());
					return (Screen) factoryType.getMethod("create", Screen.class).invoke(factory, (Object) null);
				}
			}
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Mod Menu could not open our settings", e);
		}
		throw new AssertionError("Expected: Survival Cinema in Mod Menu's list");
	}

	// ------------------------------------------------------------------ the machinery

	private void step(String name, Step step) {
		step(name, step, STEP_TIMEOUT);
	}

	private void step(String name, Step step, int timeout) {
		steps.add(new Named(name, step, timeout));
	}

	private void wait(int ticks) {
		step("wait " + ticks + " ticks", (mc, t) -> t >= ticks);
	}

	private void commands(String... commands) {
		step("/" + String.join(", /", commands), (mc, t) -> {
			for (String command : commands) {
				command(mc, command);
			}
			return true;
		});
	}

	/** "x y z" of the middle of a block's floor, for commands ("%d.5" would be the wrong block for negative x and z). */
	private static String middle(int x, int y, int z) {
		return String.format(Locale.ROOT, "%.1f %d %.1f", x + 0.5, y, z + 0.5);
	}

	private static void command(Minecraft mc, String command) {
		LOGGER.info("/{}", command);
		mc.player.connection.sendCommand(command);
	}

	private void tick(Minecraft mc) {
		if (over) {
			return;
		}
		totalTicks++;
		try {
			if (current == null) {
				current = steps.poll();
				stepTicks = 0;
				route = null;
				routeTries = 0;
				chaseGoal = null;
				chaseAgainAt = 0;
				if (current == null) {
					return;
				}
				LOGGER.info("Playthrough: {}", current.name());
			}
			stepTicks++;
			if (current.step().tick(mc, stepTicks)) {
				current = null;
			} else if (stepTicks > current.timeout()) {
				throw new AssertionError("Timed out: " + current.name() + (mc.player == null ? "" : ", the bot at " + mc.player.position()));
			}
			if (mc.player != null && mc.screen == null) {
				turnHead(mc.player);
			}
			if (!errors.isEmpty() && current == null && steps.isEmpty()) {
				finish(mc, null);
			}
		} catch (Throwable t) {
			finish(mc, t);
		}
	}

	private void finish(Minecraft mc, @Nullable Throwable failure) {
		over = true;
		stop(mc);
		if (call != null) {
			call.destroyForcibly();
		}
		CallVoiceRecorder.test = null;
		String where = current == null ? "" : " at \"" + current.name() + "\"";
		boolean passed = failure == null && errors.isEmpty();
		StringBuilder report = new StringBuilder(passed ? "PLAYTHROUGH PASSED" : "PLAYTHROUGH FAILED" + where).append('\n');
		if (failure != null) {
			report.append("failure: ").append(failure).append('\n');
		}
		for (String error : errors) {
			report.append("error: ").append(error).append('\n');
		}
		for (String note : notes) {
			report.append("note: ").append(note).append('\n');
		}
		report.append(String.format(Locale.ROOT, "%d blocks placed by the bot, %d times put over an obstacle, %d s of play and filming%n",
			placed, teleports, totalTicks / 20));
		if (passed) {
			LOGGER.info("{}", report);
		} else {
			LOGGER.error("{}", report, failure);
		}
		try {
			Files.writeString(mc.gameDirectory.toPath().resolve("playtest-result.txt"), report.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Could not write the result", e);
		}
		shot(mc, passed ? "play-end" : "play-failed");
		mc.execute(mc::stop);
	}

	/** Clicks the button with this text, like a player would. */
	private static void press(Minecraft mc, String translationKey) {
		String text = Component.translatable(translationKey).getString();
		for (AbstractWidget widget : Screens.getButtons(mc.screen)) {
			if (widget instanceof AbstractButton button && button.active && text.equals(button.getMessage().getString())) {
				button.onPress(new MouseButtonInfo(0, 0));
				return;
			}
		}
		throw new AssertionError("Expected: a button \"" + text + "\" on " + mc.screen);
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), 1, message -> {
		});
	}

	private final java.util.Set<RenderPipeline.Phase> phasesShot = java.util.EnumSet.noneOf(RenderPipeline.Phase.class);

	private void screenshotPhase(Minecraft mc, RenderPipeline pipeline) {
		if (!FlashbackBridge.isExporting() && phasesShot.add(pipeline.phase())) {
			shot(mc, "play-film-" + pipeline.phase().name().toLowerCase(Locale.ROOT));
		}
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("Expected: " + what);
		}
	}

	/** Only this test's own world and recordings: the game folder is the test's (a dev run folder or a test instance). */
	private static void cleanGameFolder(Minecraft mc) throws IOException {
		Path game = mc.gameDirectory.toPath();
		check(Files.isRegularFile(game.resolve("survivalcinema-playtest.txt")) || FabricLoader.getInstance().isDevelopmentEnvironment(),
			"a test game folder (it has survivalcinema-playtest.txt): " + game);
		for (Path folder : List.of(game.resolve("saves").resolve(WORLD_NAME), game.resolve("survivalcinema"),
			FlashbackBridge.replayFolder(), FlashbackBridge.replayFolder().resolveSibling("temp"))) {
			deleteTree(folder);
		}
		Files.deleteIfExists(game.resolve("playtest-result.txt"));
	}

	private static void deleteTree(Path folder) throws IOException {
		if (!Files.exists(folder)) {
			return;
		}
		try (Stream<Path> files = Files.walk(folder)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(file);
			}
		}
	}

	/** Every error (and warning) Survival Cinema logs during the test is a finding. */
	private void watchErrors() {
		LoggerContext context = (LoggerContext) LogManager.getContext(false);
		AbstractAppender appender = new AbstractAppender("survivalcinema-playtest", null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				String logger = event.getLoggerName();
				if (logger == null || !logger.startsWith(SurvivalCinemaClient.MOD_ID) || logger.equals("survivalcinema-playtest")) {
					return;
				}
				String message = event.getMessage().getFormattedMessage();
				if (event.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.ERROR)) {
					errors.add("logged error: " + message + (event.getThrown() == null ? "" : " (" + event.getThrown() + ")"));
				} else if (event.getLevel().isMoreSpecificThan(org.apache.logging.log4j.Level.WARN)) {
					notes.add("logged warning: " + message + (event.getThrown() == null ? "" : " (" + event.getThrown() + ")"));
				}
			}
		};
		appender.start();
		context.getConfiguration().getRootLogger().addAppender(appender, org.apache.logging.log4j.Level.WARN, null);
		context.updateLoggers();
	}
}
