package qouteall.imm_ptl.core.compat.mixin.voxy;

import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.voxy_compatibility.VoxyInterface;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Voxy compat, tier 2 — one Voxy viewport per (portal, layer) (design {@code migration/VOXY_COMPAT.md}).
 *
 * <p>A Voxy {@code Viewport} carries cross-frame state: the HiZ pyramid, the depth-bounding
 * buffer, the frame counter the temporal occlusion traversal keys on, the last camera. Voxy's
 * {@code ViewportSelector} already hands out extra viewports per context (a Vivecraft eye, the
 * Iris shadow pass) so two cameras never share one; a portal pass is another such context.
 * Without this, a same-dimension portal pass would feed the portal camera into the MAIN
 * viewport every frame, alternating with the player's camera — the occlusion state would never
 * settle (LOD pop/flicker in both views).
 *
 * <p>Keyed on the rendering portal's UUID + the portal layer (two portals on screen at the same
 * layer must not share either). Viewports unused for 300 frames are deleted (each holds a
 * screen-sized HiZ texture), and all are deleted with the selector.
 *
 * <p>Bound to voxy-0.2.19-beta's {@code ViewportSelector} (private {@code creator} supplier,
 * public {@code getViewport()}, {@code free()}); the class name carries {@code Voxy} for the
 * compat plugin's substring gate.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.ViewportSelector", remap = false)
public abstract class MixinVoxyViewportSelector_PortalViewports {

    @Shadow
    @Final
    private Supplier<?> creator;

    @Unique
    private final Map<String, Viewport<?>> ip_portalViewports = new HashMap<>();
    @Unique
    private final Map<String, Integer> ip_portalViewportLastUsed = new HashMap<>();
    @Unique
    private int ip_lastPruneFrame = Integer.MIN_VALUE;

    @Inject(method = "getViewport", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void ip_portalViewport(CallbackInfoReturnable cir) {
        if (!VoxyInterface.isPortalLodPass() || IrisUtil.irisShadowActive()) {
            return;
        }
        String key = PortalRendering.getRenderingPortal().getUUID() + "#" + PortalRendering.getPortalLayer();
        int frame = RenderStates.frameIndex;
        Viewport<?> viewport = this.ip_portalViewports.get(key);
        if (viewport == null) {
            viewport = (Viewport<?>) this.creator.get();
            this.ip_portalViewports.put(key, viewport);
        }
        this.ip_portalViewportLastUsed.put(key, frame);
        if (frame - this.ip_lastPruneFrame > 120) {
            this.ip_lastPruneFrame = frame;
            Iterator<Map.Entry<String, Integer>> it = this.ip_portalViewportLastUsed.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Integer> e = it.next();
                if (frame - e.getValue() > 300) {
                    Viewport<?> stale = this.ip_portalViewports.remove(e.getKey());
                    if (stale != null) {
                        stale.delete();
                    }
                    it.remove();
                }
            }
        }
        cir.setReturnValue(viewport);
    }

    @Inject(method = "free", at = @At("TAIL"))
    private void ip_freePortalViewports(CallbackInfo ci) {
        for (Viewport<?> viewport : this.ip_portalViewports.values()) {
            viewport.delete();
        }
        this.ip_portalViewports.clear();
        this.ip_portalViewportLastUsed.clear();
    }
}
