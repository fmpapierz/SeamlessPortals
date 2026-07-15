package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.portal.PortalDetector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register a freshly-lit SOURCE portal the INSTANT it is created (vanilla ignition path:
 * {@code BaseFireBlock} → {@link PortalShape#createPortalBlocks}), instead of waiting up to
 * ~2s for {@code PortalChunkTracker}'s periodic block-scan to discover it.
 *
 * <p>Part 1 of the instant-portal-view work: registration kicks off the whole dest chain at
 * light-time — dest portal creation (PortalForcer), the link, the prewarm + residency tickets
 * (async chunk load/generation), and the nearest-first chunk streaming (Part 2) — so the
 * portal window starts filling within the first second. {@code onNetherPortalFormed} is
 * idempotent (reuses an existing link), so the periodic scan remains a harmless fallback for
 * pre-existing portals. ({@code PortalForcerMixin} already covers the DESTINATION portal.)
 */
@Mixin(PortalShape.class)
public abstract class PortalShapeFormMixin {

    @Shadow @Final private BlockPos bottomLeft;

    @Inject(method = "createPortalBlocks", at = @At("TAIL"))
    private void seamlessportals$onPortalLit(LevelAccessor level, CallbackInfo ci) {
        // D3 EXCLUSIVITY GATE (A1 — block-era portal-formation hook). Flag ON → the peripheral
        // ignition chain (MixinAbstractFireBlock_CVB → IntrinsicPortalGeneration) owns portal
        // formation; this must NOT queue block-era formations (its drain, PortalChunkTracker.tick, is
        // gated off too). Flag OFF (default) → unchanged.
        if (com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) return;
        if (level instanceof ServerLevel sl) {
            // QUEUE, don't run inline: this fires inside the fire block's onPlace. Doing the
            // dest-portal search/creation here stalled the tick and delayed the block broadcast
            // that replaces the client's predicted flint-and-steel flame with portal blocks —
            // the visible flame in the frame. Drained next tick by PortalChunkTracker.tick.
            PortalDetector.queueFormation(sl, this.bottomLeft);
        }
    }
}
