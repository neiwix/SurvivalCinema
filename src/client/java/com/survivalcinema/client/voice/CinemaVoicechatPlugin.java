package com.survivalcinema.client.voice;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * Simple Voice Chat entrypoint. Only loaded by SVC itself, so the mod works without SVC installed.
 * Flashback records the actual voices; we listen for how loud they are, and keep a copy for slow motion
 * ({@link VoiceChatCapture}).
 */
public class CinemaVoicechatPlugin implements VoicechatPlugin {
	@Override
	public String getPluginId() {
		return "survivalcinema";
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		VoiceActivityMeter meter = VoiceActivityMeter.get();
		registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, e -> heard(e.getId(), e.getRawAudio()));
		registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, e -> heard(e.getId(), e.getRawAudio()));
		registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, e -> heard(e.getId(), e.getRawAudio()));
		registration.registerEvent(ClientVoicechatConnectionEvent.class, e -> meter.setVoiceChatConnected(e.isConnected()));
		registration.registerEvent(ClientSoundEvent.class, e -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				heard(mc.player.getUUID(), e.getRawAudio());
			}
		});
	}

	/** Someone's voice (ours too): how loud it is for the moments, and our own copy for slow motion. */
	private static void heard(UUID speaker, short[] pcm) {
		VoiceActivityMeter.get().onAudio(speaker, pcm);
		VoiceChatCapture.onAudio(speaker, pcm);
	}
}
