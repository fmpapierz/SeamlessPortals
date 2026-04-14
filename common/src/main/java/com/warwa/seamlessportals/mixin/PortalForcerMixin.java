package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.portal.PortalDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.level.portal.PortalForcer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(PortalForcer.class)
public abstract class PortalForcerMixin {

    @Shadow @Final private ServerLevel level;

    /**
     * Exact target method signature:
     *   Optional<BlockUtil.FoundRectangle> createPortal(BlockPos pos, Direction.Axis axis)
     *
     * Following IP's architecture, we only observe VANILLA portal creation here.
     * Detection/link setup never calls createPortal() itself anymore.
     */
    @Inject(method = "createPortal", at = @At("RETURN"))
    private void seamlessportals$onPortalCreated(BlockPos pos, Direction.Axis axis,
                                                  CallbackInfoReturnable<Optional<BlockUtil.FoundRectangle>> cir) {
        Optional<BlockUtil.FoundRectangle> result = cir.getReturnValue();
        if (result.isPresent()) {
            BlockPos portalPos = result.get().minCorner;
            PortalDetector.onNetherPortalFormed(level, portalPos, level.getServer());
        }
    }
}
