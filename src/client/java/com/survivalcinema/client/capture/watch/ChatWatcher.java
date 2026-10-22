package com.survivalcinema.client.capture.watch;

import com.survivalcinema.client.capture.EventSink;
import com.survivalcinema.director.event.EventType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Other players' advancements and deaths, from the vanilla system chat messages. Our own are caught elsewhere.
 */
public final class ChatWatcher {
	public void onGameMessage(Minecraft mc, Component message, @Nullable EventSink sink) {
		if (sink == null || mc.player == null || !(message.getContents() instanceof TranslatableContents tc)) {
			return;
		}
		String key = tc.getKey();
		Object[] args = tc.getArgs();
		if (key.startsWith("chat.type.advancement.") && args.length >= 2) {
			String who = asString(args[0]);
			if (!isSelf(mc, who)) {
				sink.emit(EventType.OTHER_ADVANCEMENT, key, who + ": " + asString(args[1]), positionOf(mc, who), 0.4, false, null);
			}
		} else if (key.startsWith("death.") && args.length >= 1) {
			String who = asString(args[0]);
			if (!isSelf(mc, who)) {
				sink.emit(EventType.OTHER_DEATH, key, message.getString(), positionOf(mc, who), 0.6, false, who);
			}
		}
	}

	/** Messages show the display name (with team prefixes on many servers), not the plain profile name. */
	private static boolean isSelf(Minecraft mc, String who) {
		return who.equals(mc.player.getName().getString()) || who.equals(mc.player.getDisplayName().getString());
	}

	private static String asString(Object arg) {
		return arg instanceof Component component ? component.getString() : String.valueOf(arg);
	}

	private static @Nullable Vec3 positionOf(Minecraft mc, String name) {
		if (mc.level == null) {
			return null;
		}
		for (Player player : mc.level.players()) {
			if (player.getName().getString().equals(name) || player.getDisplayName().getString().equals(name)) {
				return player.position();
			}
		}
		return null;
	}
}
