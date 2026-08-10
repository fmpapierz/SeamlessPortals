package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

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
 * <p><b>Fix:</b> treat a cross-dim watcher as "close enough". If any player in
 * another dimension has a portal linking into THIS dimension with its
 * destination centre within {@code portalRenderDistance} of {@code pos}, allow
 * fire to spread. We only ever ADD a {@code true} result via an early return —
 * when no watcher is near we defer to vanilla, so normal single-dimension play
 * is unaffected. The proximity test mirrors {@link LevelChunkSetBlockStateMixin}
 * (the block-change mirror), so "fire that is visible through the portal" and
 * "fire that is allowed to spread" cover exactly the same region.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelFireSpreadMixin {

    /** Gated confirmation log — first few cross-dim allowances per session. */
    private static int seamlessportals$fireAllowLog = 0;

    @Inject(method = "canSpreadFireAround", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$allowFireForPortalWatchers(
            BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        // S17 sweep (wf_5183007f-fee CONFIRMED LEAK): self-gate — the block-era PortalManager
        // registry keeps this transitively inert flag-ON on Fabric, but the NeoForge driver was
        // ungated and the inert-by-dormancy shape has failed 4x; structural inertness on all
        // platforms (D3), flag-OFF unchanged.
        if (SeamlessPortalsConfig.isEntityPortals()) {
            // ★ D2 — THE ENTITY-ERA WATCHER GATE (user order 2026-08-10: mirrored fire "spreads
            // as normal from that dest seam to dest blocks"). Same rule as the block-era branch
            // below, rebuilt on the live qouteall Portal registry: a player within the fire
            // gamerule radius of a portal ENTRANCE counts as within it of positions near that
            // portal's EXIT — covering cross-dim watchers (absent from this dim's player map)
            // AND same-dim far ends (present but beyond the radius). Only ever ADDS a true via
            // early return; when no watcher qualifies, vanilla decides as before.
            seamlessportals$entityEraWatcherGate(pos, cir);
            return;
        }
        ServerLevel self = (ServerLevel) (Object) this;
        MinecraftServer server = self.getServer();
        if (server == null) return;

        ResourceKey<Level> thisDim = self.dimension();
        PortalManager manager = PortalManager.getServerInstance();
        double rangeBlocks = SeamlessPortalsConfig.get().getPortalRenderDistance() * 16.0;
        double rangeSq = rangeBlocks * rangeBlocks;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ResourceKey<Level> playerDim = player.level().dimension();
            // Same dim → vanilla's own player-proximity check already covers it.
            if (playerDim.equals(thisDim)) continue;

            List<PortalLink> links = manager.getLinksInRange(
                playerDim, player.blockPosition(), rangeBlocks);
            if (links.isEmpty()) continue;

            for (PortalLink link : links) {
                PortalInfo destPortal = link.getDestination();
                if (!destPortal.getDimension().equals(thisDim)) continue;

                Vec3 destCenter = destPortal.getCenter();
                double dx = pos.getX() + 0.5 - destCenter.x;
                double dz = pos.getZ() + 0.5 - destCenter.z;
                if (dx * dx + dz * dz > rangeSq) continue;

                if (seamlessportals$fireAllowLog < 5) {
                    seamlessportals$fireAllowLog++;
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS FIRE] allow cross-dim fire spread at {} in {} (watcher {} in {})",
                        pos.toShortString(), thisDim.identifier(),
                        player.getName().getString(), playerDim.identifier());
                }
                cir.setReturnValue(true);
                return;
            }
        }
    }

    /**
     * ★ D2 entity-era body. Cost note: runs per fire scheduled-tick / lava random-tick; the
     * portal search is {@code McHelper.findEntitiesRough} (chunk-grid scan around each player),
     * bounded by the gamerule radius in chunks — acceptable at survival fire densities, and
     * zero-cost when the gamerule is -1 (vanilla already allows everything).
     */
    private void seamlessportals$entityEraWatcherGate(
            BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel self = (ServerLevel) (Object) this;
        int radius = (Integer) self.getGameRules().get(
            net.minecraft.world.level.gamerules.GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER);
        if (radius == -1) {
            return;   // vanilla returns true unconditionally
        }
        MinecraftServer server = self.getServer();
        if (server == null) {
            return;
        }
        ResourceKey<Level> thisDim = self.dimension();
        double radiusSq = (double) radius * radius;
        int radiusChunks = radius / 16 + 1;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) {
                continue;
            }
            List<qouteall.imm_ptl.core.portal.Portal> portals =
                qouteall.imm_ptl.core.McHelper.findEntitiesRough(
                    qouteall.imm_ptl.core.portal.Portal.class,
                    player.level(), player.position(), radiusChunks,
                    p -> p.getDestDim() == thisDim && p.broadcastToPlayer(player));
            for (qouteall.imm_ptl.core.portal.Portal portal : portals) {
                if (player.position().distanceToSqr(portal.getOriginPos()) > radiusSq) {
                    continue;   // rough search over-collects; enforce the entrance leg exactly
                }
                Vec3 destCenter = portal.getDestPos();
                if (destCenter == null) {
                    continue;
                }
                if (destCenter.distanceToSqr(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > radiusSq) {
                    continue;   // the exit leg: fire must be within the radius of the exit
                }
                if (seamlessportals$fireAllowLog < 5) {
                    seamlessportals$fireAllowLog++;
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS FIRE] allow fire tick at {} in {} (watcher {} through portal at {})",
                        pos.toShortString(), thisDim.identifier(),
                        player.getName().getString(), portal.getOriginPos());
                }
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
