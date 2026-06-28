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
}
