package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link ClientLevel#chunkSource} (private final, ClientLevel.java:169) so T3 can
 * swap it for the unbounded {@link com.warwa.seamlessportals.client.SeamlessClientChunkMap}
 * on mod-created portal SECONDARY levels (in PortalWorldManager.createRenderer, gated by the
 * off-by-default config flag). {@code @Mutable} drops the {@code final} modifier.
 */
@Mixin(ClientLevel.class)
public interface ClientLevelChunkSourceAccessor {

    @Accessor("chunkSource")
    @Mutable
    void seamlessportals$setChunkSource(ClientChunkCache chunkSource);
}
