package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamDeliveryProbe;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DELIVERY PROBE STAGE 7 — was the dirty mark ever CONSUMED, i.e. did a rebuild actually get
 * scheduled?
 *
 * <p>Stage 6 records that the section was flagged dirty. That is not the same as being rebuilt, and
 * the live evidence forced the distinction: the user's own session showed every mirrored write
 * reaching {@code 6 REMESH: ACCEPTED} on a portal whose two ends are only ~7 chunks apart — well
 * inside the tracker window — and the picture still never changed. So the loss is downstream of the
 * flag.
 *
 * <p>The consumer is {@code LevelExtractor.java:152-169}:
 * <pre>
 * for (RenderSection section : this.levelRenderer.visibleSections()) {
 *     SectionDirtyState dirtyState = this.sectionUpdateTracker.getDirtyState(section.getSectionNode());
 *     if (dirtyState != null &amp;&amp; dirtyState.isDirty() &amp;&amp; ...) {
 *         this.levelRenderState.sectionUpdateRenderStates.add(new SectionUpdateRenderState(...));
 *         dirtyState.setNotDirty();
 *     }
 * }
 * </pre>
 * It iterates <b>{@code visibleSections()}</b> — the sections the MAIN CAMERA's walk produced. A
 * section that is dirty but absent from that list is never scheduled and its flag is never cleared:
 * it stays dirty forever and is never rebuilt.
 *
 * <p>{@code setNotDirty()} is called from exactly one place, that loop (verified by grep over the
 * whole client package), which is what makes it an exact instrument: <b>it fires if and only if a
 * rebuild was scheduled for that section.</b> Silence is therefore evidence, not absence of
 * evidence — which is the property stage 6 alone does not have.
 */
@Mixin(SectionUpdateTracker.SectionDirtyState.class)
public abstract class SectionDirtyConsumedProbeMixin {

    @Shadow public abstract long getSectionNode();

    @Inject(method = "setNotDirty", at = @At("HEAD"))
    private void seamlessportals$noteDirtyConsumed(CallbackInfo ci) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return;
        }
        try {
            long node = this.getSectionNode();
            SeamDeliveryProbe.noteRebuildScheduled(
                SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
        }
        catch (Throwable ignored) {
            // A diagnostic must never take down the extract pass.
        }
    }
}
