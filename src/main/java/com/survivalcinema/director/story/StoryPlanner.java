package com.survivalcinema.director.story;

import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Picks which scenes make the film and in what chapters. It works like an editor going through the footage:
 * <ul>
 *     <li>the opening, the first night and the dragon fight are always in;</li>
 *     <li>then the best scenes, but with variety: every scene of a kind already picked makes the next one of that
 *     kind worth less, and some kinds have a hard limit (nobody wants six scenes of getting hit);</li>
 *     <li>the whole adventure is covered: a scene right next to one already picked is worth less, and a part of the
 *     playthrough with nothing picked yet is worth more;</li>
 *     <li>voice-only moments may be at most 40% of the film, so it still shows the adventure;</li>
 *     <li>if the good moments run out before the requested length, a second pass takes medium moments and travel
 *     footage, but never junk; a world with little going on gets a shorter film instead of filler;</li>
 *     <li>finally everything goes back into chronological order, split into chapters by in-game day and first
 *     trips to new dimensions.</li>
 * </ul>
 */
public final class StoryPlanner {
	public static final double INTRO_SECONDS = 5;
	public static final double CHAPTER_CARD_SECONDS = 2.5;
	public static final double CREDITS_SECONDS = 10;
	static final double DIVERSITY_FACTOR = 0.75;
	/** Voice moments are the heart of the film, so they lose value slower than other kinds when picked often. */
	static final double VOICE_DIVERSITY_FACTOR = 0.85;
	/** First pass: only scenes worth at least this. */
	static final double MIN_SCENE_SCORE = 0.12;
	/** Second pass, used when the first pass filled less than {@link #SECOND_PASS_BELOW} of the film. */
	static final double SECOND_PASS_MIN_SCORE = 0.045;
	static final double SECOND_PASS_BELOW = 0.75;
	static final double VOICE_ONLY_SHARE = 0.4;
	/** Picked scenes this close in play time (2 minutes) make each other less attractive. */
	static final int NEIGHBOUR_TICKS = 20 * 120;
	static final double NEIGHBOUR_FACTOR = 0.7;
	static final double EMPTY_PART_BONUS = 1.3;
	static final int MIN_TRIMMED_TICKS = 60;
	static final int OPENING_TICKS = 120;
	/** How long before the dragon dies its fight scene may start and still belong to the finale (3 minutes). */
	static final int FINALE_FIGHT_TICKS = 20 * 180;

	/** Hard limits per 5 minutes of film for the kinds that would otherwise repeat; kinds not listed are free. */
	static final Map<EventType, Integer> CAPS_PER_5_MIN = new EnumMap<>(Map.ofEntries(
		Map.entry(EventType.BIG_DAMAGE, 1), Map.entry(EventType.KILL, 2), Map.entry(EventType.KILL_STREAK, 2),
		Map.entry(EventType.BUILD_BURST, 2), Map.entry(EventType.NEW_BIOME, 3), Map.entry(EventType.NIGHT_FALL, 1),
		Map.entry(EventType.SLEEP, 1), Map.entry(EventType.NEW_DAY, 0), Map.entry(EventType.RARE_ITEM, 3),
		Map.entry(EventType.ELYTRA_FLIGHT, 2), Map.entry(EventType.OTHER_ADVANCEMENT, 2), Map.entry(EventType.BIG_FALL, 2),
		Map.entry(EventType.SCENERY, 3), Map.entry(EventType.DEATH, 4), Map.entry(EventType.OTHER_DEATH, 2),
		Map.entry(EventType.ADVANCEMENT, 6), Map.entry(EventType.CONVERSATION, 2)));
	/** Calm talks reserved per 5 minutes of film, so it is the story of friends and not only of fights. */
	static final double CONVERSATIONS_PER_5_MIN = 1;
	/** A talk after the dragon is the perfect epilogue; the first talk of the world is how it all began. */
	static final double EPILOGUE_BONUS = 2.0;
	static final double FIRST_SESSION_TALK_BONUS = 1.4;
	/** If less than this share of all play came after the dragon, the dragon is the film's finale. */
	static final double FINALE_TAIL_SHARE = 0.15;
	/**
	 * What comes right after the kill is still the victory, not more story: the death animation, the egg, the exit
	 * portal, the credits (3 minutes). It does not count as "played after the dragon", which matters in short worlds.
	 */
	static final int DRAGON_CEREMONY_TICKS = 20 * 180;

	public List<Chapter> plan(List<SessionData> sessions, List<Scene> candidates, int targetSeconds) {
		return plan(sessions, candidates, List.of(), targetSeconds);
	}

	public List<Chapter> plan(List<SessionData> sessions, List<Scene> candidates, List<Scene> scenery, int targetSeconds) {
		List<Scene> selected = new Selection(sessions, targetSeconds).run(candidates, scenery);
		selected.sort(Comparator.comparingLong(Scene::order));
		return chapters(selected, maxChapters(targetSeconds));
	}

	static int maxChapters(int targetSeconds) {
		return Math.max(2, Math.min(10, targetSeconds / 60 + 1));
	}

	static int cap(EventType type, int targetSeconds) {
		Integer per5 = CAPS_PER_5_MIN.get(type);
		if (per5 == null) {
			return Integer.MAX_VALUE;
		}
		return (int) Math.ceil(per5 * Math.max(1, targetSeconds / 300.0));
	}

	/** One run of the editor's selection. */
	private static final class Selection {
		private final List<SessionData> sessions;
		private final int targetSeconds;
		private final int budget;
		private final Map<SessionData, Long> sessionOffset = new IdentityHashMap<>();
		private final long totalTicks;
		private final int parts;
		private final List<Scene> selected = new ArrayList<>();
		private final Map<EventType, Integer> typeCounts = new EnumMap<>(EventType.class);
		private final boolean[] partFilled;
		private int used;
		private int voiceOnlyTicks;

		Selection(List<SessionData> sessions, int targetSeconds) {
			this.sessions = sessions;
			this.targetSeconds = targetSeconds;
			double fixedSeconds = INTRO_SECONDS + CREDITS_SECONDS + CHAPTER_CARD_SECONDS * maxChapters(targetSeconds);
			this.budget = (int) Math.max(20 * 20, (targetSeconds - fixedSeconds) * 20);
			long offset = 0;
			List<SessionData> ordered = new ArrayList<>(sessions);
			ordered.sort(Comparator.comparingInt(SessionData::index));
			for (SessionData s : ordered) {
				sessionOffset.put(s, offset);
				offset += Math.max(1, s.lengthTicks());
			}
			this.totalTicks = Math.max(1, offset);
			this.parts = Math.max(4, targetSeconds / 20);
			this.partFilled = new boolean[parts];
		}

		List<Scene> run(List<Scene> candidates, List<Scene> scenery) {
			List<Scene> pool = new ArrayList<>(candidates);
			for (Scene scene : mustHaves(pool)) {
				add(scene);
				pool.remove(scene);
			}
			opening().filter(o -> !overlapsSelected(o)).ifPresent(this::add);
			reserveConversations(pool);

			fill(pool, MIN_SCENE_SCORE);
			if (used < budget * SECOND_PASS_BELOW) {
				pool.addAll(scenery);
				fill(pool, SECOND_PASS_MIN_SCORE);
			}
			return selected;
		}

		private void fill(List<Scene> pool, double minScore) {
			List<Scene> candidates = new ArrayList<>(pool);
			candidates.removeIf(s -> s.score() < minScore);
			while (used < budget && !candidates.isEmpty()) {
				Scene best = null;
				double bestValue = 0;
				for (Scene scene : candidates) {
					double value = value(scene);
					if (value > bestValue) {
						bestValue = value;
						best = scene;
					}
				}
				if (best == null) {
					break; // everything left is capped
				}
				candidates.remove(best);
				pool.remove(best);
				Scene chosen = fitting(best, budget - used);
				if (chosen == null || overlapsSelected(chosen)) {
					continue;
				}
				if (isVoiceOnly(chosen) && voiceOnlyTicks + filmTicks(chosen) > budget * VOICE_ONLY_SHARE) {
					continue;
				}
				add(chosen);
			}
		}

		/** The scene, or a trimmed version of it, that fits in the remaining film time; null if nothing useful fits. */
		private static Scene fitting(Scene scene, int remaining) {
			if (filmTicks(scene) <= remaining) {
				return scene;
			}
			for (int raw = Math.min(scene.durationTicks(), remaining); raw >= MIN_TRIMMED_TICKS; raw -= 20) {
				Scene trimmed = trim(scene, raw);
				if (trimmed != null && filmTicks(trimmed) <= remaining) {
					return trimmed;
				}
			}
			return null;
		}

		/** The best calm talks get in before the fights compete for the time. */
		private void reserveConversations(List<Scene> pool) {
			int quota = (int) Math.min(cap(EventType.CONVERSATION, targetSeconds),
				Math.max(1, Math.round(CONVERSATIONS_PER_5_MIN * targetSeconds / 300.0)));
			List<Scene> talks = new ArrayList<>(pool.stream()
				.filter(s -> s.primary().event().type() == EventType.CONVERSATION)
				.toList());
			talks.sort(Comparator.comparingDouble((Scene s) -> s.score() * talkBonus(s)).reversed());
			// The closing talk after the final battle is part of the ending, on top of the quota.
			if (isDragonTheFinale()) {
				for (Scene talk : talks) {
					if (!isAfterDragon(talk)) {
						continue;
					}
					Scene chosen = fitting(talk, budget - used);
					if (chosen != null && !overlapsSelected(chosen)) {
						add(chosen);
						pool.remove(talk);
						break;
					}
				}
				talks.removeIf(t -> !pool.contains(t));
			}
			int taken = 0;
			for (Scene talk : talks) {
				if (taken >= quota || used >= budget) {
					break;
				}
				if (isDragonTheFinale() && isAfterDragon(talk) && hasEpilogue()) {
					continue; // one closing talk is enough
				}
				Scene chosen = fitting(talk, budget - used);
				if (chosen != null && !overlapsSelected(chosen)) {
					add(chosen);
					pool.remove(talk);
					taken++;
				}
			}
		}

		private boolean hasEpilogue() {
			return selected.stream().anyMatch(s -> s.primary().event().type() == EventType.CONVERSATION && isAfterDragon(s));
		}

		private double talkBonus(Scene scene) {
			if (scene.primary().event().type() != EventType.CONVERSATION) {
				return 1;
			}
			if (isAfterDragon(scene)) {
				return EPILOGUE_BONUS;
			}
			return scene.session().index() == 0 ? FIRST_SESSION_TALK_BONUS : 1;
		}

		/**
		 * The dragon is the finale when little was played after it (the film is made right after the victory).
		 * When people kept playing for hours, the story simply goes on.
		 */
		private boolean isDragonTheFinale() {
			for (Scene s : selected) {
				if (contains(s, EventType.DRAGON_KILLED)) {
					long position = sessionOffset.getOrDefault(s.session(), 0L) + s.primary().tick();
					return totalTicks - position - DRAGON_CEREMONY_TICKS <= totalTicks * FINALE_TAIL_SHARE;
				}
			}
			return false;
		}

		private boolean isAfterDragon(Scene scene) {
			for (Scene s : selected) {
				if (contains(s, EventType.DRAGON_KILLED)) {
					int sessionDiff = Integer.compare(scene.session().index(), s.session().index());
					return sessionDiff > 0 || (sessionDiff == 0 && scene.startTick() >= s.primary().tick());
				}
			}
			return false;
		}

		private double value(Scene scene) {
			EventType type = scene.primary().event().type();
			int count = typeCounts.getOrDefault(type, 0);
			if (count >= cap(type, targetSeconds)) {
				return 0;
			}
			if (type != EventType.CONVERSATION && isDragonTheFinale() && isAfterDragon(scene)) {
				return 0; // after the final battle only the friends' talk comes, then the credits
			}
			double value = scene.score() * talkBonus(scene) * Math.pow(type.isVoice() ? VOICE_DIVERSITY_FACTOR : DIVERSITY_FACTOR, count);
			for (Scene s : selected) {
				if (s.session() == scene.session() && Math.abs(s.primary().tick() - scene.primary().tick()) < NEIGHBOUR_TICKS) {
					value *= NEIGHBOUR_FACTOR;
				}
			}
			if (!partFilled[part(scene)]) {
				value *= EMPTY_PART_BONUS;
			}
			return value;
		}

		private void add(Scene scene) {
			selected.add(scene);
			used += filmTicks(scene);
			if (isVoiceOnly(scene)) {
				voiceOnlyTicks += filmTicks(scene);
			}
			typeCounts.merge(scene.primary().event().type(), 1, Integer::sum);
			partFilled[part(scene)] = true;
		}

		private int part(Scene scene) {
			long position = sessionOffset.getOrDefault(scene.session(), 0L) + scene.primary().tick();
			return (int) Math.min(parts - 1, position * parts / totalTicks);
		}

		/**
		 * The story beats no survival film can skip: the first night, the first arrival in the Nether and in the End,
		 * the dragon kill and the fight leading up to it.
		 */
		private List<Scene> mustHaves(List<Scene> pool) {
			List<Scene> must = new ArrayList<>();
			for (Scene scene : pool) {
				if (contains(scene, EventType.DRAGON_KILLED) || containsFirst(scene, EventType.NIGHT_FALL)
					|| scene.events().stream().anyMatch(e -> isFirstArrival(e.event()))) {
					must.add(scene);
				}
			}
			for (Scene kill : List.copyOf(must)) {
				if (!contains(kill, EventType.DRAGON_KILLED)) {
					continue;
				}
				int killTick = kill.primary().tick();
				// (often the arrival in the End itself, already in: the boss bar appears as you come out of the portal)
				pool.stream()
					.filter(s -> s.session() == kill.session() && s != kill && !must.contains(s) && s.endTick() <= kill.startTick())
					.filter(s -> killTick - s.startTick() <= FINALE_FIGHT_TICKS)
					.filter(s -> s.events().stream().anyMatch(e -> e.event().type() == EventType.BOSS_FIGHT_START
						&& e.event().subject().contains("ender_dragon")))
					.max(Comparator.comparingInt(Scene::startTick))
					.ifPresent(must::add);
			}
			return must;
		}

		private Optional<Scene> opening() {
			return sessions.stream().min(Comparator.comparingInt(SessionData::index)).flatMap(first -> {
				GameEvent start = first.events().stream().filter(e -> e.type() == EventType.SESSION_START).findFirst().orElse(null);
				if (start == null || first.index() != 0 || first.lengthTicks() < OPENING_TICKS) {
					return Optional.empty();
				}
				ScoredEvent primary = new ScoredEvent(first, start, 1.0);
				return Optional.of(new Scene(first, 0, OPENING_TICKS, primary, List.of(primary), 1.0));
			});
		}

		private boolean overlapsSelected(Scene candidate) {
			for (Scene s : selected) {
				if (s.session() == candidate.session() && candidate.startTick() < s.endTick() && s.startTick() < candidate.endTick()) {
					return true;
				}
			}
			return false;
		}
	}

	private static boolean contains(Scene scene, EventType type) {
		return scene.events().stream().anyMatch(e -> e.event().type() == type);
	}

	private static boolean isFirstArrival(GameEvent event) {
		return event.type() == EventType.DIMENSION_CHANGE && event.first()
			&& (event.subject().equals("minecraft:the_nether") || event.subject().equals("minecraft:the_end"));
	}

	private static boolean containsFirst(Scene scene, EventType type) {
		return scene.events().stream().anyMatch(e -> e.event().type() == type && e.event().first());
	}

	/** How long the scene lasts in the finished film: slow motion stretches it. */
	static int filmTicks(Scene scene) {
		int slowFrom = ShotPlanner.slowMotionFrom(scene);
		double ticks = 0;
		for (int[] part : DragonFinale.parts(scene)) {
			int slow = slowFrom < 0 ? 0 : Math.max(0, part[1] - Math.max(part[0], slowFrom));
			ticks += part[1] - part[0] + slow * (1 / Shot.SLOW_MOTION_SPEED - 1);
		}
		return (int) Math.ceil(ticks);
	}

	static boolean isVoiceOnly(Scene scene) {
		return scene.primary().event().type().isVoice()
			&& scene.events().stream().noneMatch(e -> e.event().type().isImportant());
	}

	/** A shorter version of the scene, centred on its main moment (more lead-in than follow-up). */
	private static Scene trim(Scene scene, int ticks) {
		if (ticks < MIN_TRIMMED_TICKS) {
			return null;
		}
		int center = scene.primary().tick();
		int start = Math.max(scene.startTick(), center - ticks * 2 / 3);
		int end = Math.min(scene.endTick(), start + ticks);
		start = Math.max(scene.startTick(), end - ticks);
		return scene.withRange(start, end);
	}

	static List<Chapter> chapters(List<Scene> scenes, int maxChapters) {
		List<List<Scene>> groups = new ArrayList<>();
		List<String> dimensions = new ArrayList<>();
		int currentDay = Integer.MIN_VALUE;
		Set<String> shownDimensions = new HashSet<>();
		for (Scene scene : scenes) {
			String newDimension = firstSceneIn(scene, shownDimensions);
			boolean newDay = scene.day() != currentDay;
			if (groups.isEmpty() || newDay || newDimension != null) {
				groups.add(new ArrayList<>());
				dimensions.add(newDimension);
				currentDay = scene.day();
			}
			groups.getLast().add(scene);
		}
		// Too many title cards for a short film: merge the smallest neighbouring chapters ("Days 3-5").
		while (groups.size() > maxChapters) {
			int smallest = -1;
			int smallestSize = Integer.MAX_VALUE;
			for (int i = 1; i < groups.size(); i++) {
				if (dimensions.get(i) != null) {
					continue; // a first trip to the Nether always keeps its own card
				}
				int size = groups.get(i - 1).size() + groups.get(i).size();
				if (size < smallestSize) {
					smallestSize = size;
					smallest = i;
				}
			}
			if (smallest < 0) {
				break;
			}
			groups.get(smallest - 1).addAll(groups.remove(smallest));
			dimensions.remove(smallest);
		}

		List<Chapter> chapters = new ArrayList<>();
		for (int i = 0; i < groups.size(); i++) {
			List<Scene> group = groups.get(i);
			int from = group.stream().mapToInt(Scene::day).min().orElse(1);
			int to = group.stream().mapToInt(Scene::day).max().orElse(from);
			// The subtitle names the chapter's best real moment ("Diamonds!"), never "Loud: Steve" or travel footage.
			String subtitle = group.stream()
				.flatMap(s -> s.events().stream())
				.filter(e -> isSubtitleWorthy(e.event()))
				.max(Comparator.comparingDouble(ScoredEvent::score))
				.map(e -> e.event().label())
				.orElse(null);
			chapters.add(new Chapter(from, to, dimensions.get(i), subtitle, List.copyOf(group)));
		}
		return chapters;
	}

	private static boolean isSubtitleWorthy(GameEvent event) {
		EventType type = event.type();
		return event.label() != null && !event.label().isBlank() && !type.isVoice()
			&& type != EventType.SESSION_START && type != EventType.SCENERY && type.baseWeight() >= 0.3;
	}

	/**
	 * The dimension (Nether, End) a scene opens a chapter for: the film's first scene there, whatever happens in it. The
	 * arrival itself is often not in the film (the dragon fight is, minutes later), and the card must still say "The End".
	 */
	private static @Nullable String firstSceneIn(Scene scene, Set<String> shown) {
		GameEvent event = scene.primary().event();
		String dimension = event.type() == EventType.DIMENSION_CHANGE ? event.subject() // where it arrives
			: scene.session().dimensionOf(event);
		if (dimension == null || dimension.isEmpty() || dimension.endsWith(":overworld")) {
			return null;
		}
		return shown.add(dimension) ? dimension : null;
	}
}
