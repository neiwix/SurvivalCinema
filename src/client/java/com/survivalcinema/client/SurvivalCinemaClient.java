package com.survivalcinema.client;

import com.survivalcinema.client.capture.CaptureHooks;
import com.survivalcinema.client.capture.EventSink;
import com.survivalcinema.client.capture.RecordingController;
import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.client.debug.DebugCommands;
import com.survivalcinema.client.debug.HangWatchdog;
import com.survivalcinema.client.edit.EncoderSelector;
import com.survivalcinema.client.flashback.FlashbackCheck;
import com.survivalcinema.client.render.ClipQueue;
import com.survivalcinema.client.render.RenderPipeline;
import com.survivalcinema.client.ui.DragonOfferWatcher;
import com.survivalcinema.client.ui.PauseMenuButton;
import com.survivalcinema.client.ui.RecordingIndicator;
import com.survivalcinema.client.voice.VoiceActivityMeter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SurvivalCinemaClient implements ClientModInitializer {
	public static final String MOD_ID = "survivalcinema";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final RecordingController CONTROLLER = new RecordingController();
	private static boolean flashbackWarned;

	public static RecordingController controller() {
		return CONTROLLER;
	}

	@Override
	public void onInitializeClient() {
		LOGGER.info("Survival Cinema loaded");
		CaptureHooks.install(CONTROLLER);
		HangWatchdog watchdog = HangWatchdog.start();
		EncoderSelector.startWarmUp();

		if (FabricLoader.getInstance().isDevelopmentEnvironment() || SurvivalCinemaConfig.get().debugCommands) {
			// developer tools; players make films from the pause menu
			ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> DebugCommands.register(dispatcher));
		}
		DragonOfferWatcher dragonOffer = new DragonOfferWatcher();
		CONTROLLER.setOnDragonKilled(dragonOffer::dragonKilled);
		PauseMenuButton.install();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			watchdog.heartbeat();
			CONTROLLER.tick(client);
			ClipQueue.tick();
			RenderPipeline.tickCurrent(client);
			dragonOffer.tick(client);
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> CONTROLLER.onJoin(client));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> CONTROLLER.onDisconnect(client));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> CONTROLLER.onClientStopping());
		// checks that this Flashback has everything we use; when not, recording and filming stay off (and we say why)
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> FlashbackCheck.result());
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof TitleScreen && !flashbackWarned && FlashbackCheck.problem() != null) {
				flashbackWarned = true;
				client.getToastManager().addToast(SystemToast.multiline(client, SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
					Component.translatable("survivalcinema.flashback.off"), FlashbackCheck.problem()));
			}
			String crashed = screen instanceof TitleScreen ? EncoderSelector.takeCrashed() : null;
			if (crashed != null) {
				client.getToastManager().addToast(SystemToast.multiline(client, SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
					Component.translatable("survivalcinema.encoder.crashed.title"), Component.translatable("survivalcinema.encoder.crashed", crashed)));
			}
		});

		VoiceActivityMeter.get().setActivityListener(CONTROLLER::noteActivity);
		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> CONTROLLER.noteActivity());
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				CONTROLLER.chat.onGameMessage(Minecraft.getInstance(), message, CONTROLLER.sink());
			}
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (level.isClientSide()) {
				CONTROLLER.combat.onAttack(entity);
			}
			return InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			EventSink sink = CONTROLLER.sink();
			if (level.isClientSide() && sink != null && player.getItemInHand(hand).getItem() instanceof BlockItem) {
				CONTROLLER.builds.onPlace(hitResult.getBlockPos().relative(hitResult.getDirection()), sink);
			}
			// sneaking with something in hand uses the item on the block, not the block: no chest opens
			boolean itemOnly = player.isSecondaryUseActive() && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty());
			if (level.isClientSide() && sink != null && !itemOnly) {
				CONTROLLER.structures.onUse(Minecraft.getInstance(), hitResult.getBlockPos());
			}
			return InteractionResult.PASS;
		});
		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
			EventSink sink = CONTROLLER.sink();
			if (sink != null) {
				CONTROLLER.wood.onBreak(Minecraft.getInstance(), pos, state, sink);
			}
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "recording_indicator"), new RecordingIndicator(CONTROLLER));
	}

}
