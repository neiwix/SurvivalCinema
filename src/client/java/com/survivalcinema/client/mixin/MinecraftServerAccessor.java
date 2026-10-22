package com.survivalcinema.client.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
	/** When the server thread wakes up for its next tick (it sleeps until then, unless a task comes in). */
	@Accessor("nextTickTimeNanos")
	void survivalcinema$setNextTickTimeNanos(long nanos);
}
