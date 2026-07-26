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
 * <p><b>Fix:</b> treat a cross-dim portal watcher as "close enough". We only ever
 * ADD a {@code true} result via an early return — when no watcher is near we
 * defer to vanilla, so normal single-dimension play is unaffected.
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
 * <p>The same-dimension case is still skipped: vanilla's own player-proximity
 * check already covers it, and re-answering it here would widen the gamerule.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelFireSpreadMixin {

    /** Gated confirmation log — first few cross-dim allowances per session. */
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
        // The IP analogue of the retired portalRenderDistance * 16 (see the javadoc).
        int radiusBlocks = server.getPlayerList().getViewDistance() * 16;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Same dim → vanilla's own player-proximity check already covers it.
            if (player.level().dimension().equals(thisDim)) continue;

            if (qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking
                    .isPlayerWatchingChunkWithinRadius(player, thisDim, chunkX, chunkZ, radiusBlocks)) {
                if (seamlessportals$fireAllowLog < 5) {
                    seamlessportals$fireAllowLog++;
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS FIRE] allow cross-dim fire spread at {} in {} (watcher {} in {})",
                        pos.toShortString(), thisDim.identifier(),
                        player.getName().getString(), player.level().dimension().identifier());
                }
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
