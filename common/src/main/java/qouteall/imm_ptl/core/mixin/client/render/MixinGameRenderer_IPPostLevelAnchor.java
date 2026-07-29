package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.ShaderpackViewsProbe;
import qouteall.imm_ptl.core.compat.iris_compatibility.ShaderpackViewsProbeLever;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

/**
 * IS0 — THE INERT POST-MAIN ANCHOR (iris shaders-ON engagement;
 * {@code migration/IRIS_SHADERS_ON_DESIGN.md} §1 IS0 deliverable 2, deviation D17;
 * concrete spec = port-note {@code migration/port-notes/IS-iris-shaders-on.md} §1.2 — the
 * census-RE-DECIDED anchor, NOT the design's stale candidate-1).
 *
 * <p><b>The slot.</b> {@code GameRenderer.renderLevel} @ At INVOKE the 8-arg
 * {@code LevelRenderer.render(...)}, shift=AFTER — lands at mc262 GameRenderer.renderLevel:566,
 * the sole {@code LevelRenderer.render} INVOKE in the method (unique match):
 * <ul>
 *   <li>AFTER iris finalize — {@code iris$endLevelRender} runs INSIDE the 8-arg render, at its
 *       own {@code Matrix4fStack.popMatrix} tail (port-note §1-A);</li>
 *   <li>scene depth VALID — before the :571 {@code clearDepthTexture} (OQ4 statically POSITIVE
 *       here; the P-OQ4 probe leg confirms live);</li>
 *   <li>WORLD projection still active — before the :568-570 HUD projection switch;</li>
 *   <li>before vanilla hand (:572) and before {@code iris$runColorSpace} (renderLevel TAIL).</li>
 * </ul>
 * No iris inject exists between the 8-arg render return and {@code renderItemInHand}
 * (port-note §1-A javap census), so no priority clause is needed — ordering is deterministic by
 * distinct bytecode positions. Named fallback (NOT used): DS6 = {@code GameRenderer.render} @
 * INVOKE renderLevel shift=AFTER (:426) — post-hand + post-depth-clear + post-colorspace; only
 * if the :566 slot proves unusable live.
 *
 * <p><b>Inert by RECEIVER REACHABILITY</b> (Lens-B IS0 verify correction — the inert label
 * holds, but its ground is reachability, NOT absence of overriders). The dispatched hook
 * {@link PortalRenderer#onBeforeHandRendering(Matrix4f)} has an EMPTY base body
 * (PortalRenderer.java:111). Two held-source iris renderers DO override it
 * (IrisPortalRenderer:135, IrisCompatibilityPortalRenderer:178) but are DEFER-DORMANT,
 * unreachable receivers: {@code IPCGlobal.renderer} is assigned at exactly two sites —
 * IPModMainClient:86 ({@code rendererUsingStencil}) and PortalRenderer.switchRenderer:477, fed
 * solely by switchToCorrectRenderer:461-471 = {rendererDummy under iris+pack per D8,
 * rendererUsingStencil, rendererUsingFrameBuffer, rendererDebug} — and none of those four
 * overrides the hook. The call is a no-op for every REACHABLE receiver until IS1 deliberately
 * flips the D8 routing. The handler body is deliberately minimal: one static field read + null
 * check, the ONE {@code Matrix4f} copy the §1.2 spec requires (the hook's contract passes an
 * OWNED mutable copy of the view-rotation matrix — the same defensive-copy idiom the
 * AFTER_TRANSLUCENT_TERRAIN driver uses, SeamlessPortalsClientFabric:141-144; skipping the copy
 * would hand receivers a mutable alias of the shared
 * {@code cameraRenderState.viewRotationMatrix}), and one virtual call with no reachable
 * overrider. The probe dispatch below reads a {@code static final boolean} hosted on
 * {@link ShaderpackViewsProbeLever} (a minimal holder, so the per-frame check cannot
 * class-initialize the probe suite) — reachable-code-free at default (property absent
 * {@code =>} false, folded by the JIT).
 *
 * <p><b>S20-safe:</b> registered in {@code seamlessportals-ip-client.mixins.json} (the qouteall
 * client config; weave-gated flag-ON like every qouteall.* mixin); zero {@code com.warwa}
 * imports. NOTE the port-note §1.2 spec block names the class FQN
 * {@code qouteall.imm_ptl.core.render.MixinGameRenderer_IPPostLevelAnchor}; the config's mixin
 * package root is {@code qouteall.imm_ptl.core.mixin}, so the class lives HERE (a mixin must
 * live under its config's package tree) — same class name, config-mandated package, ledgered at
 * the IS0 return.
 *
 * <p>26.2 mid-packet frames: no player/level assertion — the hook is a no-op and the probe
 * guards its own preconditions (skip, never assert).
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer_IPPostLevelAnchor {

    @Shadow
    public abstract GameRenderState gameRenderState();

    @Inject(
        method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;render("
                + "Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;"
                + "Lnet/minecraft/client/DeltaTracker;Z"
                + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                + "Lorg/joml/Matrix4fc;"
                + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                + "Lorg/joml/Vector4f;Z)V",
            shift = At.Shift.AFTER
        )
    )
    private void seamlessportals$onPostLevelPreHand(DeltaTracker deltaTracker, CallbackInfo ci) {
        // TP-XDIM census witness: this anchor injects INSIDE renderLevel, so it CANNOT fire on a
        // frame that CrossPortalViewRendering rendered instead. One folded static-final test plus
        // one int increment; byte-inert at the default. Deliberately an increment on IPGlobal and
        // NOT a call into com.warwa — this class's S20-safe clause above (zero com.warwa imports).
        if (IPGlobal.TP_XDIM_CENSUS_LEVER) {
            IPGlobal.noteTpXdimIs0AnchorFired();
        }
        PortalRenderer renderer = IPCGlobal.renderer;
        if (renderer != null) {
            // == the object passed as render()'s 5th arg (GameRenderer:532-533); no @Local
            // needed (port-note §1.2 Body).
            Matrix4f modelView = new Matrix4f(
                gameRenderState().levelRenderState.cameraRenderState.viewRotationMatrix
            );
            renderer.onBeforeHandRendering(modelView);
        }
        // IS0 probe suite dispatch — the lever lives on a minimal HOLDER class so this
        // per-frame read cannot class-init the probe suite (Lens-B IS0 verify correction;
        // Boolean.getBoolean is not a javac constant). Property absent => static-final
        // false, folded => the probe class never loads.
        if (ShaderpackViewsProbeLever.PROBE_ENABLED) {
            ShaderpackViewsProbe.onPostLevelAnchor(
                (GameRenderer) (Object) this, deltaTracker
            );
        }
    }
}
