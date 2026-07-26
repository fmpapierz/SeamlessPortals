package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamDeliveryProbe;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.SectionUpdateTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DELIVERY PROBE STAGE 6 — was the remesh request ACCEPTED, or dropped for being out of window?
 *
 * <p>This is the fifth silent drop point in the chain and the only one downstream of the client
 * having the correct block data. {@code SectionUpdateTracker.setDirty} (REF {@code :26-31}) is:
 *
 * <pre>
 * SectionDirtyState section = this.storage.getValue(sectionX, sectionY, sectionZ);
 * if (section != null) { section.setDirty(playerChanged); }
 * </pre>
 *
 * <p>{@code storage} is a {@code RotatingSectionStorage} sized by render distance and re-centred on
 * the CAMERA. A section outside that window returns {@code null} and the request is discarded with
 * no log, no exception and no return value — the {@code ClientLevel} holds the new block and its
 * mesh is never rebuilt.
 *
 * <p><b>Why this stage is dimension-asymmetric, which is the whole shape of the open bug.</b> A
 * CROSS-dimension destination lives in another {@code ClientLevel} with its own
 * {@code LevelExtractor} and therefore its own tracker, centred on that dimension's portal-view
 * camera — so the mark lands. A SAME-dimension destination shares the one tracker the player's own
 * view uses, centred on the PLAYER; a destination further than render distance away is outside the
 * window and the mark is dropped.
 *
 * <p><b>Measured, not assumed.</b> The lookup is repeated here rather than inferred — the identical
 * call on the identical object in the same instant, so the probe cannot disagree with the code it
 * measures. Attribution is by position: {@code SectionUpdateTracker} holds no level reference, so
 * the tracker's identity hash is logged instead, which is what makes "two writes, two different
 * trackers" visible.
 */
@Mixin(SectionUpdateTracker.class)
public abstract class SectionUpdateTrackerDeliveryProbeMixin {

    @Shadow @Final private RotatingSectionStorage<SectionUpdateTracker.SectionDirtyState> storage;

    @Inject(method = "setDirty", at = @At("HEAD"))
    private void seamlessportals$noteDeliveryRemesh(
        int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci
    ) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return;
        }
        if (!SeamDeliveryProbe.isWatchedSection(sectionX, sectionY, sectionZ)) {
            return;
        }
        try {
            boolean accepted = this.storage.getValue(sectionX, sectionY, sectionZ) != null;
            SeamDeliveryProbe.noteRemeshRequest(
                sectionX, sectionY, sectionZ, accepted,
                "tracker=" + Integer.toHexString(System.identityHashCode(this))
                    + " windowSections=" + this.storage.size());
        }
        catch (Throwable ignored) {
            // A diagnostic must never take down the render path.
        }
    }
}
