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

    // 26.3: isEntityVisible(Entity, Frustum, double, double, double) gained two trailing params ->
    // (.., float partialTicks, long chunkFadeDuration) (mc262-ref LevelExtractor.java:251 -> mc263-ref :290; merged 26.3 jar
    // descriptor (Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDFJ)Z). An @Inject handler
    // must mirror the full target parameter list, so the two are appended; the bodies never read them.
    @Inject(method = "isEntityVisible", at = @At("HEAD"))
    private void seamlessportals$probeConsidered(
        Entity entity, Frustum frustum, double camX, double camY, double camZ,
        float partialTicks, long chunkFadeDuration, // 26.3: new target params (see note above)
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
        float partialTicks, long chunkFadeDuration, // 26.3: new target params (see note at seamlessportals$probeConsidered)
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (EntityVisibilityProbe.ENABLED && SecondaryWorldRenderCore.isDestExtracting
            && !cir.getReturnValueZ()) {
            EntityVisibilityProbe.rejected++;
        }
    }
}
