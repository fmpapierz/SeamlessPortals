package qouteall.imm_ptl.peripheral.mixin.common.nether_portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration;

import java.util.Optional;

// S16 1:1 port of IP:peripheral/mixin/common/nether_portal/MixinAbstractFireBlock_CVB.java:1-73
// — THE STRUCTURAL SUPPRESSION (D3 ledger swap): redirecting vanilla's findEmptyPortalShape in
// onPlace means vanilla purple portal blocks CANNOT form flag-ON (except the deliberate
// crouch-ignite escape hatch in IntrinsicPortalGeneration.onCrouchingPlayerIgnite and
// mode=vanilla passthrough) — the fire-on-obsidian event routes into IP generation instead.
// 26.2 target verification (port-note S16): onPlace signature unchanged (BaseFireBlock.java:153;
// the findEmptyPortalShape INVOKE at :156); isPortal's Optional.isPresent at :213 with the
// obsidian-adjacency pre-check at :196-208 (present in IP-era vanilla too — verify correction:
// nothing 26.2-new there; the redirect's effect is near-obsidian-scoped in both eras). The
// block-era handlePortal cancel is DEMOTED to flag-OFF-only since S16.2 (EntityMixin — verify
// ruling wf_91b049a9-0c1: IP has NO handlePortal suppression; the crouch hatch exists to give
// a WORKING vanilla portal): flag-ON, crouch-hatch/legacy vanilla portal blocks teleport
// vanilla-style per IP, and PortalForcerMixin is flag-gated against block-era double-handling.
@Mixin(BaseFireBlock.class)
public class MixinAbstractFireBlock_CVB {
    @Redirect(
        method = "onPlace",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/portal/PortalShape;findEmptyPortalShape(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction$Axis;)Ljava/util/Optional;"
        )
    )
    Optional<PortalShape> redirectCreateAreaHelper(LevelAccessor worldAccess, BlockPos blockPos, Direction.Axis axis) {
        if (IPGlobal.netherPortalMode == IPGlobal.NetherPortalMode.disabled) {
            return Optional.empty();
        }

        if (IPGlobal.netherPortalMode == IPGlobal.NetherPortalMode.vanilla) {
            return PortalShape.findEmptyPortalShape(worldAccess, blockPos, axis);
        }

        if (isNearObsidian(worldAccess, blockPos)) {
            IntrinsicPortalGeneration.onFireLitOnObsidian(
                ((ServerLevel) worldAccess),
                blockPos,
                null
            );
        }

        return Optional.empty();
    }

    private static boolean isNearObsidian(LevelAccessor access, BlockPos blockPos) {
        for (Direction value : Direction.values()) {
            if (O_O.isObsidian(access.getBlockState(blockPos.relative(value)))) {
                return true;
            }
        }
        return false;
    }

    // allow lighting fire on the side of obsidian
    // for lighting horizontal portals
    @Redirect(
        method = "isPortal",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Optional;isPresent()Z"
        )
    )
    private static boolean redirectIsPresent(Optional optional) {
        if (IPGlobal.netherPortalMode != IPGlobal.NetherPortalMode.vanilla) {
            return true;
        }
        else {
            return optional.isPresent();
        }
    }
}
