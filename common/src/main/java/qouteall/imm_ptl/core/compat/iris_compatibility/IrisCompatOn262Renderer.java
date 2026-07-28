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

    private final SecondaryFrameBuffer deferredBuffer = new SecondaryFrameBuffer();

    // IP-verbatim field (the held source's onBeforeTranslucentRendering capture); consumed by
    // the post-main workhorse — set every frame by the F1 AFTER_TRANSLUCENT_TERRAIN driver
    // BEFORE the anchor fires (both live inside the same renderLevel).
    private Matrix4f passingModelView = new Matrix4f();

    public final boolean isDebugMode;

    // D23 detector: true exactly while THIS renderer's own renderPortals loop is on the stack
    // (the only context where the snapshot exists and the full-pipeline clobber is legal).
    private boolean isInsideOwnRenderPortals = false;

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

        // Deferred-buffer prepare — the auto-resize to the main RT runs BEFORE copyDepthFrom
        // (LOAD-BEARING ordering, port-note §1-E OQ5: copyDepthFrom copies DEST.width x
        // DEST.height and throws if either target lacks depth).
        deferredBuffer.prepare();
        if (deferredBuffer.fb == null || deferredBuffer.fb.getColorTextureView() == null) {
            return;
        }

        // Held-source G7 re-expression: device-clear the deferred target; depth = 0.0
        // (R5 reversed-Z FAR). Belt only — the snapshot pair below overwrites the whole target.
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
            deferredBuffer.fb.getColorTexture(), new Vector4f(1, 0, 0, 0),
            deferredBuffer.fb.getDepthTexture(), 0.0
        );

        // SNAPSHOT the finished main frame: depth via copyDepthFrom (replace; D19 — no stencil
        // bits, the stencil-free shape consumes none), color via the STRAIGHT-COPY pass
        // (blitAndBlendToTexture is ALPHA-BLEND — settled OQ5, never on this path).
        deferredBuffer.fb.copyDepthFrom(mainRT);
        IrisCompatPaste.drawStraightCopy(mainRT, deferredBuffer.fb);

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

        isInsideOwnRenderPortals = true;
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
            isInsideOwnRenderPortals = false;
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
            IrisCompatPaste.drawStraightCopy(deferredBuffer.fb, mainRT);

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

    /** §2.2-4 — one layer: occlusion test → push → full-pipeline content → pop → stamp. */
    protected void doRenderPortal(Portal portal, Matrix4f modelView) {
        if (PortalRendering.isRendering()) {
            // this renderer only supports one-layer portal (IP-verbatim)
            return;
        }

        if (!testShouldRenderPortal(portal, modelView)) {
            return;
        }

        if (!isDebugMode && IPGlobal.isIrisBloomApertureMaskActive()) {
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

        // IS5-G ghost-wave discriminator run 2: the dedicated stamp-clamp lever skips ONLY this
        // bracket (the aperture/occlusion-query draws keep their own clamp), splitting sub-cause
        // (b) clamp-wedge-overreach from (a)/(b') — ghost shrinking with the clamp off = (b).
        if (!IPGlobal.debugNoStampDepthClamp) {
            CHelper.enableDepthClamp();
        }

        // IS5-SEAM-CONTENT probe (lever-gated -Dseamlessportals.seamContentProbe, DEFAULT OFF;
        // 1 Hz, only inside the crossing window): mainRT holds the FINISHED dest frame here —
        // read the center column's color+depth to classify the band pixels (void vs painted
        // black). Self-disarming; never throws into the pass.
        com.warwa.seamlessportals.render.SeamDestContentProbe.sample(
            portal, client.gameRenderer.mainRenderTarget());

        if (!isDebugMode) {
            // THE STAMP (D20): portal-shaped copy main→deferred, snapshot-depth-tested.
            // Matrices per the held source: the passing model view + the live layer-0 draw
            // projection (we are OUTSIDE the pushed layer again — scaling back to 1).
            IrisCompatPaste.stampPortalArea(
                portal,
                client.gameRenderer.mainRenderTarget(),
                deferredBuffer.fb,
                modelView,
                getCurrentProjectionMatrix()
            );
        }
        else {
            // debugModeInstance (§2.2 member walk): full-screen RAW view of the nested render —
            // the live-round diagnostic that splits "render() produced nothing" from "stamp/copy
            // defect" (design §1 IS1 discriminators).
            IrisCompatPaste.drawStraightCopy(
                client.gameRenderer.mainRenderTarget(), deferredBuffer.fb
            );
        }

        if (!IPGlobal.debugNoStampDepthClamp) {
            CHelper.disableDepthClamp();
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
        if (!isInsideOwnRenderPortals) {
            // D23: a layer-0 invocation (CrossPortalViewRendering:158-171 / GuiPortalRendering
            // call prepare/invoke/finish directly) — NO snapshot context exists and a
            // full-pipeline render would clobber the main target mid-frame. Fall back to the
            // decomposed driver (strictly better than nothing; full fidelity deferred).
            MyGameRenderer.renderWorldNew(worldRenderInfo, Runnable::run);
            return;
        }
        // IS5-PH gate: a full-pipeline dest render is about to run this frame — arm the once-per-
        // frame prev-uniform heal in onBeforeHandRendering's finally (see there).
        anyFullPipelineDestRendered = true;
        // IS5-L in-portal-fullbright fix: bump iris's per-frame uniform counter BEFORE + AFTER the nested dest
        // render so iris re-uploads the dest pass's PER_FRAME lighting uniforms from the (already dest-primed)
        // sources — otherwise the reused same-dim programs skip the re-upload and the dest terrain is lit with
        // the MAIN camera's uniforms (the direction-dependent fullbright). Facade is a no-op when iris is
        // absent / the lever is off. The after-bump re-freshens the post-anchor hand/GUI programs. This is the
        // full-pipeline (isInsideOwnRenderPortals==true) dest path only. IP precedent: ExperimentalIrisPortalRenderer.
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

    /** Mining §8-20: destroy the deferred buffer (re-created lazily by prepare() on next use). */
    public void teardown() {
        if (deferredBuffer.fb != null) {
            try {
                deferredBuffer.fb.destroyBuffers();
            } catch (Throwable t) {
                // disposal is best-effort
            }
            deferredBuffer.fb = null;
        }
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
