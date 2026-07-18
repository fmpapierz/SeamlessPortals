package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.render.GuiPortalRendering;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * S12-B (render client-mixin half) — IP {@code MixinGameRenderer}
 * ({@code IP:mixin/client/render/MixinGameRenderer.java}), 26.2-RETARGETED. Held/UNREGISTERED until S13.
 *
 * <p>IP's 1.21.3 {@code MixinGameRenderer} was an 11-handler frame driver + the {@link IEGameRenderer}
 * duck holder. On 26.2 most of it re-expresses onto MOD-OWNED / already-ported code, so this mixin keeps
 * only the three pieces that have NO other 26.2 home:
 * <ul>
 *   <li><b>A2 — the view-bob scaling trio</b> (USER DECISION C5, BINDING; EXCLUSIVITY_LEDGER row A7).</li>
 *   <li><b>the {@link IEGameRenderer} ducks</b> — {@code ip_setCamera} / {@code ip_setLightmapTextureManager}
 *       are LIVE-called by the ported driver ({@code MyGameRenderer.switchAndRenderTheWorld:235,247,297,302},
 *       {@code RenderStates.onTotalRenderEnd:233}, {@code ClientTeleportationManager:484}) via
 *       {@code (IEGameRenderer) client.gameRenderer}, so the impl MUST live on a {@code GameRenderer} mixin
 *       or those casts CCE at runtime.</li>
 *   <li><b>R13k handler ⑪ — the view-rotation post-process</b> (see below).</li>
 * </ul>
 *
 * <p><b>Frame lifecycle hooks (IP handlers ②–⑥) are RE-HOMED, not re-ported.</b> IP drove the whole
 * portal-render frame from {@code GameRenderer.render}/{@code renderLevel} hooks
 * ({@code switchToCorrectRenderer}+{@code prepareRendering} before renderLevel; {@code renderCrossPortalView}
 * redirect; {@code finishRendering}+{@code onTotalRenderEnd}+{@code lateUpdateLight} after;
 * {@code onHandRenderingEnded} at TAIL; {@code onBeforeHandRendering} wrapping the world draw). On 26.2 the
 * mod's proven driver seam is a Fabric {@code LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN} callback
 * (EXCLUSIVITY_LEDGER rows 14/15: {@code StencilPortalRenderer.renderPortals()} REPLACE-BY
 * {@code PortalRenderer}+{@code RendererUsingStencil}+{@code MyGameRenderer}), which the S13 flag-ON wiring
 * registers in place of the block-era driver. Re-porting those hooks as {@code GameRenderer} injections
 * would DOUBLE-drive the renderer. Handler ① (pre-render chain + render-time teleport) is likewise re-homed
 * to the MOD-OWNED {@code MinecraftFramePumpMixin} at {@code Minecraft.renderFrame} pre-{@code update}
 * (CUTOVER_SPEC §4.2/§4.3, S3 soak-proven), because a render-time teleport must move the camera BEFORE
 * extraction. Handler ⑩ ({@code getProjectionMatrix} redirect) is re-expressed by
 * {@code PortalRenderer.getCurrentProjectionMatrix}, which returns the live main-pass DRAW projection —
 * the POST-spin bobbed capture below scaled by {@code getExtraModelViewScaling} (S13-M P1/P2/P3) — the
 * 26.2 stand-in for IP's ambient {@code RenderSystem.getProjectionMatrix()} (NOT the bob-free
 * {@code cameraState.projectionMatrix}, which stays the extract-time cull base).
 *
 * <p><b>A2 (VERBATIM IP, {@code MixinGameRenderer.java:200-253}).</b> IP distance-scales the WORLD view-bob
 * offset near portals: {@code @ModifyArg} on all three args of the single {@code PoseStack.translate(FFF)}
 * inside {@code bobView}, each multiplied by {@code RenderStates.getViewBobbingOffsetMultiplier()} — UNLESS
 * the hand is rendering ({@code portal_isRenderingHand}, bracketing {@code renderItemInHand}). 26.2 retarget
 * (mixin-client.md §10 ⑨): 1.21.3 {@code bobView(PoseStack,F)} → {@code bobView(CameraRenderState,PoseStack)}
 * ({@code 26.2:GameRenderer.java:322}), whose body still has exactly one {@code poseStack.translate(FFF)}
 * ({@code :326-330}); {@code renderItemInHand(Camera,F,Matrix4f)} → {@code renderItemInHand(CameraRenderState,
 * float,Matrix4fc)} ({@code :336}). REPLACES the live {@code MainProjectionBobMixin} at the S13 exclusivity
 * flip (row A7); flag-OFF only the block-era mixin runs, flag-ON only this — never both.
 *
 * <p><b>R13k handler ⑪ — view-rotation post-process (API_RISKS R13k; render-sub C2; mixin-client §10 ⑪).</b>
 * IP wrapped {@code Matrix4f.rotation(Quaternionfc)} inside 1.21.3 {@code renderLevel} to run
 * {@code TransformationManager.processTransformation} on the camera view matrix. On 26.2 that rotation moved
 * into {@code Camera.getViewRotationMatrix}, which is DIRTY-FLAG CACHED ({@code Camera.java:385-389}) and
 * copied to {@code cameraState.viewRotationMatrix} at extract ({@code :135}). R13k mandate: NEVER wrap the
 * cached getter — post-process {@code cameraState.viewRotationMatrix} AFTER extract. Injected at
 * {@code GameRenderer.extract} RETURN, where the R3 entity-clip / terrain consumers (which read
 * {@code cameraState.viewRotationMatrix} downstream, e.g. {@code PerEntityClipBracket}) then see the
 * transformed rotation, exactly as IP's downstream consumers saw the wrapped matrix. Runtime finalization
 * (exact per-pass timing / gating, the {@code viewRotationMatrix} ↔ pushed {@code modelViewMatrix}
 * equivalence) is a named S13 rung-1 check (S12A-renderers.md §4/§6).
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer implements IEGameRenderer {

    // 26.2: LightTexture split into Lightmap (render-core G4); GameRenderer holds `private final Lightmap
    // lightmap` (@Mutable to swap a per-dim lightmap in via ip_setLightmapTextureManager).
    @Shadow
    @Final
    @Mutable
    private Lightmap lightmap;

    @Shadow
    @Final
    @Mutable
    private Camera mainCamera;

    // for R13k handler ⑪ (view-rotation post-process after extract)
    @Shadow
    public abstract GameRenderState gameRenderState();

    @Unique
    private static boolean portal_isRenderingHand = false;

    /**
     * S14.28 round-4 wedge kit (live-defect hunt r3): frame-boundary PROBE + capability GUARD at
     * renderLevel HEAD — before the framegraph (clear pass + sky pass) is built/executed, i.e. the
     * exact point the previous frame's leaked raw-GL capability state would first damage vanilla
     * draws. 26.2 vanilla owns ZERO stencil or clip-capability state (applyPipelineState toggles
     * neither), so any leaked GL_STENCIL_TEST / GL_CLIP_DISTANCE0 persists silently across ALL
     * passes and frames — a new-in-26.2 hazard class with no IP analog (IP needed no frame-start
     * guard; ledgered substrate deviation). PROBE reads FIRST (pre-guard state, 1Hz, lever-gated);
     * GUARD then disables both capabilities — in steady state a provable no-op (both already off).
     */
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void portal_onRenderLevelHead(DeltaTracker deltaTracker, CallbackInfo ci) {
        // S14.31: one-frame draw-trace bracket (armed by debug_capture_frame; dumped at render TAIL).
        qouteall.imm_ptl.core.render.DrawCallTrace.onFrameStart();
        // S14.42: post-promote render-chain probe (self-armed by every promote; 1Hz; also the
        // debug_dump_render_chain one-shot).
        qouteall.imm_ptl.core.render.RenderChainProbe.onFrame();
        if (qouteall.imm_ptl.core.IPGlobal.debugFrameBoundaryProbe) {
            long now = System.currentTimeMillis();
            if (now - portal_lastBoundaryProbeMs > 1000) {
                portal_lastBoundaryProbeMs = now;
                var clip = com.warwa.seamlessportals.render.FrontClipping.capture();
                qouteall.q_misc_util.Helper.log(
                    "[frame-boundary] stencilTest=" + org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_STENCIL_TEST)
                    + " stencilFunc=" + org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_STENCIL_FUNC)
                    + " stencilRef=" + org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_STENCIL_REF)
                    + " clipCap=" + org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL30.GL_CLIP_DISTANCE0)
                    + " plane=(" + clip.x + "," + clip.y + "," + clip.z + "," + clip.w + ") planeEnabled=" + clip.enabled
                    // S14.30 (round-5 discriminator): the value addSkyPass gates on — pins whether
                    // the current dim can have a vanilla sky pass at all.
                    + " skybox=" + (net.minecraft.client.Minecraft.getInstance().level != null
                        ? net.minecraft.client.Minecraft.getInstance().level.dimensionType().skybox()
                        : "null")
                    // S14.34: the MV-stack top at frame boundary — the sky family multiplies its
                    // pose onto this assuming identity; non-identity here = the wedge transform.
                    + " " + qouteall.imm_ptl.core.render.DrawCallTrace.mvTop()
                );
            }
        }
        // GUARD (always-on, flag-ON-only mixin): both disables route through the correct owners —
        // raw GL for stencil (the mod's stencil idiom; vanilla has no stencil cache to desync),
        // FrontClipping.disable() for the clip cap (single-writer cached bool + no-op plane reset;
        // never raw-GL a cached state — 26.2 invariant).
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
        com.warwa.seamlessportals.render.FrontClipping.disable();
    }

    @Unique
    private static long portal_lastBoundaryProbeMs = 0;

    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void onRenderHandBegins(CameraRenderState cameraState, float f, Matrix4fc modelViewMatrix, CallbackInfo ci) {
        portal_isRenderingHand = true;
    }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void onRenderHandEnds(CameraRenderState cameraState, float f, Matrix4fc modelViewMatrix, CallbackInfo ci) {
        portal_isRenderingHand = false;
    }

    // not using ModifyArgs because ModifyArgs seems broken on Forge
    @ModifyArg(
        method = "bobView",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
        index = 0
    )
    private float modifyBobViewTranslateX(float f) {
        if (portal_isRenderingHand) {
            return f;
        }
        else {
            return (float) (f * RenderStates.getViewBobbingOffsetMultiplier());
        }
    }

    @ModifyArg(
        method = "bobView",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
        index = 1
    )
    private float modifyBobViewTranslateY(float f) {
        if (portal_isRenderingHand) {
            return f;
        }
        else {
            return (float) (f * RenderStates.getViewBobbingOffsetMultiplier());
        }
    }

    @ModifyArg(
        method = "bobView",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"),
        index = 2
    )
    private float modifyBobViewTranslateZ(float f) {
        if (portal_isRenderingHand) {
            return f;
        }
        else {
            return (float) (f * RenderStates.getViewBobbingOffsetMultiplier());
        }
    }

    // S13-M Finding B — capture the MAIN-pass DRAW projection so the portal-view content AND the stencil
    // aperture bob IN SYNC with the frame. 26.2 applies view-bob AND the nausea/portal spin to the
    // PROJECTION (not the model-view): renderLevel builds `projectionMatrix = new Matrix4f(cameraState
    // .projectionMatrix)`, multiplies the bob pose (renderLevel:535,542), applies the spin skew
    // (:547-554), then uploads it via `levelProjectionMatrixBuffer.getBuffer(projectionMatrix)` (:557).
    // We WRAP that getBuffer (S13-M P2 — the POST-spin site; the same interception point the block-era
    // GameRendererObliqueClipMixin proves) and snapshot its Matrix4f arg = base*bob*spin, the TRUE ambient
    // the main pass rasterizes with.
    //
    // P2 correction: the earlier capture was at the :542 bob multiply (PRE-spin), on the FALSE premise
    // that "IP's dest excluded the spin". IP's dest re-enters the FULL renderLevel (IP:MyGameRenderer:231)
    // and gets the SAME nausea/portal spin at frame-identical intensity, so IP's dest is base*bob*spin like
    // its main pass. Capturing POST-spin matches it — and in normal play spin==0, so this equals base*bob
    // then (the normal-play wobble fix is unaffected; only the nausea/portal-overlay edge is corrected).
    //
    // RenderStates.getPortalDrawProjection derives the portal-view draw projection from this (bob
    // TRANSLATION scaled by the pass's getExtraModelViewScaling — P3); PortalRenderer
    // .getCurrentProjectionMatrix returns it for the stencil aperture / depth restore / cull frustum (P1),
    // leaving the dest frustum + cameraRenderState on the unbobbed base (vanilla keeps cameraState
    // .projectionMatrix bob-free). The `getBuffer(Matrix4f)` descriptor matches ONLY the level-projection
    // upload at :557 (the :570 HUD upload takes a Projection, not a Matrix4f); ordinal 0 pins it. Woven
    // flag-ON only, so flag-OFF is untouched. Returns the original GpuBufferSlice unchanged (passive snapshot).
    @WrapOperation(
        method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",
            ordinal = 0
        )
    )
    private GpuBufferSlice seamlessportals$captureMainPassBobbedProjection(
        ProjectionMatrixBuffer instance, Matrix4f projectionMatrix, Operation<GpuBufferSlice> original
    ) {
        RenderStates.capturedMainPassBobbedProjection = new Matrix4f(projectionMatrix);
        return original.call(instance, projectionMatrix);
    }

    // R13k handler ⑪ — post-process cameraState.viewRotationMatrix AFTER extract (see class javadoc).
    @Inject(method = "extract", at = @At("RETURN"))
    private void onExtractEnded(DeltaTracker deltaTracker, boolean bl, CallbackInfo ci) {
        CameraRenderState cameraRenderState = gameRenderState().levelRenderState.cameraRenderState;
        cameraRenderState.viewRotationMatrix = TransformationManager.processTransformation(
            mainCamera, cameraRenderState.viewRotationMatrix
        );
    }

    // S13-H P3(d) LIFECYCLE TAIL (S13H-driver-core-design.md §3 row (d) / IP
    // MixinGameRenderer.onAfterRenderingCenter:127-142). IP fired the frame-END portal lifecycle from
    // an @Inject at the render→renderLevel INVOKE, shift AFTER; the 26.2 anchor is the same call
    // (26.2:GameRenderer.render:425 → renderLevel(DeltaTracker); reached only when a level rendered,
    // matching IP). Runs ONCE per frame after the MAIN world render (the driver core no longer nests a
    // recursive renderLevel, so this INVOKE matches only the main call) and BEFORE guiRenderer.render
    // (:443) — required so _onGameRenderEnd's GUI-portal framebuffers precede the GUI pass.
    //
    // finishRendering() STAYS in the flag-ON dispatch callback (SeamlessPortalsClientFabric:114) — a
    // no-op on RendererUsingStencil; the remaining three run here in verbatim IP order. onTotalRenderEnd
    // restores the current dim's lightmap identity before GUI/next-extract; lateUpdateLight runs at
    // frame-render END (memory portalview-light-engine-half-port), iterating ClientWorldLoader's worlds
    // — disjoint from the block-era TAIL substrate's PortalWorldManager map (which is empty flag-ON).
    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            shift = At.Shift.AFTER
        )
    )
    private void seamlessportals$onAfterRenderingCenter(DeltaTracker deltaTracker, boolean bl, CallbackInfo ci) {
        RenderStates.onTotalRenderEnd();

        GuiPortalRendering._onGameRenderEnd();

        if (IPCGlobal.lateClientLightUpdate) {
            MyRenderHelper.lateUpdateLight();
        }
    }

    // ==== IEGameRenderer ducks (LIVE-called by MyGameRenderer.switchAndRenderTheWorld) ====

    @Override
    public void ip_setLightmapTextureManager(Lightmap manager) {
        lightmap = manager;
    }

    @Override
    public boolean ip_getDoRenderHand() {
        // 26.2: GameRenderer has no `renderHand` flag field (G18). IP's save/restore consumer of this duck
        // was DROPPED at S11-B (hand suppression re-expresses via WorldRenderInfo.doRenderHand + gating the
        // renderItemInHand call at S13); nothing reads this today. Return the vanilla default.
        return true;
    }

    @Override
    public void ip_setCamera(Camera camera_) {
        mainCamera = camera_;
    }

    @Override
    public void ip_setIsRenderingPanorama(boolean cond) {
        // 26.2: GameRenderer.panoramicMode moved onto Camera (isPanoramicMode + enable/disablePanoramicMode,
        // Camera.java:79,495-502) + cameraState.isPanoramicMode. No held caller today (dead like
        // ip_getDoRenderHand), but the duck contract requires a body; delegate to the current main camera.
        if (cond) {
            mainCamera.enablePanoramicMode();
        }
        else {
            mainCamera.disablePanoramicMode();
        }
    }
}
