package com.warwa.seamlessportals.mixin.client;

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
        return afterTerrain || StencilPortalRenderer.anyPortalNearCamera();
    }
}
