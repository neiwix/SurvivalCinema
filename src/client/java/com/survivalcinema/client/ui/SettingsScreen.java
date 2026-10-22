package com.survivalcinema.client.ui;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.capture.WorldMemory;
import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.client.edit.MusicLibrary;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.director.render.RenderQuality;
import com.survivalcinema.director.story.FilmLength;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * All of Survival Cinema's settings in one vanilla options list: how recording works (and this world's answer to
 * "record this world?", which can be changed here after "Never"), and the film's defaults. Opened from Mod Menu and
 * from "Make a film". The world's choice is applied when the screen closes, so cycling through "Always" on the way to
 * "Never" does not start and stop a recording.
 */
public final class SettingsScreen extends OptionsSubScreen {
	private static final List<Integer> AFK_MINUTES = List.of(0, 1, 3, 5, 10, 15);
	private static final int WIDE = 310;

	private final @Nullable WorldMemory world;
	private WorldMemory.Consent consent;
	private boolean voices;

	public SettingsScreen(Screen parent) {
		super(parent, Minecraft.getInstance().options, Component.translatable("survivalcinema.settings.title"));
		this.world = FlashbackBridge.isInReplay() ? null : SurvivalCinemaClient.controller().world();
		this.consent = world == null ? WorldMemory.Consent.ASK : world.consent;
		this.voices = world != null && world.recordVoiceChat;
	}

	@Override
	protected void addOptions() {
		SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
		list.addHeader(Component.translatable("survivalcinema.settings.recording"));
		if (world != null) {
			list.addSmall(
				CycleButton.<WorldMemory.Consent>builder(c -> Component.translatable(consentKey(c)), consent)
					.withValues(WorldMemory.Consent.values())
					.withTooltip(c -> Tooltip.create(Component.translatable(consentKey(c) + ".tooltip", world.displayName)))
					.create(0, 0, 150, 20, Component.translatable("survivalcinema.settings.consent"), (b, value) -> consent = value),
				CycleButton.onOffBuilder(voices)
					.withTooltip(v -> Tooltip.create(Component.translatable("survivalcinema.settings.voices.tooltip")))
					.create(0, 0, 150, 20, Component.translatable("survivalcinema.settings.voices"), (b, value) -> voices = value));
		}
		int afk = AFK_MINUTES.contains(config.afkMinutes) ? config.afkMinutes : 3;
		list.addSmall(
			CycleButton.<Integer>builder(m -> m == 0 ? Component.translatable("options.off")
					: Component.translatable("survivalcinema.setup.minutes", m), afk)
				.withValues(AFK_MINUTES)
				.withTooltip(m -> Tooltip.create(Component.translatable("survivalcinema.settings.afk.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("survivalcinema.settings.afk"), (b, value) -> config.afkMinutes = value),
			CycleButton.onOffBuilder(config.showRecordingIndicator)
				.withTooltip(v -> Tooltip.create(Component.translatable("survivalcinema.settings.indicator.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("survivalcinema.settings.indicator"), (b, value) -> config.showRecordingIndicator = value));
		list.addSmall(
			CycleButton.onOffBuilder(config.addFlashbackMarkers)
				.withTooltip(v -> Tooltip.create(Component.translatable("survivalcinema.settings.markers.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("survivalcinema.settings.markers"), (b, value) -> config.addFlashbackMarkers = value),
			Button.builder(Component.translatable("survivalcinema.consent.voice_settings"), b -> minecraft.setScreen(new VoiceSettingsScreen(this)))
				.width(150).build());

		list.addHeader(Component.translatable("survivalcinema.settings.film"));
		list.addSmall(
			CycleButton.<FilmLength>builder(l -> Component.translatable("survivalcinema.setup.minutes", l.minutes()), config.filmLength())
				.withValues(FilmLength.values())
				.withTooltip(l -> Tooltip.create(Component.translatable("survivalcinema.settings.length.tooltip")))
				.create(0, 0, 150, 20, Component.translatable("survivalcinema.settings.length"), (b, value) -> config.filmMinutes = value.minutes()),
			CycleButton.<RenderQuality>builder(q -> Component.translatable(key("survivalcinema.setup.quality.", q)), config.renderQuality())
				.withValues(RenderQuality.values())
				.withTooltip(q -> Tooltip.create(Component.translatable(key("survivalcinema.setup.quality.", q) + ".tooltip")))
				.create(0, 0, 150, 20, Component.translatable("survivalcinema.setup.quality"), (b, value) -> config.renderQuality = value.name()));
		list.addSmall(
			CycleButton.<MusicLibrary.Source>builder(m -> Component.translatable(key("survivalcinema.setup.music.", m)), MusicLibrary.Source.parse(config.music))
				.withValues(MusicLibrary.Source.values())
				.withTooltip(m -> Tooltip.create(Component.translatable(key("survivalcinema.setup.music.", m) + ".tooltip")))
				.create(0, 0, 150, 20, Component.translatable("survivalcinema.setup.music"), (b, value) -> config.music = value.name()),
			Button.builder(Component.translatable("survivalcinema.setup.music_folder"), b -> MusicLibrary.openFolder(minecraft))
				.width(150).build());
		list.addSmall(
			CycleButton.onOffBuilder(config.deleteRecordingsAfterFilm)
				.withTooltip(v -> Tooltip.create(Component.translatable("survivalcinema.storage.delete_after.tooltip")))
				.create(0, 0, WIDE, 20, Component.translatable("survivalcinema.storage.delete_after"), (b, value) -> config.deleteRecordingsAfterFilm = value),
			null);
		if (world != null) {
			list.addSmall(
				Button.builder(Component.translatable("survivalcinema.sessions.button_short"), b -> minecraft.setScreen(new SessionsScreen(this, world)))
					.width(150).build(),
				Button.builder(Component.translatable("survivalcinema.storage.button"), b -> minecraft.setScreen(new StorageScreen(this, world)))
					.width(150).build());
		}
	}

	private static String consentKey(WorldMemory.Consent consent) {
		return "survivalcinema.settings.consent." + consent.name().toLowerCase(Locale.ROOT);
	}

	private static String key(String prefix, Enum<?> value) {
		return prefix + value.name().toLowerCase(Locale.ROOT);
	}

	@Override
	public void removed() {
		super.removed();
		SurvivalCinemaConfig.get().save();
	}

	@Override
	public void onClose() {
		if (world != null && (world.consent != consent || world.recordVoiceChat != voices)) {
			SurvivalCinemaClient.controller().changeWorldChoice(world, consent, voices);
		}
		super.onClose();
	}
}
