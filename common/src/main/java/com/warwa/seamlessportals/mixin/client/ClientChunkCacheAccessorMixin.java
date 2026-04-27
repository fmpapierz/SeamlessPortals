package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Expose the {@code emptyChunk} field on {@link ClientChunkCache} so
 * {@link com.warwa.seamlessportals.chunk.SeamlessClientChunkMap} can
 * return the same sentinel as vanilla when {@code create=true} but no
 * chunk is actually loaded. The field is {@code private final} on the
 * vanilla class.
 */
@Mixin(ClientChunkCache.class)
public interface ClientChunkCacheAccessorMixin {

    @Accessor("emptyChunk")
    LevelChunk seamlessportals$getEmptyChunk();
}
