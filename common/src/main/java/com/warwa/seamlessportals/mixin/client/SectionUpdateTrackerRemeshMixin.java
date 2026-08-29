package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.render.SameDimRemesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * THE HOOK for same-dimension portal terrain freshness — see
 * {@link SameDimRemesh} for what is broken and why the fix is shaped this way.
 *
 * <p>{@code SectionUpdateTracker.setDirty} is where vanilla asks for a section to be rebuilt, and it
 * is where BOTH losses happen: the request is either refused outright (out of the tracker's rotating
 * window) or stored and then never consumed (the section is not in the main camera's
 * {@code visibleSections}). The two are indistinguishable from here, which is precisely why this is
 * the right hook — recording both and letting the drain sort it out is what lets one mechanism cover
 * the pair.
 *
 * <p>Injected at {@code HEAD} deliberately, not {@code TAIL}: the target's own early return on a
 * refused lookup is invisible from {@code TAIL} in exactly the case that matters most.
 *
 * <p><b>Additive only.</b> It reads the call and returns; it cancels nothing, redirects nothing, and
 * changes no argument. With {@code -Dseamlessportals.disableSameDimRemesh=true} it costs one
 * static-final boolean read and vanilla behaviour is byte-identical.
 */
@Mixin(SectionUpdateTracker.class)
public abstract class SectionUpdateTrackerRemeshMixin {

    @Shadow @Final private RotatingSectionStorage<SectionUpdateTracker.SectionDirtyState> storage;

    @Inject(method = "setDirty", at = @At("HEAD"))
    private void seamlessportals$noteRefusedDirtyMark(
        int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci
    ) {
        if (AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SAME_DIM_REMESH
            || !SeamlessPortalsConfig.isEntityPortals()) {
            return;
        }
        try {
            // ONLY the refused case. Marks the tracker ACCEPTS need nothing here — the end-of-tick
            // sweep finds them precisely, by the fact that they are still dirty. Recording accepted
            // marks too is what made the first cut of this fix saturate its queue on chunk-load
            // dirtying and then drop the genuine block change it existed to carry.
            if (this.storage.getValue(sectionX, sectionY, sectionZ) != null) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            LevelExtractor main = mc == null ? null : mc.levelExtractor;
            if (main == null) {
                return;
            }
            // Only the tracker the PLAYER'S OWN view uses can be the same-dimension case. A
            // secondary dimension's tracker is already serviced by its own dest extract pass
            // (SecondaryWorldRenderCore.renderDestWorld's cross-dim branch) and must be left alone.
            SectionUpdateTracker mainTracker =
                ((LevelExtractorAccessor) (Object) main).seamlessportals$getSectionUpdateTracker();
            SameDimRemesh.onDirtyMarkRefused(
                mainTracker == (Object) this, sectionX, sectionY, sectionZ);
        }
        catch (Throwable ignored) {
            // Never let terrain bookkeeping take down the render path.
        }
    }
}
