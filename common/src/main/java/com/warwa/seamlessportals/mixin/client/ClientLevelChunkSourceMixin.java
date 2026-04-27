package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.chunk.SeamlessClientChunkMap;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stage 1 of IP-architecture parity: replace vanilla {@link ClientChunkCache}
 * (RD-bounded 2-D array) with {@link SeamlessClientChunkMap} (hash map,
 * no RD limit) on EVERY {@link ClientLevel} construction — both the
 * player's main level and any cached secondary levels we create for
 * portal views.
 *
 * <p>Why both: the hash map's contract honors the full
 * {@code ClientChunkCache} interface, so vanilla code paths that read
 * {@code level.getChunkSource().getChunk(x, z, ...)} continue to work
 * unchanged. Memory growth on the active level is bounded by vanilla's
 * server-driven {@code ClientboundForgetLevelChunkPacket} eviction
 * (which routes through our overridden {@link SeamlessClientChunkMap#drop}).
 *
 * <p>Empirically the bottleneck this unblocks: cached destination
 * levels could only hold ~50 chunks (whatever fit in the small array
 * around the portal-view "camera" section). After teleport, the SOG's
 * BFS through {@code level.hasChunk(x, z)} stopped at the array's
 * edge → visibleSections capped at ~25 → world rendered mostly empty
 * for ~5 s while vanilla's per-tick chunk streamer caught up.
 *
 * <p>With the hash map:
 * <ul>
 *   <li>Cached levels can hold any chunk we feed them (Stage 2/3 will
 *       feed full RD around dest portals).</li>
 *   <li>Active level is unaffected for normal play — chunks within RD
 *       arrive via vanilla packet path and land in the hash map; chunks
 *       beyond RD are not sent by vanilla so don't accumulate.</li>
 *   <li>{@code SectionOcclusionGraph}'s BFS can traverse the full
 *       cached chunk set after teleport, populating visibleSections
 *       to the expected ~thousands.</li>
 * </ul>
 *
 * <p>Mirrors IP's {@code MixinClientLevel.onConstructed}.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelChunkSourceMixin {

    @Shadow @Final @Mutable
    private ClientChunkCache chunkSource;

    @Inject(
        method = "<init>",
        at = @At("RETURN"),
        require = 1
    )
    private void seamlessportals$replaceChunkSource(
            ClientPacketListener clientPacketListener,
            ClientLevel.ClientLevelData clientLevelData,
            ResourceKey<Level> dimension,
            Holder<DimensionType> dimensionType,
            int loadDistance,
            int simulationDistance,
            LevelRenderer levelRenderer,
            boolean isDebug,
            long biomeZoomSeed,
            int seaLevel,
            CallbackInfo ci) {
        ClientLevel self = (ClientLevel) (Object) this;
        SeamlessClientChunkMap newChunkSource =
            new SeamlessClientChunkMap(self, loadDistance);
        chunkSource = newChunkSource;
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS PHASE2] ClientLevel chunkSource swapped to SeamlessClientChunkMap for {} (loadDistance={})",
            dimension.identifier(), loadDistance);
    }
}
