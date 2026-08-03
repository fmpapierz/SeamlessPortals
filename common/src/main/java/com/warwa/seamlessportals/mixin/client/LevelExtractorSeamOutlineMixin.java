package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.SeamCounterpartOutline;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * ★ THE FAR→NEAR HALF OF WHOLE-OBJECT SELECTION (user order 2026-08-03). When the player targets a
 * seam object THROUGH the window, IP sets the local {@code hitResult} to its MISS placeholder — so
 * the main pass draws no outline at all, and the object's NEAR half (standing right in front of
 * the player, in their own dimension) goes unmarked while its far half lights up. This swaps the
 * counterpart hit in for exactly the duration of the MAIN pass's outline extract, completing the
 * one-block-two-charts selection in both directions.
 *
 * <p>Guards, each load-bearing:
 * <ul>
 *   <li>{@code !PortalRendering.isRendering()} — the same private method is invoked by the
 *       dest-pass and same-dim outline paths, which manage their own hit swap; this mixin must
 *       only touch the main framegraph extract.</li>
 *   <li>only when the current hit is NOT a real block hit — a genuine local target always wins;
 *       this fills the MISS-placeholder case only.</li>
 * </ul>
 *
 * <p>Target bytecode-verified: {@code extract.LevelExtractor.extractBlockOutline(Camera,
 * LevelRenderState)}, private.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorSeamOutlineMixin {

    @Unique
    private HitResult seamlessportals$savedHit;

    @Unique
    private boolean seamlessportals$swapped;

    @Inject(method = "extractBlockOutline", at = @At("HEAD"), require = 1)
    private void seamlessportals$nearHalfIn(
        Camera camera, LevelRenderState state, CallbackInfo ci
    ) {
        seamlessportals$swapped = false;
        if (PortalRendering.isRendering()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean localIsRealBlockHit = mc.hitResult instanceof BlockHitResult b
            && b.getType() != HitResult.Type.MISS;
        if (!localIsRealBlockHit && SeamCounterpartOutline.nearHit != null) {
            seamlessportals$savedHit = mc.hitResult;
            mc.hitResult = SeamCounterpartOutline.nearHit;
            seamlessportals$swapped = true;
        }
    }

    @Inject(method = "extractBlockOutline", at = @At("RETURN"), require = 1)
    private void seamlessportals$nearHalfOut(
        Camera camera, LevelRenderState state, CallbackInfo ci
    ) {
        if (seamlessportals$swapped) {
            Minecraft.getInstance().hitResult = seamlessportals$savedHit;
            seamlessportals$savedHit = null;
            seamlessportals$swapped = false;
        }
    }
}
