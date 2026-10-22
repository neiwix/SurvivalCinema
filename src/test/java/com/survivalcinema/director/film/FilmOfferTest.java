package com.survivalcinema.director.film;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilmOfferTest {
	/** Ticks until the offer comes, or -1 within the limit. */
	private static int ticksUntilAsked(DragonOffer offer, int limit, boolean credits, boolean inEnd, boolean calm) {
		for (int i = 1; i <= limit; i++) {
			if (offer.tick(credits, inEnd, calm)) {
				return i;
			}
		}
		return -1;
	}

	@Test
	void asksAfterTheCreditsOnceThePlayerIsBack() {
		DragonOffer offer = new DragonOffer();
		assertEquals(-1, ticksUntilAsked(offer, 100, false, false, true), "no dragon, no question");
		offer.dragonKilled();
		assertTrue(offer.isWaitingForCredits());
		assertEquals(-1, ticksUntilAsked(offer, 400, false, true, true), "still in the End right after the fight");
		assertEquals(-1, ticksUntilAsked(offer, 20 * 60 * 10, true, true, false), "never during the poem and credits");
		assertEquals(-1, ticksUntilAsked(offer, 40, false, false, false), "the world is still loading");
		assertEquals(DragonOffer.CALM_TICKS, ticksUntilAsked(offer, 1000, false, false, true));
		assertFalse(offer.isWaitingForCredits());
		assertEquals(-1, ticksUntilAsked(offer, 1000, false, false, true), "asked once");
		offer.dragonKilled();
		assertEquals(-1, ticksUntilAsked(offer, 20 * 60 * 10, false, false, true), "not again for the next dragon");
	}

	@Test
	void waitsForACalmMoment() {
		DragonOffer offer = new DragonOffer();
		offer.dragonKilled();
		offer.tick(true, true, false);
		offer.tick(false, false, false);
		assertEquals(-1, ticksUntilAsked(offer, DragonOffer.CALM_TICKS - 1, false, false, true));
		assertFalse(offer.tick(false, false, false), "hurt (or a screen opened): start counting again");
		assertEquals(DragonOffer.CALM_TICKS, ticksUntilAsked(offer, 1000, false, false, true));
	}

	@Test
	void leavingTheEndWithoutCreditsCountsToo() {
		// the credits play once per world: the second time the game sends the player straight home
		DragonOffer offer = new DragonOffer();
		offer.dragonKilled();
		offer.tick(false, true, true);
		assertEquals(DragonOffer.CALM_TICKS, ticksUntilAsked(offer, 1000, false, false, true));
	}

	@Test
	void stayingInTheEndIsAskedAfterAWhile() {
		DragonOffer offer = new DragonOffer();
		offer.dragonKilled();
		int asked = ticksUntilAsked(offer, DragonOffer.STAY_IN_END_TICKS * 2, false, true, true);
		assertEquals(DragonOffer.STAY_IN_END_TICKS + DragonOffer.CALM_TICKS, asked);
	}

	@Test
	void leavingTheWorldForgetsTheDragon() {
		DragonOffer offer = new DragonOffer();
		offer.dragonKilled();
		offer.reset();
		assertEquals(-1, ticksUntilAsked(offer, 1000, false, false, true));
	}

	@Test
	void sizesReadLikeExplorer() {
		assertEquals(new DiskSize("0", DiskSize.Unit.B), DiskSize.of(0));
		assertEquals(new DiskSize("830", DiskSize.Unit.KB), DiskSize.of(830L * 1024));
		assertEquals(new DiskSize("1.5", DiskSize.Unit.MB), DiskSize.of(1536L * 1024));
		assertEquals(new DiskSize("1,4", DiskSize.Unit.GB), DiskSize.of((long) (1.4 * 1024 * 1024 * 1024), ','));
		assertEquals(new DiskSize("12", DiskSize.Unit.MB), DiskSize.of(12L * 1024 * 1024));
		assertEquals(new DiskSize("10", DiskSize.Unit.MB), DiskSize.of((long) (9.97 * 1024 * 1024)), "no \"10.0\"");
		assertEquals(new DiskSize("1023", DiskSize.Unit.B), DiskSize.of(1023));
		assertEquals(new DiskSize("0", DiskSize.Unit.B), DiskSize.of(-5));
	}
}
