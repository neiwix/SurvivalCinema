package com.survivalcinema.client.flashback;

/**
 * Added by our mixins to the Flashback classes they change, so {@link FlashbackCheck} can see that they were applied
 * (they are optional: a mixin that no longer fits a Flashback update is skipped instead of crashing the game).
 */
public interface FlashbackHooked {
}
