package com.warwa.seamlessportals.mixin.passthrough;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.warwa.seamlessportals.passthrough.SeamFractional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ★ SEAM DROP-SIDE CLAMP driver (2026-09-11 — full provenance on
 * {@link SeamFractional#clampDropToBreakerSide}): the private
 * {@code Block.popResource(Level, Supplier, ItemStack)} is the ONE funnel both public drop
 * variants feed (26.2 Block.java:416-421; javap-verified identical on the loom and
 * NeoForge-patched jars, one {@code addFreshEntity} invoke each), and wrapping the
 * {@code addFreshEntity} call reaches the constructed item without touching the
 * compiler-named position lambdas. Inert off the player-break bracket (the ThreadLocal is
 * null) and for non-seam cells (one map lookup).
 */
@Mixin(Block.class)
public abstract class BlockPopResourceSeamMixin {

    @WrapOperation(
        method = "popResource(Lnet/minecraft/world/level/Level;Ljava/util/function/Supplier;"
            + "Lnet/minecraft/world/item/ItemStack;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity"
                + "(Lnet/minecraft/world/entity/Entity;)Z"
        )
    )
    private static boolean seamlessportals$clampSeamDropSide(
        Level level, Entity entity, Operation<Boolean> original
    ) {
        if (entity instanceof ItemEntity item) {
            SeamFractional.clampDropToBreakerSide(item);
        }
        return original.call(level, entity);
    }
}
