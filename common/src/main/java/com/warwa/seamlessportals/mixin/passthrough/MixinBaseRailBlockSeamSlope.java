package com.warwa.seamlessportals.mixin.passthrough;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamRegistry;
import com.warwa.seamlessportals.passthrough.SeamShadow;
import com.warwa.seamlessportals.passthrough.SeamShadowBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.RailShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * RS PASSTHROUGH (b) — SLOPE SUPPORT ACROSS THE SEAM.
 *
 * <p>{@code BaseRailBlock.shouldBeRemoved} (REF :92-104, reached only from {@code neighborChanged})
 * demands a solid support cube in the cell an ascending rail climbs toward. When that cell lies
 * across the seam, vanilla reads the source dimension's cell behind the portal and pops the rail.
 * This bridges exactly that read: LOCAL FIRST — it can only FIND support vanilla missed, never
 * remove support vanilla found — and only for the four horizontal ascending probes (the
 * {@code pos.below()} probe at :93 is vertical and never crosses a horizontal seam).
 *
 * <p>Interplay, recorded: for a MIRROR-CREATED cell this wrap is dead code, because
 * {@code MixinBaseRailBlockMirrorAuthority} cancels {@code neighborChanged} at HEAD before
 * {@code shouldBeRemoved} runs. It is live for the PLAYER-placed half of a coincident pair and for
 * every rail on a boundary-phase seam, where nothing is mirror-created.
 */
@Mixin(BaseRailBlock.class)
public abstract class MixinBaseRailBlockSeamSlope {

    // :93 (below) plus the four ascending arms at :98-101.
    @WrapOperation(
        method = "shouldBeRemoved",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/BaseRailBlock;"
            + "canSupportRigidBlock(Lnet/minecraft/world/level/BlockGetter;"
            + "Lnet/minecraft/core/BlockPos;)Z"),
        require = 5, allow = 5
    )
    private static boolean seamlessportals$slopeSupport(
        BlockGetter getter, BlockPos query, Operation<Boolean> original,
        BlockPos pos, Level level, RailShape shape
    ) {
        if (original.call(getter, query)) {
            return true;                                   // ── R1' LOCAL FIRST ──
        }
        if (AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SEAM_SHADOW
            || AperturePassthroughLever.DISABLE_SEAM_RAIL_SLOPE
            || !SeamlessPortalsConfig.isEntityPortals()
            || query.getY() != pos.getY()                  // :93 is the vertical probe — never a seam
            || !SeamRegistry.sectionHasSeam(level, pos)) {
            return false;
        }
        SeamShadow s = SeamShadowBridge.shadowFor(
            level, SeamRegistry.lookup(level, pos), pos, query, 0);
        return s != null
            && s.farResident(query)
            && Block.canSupportRigidBlock(s.farLevel(), s.toFar(query));
    }
}
