package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.render.SameDimRemesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * THE OUTCOME INSTRUMENT for {@link SameDimRemesh} — a compile actually finished and replaced a
 * section's mesh.
 *
 * <p><b>Why this exists rather than trusting the request.</b> Two successive versions of the fix
 * passed a gate and did nothing. The first asserted "some rebuilds were scheduled" while dropping
 * the write under test; the second asserted "this section was scheduled" while the schedule was
 * being cleared unread by the next frame's {@code LevelRenderState.reset()}. Both assertions sat one
 * step short of reality, and both times the user found out instead of the suite.
 *
 * <p>{@code setSectionMesh} is the end of the chain: past this point the new geometry is what gets
 * drawn. An assertion built on it cannot pass while the picture stays stale, which is exactly the
 * property the previous two lacked.
 *
 * <p>Additive and cheap. It fires for every section compile in the game, so it does nothing but hand
 * over a packed long; {@link SameDimRemesh#notifyMeshReplaced} discards anything this fix did not
 * ask for. With {@code -Dseamlessportals.disableSameDimRemesh=true} it is one static-final read.
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMeshReplacedMixin {

    @Shadow public abstract long getSectionNode();

    @Inject(method = "setSectionMesh", at = @At("HEAD"))
    private void seamlessportals$noteMeshReplaced(
        SectionMesh sectionMesh, CallbackInfoReturnable<SectionMesh> cir
    ) {
        if (AperturePassthroughLever.DISABLE_SAME_DIM_REMESH) {
            return;
        }
        try {
            SameDimRemesh.notifyMeshReplaced(this.getSectionNode());
        }
        catch (Throwable ignored) {
            // Never let bookkeeping take down section compilation.
        }
    }
}
