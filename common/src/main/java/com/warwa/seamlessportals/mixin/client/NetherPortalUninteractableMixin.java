package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.NetherPortalBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Make nether_portal blocks completely uninteractable via left-click on
 * the client side.
 *
 * <p>Vanilla's {@code MultiPlayerGameMode.startDestroyBlock} fires the
 * swing animation, spawns the hit particle, plays the step/hit sound,
 * sends the {@code START_DESTROY_BLOCK} action packet, and begins
 * progress tracking — all in one call. Canceling at its head kills every
 * side-effect of the left-click before anything visible or audible
 * happens. A server-side check alone (e.g. blocking destroy in
 * {@code ServerPlayerGameMode}) would still let the client play swing +
 * particles + sound.
 *
 * <p>Required because in creative mode (or any fast-break scenario) the
 * {@code getDestroyProgress} override alone doesn't stop destruction —
 * creative skips the progress bar entirely. Our
 * {@link com.warwa.seamlessportals.mixin.NetherPortalBlockMixin}'s
 * {@code getDestroyProgress = 0} handles the survival path; this mixin
 * handles creative + feedback suppression across all game modes.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class NetherPortalUninteractableMixin {

    @Inject(
        method = "startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 1
    )
    private void seamlessportals$cancelOnPortalBlock(
            BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        // S16.2 (B7 disposition update, verify wf_91b049a9-0c1): flag-OFF only. Flag-ON,
        // vanilla portal blocks exist solely via the deliberate crouch escape hatch (or legacy
        // worlds) and behave VANILLA-STYLE per IP — including creative breakability; IP has no
        // such suppression. Flag-OFF (block-era visuals) unchanged.
        if (com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (mc.level.getBlockState(pos).getBlock() instanceof NetherPortalBlock) {
            // No swing, no sound, no particle, no packet — full no-op.
            cir.setReturnValue(false);
        }
    }

    /**
     * Also cancel continued-destroy ticks so if a left-click was already
     * in progress (on another block) and the crosshair drifts onto a
     * portal block, we don't keep the bar going.
     */
    @Inject(
        method = "continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void seamlessportals$cancelContinueOnPortalBlock(
            BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (mc.level.getBlockState(pos).getBlock() instanceof NetherPortalBlock) {
            cir.setReturnValue(false);
        }
    }
}
