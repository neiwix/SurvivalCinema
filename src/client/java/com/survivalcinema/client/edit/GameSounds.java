package com.survivalcinema.client.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.survivalcinema.client.SurvivalCinemaClient;
import com.survivalcinema.director.edit.SoundCues;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The game's own sound files for the editor's sound effects, found the way the game finds them
 * ({@code sounds.json}, resource packs included).
 */
final class GameSounds {
	private GameSounds() {
	}

	/** Reads every effect's sound file; call on the client thread. Missing sounds are left out. */
	static Map<SoundCues.Effect, byte[]> load(ResourceManager resources) {
		Map<SoundCues.Effect, byte[]> sounds = new EnumMap<>(SoundCues.Effect.class);
		List<JsonObject> definitions = definitions(resources);
		for (SoundCues.Effect effect : SoundCues.Effect.values()) {
			try {
				String name = soundFile(definitions, effect.sound);
				if (name == null) {
					continue;
				}
				Identifier id = Identifier.parse(name);
				Identifier file = Identifier.fromNamespaceAndPath(id.getNamespace(), "sounds/" + id.getPath() + ".ogg");
				Resource resource = resources.getResource(file).orElse(null);
				if (resource != null) {
					try (InputStream in = resource.open()) {
						sounds.put(effect, in.readAllBytes());
					}
				}
			} catch (Exception e) {
				SurvivalCinemaClient.LOGGER.warn("Could not load the sound {}", effect.sound, e);
			}
		}
		return sounds;
	}

	/** Every pack's {@code sounds.json}, the top pack first. */
	private static List<JsonObject> definitions(ResourceManager resources) {
		List<JsonObject> definitions = new ArrayList<>();
		List<Resource> stack = resources.getResourceStack(Identifier.withDefaultNamespace("sounds.json"));
		for (int i = stack.size() - 1; i >= 0; i--) {
			try (Reader reader = stack.get(i).openAsReader()) {
				definitions.add(JsonParser.parseReader(reader).getAsJsonObject());
			} catch (Exception e) {
				SurvivalCinemaClient.LOGGER.warn("Skipping a broken sounds.json", e);
			}
		}
		return definitions;
	}

	/** The first sound file of a sound event, from the top resource pack that defines it. */
	private static @Nullable String soundFile(List<JsonObject> definitions, String event) {
		for (JsonObject json : definitions) {
			if (!json.has(event)) {
				continue;
			}
			for (JsonElement sound : json.getAsJsonObject(event).getAsJsonArray("sounds")) {
				if (sound.isJsonPrimitive()) {
					return sound.getAsString();
				}
				JsonObject object = sound.getAsJsonObject();
				if (!object.has("type") || "file".equals(object.get("type").getAsString())) {
					return object.get("name").getAsString();
				}
			}
		}
		return null;
	}
}
