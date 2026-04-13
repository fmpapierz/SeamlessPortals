package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.portal.*;
import net.minecraft.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * IP's critical hook: when vanilla creates the REAL destination portal,
 * 1. Register the real portal
 * 2. Find the source portal and create CORRECT link
 * 3. SYNC to client so client has real positions (not expected/wrong ones)
 */
@Mixin(PortalForcer.class)
public abstract class PortalForcerMixin {

    @Shadow @Final private ServerLevel level;

    @Inject(method = "createPortal", at = @At("RETURN"))
    private void seamlessportals$onPortalCreated(BlockPos pos, Direction.Axis axis,
                                                  CallbackInfoReturnable<Optional<BlockUtil.FoundRectangle>> cir) {
        Optional<BlockUtil.FoundRectangle> result = cir.getReturnValue();
        if (result.isPresent()) {
            BlockPos realDestPos = result.get().minCorner;
            ResourceKey<Level> destDim = level.dimension();
            ResourceKey<Level> srcDim = PortalType.NETHER.getDestinationFor(destDim);
            if (srcDim == null) return;

            // 1. Register the REAL destination portal
            PortalDetector.onNetherPortalFormed(level, realDestPos, level.getServer());

            PortalManager manager = PortalManager.getServerInstance();
            PortalTracker destTracker = manager.getTracker(destDim);
            PortalTracker srcTracker = manager.getTracker(srcDim);

            // Find the real portal we just registered
            Optional<PortalInfo> realDest = destTracker.getPortalAt(realDestPos);
            if (realDest.isEmpty()) {
                realDest = destTracker.findNearestPortal(realDestPos, 16, PortalType.NETHER);
            }
            if (realDest.isEmpty()) return;

            // 2. Find source portal. Search broadly (vanilla PortalForcer offset).
            double scale = PortalType.NETHER.getCoordinateScale();
            BlockPos expectedSrc;
            double searchRadius;
            if (destDim == Level.NETHER) {
                expectedSrc = new BlockPos(
                    (int)(realDestPos.getX() * scale), realDestPos.getY(),
                    (int)(realDestPos.getZ() * scale));
                searchRadius = 1024; // 128 nether * 8
            } else {
                expectedSrc = new BlockPos(
                    (int)(realDestPos.getX() / scale), realDestPos.getY(),
                    (int)(realDestPos.getZ() / scale));
                searchRadius = 128;
            }

            Optional<PortalInfo> srcPortal = srcTracker.findNearestPortal(
                expectedSrc, (int) searchRadius, PortalType.NETHER);

            if (srcPortal.isPresent()) {
                // Create CORRECT link with REAL positions
                manager.createLink(srcPortal.get(), realDest.get());

                PortalInfo src = srcPortal.get();
                PortalInfo dest = realDest.get();

                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS] PortalForcer: linked REAL {} in {} <-> {} in {}",
                    src.getOrigin(), srcDim.identifier(),
                    dest.getOrigin(), destDim.identifier()
                );

                // 3. SYNC to ALL players so clients have real positions
                String axisStr = dest.getAxis().name().toLowerCase();
                ModPayloads.PortalSyncPayload syncPayload = new ModPayloads.PortalSyncPayload(
                    srcDim.identifier().toString(), src.getOrigin(),
                    destDim.identifier().toString(), dest.getOrigin(),
                    axisStr, dest.getWidth(), dest.getHeight()
                );

                for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                    PlatformHelper.getInstance().sendToClient(player, syncPayload);
                }

                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS] Portal sync sent to all players: {} <-> {}",
                    src.getOrigin(), dest.getOrigin()
                );
            } else {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS] PortalForcer: dest at {} in {} but no source found in {}",
                    realDestPos, destDim.identifier(), srcDim.identifier()
                );
            }
        }
    }
}
