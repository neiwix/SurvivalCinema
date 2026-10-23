package com.survivalcinema.client.ui;

import com.survivalcinema.client.config.SurvivalCinemaConfig;
import com.survivalcinema.client.flashback.FlashbackBridge;
import com.survivalcinema.client.play.FilmPlayer;
import com.survivalcinema.director.play.Letterbox;
import com.survivalcinema.director.play.PlayTime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.List;

/**
 * The in-game cinema: plays the finished film full screen, black bars around, with the controls of a video player
 * made of vanilla widgets (pause, a seek bar, volume, "open in a player", close). The controls hide while the film
 * plays and come back with the mouse. Space pauses, the arrows jump 5 seconds, &lt; and &gt; change the speed, Esc goes
 * back to where it was opened.
 * <p>
 * The game's music keeps quiet while it is open ({@code MusicManagerMixin}).
 */
public final class FilmPlayerScreen extends Screen {
	private static final int MARGIN = 10;
	private static final int GAP = 4;
	private static final long CONTROLS_HIDE_NANOS = 3_000_000_000L;
	private static final double JUMP_SECONDS = 5;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFA0A0A0;
	private static final int SPEED_WIDTH = 40;
	/** Like a video site's player; the sound keeps its pitch at all of them. */
	private static final List<Double> SPEEDS = List.of(0.5, 0.75, 1.0, 1.25, 1.5, 2.0);

	private final Screen parent;
	private final Path file;
	private final Component filmTitle;
	private @Nullable FilmPlayer player;
	private float volume = SurvivalCinemaConfig.get().playerVolume;
	private double speed = 1;
	private long lastActivity = System.nanoTime();
	private double lastMouseX = Double.NaN;
	private double lastMouseY = Double.NaN;
	private boolean released;

	private Button playButton;
	private Button speedButton;
	private SeekBar seekBar;
	private VolumeSlider volumeSlider;
	private List<AbstractWidget> controls = List.of();

	public FilmPlayerScreen(Screen parent, Path file, String worldName) {
		super(Component.translatable("survivalcinema.player.title", worldName));
		this.parent = parent;
		this.file = file;
		this.filmTitle = Component.translatable("survivalcinema.player.title", worldName);
	}

	/** The cinema is on screen (the game's music waits). */
	public static boolean isOpen() {
		return Minecraft.getInstance().screen instanceof FilmPlayerScreen;
	}

	@Override
	protected void init() {
		if (player == null && !released) {
			player = new FilmPlayer(file, minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
		}
		boolean narrow = width < 460;
		int playWidth = 70;
		int volumeWidth = narrow ? 80 : 110;
		int systemWidth = narrow ? 90 : 120;
		int closeWidth = 70;
		int rowY = height - MARGIN - 20;
		int seekY = rowY - 24;

		seekBar = addRenderableWidget(new SeekBar(MARGIN, seekY, width - 2 * MARGIN, 20));
		playButton = addRenderableWidget(Button.builder(playLabel(), b -> togglePause())
			.bounds(MARGIN, rowY, playWidth, 20).build());
		volumeSlider = addRenderableWidget(new VolumeSlider(MARGIN + playWidth + GAP, rowY, volumeWidth, 20));
		speedButton = addRenderableWidget(Button.builder(speedLabel(), b -> changeSpeed(1, true))
			.bounds(volumeSlider.getX() + volumeWidth + GAP, rowY, SPEED_WIDTH, 20)
			.tooltip(Tooltip.create(Component.translatable("survivalcinema.player.speed.hint"))).build());
		Button close = addRenderableWidget(Button.builder(Component.translatable("survivalcinema.player.close"), b -> onClose())
			.bounds(width - MARGIN - closeWidth, rowY, closeWidth, 20).build());
		Button system = addRenderableWidget(Button.builder(Component.translatable("survivalcinema.player.system"), b -> openInSystemPlayer())
			.bounds(close.getX() - GAP - systemWidth, rowY, systemWidth, 20).build());
		controls = List.of(seekBar, playButton, volumeSlider, speedButton, system, close);
	}

	/** One step faster (or slower); the button goes round from the fastest back to the slowest. */
	private void changeSpeed(int step, boolean round) {
		int next = SPEEDS.indexOf(speed) + step;
		if (round) {
			next = Math.floorMod(next, SPEEDS.size());
		}
		speed = SPEEDS.get(Math.max(0, Math.min(SPEEDS.size() - 1, next)));
		if (player != null) {
			player.setSpeed(speed);
		}
		speedButton.setMessage(speedLabel());
		activity();
	}

	private Component speedLabel() {
		String number = speed == Math.rint(speed) ? String.valueOf((int) speed) : String.valueOf(speed);
		return Component.literal(number + "×");
	}

	// ---- playing ----

	private void togglePause() {
		if (player != null) {
			player.togglePause();
			activity();
		}
	}

	private void jump(double seconds) {
		if (player != null) {
			player.seek(player.position() + seconds);
			activity();
		}
	}

	private void openInSystemPlayer() {
		if (player != null) {
			player.pause();
		}
		Util.getPlatform().openPath(file);
	}

	private float gain() {
		return volume * minecraft.options.getSoundSourceVolume(SoundSource.MASTER);
	}

	private Component playLabel() {
		FilmPlayer.State state = player == null ? FilmPlayer.State.OPENING : player.state();
		return Component.translatable(switch (state) {
			case PLAYING -> "survivalcinema.player.pause";
			case ENDED -> "survivalcinema.player.replay";
			default -> "survivalcinema.player.play";
		});
	}

	private void activity() {
		lastActivity = System.nanoTime();
	}

	private boolean controlsShown() {
		if (player == null || player.state() != FilmPlayer.State.PLAYING || seekBar != null && seekBar.held) {
			return true;
		}
		return System.nanoTime() - lastActivity < CONTROLS_HIDE_NANOS;
	}

	// ---- drawing ----

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		FilmPlayer film = player;
		if (film != null) {
			film.update(gain());
		}
		graphics.fill(0, 0, width, height, 0xFF000000);
		Identifier texture = film == null ? null : film.texture();
		if (texture != null) {
			int windowWidth = minecraft.getWindow().getWidth();
			int windowHeight = minecraft.getWindow().getHeight();
			int scale = Math.max(1, minecraft.getWindow().getGuiScale());
			// in window pixels, so the film fills the screen exactly whatever the GUI scale
			Letterbox box = Letterbox.fit(film.videoWidth(), film.videoHeight(), windowWidth, windowHeight);
			graphics.pose().pushMatrix();
			graphics.pose().scale(1f / scale, 1f / scale);
			graphics.blit(RenderPipelines.GUI_TEXTURED, texture, box.x(), box.y(), 0, 0, box.width(), box.height(),
				film.videoWidth(), film.videoHeight(), film.videoWidth(), film.videoHeight());
			graphics.pose().popMatrix();
		}
		Component status = status(film);
		if (status != null) {
			graphics.drawCenteredString(font, status, width / 2, height / 2 - 4, film != null && film.state() == FilmPlayer.State.FAILED ? WHITE : GREY);
		}

		boolean shown = controlsShown();
		for (AbstractWidget widget : controls) {
			widget.visible = shown;
		}
		if (shown) {
			graphics.fillGradient(0, height - 70, width, height, 0x00000000, 0xC0000000);
			graphics.fillGradient(0, 0, width, 30, 0xA0000000, 0x00000000);
			graphics.drawString(font, filmTitle, MARGIN, MARGIN, WHITE, true);
			playButton.setMessage(playLabel());
			seekBar.follow();
			if (film == null || !film.hasSound() && film.state() != FilmPlayer.State.OPENING && film.state() != FilmPlayer.State.FAILED) {
				graphics.drawString(font, Component.translatable("survivalcinema.player.no_sound"), MARGIN, MARGIN + 12, GREY, true);
			}
			Component hint = Component.translatable("survivalcinema.player.hint");
			graphics.drawString(font, hint, width - MARGIN - font.width(hint), MARGIN, GREY, true);
		}
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	private @Nullable Component status(@Nullable FilmPlayer film) {
		if (film == null || film.state() == FilmPlayer.State.OPENING) {
			return Component.translatable("survivalcinema.player.opening");
		}
		if (film.state() == FilmPlayer.State.FAILED) {
			return Component.translatable("survivalcinema.player.failed", String.valueOf(film.problem()));
		}
		if (film.isBuffering() && film.texture() == null) {
			return Component.translatable("survivalcinema.player.opening");
		}
		return null;
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// black, drawn in render(): no blur or menu background behind a film
	}

	// ---- input ----

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		if (!Double.isNaN(lastMouseX) && (Math.abs(mouseX - lastMouseX) > 0.5 || Math.abs(mouseY - lastMouseY) > 0.5)) {
			activity();
		}
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		super.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		boolean wereShown = controlsShown();
		activity();
		if (wereShown && super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && (!wereShown || event.y() < seekBar.getY() - 4)) {
			togglePause(); // a click on the picture, like any video player
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		activity();
		if (event.isEscape()) {
			onClose();
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_SPACE || event.key() == GLFW.GLFW_KEY_K) {
			togglePause();
			return true;
		}
		// Shift+> and Shift+<, like video sites (also without Shift)
		if (event.key() == GLFW.GLFW_KEY_PERIOD) {
			changeSpeed(1, false);
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_COMMA) {
			changeSpeed(-1, false);
			return true;
		}
		if (getFocused() != volumeSlider) {
			if (event.isLeft()) {
				jump(-JUMP_SECONDS);
				return true;
			}
			if (event.isRight()) {
				jump(JUMP_SECONDS);
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		release();
		minecraft.setScreen(parent);
	}

	@Override
	public void removed() {
		release();
	}

	private void release() {
		if (released) {
			return;
		}
		released = true;
		if (player != null) {
			player.close();
			player = null;
		}
		SurvivalCinemaConfig config = SurvivalCinemaConfig.get();
		if (config.playerVolume != volume) {
			config.playerVolume = volume;
			config.save();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return !FlashbackBridge.isInReplay();
	}

	// ---- for the end-to-end test ----

	/** What the cinema is doing now. */
	public record Status(FilmPlayer.State state, double position, double duration, int shownPictures, boolean hasSound,
						 boolean soundPlaying) {
	}

	public Status status() {
		FilmPlayer film = player;
		if (film == null) {
			return new Status(FilmPlayer.State.FAILED, 0, 0, 0, false, false);
		}
		return new Status(film.state(), film.position(), film.duration(), film.shownPictures(), film.hasSound(), film.isSoundPlaying());
	}

	public void setSpeed(double speed) {
		this.speed = speed;
		if (player != null) {
			player.setSpeed(speed);
		}
		if (speedButton != null) {
			speedButton.setMessage(speedLabel());
		}
	}

	public void seekTo(double seconds) {
		if (player != null) {
			player.seek(seconds);
		}
	}

	public void setPaused(boolean paused) {
		if (player != null) {
			if (paused) {
				player.pause();
			} else {
				player.play();
			}
		}
	}

	// ---- widgets ----

	/** "1:23 / 5:00": drag to look for a moment; the film jumps there on release. */
	private final class SeekBar extends AbstractSliderButton {
		private boolean held;

		SeekBar(int x, int y, int width, int height) {
			super(x, y, width, height, Component.empty(), 0);
			updateMessage();
		}

		private double duration() {
			return player == null ? 0 : player.duration();
		}

		/** Moves the handle with the film, unless the player holds it. */
		void follow() {
			if (!held && player != null && duration() > 0) {
				value = Math.max(0, Math.min(1, player.position() / duration()));
			}
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			double at = held || player == null ? value * duration() : player.position();
			setMessage(Component.literal(PlayTime.format(at) + " / " + PlayTime.format(duration())));
		}

		@Override
		protected void applyValue() {
			if (!held && player != null) {
				player.seek(value * duration()); // from the keyboard
			}
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			held = true;
			super.onClick(event, doubleClick);
		}

		@Override
		public void onRelease(MouseButtonEvent event) {
			super.onRelease(event);
			if (held) {
				held = false;
				if (player != null) {
					player.seek(value * duration());
				}
			}
		}
	}

	private final class VolumeSlider extends AbstractSliderButton {
		VolumeSlider(int x, int y, int width, int height) {
			super(x, y, width, height, Component.empty(), volume);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("survivalcinema.player.volume", (int) Math.round(value * 100)));
		}

		@Override
		protected void applyValue() {
			volume = (float) value;
		}
	}
}
