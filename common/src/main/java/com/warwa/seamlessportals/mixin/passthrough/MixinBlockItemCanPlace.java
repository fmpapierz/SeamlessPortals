package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.passthrough.SeamMirror;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * RS PASSTHROUGH (a) step 5 — THE PLACEMENT VETO.
 *
 * <p>Enforces the user's refuse-on-conflict rule at the last point before any world write:
 * {@code BlockItem.canPlace} ({@code REF net/minecraft/world/item/BlockItem.java:132}), reached from
 * {@code getPlacementState} at {@code :115}. Refusing here costs the player nothing — no block, no
 * sound, no item consumed — whereas refusing after the write would mean a rollback, which is exactly
 * what makes non-item writes unfixable for now.
 *
 * <p>This mixin also carries the {@code BlockState}, which the {@code BlockPlaceContext} form does
 * not, so it is the only place the block-entity and multi-cell refusals can be evaluated.
 *
 * <p><b>Carries the entityPortals gate.</b> {@code SeamlessMixinConfigPlugin} only flag-gates
 * {@code qouteall.*} mixins ({@code SeamlessMixinConfigPlugin.java:148}), so every {@code com.warwa.*}
 * mixin weaves in BOTH flag states and must gate itself — matching
 * {@code LevelChunkSetBlockStateMixin.java:89}.
 */
@Mixin(BlockItem.class)
public abstract class MixinBlockItemCanPlace {

    @Inject(
        method = "canPlace(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void seamlessportals$vetoSeamPlacement(
        BlockPlaceContext context, BlockState stateForPlacement, CallbackInfoReturnable<Boolean> cir
    ) {
        if (!SeamlessPortalsConfig.isEntityPortals()) {
            return;
        }
        if (!SeamMirror.mayPlace(context.getLevel(), context.getClickedPos(), stateForPlacement)) {
            cir.setReturnValue(false);
        }
    }
}
