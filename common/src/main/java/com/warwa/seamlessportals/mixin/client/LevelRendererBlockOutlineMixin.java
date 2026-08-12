package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.EntityPortalsFlag;
import com.warwa.seamlessportals.render.StencilPortalRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Re-buckets the targeted-block outline into vanilla's after-terrain draw phase
 * while a portal is in render range — fixes the "outline sliver": a tiny
 * see-through line of SOURCE-dimension colour where the block-selection outline
 * touches the portal render.
 *
 * <p>Mechanism (all source-verified on 26.2):
 * <ul>
 *   <li>The outline is submitted by {@code LevelRenderer.submitBlockOutline}
 *       (:705) via two {@code submitHitOutline} calls whose LAST arg
 *       ({@code afterTerrain}) picks the draw bucket: {@code false} →
 *       {@code shapeOutlines} (drawn in {@code executeTranslucent}, main pass
 *       :434, BEFORE translucent terrain), {@code true} → {@code afterTerrain}
 *       (drawn in {@code executeTranslucentAfterTerrain}, :440). Vanilla passes
 *       {@code state.isTranslucent()} — i.e. it already defers the outline for
 *       translucent targeted blocks; this mixin extends that to "a portal is
 *       near".</li>
 *   <li>The outline lines use {@code RenderPipelines.LINES} =
 *       {@code DepthStencilState.DEFAULT} = GEQUAL + <b>depth write TRUE</b>, at
 *       {@code appropriateLineWidth} (~2px). Drawn BEFORE our portal render
 *       (Fabric's AFTER_TRANSLUCENT_TERRAIN wraps the translucent renderGroup at
 *       :438), the line pixels overhanging the portal opening write NEARER depth,
 *       the depth-tested STEP 2 stencil write fails there (stencil=0), the
 *       stencil-gated fill skips them, and stale source colour shows — the
 *       sliver.</li>
 *   <li>Re-bucketed to :440 the outline draws immediately AFTER our portal
 *       render: the STEP 3.7 NEAR depth shield (1.0 across the opening) makes the
 *       overhanging line pixels FAIL GEQUAL — the outline is cleanly clipped at
 *       the window edge, and on the obsidian faces it draws exactly as before.</li>
 * </ul>
 *
 * <p>No ordinal on the @At: both {@code submitHitOutline} calls (the
 * high-contrast black backing line and the main outline) are modified.
 *
 * <p><b>S18.5 flag-ON trigger (the S17 sweep finding closed):</b> {@code anyPortalNearCamera}
 * scans the BLOCK-ERA PortalManager/PortalTracker only — under {@code entityPortals=true} it never
 * fires for entity portals, so the sliver returned flag-ON. The flag-ON branch keys on IP's own
 * per-frame structure instead: {@code RenderStates.lastPortalRenderInfos} — non-empty exactly when
 * a portal RENDERED last frame (the outline submit happens before this frame's portal passes, so
 * the 1-frame-stale signal is the freshest available; hysteresis is invisible for a draw-bucket
 * choice). Exclusive ternary: flag-OFF byte-equivalent to the pre-S18 behavior, and the qouteall
 * class is never touched (short-circuit = no class-load) flag-OFF.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererBlockOutlineMixin {

    @ModifyArg(
        method = "submitBlockOutline",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;submitHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/state/level/BlockOutlineRenderState;IFZ)V"
        ),
        index = 6
    )
    private boolean seamlessportals$outlineAfterPortalRender(boolean afterTerrain) {
        return afterTerrain
            || (EntityPortalsFlag.isOn()
                ? !qouteall.imm_ptl.core.render.context_management.RenderStates
                    .lastPortalRenderInfos.isEmpty()
                : StencilPortalRenderer.anyPortalNearCamera());
    }

    /**
     * IS5-OUTLINE (2026-08-11, the shaders-ON sliver): the re-bucket above cannot help the
     * compat path — the IS5-PRE stamp runs at composite renderAll HEAD, AFTER every world
     * bucket, so the outline's depth (RenderPipelines.LINES = DepthStencilState.DEFAULT =
     * writeDepth TRUE, bytecode-pinned) is in depthtex0 before the stamp regardless, the
     * stamp's LEQUAL loses on the line's overhang past the block silhouette, and the raw
     * pre-stamp SOURCE terrain shows as a sliver hugging the outline. Fix: swap the MAIN
     * outline draw (the second submitHitOutline, ordinal=1 — the high-contrast backing line
     * at ordinal 0 already has writeDepth FALSE) to RenderTypes.linesTranslucent() — the
     * IDENTICAL pipeline (same LINES_SNIPPET, same iris ShaderKey.LINES/gbuffers_line
     * mapping, same blend/format/layering) differing ONLY in writeDepth=false. The stamp
     * then wins the window; the outline stays intact on the block faces (the block is nearer
     * than the plane) and is clipped at the window edge — the same visual outcome the
     * stencil path's re-bucket achieves. Gated to shaders-ON so the closed stencil-path arc
     * keeps its variables; MixinExtras is NOT on the classpath (repo-documented) — vanilla
     * @ModifyArg with an ordinal-pinned @At, composing with the index-6 handler above
     * (different argument slots). Caveat (agent-recorded): linesTranslucent has no iris
     * shadow-pass mapping — irrelevant here, the outline never submits in the shadow pass.
     * -PdisableOutlineDepthWriteFix = the sliver reproduction.
     */
    @ModifyArg(
        method = "submitBlockOutline",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;submitHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/state/level/BlockOutlineRenderState;IFZ)V",
            ordinal = 1
        ),
        index = 2
    )
    private net.minecraft.client.renderer.rendertype.RenderType seamlessportals$outlineNoDepthWrite(
        net.minecraft.client.renderer.rendertype.RenderType renderType
    ) {
        if (!qouteall.imm_ptl.core.IPGlobal.disableOutlineDepthWriteFix
            && qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface.invoker.isShaders()) {
            return net.minecraft.client.renderer.rendertype.RenderTypes.linesTranslucent();
        }
        return renderType;
    }
}
