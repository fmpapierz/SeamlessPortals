package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.render.IrisCompatPaste;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.SecondaryFrameBuffer;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

import java.util.List;

import static org.lwjgl.opengl.GL11.GL_STENCIL_TEST;

/**
 * IS1 — THE 26.2 IRIS COMPATIBILITY RENDERER (iris shaders-ON engagement;
 * {@code migration/IRIS_SHADERS_ON_DESIGN.md} §1 IS1 deliverable 2 / §2.2 the member walk /
 * §0.3 verdict (beta); deviations D8-EVO(lever-only at IS1)/D16/D17/D19/D20/D23).
 *
 * <p><b>Fidelity reference</b>: the held {@link IrisCompatibilityPortalRenderer} (IP's shipped
 * shaders-ON compatibility renderer, UNTOUCHED in-tree) — this class re-expresses its EXACT
 * hook choreography onto the live 26.2 substrate: after the finished main frame
 * ({@code onBeforeHandRendering}, fired by the IS0 anchor
 * {@code MixinGameRenderer_IPPostLevelAnchor} at GameRenderer.renderLevel:566 — post iris
 * finalize, scene depth valid, world projection active, pre-hand), (a) snapshot the main
 * target's depth ({@code copyDepthFrom}) + color (straight-copy pass) into ONE sequential
 * deferred buffer; (b) per portal: occlusion-test → pushPortalLayer → render the DEST world
 * through the FULL pipeline INTO THE MAIN TARGET
 * ({@link MyGameRenderer#renderWorldFullPipeline} — the direct 8-arg render(); iris's
 * class-woven hooks re-enter naturally, zero iris mixins) → popPortalLayer → stamp ONLY the
 * portal-shaped area of the main target into the deferred buffer, depth-tested against the
 * SNAPSHOT depth ({@link IrisCompatPaste#stampPortalArea}, D20 stencil-free); (c) blit the
 * deferred buffer back to main. The shape never cares where iris's intermediate targets live —
 * only its presentation contract ("the final image lands in the main target",
 * P-B2 live-confirmed, port-note §1.6).
 *
 * <p><b>Selection (IS4 Q-U1 = DEFAULT-ON, shaders-gated — supersedes the IS1 lever-only rule)</b>:
 * {@code PortalRenderer.switchToCorrectRenderer} routes here when
 * {@code IPGlobal.isShaderpackPortalViewsActive(IrisInterface.invoker.isShaders())} — i.e. the
 * default-TRUE {@code experimentalShaderpackPortalViews} flag WHILE a shaderpack is actually
 * running, OR the {@code -Dseamlessportals.shaderpackViews} JVM lever (which forces BOTH shader
 * states — the dev proof rows, deliverable 6; design §0.4-11, S20-safe). This class is therefore
 * DEFAULT-LIVE for any shaderpack-ON user with NO lever — it is no longer "lever-only" nor a
 * "zero committed-default change" (the IS1 wording was retired at IS4). The shaders gate caps the
 * flip's blast radius: shaders-OFF / no-pack / plain users fall through to the pre-IS1 selection
 * (byte-identical D8 shaders-ON → dummy+notice / stencil-family), so the default-ON flag NEVER
 * pulls them here (recon §4.5). NOTE for any S20 dormancy/deletion pass: this is a DEFAULT-LIVE
 * renderer, NOT a dormant/lever-only held source — do not strand it.
 *
 * <p><b>Held-source deltas</b> (each design-mandated): {@code prepareRendering} = the raw
 * stencil-disable belt ONLY — the deferred-buffer prepare/clear moved into the post-main
 * workhorse (§2.2-1: no mid-framegraph target touches, §8-15 adjacency), and the block-era
 * {@code IPPortingLibCompat.setIsStencilEnabled} call is DROPPED (the stencil-free shape has no
 * stencil-plumbing dependency — §0.4-3, recorded as an S20 NON-dependency). The snapshot color
 * copy is the straight-copy pass, NOT {@code blitAndBlendToTexture} (ALPHA-BLEND — settled OQ5).
 * {@code invokeWorldRendering} routes to the sibling full-pipeline driver, with the D23
 * layer-0 fallback (below). One recursion layer, IP-verbatim.
 *
 * <p><b>D23 layer-0 fallback</b>: {@code CrossPortalViewRendering}/{@code GuiPortalRendering}
 * call {@code prepare/invokeWorldRendering/finish} directly at LAYER 0 — no snapshot context
 * exists there, and a full-pipeline render would clobber the main target MID-frame. The
 * {@code isInsideOwnRenderPortals} latch detects that and falls back to the decomposed
 * {@code renderWorldNew} (strictly better than nothing; full fidelity deferred).
 *
 * <p><b>Teardown (mining §8-20 — the world-exit teardown the block era forgot)</b>: the ONE
 * sequential {@link SecondaryFrameBuffer} is destroyed on client cleanup
 * ({@code IPCGlobal.CLIENT_CLEANUP_EVENT}: disconnect/world-exit) and on renderer switch-away
 * ({@code PortalRenderer.switchRenderer} calls {@link #onSwitchedAway()}), re-created lazily by
 * {@code prepare()} on next use.
 *
 * <p>SIGN NOTE (R5): the deferred clear's depth constant is 0.0 = reversed-Z FAR (the held
 * source's G7 re-expression); the stamp's compare direction lives in
 * {@link IrisCompatPaste} (GEQUAL — derivation there).
 *
 * <p><b>Both-levers precedence (gate-audit ledger, port-note §2.5)</b>: the IS0 anchor fires
 * {@code onBeforeHandRendering} (the full compat pass incl. blit-back) BEFORE the
 * {@code ShaderpackViewsProbe} dispatch — so with {@code -PshaderpackViewsProbe} AND
 * {@code -PshaderpackViews} both set, the probe snapshots/judges frames that already contain
 * compat-stamped portal views. Probe rounds are defined for renderer-lever-OFF only;
 * both-levers is a diagnostics-confounded configuration, not a supported row.
 */
@Environment(EnvType.CLIENT)
public class IrisCompatOn262Renderer extends PortalRenderer {

    public static final IrisCompatOn262Renderer instance = new IrisCompatOn262Renderer(false);
    public static final IrisCompatOn262Renderer debugModeInstance =
        new IrisCompatOn262Renderer(true);

    static {
        // §8-20 teardown: this <clinit> runs only when the lever first routes here (the class is
        // otherwise never actively used), so the registration cannot perturb unarmed sessions.
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(() -> {
            instance.teardown();
            debugModeInstance.teardown();
        });
    }

    // IS6 §6.2 — THE PER-LAYER DEFERRED-BUFFER STACK (was a single field
    // {@code SecondaryFrameBuffer deferredBuffer}). Sized to the isLaggy-INDEPENDENT ceiling
    // {@code IPGlobal.maxPortalLayer+1} (grow-only), lazily allocated at layer-0 entry
    // ({@link #ensureDeferredBuffers}); each layer L snapshots the
    // finished dest frame into {@code deferredBuffers[L]}, renders its children into MAIN + stamps
    // them into {@code deferredBuffers[L]}, then blits {@code deferredBuffers[L]}→MAIN so the
    // ENCLOSING layer's stamp sees dest+children. Without the stack, layer N's snapshot would
    // clobber layer N-1's (port-note §6.2 / §6.3 "the ONE structural build"). null until first use.
    // With the recursion lever OFF only index 0 is ever used — the exact one-layer behavior of the
    // old single field.
    private SecondaryFrameBuffer[] deferredBuffers = null;

    // IP-verbatim field (the held source's onBeforeTranslucentRendering capture); consumed by
    // the post-main workhorse — set every frame by the F1 AFTER_TRANSLUCENT_TERRAIN driver
    // BEFORE the anchor fires (both live inside the same renderLevel).
    private Matrix4f passingModelView = new Matrix4f();

    public final boolean isDebugMode;

    // D23 detector: true exactly while THIS renderer's own renderPortals loop is on the stack
    // (the only context where the snapshot exists and the full-pipeline clobber is legal).
    private boolean isInsideOwnRenderPortals = false;

    public IrisCompatOn262Renderer(boolean isDebugMode) {
        this.isDebugMode = isDebugMode;
    }

    @Override
    public boolean replaceFrameBufferClearing() {
        // IP-verbatim false: iris/vanilla clear normally — WANTED for the nested full render
        // (its clearPass painting the main target with dest fog color IS the mechanism).
        return false;
    }

    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {
        // IP-verbatim (held source :68-76). The isRendering guard is DOUBLY load-bearing here:
        // fabric level-render events RE-FIRE during the nested REAL renderLevel (unlike the
        // decomposed dest passes) — §2.4-7; the nested refire arrives inside pushPortalLayer,
        // so it early-returns.
        if (PortalRendering.isRendering()) {
            return;
        }

        passingModelView = modelView;

        GL11.glDisable(GL_STENCIL_TEST);
    }

    @Override
    public void finishRendering() {
        GL11.glDisable(GL_STENCIL_TEST);
    }

    @Override
    public void prepareRendering() {
        // §2.2-1: the raw stencil-disable belt ONLY. This fires MID-renderLevel (the F1
        // driver); the deferred-buffer prepare/clear moved into the post-main workhorse —
        // no mid-framegraph target touches (§8-15 adjacency). The held source's
        // IPPortingLibCompat.setIsStencilEnabled call is DROPPED (§0.4-3: this renderer has
        // ZERO stencil-plumbing dependency — S20 NON-dependency record).
        GL11.glDisable(GL_STENCIL_TEST);
    }

    /**
     * §2.2-3 — THE WORKHORSE, fired by the IS0 post-main anchor (after iris finalized the main
     * frame; before hand). Snapshot → per-portal loop → blit-back.
     */
    @Override
    public void onBeforeHandRendering(Matrix4f modelView) {
        if (PortalRendering.isRendering()) {
            return;
        }
        if (client.level == null || client.player == null) {
            return; // mid-packet frame class: skip, never assert
        }
        // Fable-fold ledger (port-note §2.5, F-NOTE-2): the S15 mid-packet MISMATCH frame
        // (player.level() != mc.level) is intentionally NOT guarded here — parity with the
        // F1/stencil family, which also runs portal rendering without that predicate. If the
        // IS1 live round surfaces a mismatch-frame defect on this anchor, add the one-line
        // skip (CrossPortalViewRendering:59-62 shape) rather than asserting.
        RenderTarget mainRT = client.gameRenderer.mainRenderTarget();
        if (mainRT == null
            || mainRT.getColorTextureView() == null
            || mainRT.getDepthTextureView() == null) {
            return;
        }
        if (!IrisCompatPaste.arePipelinesReady()) {
            // Fable-fold CORRECTION (port-note §2.5): if the paste pipelines failed to create,
            // the per-portal loop MUST NOT run — the snapshot copy would no-op while each
            // nested full render() clobbered the main target (whole-screen-dest-world, a
            // wrongly-discriminated corruption mode). Early-returning pairs the D7 loud
            // static-init log with an honest "portals render nothing" symptom.
            return;
        }

        CHelper.checkGlError();
        // Stencil belt (anchor slot; §6 hazard row 8-2's raw-disable family).
        GL11.glDisable(GL_STENCIL_TEST);

        // IS6 §6.2 — ensure the deferred-buffer stack at LAYER-0 entry. Sized to the
        // isLaggy-INDEPENDENT ceiling IPGlobal.maxPortalLayer+1 (NOT getMaxPortalLayer()+1):
        // getMaxPortalLayer() collapses to 1 whenever RenderStates.isLaggy, so sizing off it would
        // make `n` oscillate on every lag-attack transition and churn the layer-0 GPU FBO
        // (dispose+recreate) — a per-frame-FBO delta the OLD single `final deferredBuffer` never
        // had (vInert BLOCKER, port-note §6.2 lifecycle). The isLaggy-independent ceiling keeps `n`
        // stable across lag flips, so index 0 persists frame-to-frame exactly like OLD; the driver
        // still clamps EFFECTIVE recursion depth via getMaxPortalLayer()/effectiveMax at call time,
        // and its `layer >= deferredBuffers.length` guard stays safe against the larger array.
        // ensureDeferredBuffers is GROW-ONLY, so a maxPortalLayer config DECREASE keeps the live
        // buffers too — zero churn on any transition. With the recursion lever OFF the driver never
        // fires, so only index 0 is ever touched below (== the old single-buffer path).
        int n = IPGlobal.maxPortalLayer + 1;
        ensureDeferredBuffers(n);

        isInsideOwnRenderPortals = true;
        try {
            // Layer 0: snapshot the finished MAIN frame into deferredBuffers[0], render the
            // top-level portals into MAIN + stamp them into deferredBuffers[0], then blit
            // deferredBuffers[0]→MAIN (§6.2 choreography, the proven layer-0 sequence). The
            // driver (onDestWorldFinalizedFullPipeline) recurses this at deeper layers.
            runNestedPortalPass(0, passingModelView);
        } finally {
            isInsideOwnRenderPortals = false;
            // anchor-state neutralize (the CrossPortalViewRendering:170 precedent — §6 row 8-2)
            GL11.glDisable(GL_STENCIL_TEST);
        }

        CHelper.checkGlError();
    }

    /**
     * IS6 §6.2 — THE PER-LAYER CHOREOGRAPHY (was the inline layer-0 body of
     * {@code onBeforeHandRendering}). Snapshot THIS layer's finished dest frame from MAIN into
     * {@code deferredBuffers[layer]} (depth via {@code copyDepthFrom} = replace; color via the
     * straight-copy pass), render this layer's portals (each clobbers MAIN with its own dest
     * render + stamps into {@code deferredBuffers[layer]}), then blit {@code deferredBuffers[layer]}
     * → MAIN so the ENCLOSING layer's stamp sees dest+children. Called at layer 0 by the anchor
     * workhorse and at each deeper layer by the recursion driver.
     *
     * <p>Layer-indexing correctness (port-note §6.2 trace): {@code runNestedPortalPass(L)} snapshots
     * into {@code deferred[L]}; the portals it renders execute at {@code getPortalLayer()==L} and
     * {@code doRenderPortal} stamps them into {@code deferredBuffers[L]} (== this buffer) after
     * popping — snapshot target == stamp target. The blit-back in the finally restores MAIN for the
     * enclosing {@code doRenderPortal} (layer L-1) to stamp into {@code deferred[L-1]}.
     */
    private void runNestedPortalPass(int layer, Matrix4f modelView) {
        SecondaryFrameBuffer deferred = deferredBuffers[layer];

        // Deferred-buffer prepare — the auto-resize to the main RT runs BEFORE copyDepthFrom
        // (LOAD-BEARING ordering, port-note §1-E OQ5: copyDepthFrom copies DEST.width x
        // DEST.height and throws if either target lacks depth).
        deferred.prepare();
        if (deferred.fb == null || deferred.fb.getColorTextureView() == null) {
            return;
        }

        RenderTarget mainRT = client.gameRenderer.mainRenderTarget();

        // Held-source G7 re-expression: device-clear the deferred target; depth = 0.0
        // (R5 reversed-Z FAR). Belt only — the snapshot pair below overwrites the whole target.
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            deferred.fb.getColorTexture(), new Vector4f(1, 0, 0, 0),
            deferred.fb.getDepthTexture(), 0.0
        );

        // SNAPSHOT the finished frame currently in MAIN: depth via copyDepthFrom (replace; D19 —
        // no stencil bits, the stencil-free shape consumes none), color via the STRAIGHT-COPY pass
        // (blitAndBlendToTexture is ALPHA-BLEND — settled OQ5, never on this path).
        deferred.fb.copyDepthFrom(mainRT);
        IrisCompatPaste.drawStraightCopy(mainRT, deferred.fb);

        CHelper.checkGlError();

        try {
            // Children clobber MAIN + stamp into deferred[layer]. At deeper layers each child's
            // full-pipeline render() fires the recursion driver (post-finalize), which re-enters
            // runNestedPortalPass at layer+1 (§6.1).
            renderPortals(modelView);
        } finally {
            // BLIT-BACK: the deferred buffer (this layer's dest scene + the stamped child views)
            // → MAIN, full-screen straight copy. At layer 0 the result is the pre-portal frame
            // plus portal-shaped dest views (the pre-registered blit-order discriminator, design
            // §1 IS1); at deeper layers it restores MAIN for the enclosing stamp. IN THE FINALLY
            // (Fable-fold BLOCKER companion, port-note §2.5): the snapshot was taken
            // unconditionally above, so on a mid-loop throw this restores the composited snapshot
            // instead of leaving the last child's raw dest render on MAIN.
            IrisCompatPaste.drawStraightCopy(deferred.fb, mainRT);
        }
    }

    /**
     * IS6 §6.1 — THE RECURSION DRIVER. Fired by {@code renderDestWorldFullPipeline} at Point A
     * (post-finalize, dest world still swapped). LEVER-GATED: with
     * {@code -Dseamlessportals.irisNestedPortals} OFF (the default) this returns immediately, so
     * {@code doRenderPortal} is never re-entered at a deeper layer and behavior is byte-identical
     * to the one-layer floor. See port-note §6.1.
     */
    @Override
    public void onDestWorldFinalizedFullPipeline(Matrix4f destViewMatrix) {
        // Default OFF = the one-layer FLOOR (byte-identical to pre-IS6).
        if (!IPGlobal.IRIS_NESTED_PORTALS_LEVER) {
            return;
        }
        // D23: only recurse from within OUR OWN renderPortals loop (a layer-0 direct invocation
        // from CrossPortalViewRendering/GuiPortalRendering falls back to the decomposed path in
        // invokeWorldRendering and has no snapshot context to recurse into).
        if (!isInsideOwnRenderPortals) {
            return;
        }
        int layer = PortalRendering.getPortalLayer();
        // Bound (§6.1 derivation vs renderPortalContent:288): the driver at pushed layer L renders
        // CHILD portals into L+1, whose content renders iff L+1 <= max, i.e. L < max — so skip at
        // L >= max. §6.4 SPIKE CAP: while the reentrancy probe is armed, depth is capped to 2
        // (mirror → same-dim → cross-dim at the shallowest depth). getMaxPortalLayer() already
        // clamps to 1 under isLaggy / maxPortalLayer<=1 (→ driver never runs → one-layer).
        int configuredMax = PortalRendering.getMaxPortalLayer();
        int effectiveMax = IPGlobal.IRIS_NESTED_REENTRANCY_PROBE_LEVER
            ? Math.min(configuredMax, 2)
            : configuredMax;
        if (layer >= effectiveMax) {
            return;
        }
        if (!IrisCompatPaste.arePipelinesReady()) {
            return;
        }
        RenderTarget mainRT = client.gameRenderer.mainRenderTarget();
        if (mainRT == null
            || mainRT.getColorTextureView() == null
            || mainRT.getDepthTextureView() == null) {
            return;
        }
        // Defensive: the stack was sized IPGlobal.maxPortalLayer+1 (grow-only) at layer-0 entry, so
        // for any layer < effectiveMax <= getMaxPortalLayer() <= IPGlobal.maxPortalLayer this index
        // is in bounds. Kept as a belt against a mid-frame maxPortalLayer config change: refuse
        // rather than index OOB (a shrink leaves the array LARGER, so this never trips falsely).
        if (deferredBuffers == null || layer >= deferredBuffers.length) {
            return;
        }
        runNestedPortalPass(layer, destViewMatrix);
    }

    /** §2.2-4 — one layer: occlusion test → push → full-pipeline content → pop → stamp. */
    protected void doRenderPortal(Portal portal, Matrix4f modelView) {
        // IS6 §6.1 — THE ONE-LAYER GUARD ({@code if (PortalRendering.isRendering()) return;}) IS
        // REMOVED. The recursion bound is enforced by three composing places: (i) the driver's
        // {@code >= getMaxPortalLayer()} gate (won't call renderPortals at the deepest layer),
        // (ii) renderPortalContent:288, (iii) getPortalsToRender → shouldSkipRenderingPortal
        // (isInvalidRecursionRendering kills A→B→A loops, cannotRenderInMe, the per-layer range
        // shrink, the render predicate). BYTE-IDENTICAL AT DEFAULT: with the recursion lever OFF
        // the driver returns immediately, so nothing re-invokes renderPortals — this method is
        // only ever called at layer 0 (from onBeforeHandRendering → runNestedPortalPass(0) →
        // renderPortals), where the removed guard never fired anyway (it pops its own layer before
        // the loop continues). The guard was dead on this substrate even pre-IS6: the nested
        // render() is a direct 8-arg LevelRenderer.render (D16) that never re-fires the anchor,
        // so onBeforeHandRendering/renderPortals never re-entered.
        if (!testShouldRenderPortal(portal, modelView)) {
            return;
        }

        PortalRendering.pushPortalLayer(portal);

        // Fable-fold BLOCKER fix (port-note §2.5; the S14.29/RendererUsingStencil:332-346
        // precedent — the project's approved hardening over the held IP source's unbracketed
        // shape): renderPortalContent drives a full nested LevelRenderer.render() with iris
        // hooks — the richest throw surface in the mod. Without the finally, one escaping
        // throw leaves the layer pushed => PortalRendering.isRendering()==TRUE FOREVER =>
        // the F1 driver, the anchor workhorse, and switchToCorrectRenderer all early-return
        // for the rest of the session (silent portal death, misdiagnosis-prone live).
        try {
            // → base renderPortalContent → invokeWorldRendering (below) → the sibling driver's
            // direct 8-arg render() INTO THE MAIN TARGET (D16). The nested render re-fires the
            // F1 driver + F2 with PortalRendering.isRendering()==TRUE (we are inside the pushed
            // layer) — their early-returns are the load-bearing recursion guards (§2.4-7). AT
            // Point A after that render() returns, the recursion driver fires and (lever-ON) may
            // re-enter runNestedPortalPass at this deeper layer.
            renderPortalContent(portal);
        } finally {
            PortalRendering.popPortalLayer();
        }

        CHelper.enableDepthClamp();

        // IS6 §6.2 — LAYER-INDEXED stamp target. The portal has been popped, so getPortalLayer()
        // == L == the layer whose runNestedPortalPass(L) snapshotted deferredBuffers[L].
        int layer = PortalRendering.getPortalLayer();
        SecondaryFrameBuffer deferred = deferredBuffers[layer];

        if (!isDebugMode) {
            // THE STAMP (D20): portal-shaped copy main→deferred[L], snapshot-depth-tested.
            // Matrices per the held source: the passing model view + the live layer draw
            // projection (we are OUTSIDE the pushed layer again — this layer's scaling).
            IrisCompatPaste.stampPortalArea(
                portal,
                client.gameRenderer.mainRenderTarget(),
                deferred.fb,
                modelView,
                getCurrentProjectionMatrix()
            );
        }
        else {
            // debugModeInstance (§2.2 member walk): full-screen RAW view of the nested render —
            // the live-round diagnostic that splits "render() produced nothing" from "stamp/copy
            // defect" (design §1 IS1 discriminators).
            IrisCompatPaste.drawStraightCopy(
                client.gameRenderer.mainRenderTarget(), deferred.fb
            );
        }

        CHelper.disableDepthClamp();

        // Color-mask restore — cache-coherent via GlStateManager._colorMask(15) (all buffers,
        // 15 = R|G|B|A), the S14.22 idiom (RendererUsingStencil:451). Fable-fold CORRECTION
        // (port-note §2.5): raw glColorMask desyncs GlStateManager's per-draw-buffer COLOR_MASK
        // cache — when the stamp no-ops, the next mask-0 pipeline apply would short-circuit on
        // the stale cache and draw WITH color writes (26.2 invariant #1: never raw-GL a
        // GlStateManager-cached state).
        GlStateManager._colorMask(15);
    }

    @Override
    public void invokeWorldRendering(WorldRenderInfo worldRenderInfo) {
        if (!isInsideOwnRenderPortals) {
            // D23: a layer-0 invocation (CrossPortalViewRendering:158-171 / GuiPortalRendering
            // call prepare/invoke/finish directly) — NO snapshot context exists and a
            // full-pipeline render would clobber the main target mid-frame. Fall back to the
            // decomposed driver (strictly better than nothing; full fidelity deferred).
            MyGameRenderer.renderWorldNew(worldRenderInfo, Runnable::run);
            return;
        }
        MyGameRenderer.renderWorldFullPipeline(worldRenderInfo);
    }

    @Override
    public void onHandRenderingEnded() {
        // IP-empty
    }

    @Override
    public void renderPortalInEntityRenderer(Portal portal) {
        // IP-empty
    }

    private boolean testShouldRenderPortal(Portal portal, Matrix4f modelView) {
        // IP-verbatim: GL occlusion query around a color/depth-write-free view-area draw,
        // depth-tested against the finished main frame's scene depth (valid at the anchor slot —
        // port-note §1-A; OQ4 statically positive + P-OQ4 live-confirmed).
        // MULTI-PORTAL LEDGER (Fable-fold NOTE, port-note §2.5): for the 2nd+ portal in a frame
        // this query tests against the CURRENT main-target depth, which the previous portal's
        // nested full render replaced with DEST-world depth (nothing restores main depth between
        // portals — inherited one-layer-era shape, faithful to the held IP source; the STAMP
        // itself stays correct, tested against the untouched deferred snapshot depth). Wrong
        // show/hide decisions possible for portals 2+; pre-registered discriminator: two portals
        // side-by-side, the second window shows the static snapshot scene. IS2+ candidate fix:
        // query against the deferred buffer's snapshot depth.
        return PortalRenderInfo.renderAndDecideVisibility(portal, () -> {
            ViewAreaRenderer.renderPortalArea(
                portal, Vec3.ZERO,
                modelView,
                getCurrentProjectionMatrix(),
                true, false, false, true
            );
        });
    }

    protected void renderPortals(Matrix4f modelView) {
        List<Portal> portalsToRender = getPortalsToRender(modelView);

        for (Portal portal : portalsToRender) {
            doRenderPortal(portal, modelView);
        }
    }

    /**
     * IS6 §6.2 — ensure the deferred-buffer stack holds at least {@code n} members. GROW-ONLY: when
     * the array is already {@code >= n} long it is kept verbatim (its live buffers untouched) so a
     * maxPortalLayer DECREASE never disposes+recreates the layer-0 GPU FBO — the per-frame-FBO churn
     * class the project has repeatedly fought (vInert BLOCKER, port-note §6.2). When it must grow,
     * the EXISTING members are carried into the larger array (no live buffer is destroyed) and only
     * the new tail slots are allocated empty ({@code fb == null}; filled lazily by
     * {@code SecondaryFrameBuffer.prepare()} in {@link #runNestedPortalPass}). Called at layer-0
     * entry only. Combined with the isLaggy-independent {@code IPGlobal.maxPortalLayer+1} sizing at
     * the call site, index 0 persists frame-to-frame exactly like the OLD single field — zero churn
     * on isLaggy flips and on config transitions. Mirrors IP's {@code IrisPortalRenderer}
     * deferredFbs[] allocation shape.
     */
    private void ensureDeferredBuffers(int n) {
        if (deferredBuffers != null && deferredBuffers.length >= n) {
            return;
        }
        // GROW: preserve the existing live buffers (never destroyBuffers() on a resize) — carry
        // them into the extended array, allocate only the new tail slots.
        SecondaryFrameBuffer[] old = deferredBuffers;
        int carried = old == null ? 0 : old.length;
        deferredBuffers = new SecondaryFrameBuffer[n];
        for (int i = 0; i < carried; i++) {
            deferredBuffers[i] = old[i];
        }
        for (int i = carried; i < n; i++) {
            deferredBuffers[i] = new SecondaryFrameBuffer();
        }
    }

    private void disposeDeferredBuffers() {
        if (deferredBuffers == null) {
            return;
        }
        for (SecondaryFrameBuffer b : deferredBuffers) {
            if (b != null && b.fb != null) {
                try {
                    b.fb.destroyBuffers();
                } catch (Throwable t) {
                    // disposal is best-effort
                }
                b.fb = null;
            }
        }
    }

    /**
     * Mining §8-20: destroy every deferred buffer in the stack (re-created lazily by
     * {@link #ensureDeferredBuffers} / {@code prepare()} on next use).
     */
    public void teardown() {
        disposeDeferredBuffers();
        deferredBuffers = null;
    }

    /** Called by {@code PortalRenderer.switchRenderer} when routing AWAY from this family. */
    public static void onSwitchedAway() {
        instance.teardown();
        debugModeInstance.teardown();
    }
}
