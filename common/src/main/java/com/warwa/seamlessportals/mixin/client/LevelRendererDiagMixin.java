package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.client.SeamlessClientTeleport;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame-time + visibility instrumentation for the post-teleport window.
 *
 * <p>Armed by {@link SeamlessClientTeleport#diagLogUpdatesRemaining}
 * (set to 60 at the end of {@code doVisualSwap}). Each
 * {@code LevelRenderer.update(camera)} call within the window logs:
 * <ul>
 *   <li>HEAD: visibleSections count + dirty-section count</li>
 *   <li>RETURN: wall-time spent in {@code update} (cullTerrain +
 *       compileSections), visibleSections after, dirty after</li>
 * </ul>
 *
 * <p>This lets us see exactly which post-teleport frames are slow and
 * what's filling them: SOG propagation (visibleSections grows over
 * frames), mesh upload (dirty count drops as compileSections drains),
 * or something off-path (wall time large but neither metric moves).
 *
 * <p>Zero cost when the counter is zero.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererDiagMixin {

    @Unique
    private long seamlessportals$updateStartNs;

    @Unique
    private int seamlessportals$dirtyCountBefore;

    @Inject(method = "update", at = @At("HEAD"))
    private void seamlessportals$diagBefore(Camera camera, CallbackInfo ci) {
        if (SeamlessClientTeleport.diagLogUpdatesRemaining <= 0) return;
        seamlessportals$updateStartNs = System.nanoTime();
        LevelRendererAccessorMixin self = (LevelRendererAccessorMixin) (Object) this;
        int visBefore = self.seamlessportals$getVisibleSections().size();
        int dirty = seamlessportals$countDirty(self.seamlessportals$getViewArea());
        seamlessportals$dirtyCountBefore = dirty;
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS DIAG update#{}] HEAD: visibleSections={} dirtySections={}",
            SeamlessClientTeleport.diagLogUpdatesRemaining, visBefore, dirty);
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void seamlessportals$diagAfter(Camera camera, CallbackInfo ci) {
        if (SeamlessClientTeleport.diagLogUpdatesRemaining <= 0) return;
        long elapsedMs = (System.nanoTime() - seamlessportals$updateStartNs) / 1_000_000L;
        LevelRendererAccessorMixin self = (LevelRendererAccessorMixin) (Object) this;
        int visAfter = self.seamlessportals$getVisibleSections().size();
        int dirtyAfter = seamlessportals$countDirty(self.seamlessportals$getViewArea());
        int dirtyDelta = seamlessportals$dirtyCountBefore - dirtyAfter;
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS DIAG update#{}] RETURN: tookMs={} visibleSections={} dirtySections={} dirtyDelta={}",
            SeamlessClientTeleport.diagLogUpdatesRemaining,
            elapsedMs, visAfter, dirtyAfter, dirtyDelta);
        SeamlessClientTeleport.diagLogUpdatesRemaining--;
    }

    @Unique
    private static int seamlessportals$countDirty(ViewArea viewArea) {
        if (viewArea == null) return -1;
        int n = 0;
        SectionRenderDispatcher.RenderSection[] sections = viewArea.sections;
        if (sections == null) return -1;
        for (SectionRenderDispatcher.RenderSection s : sections) {
            if (s != null && s.isDirty()) n++;
        }
        return n;
    }
}
