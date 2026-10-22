package com.survivalcinema.director.story;

import com.survivalcinema.director.event.Details;
import com.survivalcinema.director.event.EventType;
import com.survivalcinema.director.event.GameEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns scored moments into scenes: every moment gets some lead-in before it and some time after it, and moments
 * close together are merged into one scene.
 */
public final class SceneBuilder {
	static final int MERGE_GAP_TICKS = 40;
	static final int MAX_SCENE_TICKS = 20 * 30;
	static final int MIN_SCENE_TICKS = 40;
	/** Ticks between the last sample before a jump and the first after it. */
	private static final int JUMP_TICKS = 4;
	/** Before an arrival through a portal: walking up to it and the seconds standing in its swirl. */
	static final int PORTAL_LEAD_TICKS = 160;

	public List<Scene> build(List<ScoredEvent> scored) {
		// Identity grouping: hashing a SessionData record would hash its whole event list every time.
		Map<SessionData, List<ScoredEvent>> bySession = scored.stream()
			.collect(Collectors.groupingBy(ScoredEvent::session, IdentityHashMap::new, Collectors.toList()));
		List<Scene> scenes = new ArrayList<>();
		for (Map.Entry<SessionData, List<ScoredEvent>> entry : bySession.entrySet()) {
			scenes.addAll(removeOverlaps(buildSession(entry.getKey(), entry.getValue())));
		}
		scenes.sort(Comparator.comparingLong(Scene::order));
		return scenes;
	}

	/**
	 * Lead-ins and follow-ups can make neighbouring scenes overlap. The better scene keeps the shared time; the other
	 * keeps whatever is left around its own main moment, or is dropped when that is too short.
	 */
	static List<Scene> removeOverlaps(List<Scene> scenes) {
		List<Scene> byScore = new ArrayList<>(scenes);
		byScore.sort(Comparator.comparingDouble(Scene::score).reversed());
		List<Scene> kept = new ArrayList<>();
		for (Scene scene : byScore) {
			int start = scene.startTick();
			int end = scene.endTick();
			int primary = scene.primary().tick();
			boolean lost = false;
			for (Scene other : kept) {
				if (other.endTick() <= start || end <= other.startTick()) {
					continue;
				}
				if (primary >= other.startTick() && primary <= other.endTick()) {
					lost = true; // its main moment is already shown by a better scene
					break;
				}
				if (other.startTick() > primary) {
					end = Math.min(end, other.startTick());
				} else {
					start = Math.max(start, other.endTick());
				}
			}
			if (!lost && end - start >= MIN_SCENE_TICKS) {
				kept.add(scene.withRange(start, end));
			}
		}
		kept.sort(Comparator.comparingInt(Scene::startTick));
		return kept;
	}

	private List<Scene> buildSession(SessionData session, List<ScoredEvent> events) {
		List<Scene> scenes = new ArrayList<>();
		group(session, events, scenes);
		return scenes;
	}

	/**
	 * Moments close together become one scene, but a trip through a portal always starts a new one: the arrival in the
	 * Nether is its own story beat, not the end of whatever happened before it (and the camera cannot follow through it).
	 */
	private static void group(SessionData session, List<ScoredEvent> events, List<Scene> scenes) {
		List<ScoredEvent> sorted = new ArrayList<>(events);
		// on the same tick the arrival comes first: what happens on arriving ("We Need to Go Deeper") belongs to it
		sorted.sort(Comparator.comparingInt(ScoredEvent::tick)
			.thenComparing(e -> e.event().type() != EventType.DIMENSION_CHANGE));

		List<ScoredEvent> group = new ArrayList<>();
		int groupStart = 0;
		int groupEnd = 0;
		String groupDimension = null;
		for (ScoredEvent event : sorted) {
			int[] w = window(session, event.event());
			int start = Math.max(0, event.tick() - w[0]);
			int end = Math.min(session.lengthTicks(), event.tick() + w[1]);
			boolean sameWorld = event.event().type() != EventType.DIMENSION_CHANGE
				&& Objects.equals(event.event().dimension(), groupDimension);
			// a moment inside the scene so far always joins it (the dragon's explosion, inside its long finale)
			boolean fits = !group.isEmpty() && sameWorld
				&& start <= groupEnd + MERGE_GAP_TICKS
				&& Math.max(groupEnd, end) - Math.min(groupStart, start) <= Math.max(MAX_SCENE_TICKS, groupEnd - groupStart);
			if (fits) {
				group.add(event);
				// A later moment can need a longer lead-in (a scream needs what made everyone scream).
				groupStart = Math.min(groupStart, start);
				groupEnd = Math.max(groupEnd, end);
			} else {
				addScene(scenes, session, group, groupStart, groupEnd);
				group = new ArrayList<>();
				group.add(event);
				groupStart = start;
				groupEnd = end;
				groupDimension = event.event().dimension();
			}
		}
		addScene(scenes, session, group, groupStart, groupEnd);
	}

	private static void addScene(List<Scene> scenes, SessionData session, List<ScoredEvent> group, int start, int end) {
		if (group.isEmpty()) {
			return;
		}
		// the dragon's death is about the blow, whatever else scored higher around it
		ScoredEvent primary = group.stream().filter(e -> e.event().type() == EventType.DRAGON_KILLED).findFirst()
			.orElseGet(() -> group.stream().max(Comparator.comparingDouble(ScoredEvent::score)).orElseThrow());

		// Only the moments that matter set the scene's edges, so a stream of small hits around a good moment
		// doesn't stretch it into half a minute of filler.
		double threshold = Math.max(MIN_CORE_SCORE, CORE_FRACTION * primary.score());
		int coreStart = Integer.MAX_VALUE;
		int coreEnd = Integer.MIN_VALUE;
		for (ScoredEvent e : group) {
			if (e != primary && e.score() < threshold) {
				continue;
			}
			int[] w = window(session, e.event());
			coreStart = Math.min(coreStart, e.tick() - w[0]);
			coreEnd = Math.max(coreEnd, e.tick() + w[1]);
		}
		start = Math.max(start, coreStart);
		end = Math.min(end, coreEnd);

		// Never let a scene run across a respawn or teleport: keep the piece around the main moment. An arrival through a
		// portal keeps the way into it too (the film cuts on the jump), or the trip would start on the other side.
		if (session.trail() != null && !session.trail().isEmpty()) {
			List<int[]> pieces = session.trail().continuousRanges(start, end, 1);
			int[] piece = pieceFor(pieces, primary.tick());
			if (piece != null && piece[1] - piece[0] < MIN_SCENE_TICKS) {
				// a few ticks between two jumps cannot show anything: the longest stretch of the moment's time does
				piece = pieces.stream().max(Comparator.comparingInt(p -> p[1] - p[0])).orElse(piece);
			}
			if (piece != null) {
				int[] way = wayThroughPortal(pieces, piece, primary);
				start = way != null ? way[0] : piece[0];
				end = piece[1];
			}
		}
		// The scene is about what it shows. Moments outside its time (the near death a minute before the death that
		// became the scene) are not swallowed by it: they make scenes of their own.
		int from = start;
		int to = end;
		List<ScoredEvent> shown = new ArrayList<>();
		List<ScoredEvent> rest = new ArrayList<>();
		for (ScoredEvent e : group) {
			(e == primary || e.tick() >= from && e.tick() <= to ? shown : rest).add(e);
		}
		if (end - start >= MIN_SCENE_TICKS) {
			scenes.add(new Scene(session, start, end, primary, List.copyOf(shown), sceneScore(primary, shown)));
		}
		if (!rest.isEmpty()) {
			group(session, rest, scenes);
		}
	}

	/** The piece just before an arrival through a portal (the way into it), or null when the moment is something else. */
	static int[] wayThroughPortal(List<int[]> pieces, int[] arrival, ScoredEvent moment) {
		if (moment.event().type() != EventType.DIMENSION_CHANGE || Math.abs(moment.tick() - arrival[0]) > JUMP_TICKS) {
			return null;
		}
		int index = pieces.indexOf(arrival);
		if (index <= 0) {
			return null;
		}
		int[] before = pieces.get(index - 1);
		return arrival[0] - before[1] <= JUMP_TICKS && before[1] - before[0] >= MIN_SCENE_TICKS / 2 ? before : null;
	}

	/**
	 * The continuous piece of footage that shows a moment. A moment can fall right into the jump itself (the tick
	 * someone steps through a portal): then the piece after the jump is the one that shows it (the arrival).
	 */
	static int[] pieceFor(List<int[]> pieces, int tick) {
		for (int i = 0; i < pieces.size(); i++) {
			int[] piece = pieces.get(i);
			if (tick >= piece[0] && tick <= piece[1]) {
				// the last tick before a jump is the jump itself (a portal, a teleport): the moment is the arrival
				if (tick >= piece[1] - 1 && i + 1 < pieces.size() && pieces.get(i + 1)[0] - tick <= JUMP_TICKS) {
					return pieces.get(i + 1);
				}
				return piece;
			}
		}
		for (int[] piece : pieces) {
			if (piece[0] > tick) {
				return piece;
			}
		}
		return pieces.isEmpty() ? null : pieces.getLast();
	}

	/**
	 * A scene is worth its best moment, plus some of the rest, plus a lot more when things come together:
	 * voices reacting to an important moment, or several different kinds of moments at once (a boss fight with a
	 * near death and a kill streak).
	 */
	static double sceneScore(ScoredEvent primary, List<ScoredEvent> group) {
		double others = group.stream().filter(e -> e != primary).mapToDouble(ScoredEvent::score).sum();
		double score = primary.score() + Math.min(0.8, 0.35 * others);

		boolean voice = group.stream().anyMatch(e -> e.event().type().isVoice());
		boolean important = group.stream().anyMatch(e -> e.event().type().isImportant());
		if (voice && important) {
			score *= COMBO_MULTIPLIER;
		}
		long distinctKinds = group.stream()
			.map(e -> e.event().type())
			.filter(t -> t.baseWeight() >= 0.3)
			.distinct()
			.count();
		score += KIND_BONUS * Math.max(0, distinctKinds - 1);
		return score;
	}

	static final double CORE_FRACTION = 0.4;
	static final double MIN_CORE_SCORE = 0.15;
	static final double COMBO_MULTIPLIER = 1.4;
	static final double KIND_BONUS = 0.1;

	/** {lead-in, follow-up} in ticks for a moment of this kind; the dragon's death depends on the rest of the session. */
	static int[] window(SessionData session, GameEvent event) {
		if (event.type() == EventType.DRAGON_KILLED) {
			return DragonFinale.window(session, event);
		}
		if (DragonFinale.isDragonDeath(event)) {
			return DragonFinale.deathWindow();
		}
		return window(event);
	}

	/** {lead-in, follow-up} in ticks for a moment of this kind. */
	static int[] window(GameEvent event) {
		EventType type = event.type();
		return switch (type) {
			case DEATH, OTHER_DEATH -> new int[]{160, 50};
			case NEAR_DEATH -> new int[]{120, 60};
			case TOTEM -> new int[]{100, 80};
			case BIG_DAMAGE, BIG_FALL -> new int[]{80, 40};
			case KILL -> new int[]{80, 30};
			case KILL_STREAK -> new int[]{160, 40};
			case BOSS_FIGHT_START -> new int[]{60, 200};
			case BOSS_DEFEATED -> new int[]{140, 120};
			// without the rest of the session: the last hits, then the death animation taken to start at the blow
			case DRAGON_KILLED -> new int[]{200, 210};
			case DIMENSION_CHANGE -> new int[]{PORTAL_LEAD_TICKS, 120};
			case NEW_BIOME -> new int[]{40, 120};
			case ADVANCEMENT, OTHER_ADVANCEMENT, RARE_ITEM -> new int[]{100, 50};
			case ELYTRA_FLIGHT -> new int[]{20, Math.min(240, Math.max(80, Details.getInt(event.detail(), "ticks", parseInt(event.detail(), 160))))};
			case BUILD_BURST -> new int[]{0, 160};
			// screams need the lead-up: what made everyone scream is the funny part
			case VOICE_PEAK, VOICE_CROWD -> new int[]{140, 80};
			case CONVERSATION -> new int[]{10, Math.min(MAX_SCENE_TICKS - 10, Math.max(200, Details.getInt(event.detail(), "ticks", 300)))};
			case NIGHT_FALL -> new int[]{40, 100};
			case SLEEP -> new int[]{20, 60};
			default -> new int[]{60, 60};
		};
	}

	private static int parseInt(String text, int fallback) {
		if (text == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
