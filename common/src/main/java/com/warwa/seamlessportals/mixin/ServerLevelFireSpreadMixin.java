package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Let fire spread / age / burn out in a dimension that is currently being
 * WATCHED through a portal from another dimension, even though no player is
 * physically present in this dimension.
 *
 * <p><b>Why:</b> 26.2 added the {@code fireSpreadRadiusAroundPlayer} gamerule
 * (default 128). {@code FireBlock.tick} reschedules its next tick and then
 * wraps its ENTIRE body — spread, aging, and burnout — in
 * {@code if (level.canSpreadFireAround(pos))} (FireBlock.java:142-143), and
 * {@code ServerLevel.canSpreadFireAround} returns true only when the gamerule
 * is {@code -1} OR a non-spectator player in THIS dimension's own player map is
 * within the radius of {@code pos} (ServerLevel.java:1783-1786). A portal
 * watcher standing in another dimension is absent from this dimension's player
 * map, so vanilla freezes the fire they are watching — it renders and animates
 * (the block state is mirrored to their cached client level) but never changes.
 * The same gate also blocks lava&rarr;neighbour ignition via
 * {@code LavaFluid.randomTick}, so this fixes that cross-dim case too. (Pure
 * fluid <i>flow</i> has no such gate, which is why water/lava spreading already
 * worked while fire did not.)
 *
 * <p><b>Fix:</b> treat a portal watcher as "close enough". We only ever
 * ADD a {@code true} result via an early return — when no watcher is near we
 * defer to vanilla, so normal play within a player's own view distance is unaffected.
 *
 * <p><b>S20 PORT-FORWARD RE-KEY (2026-07-26).</b> This mixin is a
 * {@code current-mod-core} PORT-FORWARD survivor and {@code EXECUTION_PLAN} §S20(a)
 * prescribes exactly this substitution: *"{@code ServerLevelFireSpreadMixin}
 * re-keyed to {@code ImmPtlChunkTracking.isPlayerWatchingChunkWithinRadius}"*.
 * The old body walked the block-era {@code PortalManager} link registry
 * ({@code getLinksInRange} &rarr; {@code PortalLink.getDestination()} &rarr;
 * destination-centre distance) and was gated {@code if (isEntityPortals()) return;},
 * i.e. FLAG-OFF-ONLY — so on the shipping flag-ON default this fix has never
 * actually run, and cross-dim watched fire has been frozen there. The re-key
 * restores the intended behaviour on the IP path and drops the flag gate with the
 * flag.
 *
 * <p><b>Why the IP query is the right equivalent, not merely a compiling one:</b>
 * {@code ImmPtlChunkTracking} is IP's authority on which players are loading which
 * chunks in which dimension THROUGH portals — the very relation the block-era link
 * walk was reconstructing by hand. Asking it directly is strictly more faithful:
 * it accounts for the real portal-view loading set (including indirect/nested
 * loaders) instead of a flat radius around one link's destination centre. The
 * radius argument keeps the "close enough" narrowing: {@code isPlayerWatchingChunkWithinRadius}
 * filters on {@code r.distanceToSource * 16 <= radiusBlocks}
 * ({@code ImmPtlChunkTracking.java:436-446}), so we pass the server's view distance
 * in blocks — the natural 26.2 analogue of the retired
 * {@code portalRenderDistance * 16} and the same order of magnitude.
 *
 * <p><b>The same-dimension case (S20 close-out §R.1b).</b> This used to be skipped outright, on the
 * stated ground that "vanilla's own player-proximity check already covers it". The live round
 * refuted that premise: vanilla's rule is per-level proximity
 * ({@code anyPlayerCloseEnoughTo(pos, FIRE_SPREAD_RADIUS_AROUND_PLAYER)}), so a player watching a
 * same-dim portal whose destination is hundreds of blocks away is NOT covered, and their fire
 * froze. Same-dim is now re-answered, but ONLY beyond the player's own view-distance ring — inside
 * it the player's ordinary loading (not a portal) explains the watch and the gamerule stays
 * vanilla's to enforce, which is the widening the original note rightly feared.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelFireSpreadMixin {

    /** Gated confirmation log — first few portal-watched allowances per session (either dimension). */
    private static int seamlessportals$fireAllowLog = 0;

    @Inject(method = "canSpreadFireAround", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$allowFireForPortalWatchers(
            BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        MinecraftServer server = self.getServer();
        if (server == null) return;

        ResourceKey<Level> thisDim = self.dimension();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int viewDistanceChunks = server.getPlayerList().getViewDistance();
        // The IP analogue of the retired portalRenderDistance * 16 (see the javadoc).
        int radiusBlocks = viewDistanceChunks * 16;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Vanilla's anyPlayerCloseEnoughTo filters spectators out; match it, or we would grant
            // through a portal what vanilla denies in person (verify lens 3).
            if (player.isSpectator()) continue;

            // S20 CLOSE-OUT FIX (§R.1b, live round 2026-07-26). This used to be an unconditional
            // `if (sameDim) continue;`, justified as "vanilla's own player-proximity check already
            // covers it". That premise is TRUE only while the watched region is near the player.
            // Vanilla is canSpreadFireAround = anyPlayerCloseEnoughTo(pos,
            // FIRE_SPREAD_RADIUS_AROUND_PLAYER) on THIS level (26.2:ServerLevel.java:1783-1786), so
            // a player watching a SAME-DIM portal whose destination is hundreds of blocks away
            // fails it — and the user saw exactly that: fire spreading normally in a cross-dim
            // portal view and frozen in a same-dim distant one.
            //
            // We re-answer for same-dim players ONLY where the player's OWN ordinary chunk loading
            // cannot explain the watch — i.e. the chunk lies beyond their own view-distance ring,
            // so the only thing that can be loading it is a portal. Inside that ring the gamerule
            // is vanilla's business and re-answering there is precisely the widening the original
            // note feared, so we still decline. Cross-dim is unaffected: vanilla's check is
            // per-level and can never cover it.
            if (player.level().dimension().equals(thisDim)) {
                // 26.2: ChunkPos is a record — the components are accessors, not public fields.
                int dx = Math.abs(player.chunkPosition().x() - chunkX);
                int dz = Math.abs(player.chunkPosition().z() - chunkZ);
                if (Math.max(dx, dz) <= viewDistanceChunks) continue;
            }

            if (qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking
                    .isPlayerWatchingChunkWithinRadius(player, thisDim, chunkX, chunkZ, radiusBlocks)) {
                if (seamlessportals$fireAllowLog < 5) {
                    seamlessportals$fireAllowLog++;
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS FIRE] allow portal-watched fire spread at {} in {} (watcher {} in {})",
                        pos.toShortString(), thisDim.identifier(),
                        player.getName().getString(), player.level().dimension().identifier());
                }
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
