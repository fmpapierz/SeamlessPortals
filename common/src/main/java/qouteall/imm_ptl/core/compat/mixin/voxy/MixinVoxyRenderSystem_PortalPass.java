package qouteall.imm_ptl.core.compat.mixin.voxy;

import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.voxy_compatibility.VoxyInterface;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.renderer.RendererUsingStencil;

/**
 * Voxy compat, tier 2 — the render system inside a portal pass (design {@code migration/VOXY_COMPAT.md}).
 *
 * <ul>
 *   <li><b>Same-dimension passes never re-centre the tracker.</b> {@code renderOpaque} ends with
 *       {@code renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ)}: the
 *       ring of top-level (512-block) LOD nodes follows the camera. For a same-dimension portal
 *       the main system renders the pass, and letting the portal camera move that ring every
 *       frame — then the player's camera moving it back — would churn nodes at the ring's edge for
 *       any far-away destination. The main camera alone drives it; a same-dimension destination
 *       farther than the ring (about 8 km at the default 16-section LOD distance) simply shows no
 *       LODs in its window. A cross-dimension pass uses the dest renderer's OWN system, whose
 *       tracker rightly follows the dest camera.</li>
 *   <li><b>Re-arm the stencil limitation after Voxy's draw.</b> {@code renderOpaque} leaves
 *       {@code GL_STENCIL_TEST} DISABLED (its state restore at the end) — fine for the main pass,
 *       fatal for a portal pass, whose every later draw (translucent terrain, entities, particles,
 *       clouds) is bounded to the aperture by exactly that test. Re-enable it with the pass's
 *       limitation ({@code EQUAL layer, KEEP}) at every return.</li>
 * </ul>
 *
 * <p>Bound to voxy-0.2.19-beta ({@code VoxyRenderSystem.renderOpaque(Viewport,int,int)}, the
 * {@code RenderDistanceTracker.setCenterAndProcess(DD)Z} call in its tail loop); the class name
 * carries {@code Voxy} for the compat plugin's substring gate.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public abstract class MixinVoxyRenderSystem_PortalPass {

    @Redirect(
        method = "renderOpaque",
        at = @At(
            value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/RenderDistanceTracker;setCenterAndProcess(DD)Z"
        )
    )
    private boolean ip_noTrackerRecentreInSameDimPass(RenderDistanceTracker tracker, double x, double z) {
        if (VoxyInterface.isSameDimLodPass()) {
            return false;
        }
        return tracker.setCenterAndProcess(x, z);
    }

    @Inject(method = "renderOpaque", at = @At("RETURN"))
    private void ip_rearmStencilLimitation(CallbackInfo ci) {
        if (VoxyInterface.isPortalLodPass()) {
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            RendererUsingStencil.setStencilLimitation(PortalRendering.getPortalLayer());
        }
    }
}
