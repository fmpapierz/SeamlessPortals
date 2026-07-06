package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Spread the storm": budget the per-frame chunk mesh-prep in {@code LevelExtractor.extract}.
 *
 * <p>extract() (mc262-ref LevelExtractor.java:152-169) iterates every visible section and,
 * for each DIRTY one, calls {@code cache.createRegion(...)} — a ~1ms synchronous chunk-data
 * snapshot on the render thread. Vanilla never stalls here because, after the loading screen,
 * only a handful of sections are dirty per frame. A SEAMLESS portal crossing has no loading
 * screen, so the entered dimension's whole un-warmed view is dirty at once → hundreds of
 * createRegion calls in one frame = the ~150ms post-crossing freeze.
 *
 * <p>This caps the number of sections meshed PER extract call to {@link #BUDGET}. Over-budget
 * dirty sections are simply skipped this frame (the {@code &&} short-circuit means they are
 * NOT added and NOT setNotDirty, so they stay dirty) and mesh on subsequent frames — turning
 * the single freeze into a brief gradual fill, exactly how vanilla/IP avoid the stall. In
 * steady-state play far fewer than BUDGET sections are dirty per frame, so the cap never bites.
 *
 * <p>Applies to every LevelExtractor instance (the main one AND each secondary portal-view
 * extractor), each with its own per-call budget — so both the crossing storm and the portal-
 * view fill spread smoothly.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorCreateRegionBudgetMixin {

    /** Max createRegion (mesh-prep) snapshots per extract call (~1ms each → ~BUDGET ms cap). */
    @Unique private static final int BUDGET = 24;

    @Unique private int seamlessportals$createRegionBudget;

    @Inject(method = "extract", at = @At("HEAD"))
    private void seamlessportals$resetCreateRegionBudget(
            DeltaTracker deltaTracker, Camera camera, float deltaPartialTick, CallbackInfo ci) {
        this.seamlessportals$createRegionBudget = BUDGET;
    }

    /**
     * Redirects the single {@code dirtyState.isDirty()} gate in extract's section-update loop.
     * Returns the real dirty value until the per-frame budget is spent, then false (defer).
     *
     * <p>CHARGE MOVED (2026-07-06, trees-before-ground amplifier fix): this gate no
     * longer decrements the budget — vanilla's condition is
     * {@code isDirty() && (mesh != UNCOMPILED || hasAllNeighbors)}, and charging
     * here burned slots on dirty-but-INELIGIBLE sections (unlit frontier columns,
     * whose neighbors aren't ready) that were never extracted. Under a
     * post-crossing re-send burst (thousands dirty at once) most of each frame's
     * 24 slots went to no-ops, stretching the occlusion cascade's per-stage
     * latency from ~1 frame to seconds — the visible "canopy floats above missing
     * ground". The charge now sits on {@code createRegion} itself (the actual
     * ~1ms cost), so only extracted sections spend budget.
     */
    @Redirect(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/SectionUpdateTracker$SectionDirtyState;isDirty()Z"))
    private boolean seamlessportals$budgetCreateRegion(SectionUpdateTracker.SectionDirtyState ds) {
        return ds.isDirty() && this.seamlessportals$createRegionBudget > 0;
    }

    /** The actual charge: one slot per real createRegion snapshot (see above). */
    @Redirect(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/RenderRegionCache;createRegion(Lnet/minecraft/client/multiplayer/ClientLevel;J)Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;"))
    private net.minecraft.client.renderer.chunk.RenderSectionRegion seamlessportals$chargeCreateRegion(
            net.minecraft.client.renderer.chunk.RenderRegionCache cache,
            net.minecraft.client.multiplayer.ClientLevel level, long sectionNode) {
        this.seamlessportals$createRegionBudget--;
        return cache.createRegion(level, sectionNode);
    }
}
