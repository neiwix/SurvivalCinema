package com.survivalcinema.client.ui;

import com.survivalcinema.director.story.EditDecisionList;
import net.minecraft.network.chat.Component;

/**
 * Turns a chapter card from the screenplay into its title in the player's language: "Day 3", "Days 4-6", "The Nether".
 */
public final class ChapterTitles {
	private ChapterTitles() {
	}

	public static Component title(EditDecisionList.Item chapter) {
		if (chapter.dimension() != null) {
			return dimension(chapter.dimension());
		}
		if (chapter.dayTo() > chapter.dayFrom()) {
			return Component.translatable("survivalcinema.chapter.days", chapter.dayFrom(), chapter.dayTo());
		}
		return Component.translatable("survivalcinema.chapter.day", chapter.dayFrom());
	}

	/**
	 * "The Nether" for {@code minecraft:the_nether}; a dimension of another mod we have no name for reads from its id
	 * ("The Aether" for {@code aether:the_aether}) instead of showing a translation key.
	 */
	public static Component dimension(String id) {
		String[] parts = id.split(":", 2);
		String path = parts.length == 2 ? parts[1] : id;
		StringBuilder fallback = new StringBuilder();
		for (String word : path.split("[_/]+")) {
			if (!word.isEmpty()) {
				fallback.append(fallback.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		String key = parts.length == 2 ? "survivalcinema.dimension." + parts[0] + "." + parts[1] : id;
		return Component.translatableWithFallback(key, fallback.isEmpty() ? id : fallback.toString());
	}
}
