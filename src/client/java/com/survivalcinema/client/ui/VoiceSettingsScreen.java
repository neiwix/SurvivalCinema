package com.survivalcinema.client.ui;

import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.client.voice.apps.CallVoiceRecorder;
import com.survivalcinema.director.voice.CallApps;
import com.survivalcinema.director.voice.VoiceSource;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where the film's voices come from: the game's voice chat, a call in Discord, Telegram... and your microphone.
 * While it is open the call apps and the microphone are listened to (nothing is written), so the bars show at once
 * whether your friends' call is heard.
 */
public final class VoiceSettingsScreen extends Screen {
	private static final int COLUMN = 310;
	private static final int HALF = 150;
	private static final int ROW = 22;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int GREEN = 0xFF55FF55;
	private static final int RED = 0xFFFF5555;
	private static final int BAR_BACK = 0xFF303030;

	/** One meter: its label and track name, where it is drawn. */
	private record Meter(@Nullable CallApps.App app, int y) {
	}

	private final Screen parent;
	private final List<Meter> meters = new ArrayList<>();
	private int notesY;

	public VoiceSettingsScreen(Screen parent) {
		super(Component.translatable("survivalcinema.voice.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		CallVoiceRecorder.get().rescan();
		SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
		meters.clear();
		int center = width / 2;
		int left = center - COLUMN / 2;
		int right = center + COLUMN / 2 - HALF;
		int y = 40;
		addRenderableWidget(CycleButton.<VoiceSource>builder(s -> Component.translatable(key(s)), config.voiceSource())
			.withValues(VoiceSource.values())
			.withTooltip(s -> Tooltip.create(Component.translatable(key(s) + ".tooltip")))
			.create(left, y, HALF, 20, Component.translatable("survivalcinema.voice.source"), (b, value) -> {
				config.voiceSource = value.name();
				config.save();
				SurvivalCinemaClient.controller().voiceSettingsChanged();
			}));
		addRenderableWidget(CycleButton.onOffBuilder(config.micCommentary)
			.withTooltip(v -> Tooltip.create(Component.translatable("survivalcinema.voice.commentary.tooltip")))
			.create(right, y, HALF, 20, Component.translatable("survivalcinema.voice.commentary"), (b, value) -> {
				config.micCommentary = value;
				config.save();
			}));
		y += ROW + 14;

		if (CallVoiceRecorder.appsSupported()) {
			for (CallApps.App app : CallApps.ALL) {
				addRenderableWidget(CycleButton.onOffBuilder(!config.voiceAppsOff.contains(app.id()))
					.create(left, y, HALF, 20, Component.literal(app.displayName()), (b, on) -> {
						config.voiceAppsOff.remove(app.id());
						if (!on) {
							config.voiceAppsOff.add(app.id());
						}
						config.save();
						CallVoiceRecorder.get().rescan();
					}));
				meters.add(new Meter(app, y));
				y += ROW;
			}
		}
		meters.add(new Meter(null, y));
		y += ROW + 6;
		notesY = y;
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
			.bounds(center - 100, Math.max(y + 40, height - 28), 200, 20).build());
	}

	private static String key(VoiceSource source) {
		return "survivalcinema.voice.source." + source.name().toLowerCase(Locale.ROOT);
	}

	@Override
	public void tick() {
		CallVoiceRecorder.get().preview();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		int center = width / 2;
		int right = center + COLUMN / 2 - HALF;
		graphics.drawCenteredString(font, title, center, 15, WHITE);
		graphics.drawCenteredString(font, Component.translatable("survivalcinema.voice.subtitle"), center, 27, GREY);
		if (CallVoiceRecorder.appsSupported() && !meters.isEmpty()) {
			graphics.drawString(font, Component.translatable("survivalcinema.voice.apps"), center - COLUMN / 2, meters.getFirst().y() - 11, GREY);
		}
		List<CallApps.Running> running = CallVoiceRecorder.get().running();
		for (Meter meter : meters) {
			CallApps.App app = meter.app();
			if (app == null) {
				graphics.drawString(font, Component.translatable("survivalcinema.voice.mic"), center - COLUMN / 2, meter.y() + 6, WHITE);
				drawMeter(graphics, right, meter.y(), CallVoiceRecorder.MIC_TRACK, Component.translatable("survivalcinema.voice.mic.idle"));
				continue;
			}
			boolean isRunning = running.stream().anyMatch(r -> r.app().id().equals(app.id()));
			Component idle = Component.translatable(isRunning ? "survivalcinema.voice.app.running" : "survivalcinema.voice.app.not_running");
			drawMeter(graphics, right, meter.y(), app.id(), idle);
		}
		int y = notesY;
		for (String note : notes()) {
			for (var line : font.split(Component.translatable(note), COLUMN)) {
				graphics.drawCenteredString(font, line, center, y, GREY);
				y += font.lineHeight + 2;
			}
			y += 2;
		}
	}

	private List<String> notes() {
		List<String> notes = new ArrayList<>();
		notes.add("survivalcinema.voice.note.tell");
		notes.add("survivalcinema.voice.note.headphones");
		if (!CallVoiceRecorder.appsSupported()) {
			notes.add("survivalcinema.voice.note.windows");
		}
		return notes;
	}

	/** A level bar while the track is heard, otherwise why not (or what went wrong). */
	private void drawMeter(GuiGraphics graphics, int x, int y, String track, Component idle) {
		float level = CallVoiceRecorder.get().level(track);
		if (level < 0) {
			String problem = CallVoiceRecorder.get().problem(track);
			Component text = problem != null ? Component.translatable("survivalcinema.voice.problem", problem) : idle;
			graphics.drawString(font, font.plainSubstrByWidth(text.getString(), HALF), x, y + 6, problem != null ? RED : GREY);
			return;
		}
		int top = y + 5;
		graphics.fill(x, top, x + HALF, top + 10, BAR_BACK);
		double db = level <= 0 ? -100 : 20 * Math.log10(level);
		int fill = (int) Math.round(Math.max(0, Math.min(1, (db + 50) / 50)) * HALF);
		graphics.fill(x, top, x + fill, top + 10, db > -30 ? GREEN : GREY);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
