package com.survivalcinema.director.story;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A part of the film with its own title card: "Day 3", "Days 4-6", or "The Nether" on the first trip there.
 *
 * @param dimension set when the chapter opens with the first visit to a new dimension
 * @param subtitle  the best moment of the chapter, shown under the title (already in the player's language)
 */
public record Chapter(int dayFrom, int dayTo, @Nullable String dimension, @Nullable String subtitle, List<Scene> scenes) {
	public int durationTicks() {
		return scenes.stream().mapToInt(Scene::durationTicks).sum();
	}
}
