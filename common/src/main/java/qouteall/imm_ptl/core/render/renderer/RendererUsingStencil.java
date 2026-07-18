package qouteall.imm_ptl.core.render.renderer;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import qouteall.q_misc_util.Helper;
import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.IPPortingLibCompat;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.ViewAreaRenderer;
import qouteall.imm_ptl.core.render.context_management.FogRendererContext;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.util.List;

import static org.lwjgl.opengl.GL11.GL_ALWAYS;
import static org.lwjgl.opengl.GL11.GL_DEPTH_FUNC;
import static org.lwjgl.opengl.GL11.GL_EQUAL;
import static org.lwjgl.opengl.GL11.GL_INCR;
import static org.lwjgl.opengl.GL11.GL_KEEP;
import static org.lwjgl.opengl.GL11.GL_LESS;
import static org.lwjgl.opengl.GL11.GL_REPLACE;
import static org.lwjgl.opengl.GL11.GL_STENCIL_TEST;

// S12-A (U10 first half) port disposition: NEW — the CONCRETE stencil renderer, the R5 reversed-Z heart
// of the atomic cutover. Ported VERBATIM from IP:render/renderer/RendererUsingStencil.java to its
// verbatim qouteall path; pinned by IPCGlobal.rendererUsingStencil (:17). Held/inert until S13 (SCC
// closure); never loaded flag-OFF, so the pipelines/substrate it consumes are only touched device-ready
// at S13+.
//
// ===== R5 REVERSED-Z CHECKLIST EXECUTED HERE (CUTOVER_SPEC §2.1, the 16-row op-by-op audit) =====
// Every depth/stencil constant below carries an inline "R5 Row N" derivation citing the CUTOVER_SPEC
// row. 26.2 reversed-Z ground truth (the derivation basis): depth CLEAR 0.0 = FAR; default depth COMPARE
// GREATER_THAN_OR_EQUAL; glDepthRange FAR = (0,0) / NEAR = (1,1) / default (0,1); GL_ALWAYS + all
// stencil ops/funcs are direction-INDEPENDENT (no flip). The ONE flip that lands as a raw constant in
// THIS class is Row 7 (clearDepthOfThePortalViewArea: glDepthRange(1,1) -> glDepthRange(0,0)); Row 4's
// LEQUAL->GEQUAL compare flip lives in the mesh pipeline (MyRenderHelper.getPortalAreaRenderType, already
// authored S11-B) that ViewAreaRenderer selects, not as a constant here. The mod's LIVE
// StencilPortalRenderer (runtime-proven, memory stencil-direct-rework-status) carries the same flipped
// values (StencilPortalRenderer:401 glDepthRange(0,0) FAR clear); this port transplants that proven
// substrate into the qouteall class.
//
// 26.2 RE-EXPRESSIONS applied (each api-map-sanctioned; NO IP logic deviated). The raw GL11 stencil/depth
// calls port VERBATIM — the KEEP substrate (GlBackendMixin/GlConstMixin/RenderTargetMixin/
// GlStateManagerMixin + StencilState) makes raw-GL stencil PERSIST through the vanilla pipeline draws
// (GlCommandEncoder.applyPipelineState never touches stencil; MOD:StencilPortalRenderer proves the exact
// GL11.glStencilFunc/Op/Mask/glDepthRange idiom works). Only the GONE 1.21.3 wrappers translate:
//   * RenderSystem.enableDepthTest()/depthMask(b)  -> GlStateManager._enableDepthTest()/_depthMask(b)
//        (26.2: the RenderSystem depth WRAPPERS are GONE; the GlStateManager forms survive at
//        com.mojang.blaze3d.opengl. This preserves IP's own note: route through GlStateManager's cache,
//        never raw glDisable(GL_DEPTH_TEST)).
//   * RenderSystem.getProjectionMatrix()           -> getCurrentProjectionMatrix() (inherited from
//        PortalRenderer; render-core G27/G19 — see that helper's derivation).
//   * Minecraft.getMainRenderTarget()              -> client.gameRenderer.mainRenderTarget() (G14).
//   * mainRenderTarget().bindWrite(false)          -> the S14.21 FrameBufferCache.getFbo resolver
//        (the LIVE main-target FBO from the same color+depth view key vanilla's createRenderPass
//        computes; the earlier StencilState.gameFboId capture is @Deprecated — it went stale at
//        resource-lifecycle events). See the inline derivation in prepareRendering().
//   * Minecraft.useShaderTransparency()            -> gameRenderState().useShaderTransparency() (R13i /
//        CUTOVER_SPEC §6.4; GameRenderState.java:17-19). Body is IP-commented (no worldRenderer.reload
//        on 26.2 — the substrate provides the stencil buffer unconditionally), so the read is inert.
//   * MyRenderHelper.renderScreenTriangle(purpose[, fogColor]) — the S12-A per-purpose re-expression of IP's
//        ambient-state full-screen triangle. IP relied on the caller's raw-GL depth/stencil/color state; on
//        26.2 the pipeline dictates depth/color state (applyPipelineState), so THIS class (the real cutover-core
//        consumer, NOT an Iris shell) passes its INTENT: replaceFrameBufferClearing -> COLOR_FILL (R5 Row 16),
//        clearDepthOfThePortalViewArea -> DEPTH_CLEAR (R5 Row 7), clampStencilValue -> STENCIL_ONLY (R5 Row 15).
//        The caller's raw glStencilFunc EQUAL + glDepthRange survive the draw (applyPipelineState touches
//        neither), so the fill stays gated to the opening and Row-7's FAR depth lands. Resolves intra-S12.
//
// render-thread-logging discipline (memory render-thread-logging-log4j-stall): NO per-frame LOGGER.
public class RendererUsingStencil extends PortalRenderer {

    // S14.21: the last FBO id the prepareRendering stencil clear resolved — value-change log gate only.
    private static int lastResolvedMainFbo = -1;

    // S14.22 lever (default OFF): 1Hz raw-GL state observation for desync diagnosis.
    private static long lastGlStateAssertMs = 0;

    // S14.26: 1Hz limiter for the resolver landing log (GUI-portal alternation guard).
    private static long lastResolverLogMs = 0;

    @Override
    public boolean replaceFrameBufferClearing() {
        boolean skipClearing = WorldRenderInfo.isRendering();
        if (skipClearing) {
            if (WorldRenderInfo.getTopRenderInfo().doRenderSky) {
                // R5 Row 16 (replaceFrameBufferClearing dest sky/fog fill): UNCHANGED. Color-only fill of
                // the stencil region with the dest world's fog color; touches no depth COMPARE. The depth
                // MASK toggle (RenderSystem.depthMask -> GlStateManager._depthMask, 26.2 wrapper move) is
                // a mask, not a compare — direction-independent. (The fog COLOR source is R9.)
                GlStateManager._depthMask(false);
                // R5 Row 16 purpose: depth test OFF + FULL color write (depth-independent dest fog fill).
                // COLOR_FILL selects PortalRenderTypes.portalCompositeBlit (depth-off), so the fill is never
                // reversed-Z GEQUAL-gated by leftover portal-plane depth; the fog COLOR rides the draw.
                qouteall.imm_ptl.core.render.DrawCallTrace.record("   [Row-16 COLOR_FILL next]");
                MyRenderHelper.renderScreenTriangle(
                    // S14.24 lever (debug_dye_portal_fill, default OFF): dye the Row-16 backdrop
                    // fill MAGENTA — any on-screen residue that dyes with it is stencil-gated dest
                    // backdrop (stencil content wrong); residue that doesn't is not this fill.
                    qouteall.imm_ptl.core.IPGlobal.debugDyePortalFill
                        ? new Vec3(1.0, 0.0, 1.0)
                        : FogRendererContext.getCurrentFogColor.get(),
                    MyRenderHelper.ScreenTrianglePurpose.COLOR_FILL
                );
                GlStateManager._depthMask(true);
            }
        }
        return skipClearing;
    }

    @Override
    public void onBeforeTranslucentRendering(Matrix4f modelView) {
        doPortalRendering(modelView);
    }

    protected void doPortalRendering(Matrix4f modelView) {
        // NOTE do not use glDisable(GL_DEPTH_TEST),
        // use GlStateManager.disableDepthTest() instead
        // because GlStateManager will cache its state.
        // Do not make its cache not synchronized
        //
        // R5 (depth TOGGLES, Row 2 category): enable + mask are direction-INDEPENDENT — no flip. 26.2:
        // the GONE RenderSystem.enableDepthTest()/depthMask(true) wrappers -> GlStateManager forms.
        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);

        Profiler.get().popPush("render_portal_total");
        renderPortals(modelView);
        if (PortalRendering.isRendering()) {
            setStencilStateForWorldRendering();
        }
        else {
            // don't do it in finishRendering()
            // as it will render outer world's transparent things later
            myFinishRendering();
        }
    }

    protected void renderPortals(Matrix4f modelView) {
        List<Portal> portalsToRender = getPortalsToRender(modelView);

        for (Portal portal : portalsToRender) {
            doRenderPortal(portal, modelView);
        }
    }

    @Override
    public void onHandRenderingEnded() {
        //nothing
    }

    @Override
    public void prepareRendering() {
        // S14.22 lever (debug_gl_state_assert, default OFF): 1Hz raw-GL observation — post-fix
        // invariant is cache==real at all times on the flag-ON path; this surfaces residue drift.
        if (qouteall.imm_ptl.core.IPGlobal.debugGlStateAssert) {
            long now = System.currentTimeMillis();
            if (now - lastGlStateAssertMs > 1000) {
                lastGlStateAssertMs = now;
                Helper.log("[GL-state] blend=" + GL11.glGetBoolean(GL11.GL_BLEND)
                    + " depthTest=" + GL11.glGetBoolean(GL11.GL_DEPTH_TEST)
                    + " depthMask=" + GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
                    + " depthFunc=" + GL11.glGetInteger(GL_DEPTH_FUNC));
            }
        }
        // 26.2 G14: Minecraft.getMainRenderTarget() is GONE -> gameRenderer.mainRenderTarget(). On 26.2
        // the substrate (RenderTargetMixin/GlConstMixin) makes the main FBO stencil-capable
        // (DEPTH24_STENCIL8) UNCONDITIONALLY, so IPPortingLibCompat's non-porting-lib branch (the 26.2
        // case) routes to the IEFrameBuffer duck and this enable-dance is effectively a no-op; ported
        // verbatim for IP fidelity.
        if (!IPPortingLibCompat.getIsStencilEnabled(client.gameRenderer.mainRenderTarget())) {
            IPPortingLibCompat.setIsStencilEnabled(client.gameRenderer.mainRenderTarget(), true);

            // R13i (CUTOVER_SPEC §6.4): Minecraft.useShaderTransparency() moved to
            // GameRenderState.useShaderTransparency() (GameRenderState.java:17-19). The if-body is
            // IP-commented (no worldRenderer.reload equivalent on 26.2 — the substrate provides the
            // stencil buffer regardless), so this read is inert; kept for IP fidelity + the R13i anchor.
            if (client.gameRenderer.gameRenderState().useShaderTransparency()) {
//                client.worldRenderer.reload();
            }
        }

        // 26.2 G12 — S14.21 CORRECTED RE-EXPRESSION (live-defect hunt, all four tracks unanimous):
        // IP's prepareRendering bound THE MAIN RENDER TARGET (bindWrite(false)) so the stencil clear
        // below lands on it. The prior form bound StencilState.gameFboId — a last-writer-wins capture
        // of "the most recent depth FBO created anywhere" (RenderTargetMixin), whose staleness flips at
        // resource-lifecycle events (world teardown, pause-menu GUI target churn via the
        // CrossFrameResourcePool TTL → FBO delete): stale-DEAD names threw GL_INVALID_OPERATION once
        // per frame (the 51,470-line U2 flood) and left FBO 0 bound so the clear hit the WINDOW; a
        // stale-LIVE name cleared a WRONG target silently. Either way the main RT's per-frame stencil
        // reset never happened — the substrate half of the rung-2 stencil-poison admission (SYMPTOM B).
        // The 1:1 26.2 translation of IP's bindWrite(false): resolve the LIVE main-target FBO from
        // FrameBufferCache with the SAME key (color view + depth view) every main-pass createRenderPass
        // computes — a cache HIT returns the exact FBO integer in use; a MISS creates it through the
        // stencil-capable substrate path. Bind through GlStateManager._glBindFramebuffer (cache-guarded;
        // a raw GL30 bind desyncs its readFbo/writeFbo cache — the old code was quietly doing that too),
        // and restore the previous read/write bindings exactly.
        RenderTarget mainRt = client.gameRenderer.mainRenderTarget();
        // 26.2: RenderSystem.getDevice() returns the GpuDevice FACADE; the GL backend is its
        // (AW-widened) `backend` field — GlDevice implements GpuDeviceBackend.
        if (RenderSystem.getDevice().backend instanceof GlDevice glDevice
            && mainRt.getColorTextureView() instanceof GlTextureView colorView
            && mainRt.getDepthTextureView() instanceof GlTextureView depthView
        ) {
            int mainFbo = glDevice.frameBufferCache().getFbo(
                glDevice.directStateAccess(), List.of(colorView), depthView
            );
            // Value-change-gated + 1Hz-limited landing proof (S14.26: value-change alone can fire
            // 2x/frame while a GUI portal alternates the swapped main target — the debug command
            // path; the rate limit keeps the log render-thread-safe in every context).
            if (mainFbo != lastResolvedMainFbo) {
                lastResolvedMainFbo = mainFbo;
                long now = System.currentTimeMillis();
                if (now - lastResolverLogMs > 1000) {
                    lastResolverLogMs = now;
                    Helper.log("[S14.21] stencil-clear main FBO resolved: " + mainFbo
                        + " (color=" + colorView + ", depth=" + depthView + ")");
                }
            }
            int prevRead = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
            int prevWrite = GlStateManager.getFrameBuffer(GL30.GL_DRAW_FRAMEBUFFER);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);

            // S14.26 (raw-GL sweep MINOR): glClear respects GL_SCISSOR_TEST, and 26.2 scissor is
            // per-pass state that PERSISTS between passes (unlike 1.21.3's popped-to-disabled
            // stack, which made IP's identical raw clear safe). Vanilla's own clear helpers
            // disable scissor first — match that discipline (cached twin; no restore needed:
            // every subsequent draw's trySetup re-establishes per-pass scissor). Matters at the
            // GuiPortalRendering call site, where leftover GUI scissor could box the clear.
            GlStateManager._disableScissorTest();

            // R5 Row 1 (prepareRendering :98-99): UNCHANGED. Clears STENCIL only — the main-frame
            // reversed-Z DEPTH buffer is deliberately preserved for the stencil-write depth test
            // (Row 4). Stencil is depth-direction-independent. The clear is LOAD-BEARING: the
            // end-of-frame clampStencilValue is SKIPPED on the occlusion-mispredict early-return, so
            // without this per-frame clear a mispredicted portal's INCR'd stencil poisons later frames.
            GL11.glClearStencil(0);
            GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);

            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevWrite);
        }

        // R5 Row 2 (:101-102): UNCHANGED. Toggles, not comparisons. (26.2 wrapper move:
        // RenderSystem/GlStateManager._enableDepthTest.)
        GlStateManager._enableDepthTest();
        GL11.glEnable(GL_STENCIL_TEST);

    }

    @Override
    public void finishRendering() {
        //nothing
    }

    private void myFinishRendering() {
        // NOT one of the 16 R5 rows: this end-of-world-render "reset stencil to always-pass" is
        // stencil-only + a depth TOGGLE, both direction-INDEPENDENT (same rationale as Rows 2/5/10). The
        // 2333 stencil ref is IP's verbatim sentinel. UNCHANGED.
        GL11.glStencilFunc(GL_ALWAYS, 2333, 0xFF);
        GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);

        GL11.glDisable(GL_STENCIL_TEST);
        GlStateManager._enableDepthTest();
    }

    protected void doRenderPortal(
        Portal portal,
        Matrix4f modelView
    ) {
        if (shouldSkipRenderingInsideFuseViewPortal(portal)) {
            return;
        }

        int outerPortalStencilValue = PortalRendering.getPortalLayer();

        Profiler.get().push("render_view_area");

        qouteall.imm_ptl.core.render.DrawCallTrace.record(
            ">> doRenderPortal " + portal.getUUID() + " destDim=" + portal.getDestDim().identifier()
            + " layer=" + PortalRendering.getPortalLayer() + " [aperture INCR draw next]");
        boolean anySamplePassed = PortalRenderInfo.renderAndDecideVisibility(portal, () -> {
            renderPortalViewAreaToStencil(portal, modelView);
        });

        Profiler.get().pop();

        if (!anySamplePassed) {
            // S14.27 F1 (round-2 painter hunt, transient M2): under the OFFSET occlusion scheme the
            // INCR draw above already EXECUTED this frame (the verdict consumed is LAST frame's), so
            // a stale-false verdict strands this frame's stencil INCR (+ the mesh's black color) for
            // later same-frame portals — clampStencilValue is otherwise only reached on the taken
            // branch. Clean the stencil half before returning. Gated on the offset scheme: the
            // synchronous path (offset_occlusion_query off) has decision==false ONLY when ZERO
            // samples passed = zero writes = nothing to clean — that path stays byte-identical with
            // IP. DEVIATION (ledgered, task #9): IP carries the same latent offset-scheme leak.
            if (qouteall.imm_ptl.core.IPGlobal.offsetOcclusionQuery) {
                clampStencilValue(outerPortalStencilValue);
            }
            setStencilStateForWorldRendering();
            return;
        }

        PortalRendering.pushPortalLayer(portal);

        int thisPortalStencilValue = outerPortalStencilValue + 1;

        // S14.29 hardening (round-3 verified LATENT mode, ledgered deviation — IP has the identical
        // unbracketed shape): a single throw escaping renderPortalContent used to skip
        // popPortalLayer, leaving isRendering()==true FOREVER — the flag-ON hook then early-returns
        // every frame (silent portal death) with GL_STENCIL_TEST frozen enabled over stale content
        // (a permanent-corruption mode; precedent throw class: the S13-J dest-clouds fence crash).
        // The finally guarantees the layer stack balances; depth-restore + clamp stay AFTER (order
        // preserved — they need the popped layer, see the ViewAreaRenderer clipping note).
        try {
            if (!portal.isFuseView()) {
                Profiler.get().push("clear_depth_of_view_area");
                clearDepthOfThePortalViewArea(portal);
                Profiler.get().pop();
            }

            setStencilStateForWorldRendering();

            renderPortalContent(portal);
        }
        finally {
            PortalRendering.popPortalLayer();
            // pop portal layer before restoring depth, for clipping, see ViewAreaRenderer
        }

        if (!portal.isFuseView()) {
            restoreDepthOfPortalViewArea(portal, modelView, thisPortalStencilValue);
        }

        clampStencilValue(outerPortalStencilValue);
    }

    @Override
    public void renderPortalInEntityRenderer(Portal portal) {
        //nothing
    }

    private void renderPortalViewAreaToStencil(
        Portal portal, Matrix4f modelView
    ) {
        int outerPortalStencilValue = PortalRendering.getPortalLayer();

        //is the mask here different from the mask of glStencilMask?
        // R5 Row 3 (:177): UNCHANGED. Stencil comparison, direction-independent.
        GL11.glStencilFunc(GL_EQUAL, outerPortalStencilValue, 0xFF);

        //if stencil and depth test pass, the data in stencil buffer will increase by 1
        // R5 Row 4 (stencil-write depth-pass op): the GL_INCR stencil op is UNCHANGED (stencil, no flip).
        // The IMPLICIT depth COMPARE for this "increment where the portal is visible" test is the ONE
        // easy-to-miss flip — but it is NOT a raw constant here: IP relied on the vanilla DEFAULT depth
        // compare (1.21.3 LEQUAL). 26.2's default flipped to GEQUAL, and the flip is carried by the mesh
        // PIPELINE that ViewAreaRenderer.renderPortalArea (below) selects via
        // MyRenderHelper.getPortalAreaRenderType -> DepthStencilState(GREATER_THAN_OR_EQUAL, writeDepth)
        // (authored S11-B). The depth WRITE MASK follows IP's own writeDepth = doModifyDepth && !fuseView
        // (here doModifyColor=true, doModifyDepth=true => a non-fuse portal WRITES depth); NOT a false
        // mask (CUTOVER_SPEC §2.1 Row 4, Fable-corrected).
        GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
        //NOTE about GL_INCR:
        //if multiple triangles occupy the same pixel and passed stencil and depth tests,
        //its stencil value will still increase by one

        // R5 Row 5 (:185): UNCHANGED.
        GL11.glStencilMask(0xFF);

        // update it before pushing
        FrontClipping.updateInnerClipping(modelView);

        ViewAreaRenderer.renderPortalArea(
            portal,
            // S14.27 L1 lever (debug_dye_view_area_mesh, default OFF): the aperture mesh draws with
            // doModifyColor=true and this fog color — IP-verbatim BLACK (Vec3.ZERO). Dyeing it GREEN
            // photographs the mesh's true rasterized footprint directly (the round-2 sector/seam
            // discriminator: green seams = double-INCR overlap keeping mesh color; green beyond the
            // portal quad = the corrupted footprint itself).
            qouteall.imm_ptl.core.IPGlobal.debugDyeViewAreaMesh ? new Vec3(0, 1, 0) : Vec3.ZERO,
            modelView,
            getCurrentProjectionMatrix(),
            true, true,
            true, true
        );
    }

    private void clearDepthOfThePortalViewArea(
        Portal portal
    ) {
        GlStateManager._enableDepthTest();
        GlStateManager._depthMask(true);

        setStencilStateForWorldRendering();

        //do not manipulate color buffer
        GlStateManager._colorMask(0); // S14.22 cache-coherent (was raw glColorMask)

        //save the state
        int originalDepthFunc = GL11.glGetInteger(GL_DEPTH_FUNC);

        //always passes depth test
        // R5 Row 6 (:214): UNCHANGED (ALWAYS). GL_ALWAYS passes regardless of depth direction.
        GlStateManager._depthFunc(GL_ALWAYS); // S14.22 cache-coherent (was raw glDepthFunc)

        // R5 Row 7 (:217) — THE flip that lands as a raw constant in this class. IP wrote window depth
        // 1.0 = FAR (1.21.3). 26.2 reversed-Z FAR = 0.0, so FLIP glDepthRange(1,1) -> glDepthRange(0,0).
        // Purpose: push the opening's depth to FAR so the dest terrain, drawn next under GEQUAL, ALL
        // passes (nothing z-rejects it). Proven: MOD:StencilPortalRenderer.java:401 glDepthRange(0,0) in
        // the stencil-direct branch, comment :391-392 "26.2 reversed-Z: FAR = 0.0, written via
        // glDepthRange(0,0)". (This is the OPPOSITE direction from the Row-12 NEAR restore range(1,1) —
        // do not conflate the FAR clear with the NEAR restore.)
        GL11.glDepthRange(0, 0);

        // R5 Row 7 purpose: ALWAYS_PASS + depth WRITE + color OFF. DEPTH_CLEAR selects
        // PortalRenderTypes.portalScreenDepthClear (ALWAYS_PASS + write + WRITE_NONE) — the pipeline carries
        // the depth/color state IP got from the ambient raw-GL above (glColorMask(false)+glDepthFunc(ALWAYS)),
        // and the glDepthRange(0,0) FAR value survives the draw (applyPipelineState never touches glDepthRange).
        MyRenderHelper.renderScreenTriangle(MyRenderHelper.ScreenTrianglePurpose.DEPTH_CLEAR);

        //retrieve the state
        GlStateManager._colorMask(15); // S14.22 cache-coherent (was raw glColorMask)
        // R5 Row 8 (:223): UNCHANGED. Save/restore of the queried prior func.
        GlStateManager._depthFunc(originalDepthFunc); // S14.22 cache-coherent (was raw glDepthFunc)
        // R5 Row 9 (:224): UNCHANGED. Returns the default full NDC->window mapping (0,1); direction-
        // independent. Proven: MOD:StencilPortalRenderer.java:403 restores glDepthRange(0,1) (NOT (0,0)).
        GL11.glDepthRange(0, 1);
    }

    protected void restoreDepthOfPortalViewArea(
        Portal portal, Matrix4f modelView,
        int portalStencilValue
    ) {
        // R5 Row 10 (setStencilLimitation, via setStencilLimitation): UNCHANGED (stencil).
        setStencilLimitation(portalStencilValue);

        int originalDepthFunc = GL11.glGetInteger(GL_DEPTH_FUNC);

        // R5 Row 11 (:235): UNCHANGED (ALWAYS).
        GlStateManager._depthFunc(GL_ALWAYS); // S14.22 cache-coherent (was raw glDepthFunc)

        // R5 Row 12 (:237-244): IP has NO explicit depth constant here — it RE-RENDERS the view-area mesh
        // at its REAL projected depth (already reversed-Z, from the projection). Ported VERBATIM (the
        // renderPortalArea call with doModifyColor=false, doModifyDepth=true, doClip=true). The mod's LIVE
        // block-era re-expression is a flat NEAR shield (glDepthRange(1,1), StencilPortalRenderer:472/408)
        // — the OPPOSITE direction from the Row-7 FAR clear — but that flat shield is the block-era form,
        // NOT the IP prescription; the qouteall port keeps IP op #12 verbatim (exact projected depth).
        // S13-H W1 RESOLVED (parent ruling 1 / S11-B note §8): the raw glDepthFunc(GL_ALWAYS) bracket
        // (Rows 11/13) was clobbered by the GEQUAL mesh pipeline that applyPipelineState installs, so the
        // restore depth-write was GEQUAL-gated against content depth (wrong where dest terrain sits in
        // FRONT of the portal plane). The alwaysPassDepth=true overload routes THIS draw ONLY through the
        // ALWAYS_PASS depth-compare pipeline variant (MyRenderHelper bit 8) so IP's exact-projected-depth
        // op #12 lands unconditionally within the stencil region — additive, no other consumer changes.
        ViewAreaRenderer.renderPortalArea(
            portal, Vec3.ZERO,
            modelView,
            getCurrentProjectionMatrix(),
            false, false,
            true,
            true, // important: should clip, otherwise depth will be abnormal when viewing scale box from inside in portal
            true  // S13-H W1: ALWAYS_PASS depth-compare pipeline variant (Row-11/12 restore only)
        );

        // R5 Row 13 (:246): UNCHANGED. Restore of the queried prior func.
        GlStateManager._depthFunc(originalDepthFunc); // S14.22 cache-coherent (was raw glDepthFunc)
    }

    public static void clampStencilValue(
        int maximumValue
    ) {
        GlStateManager._depthMask(true);

        //NOTE GL_GREATER means ref > stencil
        //GL_LESS means ref < stencil

        //pass if the stencil value is greater than the maximum value
        // R5 Row 14 (:258-261): UNCHANGED. GL_LESS here is `ref < stencil` — a STENCIL comparison, NOT a
        // depth comparison — so it does NOT flip (the CUTOVER_SPEC §2 note: this GL_LESS is deliberately
        // not a depth compare). glStencilOp is stencil-only.
        GL11.glStencilFunc(GL_LESS, maximumValue, 0xFF);

        //if stencil test passed, encode the stencil value
        GL11.glStencilOp(GL_KEEP, GL_REPLACE, GL_REPLACE);

        //do not manipulate the depth buffer
        // R5 Row 15 (:264-277): UNCHANGED. Stencil-only pass — depth is masked off and the test disabled,
        // so there is no depth comparison to flip.
        GlStateManager._depthMask(false); // S14.22 cache-coherent (was raw glDepthMask)

        //do not manipulate the color buffer
        GlStateManager._colorMask(0); // S14.22 cache-coherent (was raw glColorMask)

        GlStateManager._disableDepthTest();

        // R5 Row 15 purpose: depth test OFF + color OFF (stencil-only clamp pass). STENCIL_ONLY selects the
        // depth-off + WRITE_NONE screenquad pipeline; the caller's raw glStencilFunc(GL_LESS,…)+glStencilOp
        // (KEEP,REPLACE,REPLACE) persists through the draw (applyPipelineState never touches stencil), so the
        // pass REPLACEs the clamped stencil values — no depth compare to reversed-Z flip, no color splat.
        MyRenderHelper.renderScreenTriangle(MyRenderHelper.ScreenTrianglePurpose.STENCIL_ONLY);

        GlStateManager._depthMask(true); // S14.22 cache-coherent (was raw glDepthMask)

        GlStateManager._colorMask(15); // S14.22 cache-coherent (was raw glColorMask)

        GlStateManager._enableDepthTest();
    }

    private void setStencilStateForWorldRendering() {
        int thisPortalStencilValue = PortalRendering.getPortalLayer();

        setStencilLimitation(thisPortalStencilValue);
    }

    public static void setStencilLimitation(int stencilValue) {
        //draw content in the mask
        // R5 Row 10 (:288-291): UNCHANGED. Stencil EQUAL test + KEEP,KEEP,KEEP — all stencil,
        // direction-independent.
        GL11.glStencilFunc(GL_EQUAL, stencilValue, 0xFF);

        //do not manipulate stencil buffer now
        GL11.glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
    }

    public static boolean shouldSkipRenderingInsideFuseViewPortal(Portal portal) {
        if (!PortalRendering.isRendering()) {
            return false;
        }

        Portal renderingPortal = PortalRendering.getRenderingPortal();

        if (!renderingPortal.isFuseView()) {
            return false;
        }

        Vec3 cameraPos = CHelper.getCurrentCameraPos();

        Vec3 transformedCameraPos = portal
            .transformPoint(renderingPortal.transformPoint(cameraPos));

        // roughly test whether they are reverse portals
        return cameraPos.distanceToSqr(transformedCameraPos) < 0.1;
    }
}
