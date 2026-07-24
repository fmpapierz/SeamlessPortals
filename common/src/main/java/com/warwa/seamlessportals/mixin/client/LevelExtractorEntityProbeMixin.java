package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.EntityVisibilityProbe;
import qouteall.imm_ptl.core.render.SecondaryWorldRenderCore;

/**
 * §2b PROBE-ONLY mixin (EntityVisibilityProbe cons/rej legs; polish-queue engagement 2026-07-24).
 * Counts entities reaching {@code LevelExtractor.isEntityVisible} during a DEST extract and how
 * many it rejects — the extract-side halves of the dest-entity funnel. javap-confirmed target:
 * {@code public boolean isEntityVisible(Entity, Frustum, double, double, double)} (merged 26.2 jar).
 *
 * <p>Byte-inert unless {@code -Dseamlessportals.entityProbe=true} ({@code ENABLED} is a static
 * final read at classload; default-false folds both bodies to a dead branch). Gated on
 * {@code isDestExtracting} — main-pass extracts are never counted.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorEntityProbeMixin {

    @Inject(method = "isEntityVisible", at = @At("HEAD"))
    private void seamlessportals$probeConsidered(
        Entity entity, Frustum frustum, double camX, double camY, double camZ,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (EntityVisibilityProbe.ENABLED && SecondaryWorldRenderCore.isDestExtracting) {
            EntityVisibilityProbe.considered++;
            EntityVisibilityProbe.sampleGates();
        }
    }

    @Inject(method = "isEntityVisible", at = @At("RETURN"))
    private void seamlessportals$probeRejected(
        Entity entity, Frustum frustum, double camX, double camY, double camZ,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (EntityVisibilityProbe.ENABLED && SecondaryWorldRenderCore.isDestExtracting
            && !cir.getReturnValueZ()) {
            EntityVisibilityProbe.rejected++;
        }
    }
}
