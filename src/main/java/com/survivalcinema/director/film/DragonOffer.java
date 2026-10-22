package com.survivalcinema.director.film;

/**
 * When to ask "Make the film?" after the Ender Dragon dies: not in the middle of the moment, but after the end poem
 * and the credits, once the player stands back in the world for a few seconds. Asks once.
 * <p>
 * Someone who already saw the credits in this world goes home without them (the game skips them the second time),
 * so leaving the End counts as well. Someone who stays in the End (the dragon egg, End cities) is asked after a
 * while anyway, at a calm moment.
 */
public final class DragonOffer {
	/** Ticks back in the world, with no screen open and nothing hurting the player, before asking. */
	public static final int CALM_TICKS = 3 * 20;
	/** Without credits and still in the End, ask once this long after the dragon died. */
	public static final int STAY_IN_END_TICKS = 5 * 60 * 20;

	private enum State {WAITING, AFTER_KILL, CREDITS, BACK, ASKED}

	private State state = State.WAITING;
	private int sinceKill;
	private int calm;

	public void dragonKilled() {
		if (state == State.WAITING) {
			state = State.AFTER_KILL;
			sinceKill = 0;
		}
	}

	public boolean isWaitingForCredits() {
		return state == State.AFTER_KILL || state == State.CREDITS;
	}

	/** Forget a dragon (leaving the world before it was time to ask). */
	public void reset() {
		state = State.WAITING;
		sinceKill = 0;
		calm = 0;
	}

	/**
	 * Every client tick.
	 *
	 * @param credits the end poem or the credits are on screen
	 * @param inEnd   the player is in the End
	 * @param calm    in the world with no screen open, not hurt, standing on the ground
	 * @return true once: ask now
	 */
	public boolean tick(boolean credits, boolean inEnd, boolean calm) {
		switch (state) {
			case AFTER_KILL -> {
				sinceKill++;
				if (credits) {
					state = State.CREDITS;
				} else if (!inEnd || sinceKill > STAY_IN_END_TICKS) {
					back();
				}
			}
			case CREDITS -> {
				if (!credits) {
					back();
				}
			}
			case BACK -> {
				if (credits) {
					state = State.CREDITS;
				}
			}
			case WAITING, ASKED -> {
			}
		}
		if (state != State.BACK) {
			return false;
		}
		this.calm = calm ? this.calm + 1 : 0;
		if (this.calm >= CALM_TICKS) {
			state = State.ASKED;
			return true;
		}
		return false;
	}

	private void back() {
		state = State.BACK;
		calm = 0;
	}
}
