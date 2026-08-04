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
import qouteall.imm_ptl.core.render.CrossPortalViewRendering;
import qouteall.imm_ptl.core.render.IrisCompatPaste;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.SecondaryFrameBuffer;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
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
 * {@code ownRenderPortalsDepth} counter detects that and falls back to the decomposed
 * {@code renderWorldNew} (strictly better than nothing; full fidelity deferred).
 *
 * <p><b>Teardown (mining §8-20 — the world-exit teardown the block era forgot)</b>: EVERY layer's
 * {@link SecondaryFrameBuffer} is destroyed on client cleanup
 * ({@code IPCGlobal.CLIENT_CLEANUP_EVENT}: disconnect/world-exit) and on renderer switch-away
 * ({@code PortalRenderer.switchRenderer} calls {@link #onSwitchedAway()}), re-created lazily by
 * {@code prepare()} on next use.
 *
 * <p>SIGN NOTE, CORRECTED (IS5-REC): the old note here read "the deferred clear's depth constant is
 * 0.0 = reversed-Z FAR". THE REVERSED-Z PREMISE IS WRONG and was measured wrong by the IS5-XCUT arc
 * — at the draw, {@code clipDepthMode=NEGATIVE_ONE_TO_ONE} (39/39) and {@code range=[0,1]} (26/26),
 * i.e. small-is-near. {@code GlDevice} genuinely calls {@code glClipControl(LOWER_LEFT,
 * ZERO_TO_ONE)} — something, most plausibly iris, sets it back before our draws, so BYTECODE IS NOT
 * GROUND TRUTH HERE. The clear VALUE is unchanged (it is a belt behind a full overwrite); only the
 * rationale is corrected. The stamp's declared compare lives in {@link IrisCompatPaste}
 * (GREATER_THAN_OR_EQUAL, depth write on) — and what it ACTUALLY executes is an open question this
 * arc has deliberately NOT resolved from comments: re-measure it, do not infer it.
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

    /**
     * IS5-REC STAGE 2 — the deferred snapshot buffers, ONE PER PORTAL LAYER.
     *
     * <p>Was a single {@code SecondaryFrameBuffer}. The one-layer shape could share it because the
     * whole snapshot → loop → stamp → blit-back sequence ran once per frame; under recursion layer 2
     * would clobber layer 1's snapshot mid-frame, which is the structural blocker the handoff names.
     *
     * <p>Grown LAZILY by {@link #deferredFor(int)} rather than pre-sized to {@code maxPortalLayer+1}
     * (IP's {@code IrisPortalRenderer} pre-sizes; that renderer also allocates for depths it can no
     * longer reach on 26.2). At the committed default only index 0 is ever touched, so this allocates
     * exactly what the single field did — {@code deferredPeak} is the witness for that claim.
     *
     * <p>NOT a per-layer copy of IP's mechanism: IP masked each layer with a stencil blitted FBO→FBO
     * via {@code RenderTarget.frameBufferId}, which does not exist on 26.2 (those blocks are commented
     * out in {@code IrisPortalRenderer} :152/:210). The masking here stays the stencil-free D20 stamp.
     */
    private SecondaryFrameBuffer[] deferredBuffers = new SecondaryFrameBuffer[0];

    /**
     * Deepest layer whose nested pass actually COMMITTED a snapshot, THIS FRAME.
     *
     * <p>CORRECTED after adversarial review, and the correction matters because this gauge was
     * presented as the discriminator for "did the nested pass take its own snapshot, or did the
     * image come from somewhere else". The first version latched inside {@code deferredFor} — i.e.
     * on the mere ACT OF INDEXING, before {@code prepare()}, before the null bail, and before the
     * snapshot — and was never reset, so it was a session high-water mark. Two ways that lied:
     * it read 1 on frames that rendered no portal at all (visible in the fix leg's own tally: six
     * rows of {@code maxPortalDepth=0(destRenders=0) deferredPeak=1}), and it would have read 1 for
     * a nested pass that bailed at the null check having snapshotted nothing — the exact failure it
     * was supposed to catch.
     *
     * <p>Now set only AFTER the snapshot pair has run, and reset per frame, so
     * {@code deferredPeak=N} means "layer N really did snapshot and composite its own buffer this
     * frame" — which is the claim that was being made of it.
     */
    private int deferredPeak = 0;

    /**
     * The deferred snapshot buffer for {@code layer}, materialising it on first use.
     *
     * <p>Callers pass {@link PortalRendering#getPortalLayer()}. NOTE the two distinct reading points:
     * {@code onBeforeHandRendering} runs OUTSIDE any pushed layer (its own {@code isRendering()} guard
     * guarantees layer 0), while {@code doRenderPortal}'s stamp block runs POST-pop — so in both places
     * the value names the layer being composited INTO, not the layer whose content was just rendered.
     * That is the correct index in both cases and is 0 for both at the committed default.
     */
    private SecondaryFrameBuffer deferredFor(int layer) {
        if (layer >= deferredBuffers.length) {
            SecondaryFrameBuffer[] grown = new SecondaryFrameBuffer[layer + 1];
            System.arraycopy(deferredBuffers, 0, grown, 0, deferredBuffers.length);
            for (int i = deferredBuffers.length; i < grown.length; i++) {
                grown[i] = new SecondaryFrameBuffer();
            }
            deferredBuffers = grown;
        }
        return deferredBuffers[layer];
    }

    /** Called only once a layer's snapshot has actually been taken — see {@link #deferredPeak}. */
    private void noteDeferredCommitted(int layer) {
        if (layer > deferredPeak) {
            deferredPeak = layer;
        }
    }

    /** Census gauge accessor (see {@link #deferredPeak}). */
    public static int getDeferredPeak() {
        return Math.max(instance.deferredPeak, debugModeInstance.deferredPeak);
    }

    // IP-verbatim field (the held source's onBeforeTranslucentRendering capture); consumed by
    // the post-main workhorse — set every frame by the F1 AFTER_TRANSLUCENT_TERRAIN driver
    // BEFORE the anchor fires (both live inside the same renderLevel).
    private Matrix4f passingModelView = new Matrix4f();

    public final boolean isDebugMode;

    // D23 detector: non-zero exactly while THIS renderer's own renderPortals loop is on the stack
    // (the only context where the snapshot exists and the full-pipeline clobber is legal).
    //
    // IS5-REC STAGE 2 — WAS A BOOLEAN, AND THAT IS A LATENT DEFECT UNDER RECURSION, NOT A STYLE
    // POINT. With a nested portal pass the flag is ALREADY true when the inner pass runs; a boolean
    // cleared by the inner pass's finally would report FALSE for the OUTER loop's remaining portals,
    // sending them into the D23 layer-0 fallback (invokeWorldRendering :474) on a shaders-ON frame —
    // the exact configuration the TP-XDIM census measured as a whole-screen terrain vertex-transform
    // explosion on 272/272 rows. A depth COUNTER is the fix, and it is landed here, in the inert
    // stage, so the recursion leg does not have to attribute two changes at once.
    private int ownRenderPortalsDepth = 0;

    /** IS5-PH: set by invokeWorldRendering when a full-pipeline dest render ran this frame; consumed
     *  (once per frame) by onBeforeHandRendering's finally to fire the prev-uniform heal. */
    private boolean anyFullPipelineDestRendered = false;

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
     * IS5-PRE S3 — the FRAME-START entry ({@code migration/IS5_PRE_DESIGN.md} §1.1/§3.1), fired
     * by the IS0 anchor's shift=BEFORE sibling, only when the path flag is on. Owns the ONE
     * mechanism-wide ARM decision per frame (judge-mandated: no half-armed states — if any
     * precondition fails, the ENTIRE old path runs this frame, loudly once).
     *
     * <p><b>S3 skeleton state:</b> the arm decision structure + live witness only. The relocated
     * portal loop, the brackets (counter distant-offset, tracker save/restore, temporal guard,
     * weather), and the capture arming land in S4 — until then the decision is a permanent
     * fall-back-to-OLD with the reason printed, so a dev-lever launch is honest about what runs.
     */
    @Override
    public void ip_onFrameStartBeforeMainRender(Matrix4f unbobbedView) {
        // The anchor already gates on the static-final path flag; belt here for direct callers.
        if (!IPGlobal.STAGE_CONSISTENT_COMPOSITE) {
            return;
        }
        if (PortalRendering.isRendering()) {
            return; // never re-enter from a nested render
        }
        if (client.level == null || client.player == null) {
            return; // mid-packet frame class: skip, never assert
        }
        // THE ARM DECISION (design §3.1) — one mechanism-wide grant per frame; any failure means
        // the ENTIRE shipped old path runs at the post anchor, announced content-keyed.
        if (!IrisStageConsistentComposite.tryArmFrame(this.getClass().getSimpleName())) {
            return;
        }

        // ---- IS5-PRE FRAME-START LOOP (design §1.1) with its brackets --------------------------
        // §3.10 weather: the nested same-dim path's weatherRenderState.reset() is the one
        // shared-LRS mutation with no restore ("refilled by the next MAIN extract" — false at
        // frame start, where THIS frame's extract already ran and the main render consumes it
        // AFTER us). Save/restore the two column lists + scalars around the loop.
        var weather = client.gameRenderer.gameRenderState().levelRenderState.weatherRenderState;
        var savedRain = new java.util.ArrayList<>(weather.rainColumns);
        var savedSnow = new java.util.ArrayList<>(weather.snowColumns);
        float savedWeatherIntensity = weather.intensity;
        int savedWeatherRadius = weather.radius;

        // §3.7 counter bracket: nested binds record an unreachable lastFrame so the MAIN render
        // re-uploads every perFrame uniform after the loop (the same-dim starvation inversion).
        boolean counterBracketed = IrisStageConsistentComposite.counterBracketBegin();
        // §3.9 temporal guard: same save/restore machinery, new position — the loop now precedes
        // the main render, so the pollution it undoes would otherwise hit THIS frame's composites.
        boolean guardSaved = IrisTemporalTargetGuard.save();
        // §3.8 deliberately ABSENT: no prev-camera bracket — IrisDestPrevCamera corrects at every
        // guarded composite draw, ordering-independent (pre-registered live check on the first
        // leg; fallback named in the design). The IS5-PH heal is likewise suppressed in the
        // workhorse's query-only mode below — a post-frame re-tick here would zero the main
        // camera delta (the judged V4 trap).
        ownRenderPortalsDepth++;
        try {
            // IS5-FF suppressor, bracket moved with the loop (its premise inverts here: the main
            // shadowcomp dispatch now runs AFTER us and overwrites dest-seeded state anyway —
            // kept as the judged cheap belt for accumulating packs).
            IrisShadowCompositeSuppressor.install();
            // §3.2: bob product recomputed from iris's woven bobStack (witnessed at the post
            // anchor); feeds ONLY the dest-pose bob lock — stamp geometry resolves its matrices
            // at stamp time and never depends on this.
            Matrix4f bobbedView = IrisStageConsistentComposite.computeBobbedView(unbobbedView);
            IrisBobSync.deriveFramePose(bobbedView);
            renderPortals(bobbedView);
        } finally {
            ownRenderPortalsDepth--;
            IrisShadowCompositeSuppressor.uninstall();
            GL11.glDisable(GL_STENCIL_TEST); // belt parity with the workhorse's neutralize
            if (guardSaved) {
                IrisTemporalTargetGuard.restore();
            }
            if (counterBracketed) {
                IrisStageConsistentComposite.counterBracketEnd();
            }
            weather.rainColumns.clear();
            weather.rainColumns.addAll(savedRain);
            weather.snowColumns.clear();
            weather.snowColumns.addAll(savedSnow);
            weather.intensity = savedWeatherIntensity;
            weather.radius = savedWeatherRadius;
        }
    }

    /** IS5-PRE stamp-time matrix sources (design §3.2 note): the stamp at main renderAll HEAD
     *  reads THIS frame's post-mulLocal passingModelView (the F1 main-render fire is the last
     *  writer before that point) and the captured projection — never the frame-start values. */
    public static Matrix4f ip_stampModelViewOrNull() {
        IrisCompatOn262Renderer r = instance;
        if (r == null || r.passingModelView == null) return null;
        return new Matrix4f(r.passingModelView);
    }

    public static Matrix4f ip_stampProjectionOrNull() {
        Matrix4f p = getCurrentProjectionMatrix(); // protected static on the base
        return p == null ? null : new Matrix4f(p);
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
        // IS5-PRE (design §1.4): on a frame the frame-start loop already ran, this anchor's job
        // reduces to ISSUING occlusion queries against the finished frame's depth — the
        // visibility input the NEXT frame's loop consumes. No snapshot, no brackets, no blit:
        // the stamp already happened inside the main composite chain.
        if (IrisStageConsistentComposite.consumeFrameRanNewPath()) {
            // §3.2 witness: this modelView is the true post-mulLocal product — classify the
            // frame-start bob recompute against it (log-only, content-keyed).
            IrisStageConsistentComposite.bobWitnessPostAnchor(modelView);
            // §3.8: suppress the old-path IS5-PH heal — a post-frame re-tick after a
            // frame-start loop would yield previous==current==main(N), zeroing the main camera
            // delta (the judged V4 trap). DestPrevCamera's draw-time correction is the cover.
            anyFullPipelineDestRendered = false;
            for (Portal portal : getPortalsToRender(modelView)) {
                // Issue + shipped bookkeeping; the decision is deliberately discarded — it was
                // already consumed at frame start from LAST frame's query.
                testShouldRenderPortal(portal, modelView);
            }
            return;
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
        // IS5-HAND-STAGE A (lever-gated -Dseamlessportals.handStageDiff, DEFAULT OFF): the main
        // frame exactly as iris finalized it, before any compat touch. Stage B/C/D below.
        com.warwa.seamlessportals.render.SeamHandStageDiff.stageA(mainRT);
        // IS5-HAND-INLVL anchor (lever-gated -Dseamlessportals.handInLevelProbe, DEFAULT OFF):
        // closes the in-renderLevel stage points armed at the iris hand passes and emits the
        // five-stage block (handoff §00a instrument 1).
        com.warwa.seamlessportals.render.SeamHandInLevelProbe.anchor(mainRT);
        // IS5-HAND-LOC stage 2 (anchor): mainRT exactly as iris finalized it — the survival
        // table's "did the hand survive iris's own composite" row.
        com.warwa.seamlessportals.render.SeamHandLocator.anchor();
        // Stencil belt (anchor slot; §6 hazard row 8-2's raw-disable family).
        GL11.glDisable(GL_STENCIL_TEST);

        // IS5-REC STAGE 2: this method's own isRendering() guard above guarantees layer 0 here, so
        // deferredFor(0) resolves the same single buffer the pre-refactor field held. Read from the
        // layer stack rather than hardcoded so the Stage-3 nested entry point can share this body.
        final int passLayer = PortalRendering.getPortalLayer();
        // PER-FRAME RESET of the nesting-depth witness (see the deferredPeak javadoc). This method is
        // the frame's single layer-0 entry point for the compat pass, so resetting here is once per
        // frame by construction. Without it the gauge is a session high-water latch that keeps
        // reading 1 long after the player has looked away from every portal.
        deferredPeak = 0;
        final SecondaryFrameBuffer deferred = deferredFor(passLayer);

        // Deferred-buffer prepare — the auto-resize to the main RT runs BEFORE copyDepthFrom
        // (LOAD-BEARING ordering, port-note §1-E OQ5: copyDepthFrom copies DEST.width x
        // DEST.height and throws if either target lacks depth).
        deferred.prepare();
        if (deferred.fb == null || deferred.fb.getColorTextureView() == null) {
            return;
        }

        // Held-source G7 re-expression: device-clear the deferred target. Belt only — the snapshot
        // pair below overwrites the whole target, so this constant is never the value a stamp tests
        // against on any non-degenerate path.
        //
        // SIGN NOTE, CORRECTED (IS5-REC STAGE 2): the old comment read "depth = 0.0 (R5 reversed-Z
        // FAR)". The reversed-Z claim is one of the stale comments the IS5-XCUT arc measured as
        // WRONG — the draw reported clipDepthMode=NEGATIVE_ONE_TO_ONE (39/39) and range=[0,1]
        // (26/26), i.e. small-is-near. The VALUE is left at 0.0 deliberately and is NOT retuned
        // here: it is a belt behind a full overwrite, and changing a depth constant in the stage
        // whose whole claim is inertness would be exactly the two-variable move this project keeps
        // paying for. Only the false rationale is corrected.
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            deferred.fb.getColorTexture(), new Vector4f(1, 0, 0, 0),
            deferred.fb.getDepthTexture(), 0.0
        );

        // SNAPSHOT the finished main frame: depth via copyDepthFrom (replace; D19 — no stencil
        // bits, the stencil-free shape consumes none), color via the STRAIGHT-COPY pass
        // (blitAndBlendToTexture is ALPHA-BLEND — settled OQ5, never on this path).
        // IS5-COV geometry witness (always on, content-keyed; BOTH snapshot sites carry it):
        // copyDepthFrom is a 1:1 texel move only if both targets are the same size. If they are
        // not it must SCALE, and scaling a depth buffer dilates every silhouette by about a pixel
        // — exactly the measured occluder ring. A mismatch is a defect, so it may never be silent.
        com.warwa.seamlessportals.render.StampCoverageProbe
            .noteSnapshotGeometry(mainRT, deferred.fb);
        deferred.fb.copyDepthFrom(mainRT);
        IrisCompatPaste.drawStraightCopy(mainRT, deferred.fb);
        // IS5-COV alignment compare (lever-gated, log-only): read the SAME row out of BOTH targets
        // now that both halves of the snapshot have run, and print the same window from each. The
        // measured defect is a ONE-PIXEL disagreement between the depth edge and the colour edge in
        // `deferred`; this says whether the copy introduced it (aligned in main, skewed in
        // deferred) or whether it predates the snapshot entirely.
        com.warwa.seamlessportals.render.StampCoverageProbe
            .compareAfterSnapshot(mainRT, deferred.fb);
        // IS5-HAND-STAGE B: the snapshot as the compat pass preserved it.
        com.warwa.seamlessportals.render.SeamHandStageDiff.stageB(deferred.fb);

        CHelper.checkGlError();

        // IS5-P phantom fix (IrisTemporalTargetGuard): SAVE iris's clear=false temporal color targets
        // (Complementary's colortex2 TAA history, etc.) BEFORE the dest render pollutes them; RESTORE in the
        // finally after the blit-back. Kills the whole-screen phantom (LIVE-CONFIRMED via TAA-off) while
        // preserving the main view's TAA. Lever-gated (default-on), same-dim-scoped, throw-safe (never throws).
        boolean guardSaved = IrisTemporalTargetGuard.save();

        // IS-BOB: derive the per-frame bob+spin pose from the anchor's post-mulLocal copy — the
        // previously-IGNORED onBeforeHandRendering arg becomes load-bearing here (panel recon row
        // 3b). Per-FRAME derive; per-PORTAL apply = SecondaryWorldRenderCore Step-3b. The stencil
        // family's onBeforeHandRendering is the empty base body — shaders-OFF runs zero of this.
        IrisBobSync.deriveFramePose(modelView);

        // IS5-ACT gate probe — FRAME bracket open. MUST precede IrisShadowCompositeSuppressor
        // .install() below: after the install the MAIN pipeline's ShadowRenderer.compositeRenderer
        // is the no-op with an EMPTY passes list, and leg [2]'s roster would read a lie.
        // Log-only, default OFF (-Dseamlessportals.actProbe); never throws.
        com.warwa.seamlessportals.render.ActSeedProbe.beginFrame();

        // IS5-ACT heal retarget (LIVE-MEASURED): capture the pipeline the MAIN frame is using BEFORE
        // the portal loop. A cross-dim nested render calls preparePipeline() for the DEST dimension
        // and NOTHING restores iris's manager slot afterwards, so by the anchor-finally the slot holds
        // the DEST pipeline — ticking that one fed the DEST CameraPositionTracker the MAIN camera and
        // poisoned previousCameraPosition by ~132 blocks, killing the dest ACT flood-fill. Same-dim is
        // unaffected (the capture IS the object the old code resolved). Null when iris is absent.
        Object preLoopPipelineForHeal = IrisInterface.invoker.capturePipelineForHeal();

        ownRenderPortalsDepth++;
        try {
            // IS5-FF (§2h lava-light phantom): swap the MAIN pipeline's ShadowRenderer
            // .compositeRenderer to a no-op for the whole portal phase — the k nested per-portal
            // renderShadows calls must NOT re-run the pack's shadowcomp flood-fill with dest-seeded
            // voxel/camera state (same pipeline, same framemod2 => full overwrite of the persistent
            // ping-pong = the phantom). The main dispatch already ran (this anchor is post-main-
            // finalize); cross-dim dest pipelines are structurally untouched. FIRST statement of
            // the try + FIRST of the finally (verify-fold F1: nothing may throw while the noop is
            // installed, or the NEXT main frame's shadowcomp silently freezes).
            IrisShadowCompositeSuppressor.install();
            renderPortals(passingModelView);
        } finally {
            ownRenderPortalsDepth--;
            // IS5-FF: put the real shadowcomp composite back before anything else in the finally
            // can throw (the NEXT main frame's own renderShadows must dispatch normally) —
            // verify-fold F1 placement: ahead of the glDisable/blit-back/guard-restore/heal.
            IrisShadowCompositeSuppressor.uninstall();
            // anchor-state neutralize (the CrossPortalViewRendering:170 precedent — §6 row 8-2)
            GL11.glDisable(GL_STENCIL_TEST);

            // BLIT-BACK: the deferred buffer (main scene + the stamped portal views) → main,
            // full-screen straight copy. After this the main target is the pre-portal frame
            // plus portal-shaped dest views — whole-screen-dest-world here is the
            // pre-registered blit-order discriminator (design §1 IS1).
            // IN THE FINALLY (Fable-fold BLOCKER companion, port-note §2.5): the snapshot was
            // taken unconditionally above, so on a mid-loop throw this restores the composited
            // snapshot instead of leaving the last portal's raw dest render on the main target.
            IrisCompatPaste.drawStraightCopy(deferred.fb, mainRT);
            // IS5-HAND-STAGE D: mainRT after the blit-back — emits the four-stage diff block.
            com.warwa.seamlessportals.render.SeamHandStageDiff.stageD(mainRT);
            // IS5-HAND-LOC stage 3 (postBlit): the frame that ships — closes the survival
            // table and emits it.
            com.warwa.seamlessportals.render.SeamHandLocator.postBlit();

            // IS5-P phantom fix: undo the dest render's pollution of iris's persistent temporal targets
            // (byte-identical restore of the pre-dest history) — the phantom carrier the mainRT blit above
            // can never reach. AFTER the blit-back (which reads mainRT, not iris colortex). Throw-safe.
            if (guardSaved) {
                IrisTemporalTargetGuard.restore();
            }

            // IS5-PH prev-uniform heal (the ghost-terrain fix — see IrisInterface.healPreviousFrame
            // Uniforms javadoc): the nested dest renders ticked iris's frame notifier with the DEST
            // camera; ONE re-tick here (main camera restored) makes the next frame's natural tick
            // yield a clean MAIN-valued previousCameraPosition — otherwise Complementary's TAA
            // reprojection displaces the history by the portal offset = the camera-tracked ghost.
            // Gated on a full-pipeline dest render actually having run this frame (no-portal frames
            // byte-identical). Once per FRAME, not per portal. Throw-safe (facade never propagates).
            if (anyFullPipelineDestRendered) {
                anyFullPipelineDestRendered = false;
                IrisInterface.invoker.healPreviousFrameUniforms(preLoopPipelineForHeal);
            }

            // IS5-ACT gate probe — FRAME bracket close + the 1Hz single-call emit. Last statement
            // of the finally: strictly outside the verify-fold F1 ordering (uninstall / blit-back /
            // guard-restore / heal), and still runs on a mid-loop throw. Log-only; never throws.
            com.warwa.seamlessportals.render.ActSeedProbe.endFrame();
        }

        CHelper.checkGlError();
    }

    /**
     * IS5-REC — THE NESTED PER-LAYER PASS. Dispatched by
     * {@code SecondaryWorldRenderCore.maybeRunNestedPortalLayer} at the tail of a full-pipeline dest
     * render, where {@code client.level} and the camera are still DEST.
     *
     * <p>This is {@link #onBeforeHandRendering}'s PER-LAYER half and nothing else: snapshot the dest
     * frame the caller just produced, run this layer's portal loop (each portal's own dest render
     * clobbers the main target, then gets stamped back portal-shaped), and blit the composited
     * snapshot back to main so the OUTER layer's stamp reads a finished image.
     *
     * <p><b>Everything once-per-frame is deliberately ABSENT</b>, and that is what makes the
     * frame-scoped singletons safe under recursion rather than a stack-discipline problem:
     * <ul>
     *   <li>{@link IrisTemporalTargetGuard#save()} / restore — the outermost pass owns the TAA
     *       history save. An inner save would overwrite the layer-0 snapshot with already-polluted
     *       targets; an inner restore would hand the rest of the frame a dest-world history.</li>
     *   <li>{@link IrisShadowCompositeSuppressor} install/uninstall — an inner uninstall would
     *       un-suppress the pack's shadowcomp flood-fill while the OUTER layer still needs it
     *       suppressed, which is the IS5-FF lava-light phantom.</li>
     *   <li>The IS5-PH prev-uniform heal — once per FRAME with the MAIN camera restored, by
     *       construction. A per-layer heal would tick iris's frame notifier with a dest camera,
     *       which is the very poisoning the heal exists to undo.</li>
     *   <li>{@code IrisBobSync.deriveFramePose} and the ActSeedProbe frame bracket — both are
     *       frame-scoped by definition.</li>
     * </ul>
     * All four are still bracketed by the layer-0 {@code onBeforeHandRendering} that encloses this
     * call, so they remain correctly paired; this method simply never re-enters them.
     *
     * <p>{@code destDrawViewMatrix} is threaded straight into the portal loop and
     * {@code passingModelView} is NEVER touched — that field belongs to the frame's main pass, and
     * overwriting it here would corrupt the outer layer's cull frustum, aperture mesh, occlusion
     * query and stamp, all four of which read it.
     */
    @Override
    public void renderNestedPortalLayer(Matrix4f destDrawViewMatrix) {
        // IS5-PRE part3 scope cut, disclosed (design ledger): on an armed frame the nested-layer
        // machinery's three mainRT dereferences (snapshot source, stamp source, blit-back) point
        // at a target the outer view's content never reaches — the capture-to-capture re-aim is
        // part4. Until then the new path is single-layer; announced once, never silent.
        if (IrisStageConsistentComposite.isFrameArmed()) {
            IrisStageConsistentComposite.noteNestedLayerDeferred();
            return;
        }
        if (client.level == null || client.player == null) {
            return;
        }
        if (!IrisCompatPaste.arePipelinesReady()) {
            // Same reasoning as the anchor's gate: without working paste pipelines the snapshot
            // copy no-ops while each nested dest render still clobbers the main target — a
            // whole-screen dest world, i.e. a wrongly-discriminated corruption mode.
            return;
        }
        RenderTarget mainRT = client.gameRenderer.mainRenderTarget();
        if (mainRT == null
            || mainRT.getColorTextureView() == null
            || mainRT.getDepthTextureView() == null) {
            return;
        }

        final int layer = PortalRendering.getPortalLayer();
        final SecondaryFrameBuffer deferred = deferredFor(layer);

        deferred.prepare();
        if (deferred.fb == null || deferred.fb.getColorTextureView() == null) {
            return;
        }

        // SNAPSHOT this layer's finished dest frame — the same pair the anchor takes at layer 0.
        // The clear is the belt behind a full overwrite (see the class SIGN NOTE for why the old
        // reversed-Z rationale for this constant was wrong).
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            deferred.fb.getColorTexture(), new Vector4f(1, 0, 0, 0),
            deferred.fb.getDepthTexture(), 0.0
        );
        // IS5-COV geometry witness (always on, content-keyed; BOTH snapshot sites carry it):
        // copyDepthFrom is a 1:1 texel move only if both targets are the same size. If they are
        // not it must SCALE, and scaling a depth buffer dilates every silhouette by about a pixel
        // — exactly the measured occluder ring. A mismatch is a defect, so it may never be silent.
        com.warwa.seamlessportals.render.StampCoverageProbe
            .noteSnapshotGeometry(mainRT, deferred.fb);
        deferred.fb.copyDepthFrom(mainRT);
        IrisCompatPaste.drawStraightCopy(mainRT, deferred.fb);
        // IS5-COV alignment compare (lever-gated, log-only): read the SAME row out of BOTH targets
        // now that both halves of the snapshot have run, and print the same window from each. The
        // measured defect is a ONE-PIXEL disagreement between the depth edge and the colour edge in
        // `deferred`; this says whether the copy introduced it (aligned in main, skewed in
        // deferred) or whether it predates the snapshot entirely.
        com.warwa.seamlessportals.render.StampCoverageProbe
            .compareAfterSnapshot(mainRT, deferred.fb);
        // The snapshot is COMMITTED — only now does this layer count as having taken its own buffer.
        noteDeferredCommitted(layer);

        ownRenderPortalsDepth++;
        try {
            renderPortals(destDrawViewMatrix);
        } finally {
            ownRenderPortalsDepth--;
            GL11.glDisable(GL_STENCIL_TEST);
            // BLIT-BACK: main now holds this layer's scene WITH its nested portal windows stamped
            // in. The outer layer's stamp samples main's COLOUR, so this is what makes the nesting
            // visible. In the finally for the same reason the anchor's is: on a mid-loop throw this
            // restores the composited snapshot rather than leaving the last nested portal's raw dest
            // render filling the outer window.
            IrisCompatPaste.drawStraightCopy(deferred.fb, mainRT);
        }
    }

    /** §2.2-4 — one layer: occlusion test → push → full-pipeline content → pop → stamp. */
    protected void doRenderPortal(Portal portal, Matrix4f modelView) {
        // IS5-REC: WAS `if (PortalRendering.isRendering()) return;` — "this renderer only supports
        // one-layer portal (IP-verbatim)". That early-return was the entire cap, and THIS IS THE
        // ONLY RECURSION GUARD IN THE COMPAT PATH THAT MAY BE RELAXED.
        //
        // The other two — the F1 AFTER_TRANSLUCENT_TERRAIN and F2 BEFORE_TRANSLUCENT_TERRAIN Fabric
        // listeners — also early-return on isRendering(), and theirs are load-bearing: the nested
        // full-pipeline render RE-FIRES both events. MEASURED on the c493fb2 baseline, shaders-ON:
        // f1=YES:flagON(fired=2 reentrantSkips=1), exactly one skip per nested render, versus
        // fired=1 reentrantSkips=0 shaders-OFF where the decomposed path never re-fires them.
        // Relaxing those would run a second, unindexed portal dispatch inside every nested render
        // and overwrite passingModelView with the dest pose. Do not "tidy" them to match this one.
        //
        // At the default irisMaxPortalLayer=2 this expression admits layers 0 and 1 and rejects 2+.
        // With -PirisMaxPortalLayer=1 it is identical to the old isRendering() form, which is what
        // makes that lever an exact behavioural repro independent of the disable lever.
        //
        // THE `layer > 0` CLAUSE IS NOT REDUNDANT — it restores a legal config the bare `>=` broke.
        // maxPortalLayer=0 is a supported value with defined meaning (PortalRenderer:216-219: "if max
        // portal layer is 0, the invisible portals will be force rendered"). The OLD guard was
        // isRendering(), which is FALSE at layer 0, so layer 0 ALWAYS proceeded and the depth cut was
        // made downstream by renderPortalContent's `getPortalLayer() > getMaxPortalLayer()` — i.e.
        // the aperture and the occlusion query still ran, only the CONTENT was skipped. A bare
        // `0 >= 0` here returns before any of that, silently changing what maxPortalLayer=0 does.
        // Keeping layer 0 unconditional preserves the old behaviour exactly at every bound value.
        final int curLayer = PortalRendering.getPortalLayer();
        if (curLayer > 0 && curLayer >= IPGlobal.effectiveIrisMaxPortalLayer()) {
            return;
        }

        // IS5-REC — THE PER-FRAME PACK-SHADED RENDER BUDGET, ENFORCED IN THE LOOP.
        // The dispatch-site check in maybeRunNestedPortalLayer is an ENTRY gate: it runs once, before
        // the nested pass, so it bounds how many nested LOOPS are entered but not how many renders
        // each one issues. One layer-0 portal whose destination room holds N portals would enter the
        // loop at count=1 (under budget) and then issue N more full gbuffer+shadow+composite renders
        // with nothing re-reading the budget. The only in-loop cap was portalRenderLimit=200, which
        // is a different order of magnitude from what a pack-shaded render costs.
        // Gated to layer >= 1 so layer 0 keeps its current unbudgeted behaviour byte-for-byte — this
        // belt exists for the recursion this arc added, not to retune the shipped one-layer path.
        if (curLayer > 0
            && RenderStates.getRenderedPortalNum() >= IPGlobal.irisMaxDestRenders
        ) {
            IPGlobal.noteNestedBudgetCut();
            return;
        }

        // IS5-PRE fork (a): on an armed frame the visibility DECISION is consume-only (last
        // frame's query + the capped render-if-unknown default) — the query DRAW must never run
        // at frame start, where the main depth is cleared (V5: not stale, DESTROYED). The issue
        // path lives in the post anchor's query-only mode.
        if (IrisStageConsistentComposite.isFrameArmed()) {
            if (!IrisStageConsistentComposite.consumeVisibilityForArmedFrame(portal)) {
                return;
            }
        }
        else if (!testShouldRenderPortal(portal, modelView)) {
            return;
        }

        // IS5-PRE fork (b): arm the capture for this view — consumed by exactly one nested
        // finalize (the capture mixin). No slot ⇒ skip the view entirely: a view rendered with
        // no consumer would land its composites in the main target for nothing.
        boolean is5PreArmedView = false;
        if (IrisStageConsistentComposite.isFrameArmed() && !isDebugMode) {
            is5PreArmedView = IrisStageConsistentComposite.armCaptureForView(
                portal, modelView, getCurrentProjectionMatrix(), PortalRendering.getPortalLayer()
            );
            if (!is5PreArmedView) {
                return;
            }
        }

        // IS5-PRE §3.12: the bloom-mask arm is CALL-SITE-gated on the armed frame (mask
        // internals byte-identical — 4b913a5 undisturbed). With dest composites cancelled the
        // mask has no consumer; the structural fix (real window content under the main gather)
        // supersedes it on this path.
        if (!isDebugMode && !IrisStageConsistentComposite.isFrameArmed()
            && IPGlobal.isIrisBloomApertureMaskActive()) {
            // C3-BLOOM (§2f): arm the aperture mask for this portal's nested dest composite
            // chain — consumed inside iris's CompositeRenderer.renderAll (the mixin seam), which
            // masks colortex0 to the aperture footprint after its last writer and before the
            // bloom-tile gather. Same matrix/camera row the stamp uses (IrisCompatPaste
            // stampPortalArea args): the passing modelView + the layer-0 draw projection (we are
            // PRE-push here, so getCurrentProjectionMatrix() is the same unscaled value the
            // stamp reads post-pop — scaled portals included by construction) + the current
            // camera pos + partialTick. isDebugMode excluded: the debug instance's full-screen
            // raw view must stay unmasked.
            IrisBloomApertureMask.arm(
                portal, new Matrix4f(modelView), new Matrix4f(getCurrentProjectionMatrix()),
                CHelper.getCurrentCameraPos(), RenderStates.getPartialTick()
            );
        }

        // IS5-MB: RETAINED CALL, NOW A NO-OP. The correction is keyed on the camera each composite
        // chain carries, not on any portal bracket, so it needs no arm point — and that is the whole
        // reason it works: the IS5-CEN census measured the smearing chain running OUTSIDE this bracket
        // (labelled win=MAIN layer=0 while holding the DEST camera), which is exactly why the previous
        // bracket-keyed implementation recorded the wrong camera and never fired on the guilty bind.
        // The call stays as the documented hook point should a future mechanism need portal context.
        // NOTE the old "SAME-DIM ONLY, arm() excludes cross-dim" contract is GONE: the correction now
        // applies to every guarded composite bind. Cross-dim was measured already clean
        // (|cam-prev|=0.000 on its own per-dimension pipeline), so there it matches its own camera and
        // writes back what iris already had.
        IrisDestPrevCamera.arm(portal);

        // IS5-RC: the run self-identification block, at the first portal dest render — the moment that
        // proves portals (and, shaders-ON, the iris compat path) are actually live. Always on,
        // once-only. Three live rounds of this arc were voided because a lever silently never reached
        // the JVM and the log had no way to say so.
        com.warwa.seamlessportals.render.RunConfigReport.noteArmedFrame();

        // IS5-CEN: label census rows with the portal window they fall in. Deliberately the SAME WIDE
        // bracket (pre-push .. post-pop) that IS5-MB uses, and NOT the pushed portal layer: the
        // smearing composite4 was MEASURED firing outside the pushed layer (|cam-prev|=204.175 on the
        // main-chain control row while the in-layer pass read 0.000), so a layer-only label would file
        // it as MAIN. Both labels are printed on every row; whichever bracket the guilty bind falls
        // in, the census says which. Byte-inert without -Dseamlessportals.compositeCensus.
        // The same-dim test is computed INSIDE armWindow, not here: an expression at this call site
        // would be evaluated on every portal dest render regardless of the census lever (so the
        // byte-inert claim above would be false), and it sits between two arm() calls and the
        // try/finally below — a throw here would strand both arms with no disarm.
        com.warwa.seamlessportals.render.IrisCompositeCensus.armWindow(portal);

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
            // layer) — their early-returns are the load-bearing recursion guards (§2.4-7).
            renderPortalContent(portal);
        } finally {
            PortalRendering.popPortalLayer();
            // C3-BLOOM: once-only WARN + miss-counter if armed-but-never-consumed (mixin
            // dormant after an iris update, ineligible pack shape, plan disarm). Unconditional
            // + throw-safe: the arm must never outlive its portal window. The stamp below runs
            // after this and needs nothing from the armed state.
            IrisBloomApertureMask.disarmAndReport();
            // IS5-MB: same bracket, same discipline — the arm must never outlive its portal window.
            IrisDestPrevCamera.disarmAndReport();
            // IS5-CEN: same bracket, same discipline — an arm that outlived its window would label
            // every subsequent MAIN-chain bind as DEST, i.e. it would fabricate the exact finding the
            // census exists to test for.
            com.warwa.seamlessportals.render.IrisCompositeCensus.disarmWindow();
        }

        // IS5-PRE fork (c): on an armed view the capture (taken at the nested finalize) IS this
        // view's output — the stamp happens at the MAIN chain's renderAll HEAD, against
        // unfiltered content. The entire old post-pop stamp block (clamp bracket, snapshot-side
        // probes, stampPortalArea into the deferred buffer) is the OLD path's; running it here
        // would dereference a snapshot that was never taken this frame.
        if (is5PreArmedView) {
            return;
        }

        // IS5-REC STAGE 2: resolve the deferred buffer we are compositing INTO. We are POST-pop
        // here, so getPortalLayer() names the OUTER layer — the one whose snapshot this portal's
        // view gets stamped into — not the layer whose content was just rendered. At layer 0 this is
        // the same buffer the pre-refactor field held.
        final int stampLayer = PortalRendering.getPortalLayer();
        final SecondaryFrameBuffer stampTarget = deferredFor(stampLayer);

        // IS5-G ghost-wave discriminator run 2: the dedicated stamp-clamp lever skips ONLY this
        // bracket (the aperture/occlusion-query draws keep their own clamp), splitting sub-cause
        // (b) clamp-wedge-overreach from (a)/(b') — ghost shrinking with the clamp off = (b).
        if (!IPGlobal.debugNoStampDepthClamp) {
            CHelper.enableDepthClamp();
        }

        // IS5-SEAM-CONTENT probe (lever-gated -Dseamlessportals.seamContentProbe, DEFAULT OFF;
        // 1 Hz, only inside the crossing window): mainRT holds the FINISHED dest frame here —
        // read the center column's color+depth to classify the band pixels (void vs painted
        // black). IS5-HAND extension: also samples the HAND screen region's depth in the
        // SNAPSHOT (deferred) buffer — the stamp-over-hand fix needs the hand slice's measured
        // depth values. Self-disarming; never throws into the pass.
        // IS5-REC: LAYER 0 ONLY. This is a frame-scoped, self-disarming 1 Hz probe built to answer a
        // question about the OUTER seam window. Letting nested layers call it would let a deeper
        // layer consume the 1 Hz slot and file its sample under the outer window's identity — the
        // probe would keep printing, which is exactly why it would be believed.
        if (stampLayer == 0) {
            com.warwa.seamlessportals.render.SeamDestContentProbe.sample(
                portal, client.gameRenderer.mainRenderTarget(), stampTarget.fb);
        }

        if (!isDebugMode) {
            // THE STAMP (D20): portal-shaped copy main→deferred, snapshot-depth-tested.
            // Matrices per the held source: the passing model view + the live layer-0 draw
            // projection (we are OUTSIDE the pushed layer again — scaling back to 1).
            IrisCompatPaste.stampPortalArea(
                portal,
                client.gameRenderer.mainRenderTarget(),
                stampTarget.fb,
                modelView,
                getCurrentProjectionMatrix()
            );
        }
        else {
            // debugModeInstance (§2.2 member walk): full-screen RAW view of the nested render —
            // the live-round diagnostic that splits "render() produced nothing" from "stamp/copy
            // defect" (design §1 IS1 discriminators).
            IrisCompatPaste.drawStraightCopy(
                client.gameRenderer.mainRenderTarget(), stampTarget.fb
            );
        }

        if (!IPGlobal.debugNoStampDepthClamp) {
            CHelper.disableDepthClamp();
        }

        // IS5-HAND-STAGE C: the deferred buffer after this portal's stamp (last capture wins).
        // IS5-REC: LAYER 0 ONLY — stage C is one slot in a FOUR-STAGE frame diff (A anchor, B
        // snapshot, C post-stamp, D post-blit) whose other three are emitted once per frame by the
        // anchor. A nested layer writing C would silently pair a depth-N capture with depth-0's
        // A/B/D and print a diff of two different images as if it were one frame's evolution.
        if (stampLayer == 0) {
            com.warwa.seamlessportals.render.SeamHandStageDiff.stageC(stampTarget.fb);
        }

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
        if (ownRenderPortalsDepth == 0) {
            // ===== D23 layer-0, SPLIT BY CALLER (TP-XDIM, 2026-07-28) ============================
            // The original D23 comment wrote ONE rule for TWO callers whose contexts are OPPOSITES.
            //
            //  * CrossPortalViewRendering REPLACES vanilla renderLevel for the WHOLE frame
            //    (seamlessportals$redirectRenderingWorld returns without calling original). The main
            //    render target is the REAL one and NOTHING has been drawn into it yet — there is no
            //    snapshot to protect and no mid-frame clobber to avoid, because there is no main
            //    frame. Painting the whole main target IS the assignment. This is IP's OWN
            //    architecture: IP's cross-view invoke body was client.gameRenderer.renderLevel(...)
            //    (translation recorded at MyGameRenderer.java:68-70), which is precisely why iris's
            //    per-frame gbuffer envelope — its @Inject at LevelRenderer.render HEAD, plus the
            //    separate isRenderingLevel toggle that gates the terrain vertex-FORMAT remap —
            //    re-entered naturally under IP. The 26.2 decomposed driver never calls
            //    LevelRenderer.render, so on a cross-view frame NONE of it runs while the pack's
            //    gbuffer PROGRAMS still substitute (that substitution is gated on the pipeline
            //    being non-null, not on isRenderingLevel). MEASURED consequence (census bdd8b62,
            //    272/272 rows): a whole-screen terrain vertex-transform explosion with the sky
            //    intact and no GL errors. Restoring the 8-arg render() here is a REWRITE TO MATCH
            //    IP, not a compensation.
            //
            //  * GuiPortalRendering does NOT qualify: it swaps mainRenderTarget() to a caller FBO
            //    (ip_setFrameBuffer, hard-asserted != main at GuiPortalRendering:80-84) and runs at
            //    frame TAIL, AFTER iris finalized the real frame. A second begin/finalize
            //    LevelRendering there would re-run the pack's composite chain over the shipped
            //    frame. It KEEPS the decomposed fallback, byte-identical.
            //
            // DELIBERATE OMISSIONS vs the ownRenderPortalsDepth>0 branch below. NOTE (XWIN,
            // 2026-07-29): onBeforeHandRendering DOES now run on a cross-view frame — not from the
            // IS0 anchor (which still cannot fire; it injects inside GameRenderer.renderLevel), but
            // from SecondaryWorldRenderCore.maybeRunCrossViewPortalPass, the full-pipeline twin of
            // the decomposed core's Step 10.10, dispatched AFTER this render() returns. The
            // omissions below all still stand, on the restated ground that THIS dest render IS the
            // frame's main render and the pass that treats it as such runs after it:
            //   * anyFullPipelineDestRendered NOT set here: the prev-uniform heal is armed by the
            //     WINDOW's own nested invoke (the branch below) and consumed by the same
            //     onBeforeHandRendering invocation's finally, so the flag can no longer be stranded
            //     into the next frame — that hazard is CLOSED by XWIN, not opened.
            //   * IrisTemporalTargetGuard.clearForDestPass() NOT called: the guard never save()d
            //     this frame, and this render IS the main view — zeroing its own TAA history would
            //     be a self-inflicted ghost.
            //   * IrisShadowCompositeSuppressor NOT installed: it protects a MAIN frame's
            //     shadowcomp dispatch. There is no main frame; this render's shadowcomp is the
            //     frame's only dispatch and MUST run normally.
            //   * bumpPerFrameUniformCounter NOT called: IS5-L exists because a NESTED dest render
            //     reuses programs a MAIN pass earlier in the SAME frame already uploaded PER_FRAME
            //     uniforms for. This render is the frame's FIRST and ONLY level render — exactly
            //     like a normal frame's main pass, which gets no bump. Parity with a normal frame.
            //     (Pre-registered discriminator if the cross view comes out mis-lit.)
            // "Bare" IS the fidelity claim here: this is what a normal frame does.
            if (!IPGlobal.CROSS_VIEW_FULL_PIPELINE_DISABLED_LEVER
                && CrossPortalViewRendering.isRenderingCrossPortalView()
            ) {
                com.warwa.seamlessportals.render.TpXdimFrameCensus.noteInvokeWorldRendering(3);
                // ORDER IS LOAD-BEARING: increment BEFORE noteArmedFrame. The run-config block is
                // emitted once, synchronously, by that call — incrementing after it would print
                // "crossViewFullPipelineCount = 0" in the very block whose job is to prove the
                // route was taken, i.e. a running counter read as "never happened" (the
                // failure-sentinel-tabulated-as-a-value trap this project has already paid for).
                IPGlobal.crossViewFullPipelineCount++;
                // IS5-RC: the first proof the fix route is live. doRenderPortal's noteArmedFrame
                // never fires on a cross-view frame, so without this the run-config block would
                // wait for its 600-frame fallback — three earlier legs of this project were voided
                // for want of exactly that self-report.
                com.warwa.seamlessportals.render.RunConfigReport.noteArmedFrame();
                MyGameRenderer.renderWorldFullPipeline(worldRenderInfo);
                return;
            }
            // GuiPortalRendering, an unknown future layer-0 caller, or the
            // -PdisableCrossViewFullPipeline A/B leg — the pre-fix path, unchanged, which
            // reproduces the explosion byte-for-byte.
            // TP-XDIM census: BOTH branches of this method are instrumented, not just the one the
            // working hypothesis predicts — a census that could only ever print D23-FALLBACK could
            // not falsify the hypothesis it exists to test.
            com.warwa.seamlessportals.render.TpXdimFrameCensus.noteInvokeWorldRendering(0);
            MyGameRenderer.renderWorldNew(worldRenderInfo, Runnable::run);
            return;
        }
        com.warwa.seamlessportals.render.TpXdimFrameCensus.noteInvokeWorldRendering(1);
        // IS5-PH gate: a full-pipeline dest render is about to run this frame — arm the once-per-
        // frame prev-uniform heal in onBeforeHandRendering's finally (see there).
        anyFullPipelineDestRendered = true;
        // IS5-L in-portal-fullbright fix: bump iris's per-frame uniform counter BEFORE + AFTER the nested dest
        // render so iris re-uploads the dest pass's PER_FRAME lighting uniforms from the (already dest-primed)
        // sources — otherwise the reused same-dim programs skip the re-upload and the dest terrain is lit with
        // the MAIN camera's uniforms (the direction-dependent fullbright). Facade is a no-op when iris is
        // absent / the lever is off. The after-bump re-freshens the post-anchor hand/GUI programs. This is the
        // full-pipeline (ownRenderPortalsDepth>0) dest path only. IP precedent: ExperimentalIrisPortalRenderer.
        IrisInterface.invoker.bumpPerFrameUniformCounter();
        // IS5-G — the dest-pass TAA-history NEUTRALIZATION (the "ghost terrain" fix): clear the
        // guard-SAVED persistent history targets to zero so THIS portal's nested composite reads a
        // neutral history instead of the MAIN view's source frames (the live-proven ghost carrier;
        // the mirror direction of the phantom the guard's save/restore fixed). Per-portal by design:
        // the pack's composite chain writes each dest frame back into the history, so a once-per-
        // frame clear would hand portal 2 portal 1's frame. restore() later returns the main history
        // byte-whole. Cross-dim portals also reach here: the clear touches the MAIN pipeline's saved
        // targets (restored later) while the dest reads its own per-dim pipeline — wasted-but-
        // harmless; counts include cross-dim portals. No-op unless the guard saved this frame.
        IrisTemporalTargetGuard.clearForDestPass();
        try {
            MyGameRenderer.renderWorldFullPipeline(worldRenderInfo);
        }
        finally {
            IrisInterface.invoker.bumpPerFrameUniformCounter();
        }
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
        // portals — inherited one-layer-era shape, faithful to the held IP source). Wrong
        // show/hide decisions possible for portals 2+; pre-registered discriminator: two portals
        // side-by-side, the second window shows the static snapshot scene. IS2+ candidate fix:
        // query against the deferred buffer's snapshot depth.
        // #13 UPDATE (2026-07-21): the SEPARATE "second portal paints ON TOP of the first" bug was
        // the STAMP's missing depth write (IrisCompatPaste PORTAL_AREA_SAMPLE had writeDepth=false
        // — the port had dropped IP's _depthMask(true)); FIXED by restoring the write so the near
        // portal's plane depth GEQUAL-rejects the far stamp. This QUERY-vs-stale-main-depth issue
        // is the DISTINCT still-latent item (wrong show/hide, NOT paint-over) and remains a candidate.
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

        // IS5-ACT census: the LISTED count (pre-occlusion). Splits "no portal on screen" from
        // "every portal occlusion-rejected" in the watchdog. Log-only; never throws.
        com.warwa.seamlessportals.render.ActSeedProbe.onPortalListSize(portalsToRender.size());

        for (Portal portal : portalsToRender) {
            doRenderPortal(portal, modelView);
        }
    }

    /**
     * Mining §8-20: destroy the deferred buffers (re-created lazily by {@code prepare()} on next
     * use). IS5-REC STAGE 2: loops EVERY layer's buffer. Missing one here would leak a full-screen
     * colour+depth target per unfreed layer across every world exit and renderer switch-away — the
     * §8-20 class of defect this teardown exists to close, reintroduced by the array.
     */
    public void teardown() {
        for (SecondaryFrameBuffer buf : deferredBuffers) {
            if (buf != null && buf.fb != null) {
                try {
                    buf.fb.destroyBuffers();
                } catch (Throwable t) {
                    // disposal is best-effort
                }
                buf.fb = null;
            }
        }
        // Drop the array itself so a later session re-grows from empty; deferredPeak is deliberately
        // NOT reset — it is a session-scoped high-water witness for the census.
        deferredBuffers = new SecondaryFrameBuffer[0];
        // IS5-P phantom fix: free the static scratch textures (idempotent — shared by both instances).
        try {
            IrisTemporalTargetGuard.teardown();
        } catch (Throwable t) {
            // disposal is best-effort
        }
        // C3-BLOOM: free the mask GL program + scratch texture + cached mask GlFramebuffers
        // (idempotent — shared statics, double-called via onSwitchedAway; re-created lazily).
        try {
            IrisBloomApertureMask.teardown();
        } catch (Throwable t) {
            // disposal is best-effort
        }
        // IS5-MB: drop the per-portal previous-camera records + the location cache (idempotent).
        try {
            IrisDestPrevCamera.teardown();
        } catch (Throwable t) {
            // disposal is best-effort
        }
        // IS5-CEN: drop the program-id roster. A pack reload mints NEW program ids, so a retained
        // roster could alias a stale id onto an unrelated pass and mislabel every census row.
        try {
            com.warwa.seamlessportals.render.IrisCompositeCensus.teardown();
        } catch (Throwable t) {
            // disposal is best-effort
        }
    }

    /** Called by {@code PortalRenderer.switchRenderer} when routing AWAY from this family. */
    public static void onSwitchedAway() {
        instance.teardown();
        debugModeInstance.teardown();
    }
}
