package qouteall.imm_ptl.core.render.renderer;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.warwa.seamlessportals.event.Event;
import com.warwa.seamlessportals.event.EventFactory;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.IPModInfoChecking;
// C2-4 D8/D9: the iris renderer imports (ExperimentalIrisPortalRenderer / IrisPortalRenderer /
// IrisCompatibilityPortalRenderer) are DROPPED with the shaders-ON dummy routing — the held
// renderer sources stay in the tree for the C2-5 shaders-ON re-expression decision.
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.Helper;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

// S12-A (U10 first half) port disposition: NEW — the abstract renderer base of IP's renderer family,
// ported VERBATIM from IP:render/renderer/PortalRenderer.java to its verbatim qouteall path. Pinned by
// IPCGlobal.java:6/16 (the `PortalRenderer renderer` slot + the concrete-renderer slots
// rendererUsingStencil/FrameBuffer/Debug/Dummy). The concrete RendererUsingStencil (this stage) extends
// it; RendererUsingFrameBuffer / RendererDummy / RendererDebug are the later-S12 A1 slice. Held/inert
// until S13 (SCC closure); never loaded flag-OFF.
//
// SCOPE: this is the CONTEXT/dispatch base — portal DISCOVERY (getPortalsToRender iterates
// world.entitiesForRendering() for Portal + GlobalPortalStorage, the entity-discovery that replaces the
// block-era FBO-identity discovery at S13, CUTOVER_SPEC §6.2), the per-portal skip-cull, the
// recursive renderPortalContent handoff to MyGameRenderer.renderWorldNew, the portal-transformation
// matrix math, and switchToCorrectRenderer. The R5 reversed-Z depth/stencil CHOREOGRAPHY lives in the
// concrete RendererUsingStencil (this stage), not here.
//
// 26.2 RE-EXPRESSIONS applied (each api-map-sanctioned; no IP LOGIC deviated):
//   * RenderSystem.getProjectionMatrix()  -> getCurrentProjectionMatrix() (render-core G27/G19; the GONE
//        RenderSystem Matrix4f global re-read from the extracted render state — the exact proven idiom
//        MOD:StencilPortalRenderer.buildMainFrustum:66-68 uses). See the helper's derivation.
//   * gameRenderer.getMainCamera()        -> gameRenderer.mainCamera() (mc262 GameRenderer.java:657).
//   * Camera.getPosition()                -> Camera.position() (mc262 Camera.java:359).
//   * GlStateManager (platform)           -> com.mojang.blaze3d.opengl.GlStateManager (26.2 package move).
//   * options.graphicsMode()==FABULOUS    -> gameRenderState().useShaderTransparency() — GraphicsStatus /
//        the Fabulous graphics mode are GONE on 26.2's renderer rewrite; the fabulous-transparency
//        conflict re-anchors onto GameRenderState.useShaderTransparency() (improvedTransparency), R13i /
//        CUTOVER_SPEC §6.4 / ducks G10. Same intent: warn when the shader-transparency path that
//        conflicts with the stencil renderer is active.
//   * net.fabricmc.fabric.api.event.{Event,EventFactory} -> com.warwa.seamlessportals.event.{Event,
//        EventFactory} — F12/B1 loader-seam substitution (R13h "never Fabric types in common code";
//        port-note S10A-loader-seam.md). PORTAL_RENDERING_PREDICATE is one of IP's OWN event objects, so
//        it uses the mod-owned event seam, exactly like Portal.*_SIGNAL / IPGlobal.*_EVENT / the Helper
//        factories. The mod EventFactory.createArrayBacked(Class<? super T>, Function<T[],T>) + Event
//        invoker()/register() mirror Fabric's surface IP consumes 1:1. (This is a direct createArrayBacked
//        caller — Predicate has no Helper factory, so it does not route through Helper like the others.)
//
// FORWARD-REFS (documented S12/S13 debt, NOT translation slips):
//   * compat.IPModInfoChecking (:21 import, :~ checkShaderpack call) — lands S12-B (mission-named;
//     leave the import).
//   * compat.iris_compatibility.{ExperimentalIrisPortalRenderer,IrisCompatibilityPortalRenderer,
//     IrisPortalRenderer} — the S12 Iris compile-shells (only IrisInterface is held today).
//   * IPCGlobal.{rendererUsingFrameBuffer,rendererDebug,rendererDummy} field TYPES — the later-S12 A1
//     renderers (their IPCGlobal field slots already exist; the TYPES resolve at the A1 slice).
// SIGN NOTE (D4.4): the only depth/frustum surface here is getPortalsToRender's portal-cull Frustum —
// see its inline §2.3 reversed-Z-hazard derivation (SAFE: it never reaches SectionOcclusionGraph).
public abstract class PortalRenderer {

    /**
     * An event for filtering whether a portal should render.
     * All listeners' results are ANDed.
     */
    public static final Event<Predicate<Portal>> PORTAL_RENDERING_PREDICATE =
        EventFactory.createArrayBacked(
            Predicate.class,
            (listeners) -> (portal) -> {
                for (Predicate<Portal> listener : listeners) {
                    if (!listener.test(portal)) {
                        return false;
                    }
                }
                return true;
            }
        );

    public static final Minecraft client = Minecraft.getInstance();

    public abstract void onBeforeTranslucentRendering(Matrix4f modelView);

    // will be called when rendering portal
    public abstract void onHandRenderingEnded();

    // will be called when rendering portal
    public void onBeforeHandRendering(Matrix4f modelView) {}

    // this will NOT be called when rendering portal
    public abstract void prepareRendering();

    // this will NOT be called when rendering portal
    public abstract void finishRendering();

    // this will be called when rendering portal entities
    public abstract void renderPortalInEntityRenderer(Portal portal);

    // return true to skip framebuffer clear
    // this will also be called in outer world rendering
    public abstract boolean replaceFrameBufferClearing();

    /**
     * 26.2 re-expression of the GONE {@code RenderSystem.getProjectionMatrix()} (render-core G27/G19) —
     * the LIVE draw projection of the pass currently rendering, the value IP's
     * {@code RenderSystem.getProjectionMatrix()} returned everywhere the renderers read it (stencil-aperture
     * write, Row-11/12 depth restore, portal cull frustum). On 1.21.3 that global was a mutable
     * {@code RenderSystem} {@link Matrix4f}; on 26.2 it is GONE — only {@code getProjectionMatrixBuffer()}
     * survives, returning a {@code GpuBufferSlice} (mc262 RenderSystem.java:186-198), so it cannot be read
     * back as a matrix.
     *
     * <p><b>S13-M P1 fix.</b> Return the BOBBED (and spun) draw projection, NOT the unbobbed
     * {@code cameraRenderState.projectionMatrix}. On 26.2 view-bob + nausea/portal spin ride the PROJECTION
     * (renderLevel:535-557), while {@code cameraRenderState.projectionMatrix} is deliberately kept BOB-FREE
     * (it is the extract-time base, used for culling). IP's ambient {@code RenderSystem.getProjectionMatrix()}
     * was {@code base*bob*spin}; drawing the stencil aperture / cull frustum with the unbobbed base made the
     * aperture WOBBLE against both the frame and the (bobbing) dest content. We rebuild it from
     * {@link RenderStates#capturedMainPassBobbedProjection} (the POST-spin main capture) scaled by
     * {@link PortalRendering#getExtraModelViewScaling()} for the current layer via
     * {@link RenderStates#getPortalDrawProjection}: at the outer (layer-0) site the scaling is 1, so it is the
     * captured main projection; at a nested layer it is that layer's dest DRAW projection (bob scaled to the
     * accumulated portal scale), so a nested aperture aligns with the dest content it is drawn into. This
     * matches IP: the aperture is drawn with the projection of the pass it is written INTO — the portal is
     * pushed only around its OWN content, so the stencil write + depth restore (portal not yet / no longer on
     * the stack) see the OUTER scaling, and the content (portal pushed) sees this-portal scaling.
     *
     * <p>Returns a fresh defensive copy (callers feed it to {@code new Frustum} / install it in
     * {@code ViewAreaRenderer}). Fallback (pre-first-frame, before the first capture): the unbobbed
     * {@code cameraRenderState.projectionMatrix} else {@code RenderStates.basicProjectionMatrix} else
     * identity — never null, since {@code new Frustum} requires a non-null projection.
     */
    protected static Matrix4f getCurrentProjectionMatrix() {
        CameraRenderState cameraRenderState =
            client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        Matrix4f base = (cameraRenderState != null && cameraRenderState.projectionMatrix != null)
            ? cameraRenderState.projectionMatrix
            : RenderStates.basicProjectionMatrix;
        return RenderStates.getPortalDrawProjection(base, PortalRendering.getExtraModelViewScaling());
    }

    protected List<Portal> getPortalsToRender(Matrix4f modelView) {
        Supplier<Frustum> frustumSupplier = Helper.cached(() -> {
            // SIGN NOTE §2.3 (SPIKE-R1 reversed-Z frustum hazard): getCurrentProjectionMatrix() returns
            // the 26.2 reversed-Z render projection. Feeding it to `new Frustum` is SAFE here because
            // this frustum is only used for portal-BoundingBox culling (frustum.isVisible below) — it
            // NEVER reaches SectionOcclusionGraph.addSectionsInFrustum / offsetToFullyIncludeCameraCube
            // (the deterministic render-thread hang §2.3 warns of). The live substrate proves it: this is
            // exactly MOD:StencilPortalRenderer.buildMainFrustum (new Frustum(view, reversed-Z proj) ->
            // isVisible), which runs per frame today. Only a SOG-fed cull needs the conventional-Z
            // culling projection; a plain isVisible does not.
            Frustum frustum = new Frustum(
                modelView,
                getCurrentProjectionMatrix()
            );

            Vec3 cameraPos = client.gameRenderer.mainCamera().position();
            frustum.prepare(cameraPos.x, cameraPos.y, cameraPos.z);

            return frustum;
        });

        ObjectArrayList<Portal> renderables = new ObjectArrayList<>();

        ClientLevel world = client.level;
        assert world != null;
        List<Portal> globalPortals = GlobalPortalStorage.getGlobalPortals(world);
        for (Portal globalPortal : globalPortals) {
            if (!shouldSkipRenderingPortal(globalPortal, frustumSupplier)) {
                renderables.add(globalPortal);
            }
        }

        world.entitiesForRendering().forEach(e -> {
            if (e instanceof Portal portal) {
                if (!shouldSkipRenderingPortal(portal, frustumSupplier)) {
                    renderables.add(portal);
                }
            }
        });

        Vec3 cameraPos = CHelper.getCurrentCameraPos();
        renderables.sort(Comparator.comparingDouble(
            e -> e.getDistanceToNearestPointInPortal(cameraPos)
        ));
        return renderables;
    }

    private static boolean shouldSkipRenderingPortal(Portal portal, Supplier<Frustum> frustumSupplier) {
        if (!portal.isPortalValid()) {
            return true;
        }

        // if max portal layer is 0, the invisible portals will be force rendered
        if (!portal.isVisible() && IPGlobal.maxPortalLayer != 0) {
            return true;
        }

        if (RenderStates.getRenderedPortalNum() >= IPGlobal.portalRenderLimit) {
            return true;
        }

        Vec3 cameraPos = TransformationManager.getIsometricAdjustedCameraPos();

        if (!portal.isRoughlyVisibleTo(cameraPos)) {
            return true;
        }

        if (PortalRendering.isRendering()) {
            Portal outerPortal = PortalRendering.getRenderingPortal();

            if (outerPortal.cannotRenderInMe(portal)) {
                return true;
            }
        }

        double distance = portal.getDistanceToNearestPointInPortal(cameraPos);
        if (distance > getRenderRange()) {
            return true;
        }

        if (IPCGlobal.earlyFrustumCullingPortal) {
            // frustum culling does not work when portal is very close
            if (distance > 0.1) {
                Frustum frustum = frustumSupplier.get();
                if (!frustum.isVisible(portal.getThinBoundingBox())) {
                    return true;
                }
            }
        }

        if (PortalRendering.isInvalidRecursionRendering(portal)) {
            return true;
        }

        boolean predicateTest = PORTAL_RENDERING_PREDICATE.invoker().test(portal);
        if (!predicateTest) {
            return true;
        }

        return false;
    }

    public static double getRenderRange() {
        double range = client.options.getEffectiveRenderDistance() * 16;
        if (RenderStates.isLaggy || IPGlobal.reducedPortalRendering) {
            range = 16;
        }
        if (PortalRendering.getPortalLayer() > 1) {
            //do not render deep layers of mirror when far away
            range /= (PortalRendering.getPortalLayer());
        }
        if (PortalRendering.getPortalLayer() >= 1) {
            double outerPortalScale = PortalRendering.getRenderingPortal().getScale();
            if (outerPortalScale > 2) {
                range *= outerPortalScale;
                range = Math.min(range, 32 * 16);
            }
        }
        return range;
    }

    protected final void renderPortalContent(
        Portal portal
    ) {
        if (PortalRendering.getPortalLayer() > PortalRendering.getMaxPortalLayer()) {
            return;
        }

        ClientLevel newWorld = ClientWorldLoader.getWorld(portal.getDestDim());

        PortalRendering.onBeginPortalWorldRendering();

        int renderDistance = getPortalRenderDistance(portal);

        // armCompileScheduling CONTRACT (S11-B port-note §4 / VisibleSectionDiscovery.armCompileScheduling
        // javadoc; CUTOVER_SPEC §5.1 — the COMPILE half of the extract()/compileSections pairing). This is
        // the DEST-PASS entry: invokeWorldRendering -> MyGameRenderer.switchAndRenderTheWorld ->
        // renderLevel, and discoverVisibleSections runs INSIDE renderLevel (redirected there by the S13
        // MixinLevelRenderer). On 26.2 a hand-fed secondary's discovered sections would strand
        // dirty=false+UNCOMPILED (no vanilla driver compiles them — memories
        // ow-holes-consumed-compile-queue / walking-limbo-seed-overclaim), so the driver MUST call
        // VisibleSectionDiscovery.armCompileScheduling(newWorld, sut, cache, schedSet, budgetNs) BEFORE
        // that renderLevel — where `newWorld` is the destLevel resolved above; sut = the per-dim
        // SectionUpdateTracker off the secondary LevelExtractor; cache = a per-frame RenderRegionCache;
        // schedSet/budgetNs = driver pump state. It auto-disarms in the discovery `finally`, so a later
        // pure-IP caller cannot inherit a stale context. The arm CALL lives in the driver core (the
        // MyGameRenderer invokeWrapper), wired at S13 with that driver-core state — NOT in this verbatim
        // IP context shell (armCompileScheduling is a mod-additive A3 mechanism with no IP analog, and the
        // sut/cache/schedSet/budgetNs are not established until the S13 driver-core wiring). Documented at
        // this seam so S13 arms at exactly the right point.
        invokeWorldRendering(
            new WorldRenderInfo.Builder()
                .setWorld(newWorld)
                .setCameraPos(PortalRendering.getRenderingCameraPos())
                .setCameraTransformation(portal.getAdditionalCameraTransformation())
                .setOverwriteCameraTransformation(false)
                .setDescription(portal.getDiscriminator())
                .setRenderDistance(renderDistance)
                .setDoRenderHand(false)
                .setEnableViewBobbing(true)
                .setDoRenderSky(!portal.isFuseView())
                .build()
        );

        PortalRendering.onEndPortalWorldRendering();

        GlStateManager._enableDepthTest();

        MyRenderHelper.restoreViewPort();


    }

    private static int getPortalRenderDistance(Portal portal) {
        int mcRenderDistance = client.options.getEffectiveRenderDistance();

        if (portal.getScale() > 2) {
            double radiusBlocks = portal.getDestAreaRadiusEstimation() * 1.4;

            radiusBlocks = Math.min(radiusBlocks, 32 * 16);

            return Math.max((int) (radiusBlocks / 16), mcRenderDistance);
        }
        if (IPGlobal.reducedPortalRendering) {
            return mcRenderDistance / 3;
        }
        return mcRenderDistance;
    }

    public void invokeWorldRendering(
        WorldRenderInfo worldRenderInfo
    ) {
        MyGameRenderer.renderWorldNew(
            worldRenderInfo,
            Runnable::run
        );
    }

    @Nullable
    public static Matrix4f getPortalTransformation(Portal portal) {
        Matrix4f rot = getPortalRotationMatrix(portal);

        Matrix4f mirror = portal instanceof Mirror ?
            TransformationManager.getMirrorTransformation(portal.getNormal()) : null;

        Matrix4f scale = getPortalScaleMatrix(portal);

        return combineNullable(rot, combineNullable(mirror, scale));
    }

    @Nullable
    public static Matrix4f getPortalRotationMatrix(Portal portal) {
        if (portal.getRotation() == null) {
            return null;
        }

        Quaternionf rot = portal.getRotation().toMcQuaternion();
        rot.conjugate();
        return rot.get(new Matrix4f());
    }

    @Nullable
    public static Matrix4f combineNullable(@Nullable Matrix4f a, @Nullable Matrix4f b) {
        return Helper.combineNullable(a, b, (m1, m2) -> {
            m1.mul(m2);
            return m1;
        });
    }

    @Nullable
    public static Matrix4f getPortalScaleMatrix(Portal portal) {
        // if it's not a fuseView portal
        // whether to apply scale transformation to camera does not change triangle position
        // to avoid abrupt fog change, do not apply for non-fuse-view portal
        // for fuse-view portal, the depth value should be correct so the scale should be applied
        if (shouldApplyScaleToModelView(portal)) {
            float v = (float) (1.0 / portal.getScale());
            return new Matrix4f().scale(v, v, v);
        }
        return null;
    }

    public static boolean shouldApplyScaleToModelView(Portal portal) {
        return portal.hasScaling() && portal.isFuseView();
    }

    public void onBeginIrisTranslucentRendering(Matrix4f modelView) {}

    /**
     * IS6 §6.1 — THE RECURSION DRIVER HOOK. Called from
     * {@code SecondaryWorldRenderCore.renderDestWorldFullPipeline} at "Point A": immediately after
     * the direct 8-arg {@code destRenderer.render()} has fully RETURNED, with the dest world still
     * swapped in (before the method's own {@code finally} un-does the swap). {@code destViewMatrix}
     * is the exact view matrix that {@code render()} consumed.
     *
     * <p>Inert base no-op — reached only on the FULL-PIPELINE dest path, which is driven ONLY by
     * {@link qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatOn262Renderer}. The stencil
     * and decomposed renderers never call {@code renderDestWorldFullPipeline}, so this dispatch is
     * dead for every receiver except that one, whose override (lever-gated) re-expresses IP's
     * per-layer {@code renderLevel} recursion onto our atomic-render substrate (D16). See
     * port-note {@code IS-iris-shaders-on.md} §6.1.
     */
    public void onDestWorldFinalizedFullPipeline(Matrix4f destViewMatrix) {}

    private static boolean fabulousWarned = false;

    // C2-4 D8: one-shot (per session) notice that a shaderpack-ON frame routes portal views to
    // pass-through. Deliberately NOT reset when the pack is turned off mid-session: the state is
    // announced once; pack-off frames restore full views immediately via the fall-through below.
    private static boolean shadersOnPassThroughNotified = false;

    // IS1/IS4: one-shot (per session) notice that the EXPERIMENTAL shaderpack-views renderer is
    // live under an active pack. After the IS4 Q-U1 default-flip this FIRES AT THE DEFAULT config
    // whenever a shaderpack is active — isShaderpackPortalViewsActive(isShaders()==true) is true via
    // the default-TRUE flag, no lever needed; it also fires under the JVM lever.
    private static boolean shaderpackViewsExperimentNotified = false;

    public static void switchToCorrectRenderer() {
        if (PortalRendering.isRendering()) {
            //do not switch when rendering
            return;
        }

        // 26.2: GraphicsStatus / the Fabulous graphics mode are GONE (renderer rewrite). The
        // fabulous-transparency conflict re-anchors onto GameRenderState.useShaderTransparency()
        // (improvedTransparency) — R13i / CUTOVER_SPEC §6.4 / ducks G10. Same intent: warn once when
        // the shader-transparency path that conflicts with the stencil renderer is active.
        if (client.gameRenderer.gameRenderState().useShaderTransparency()) {
            if (!fabulousWarned) {
                fabulousWarned = true;
                CHelper.printChat(Component.translatable("imm_ptl.fabulous_warning"));
            }
        }

        IPModInfoChecking.checkShaderpack();

        // ===== IS4 Q-U1 (user-decided 2026-07-20) — the shaders-ON compat-renderer routing, now
        // DEFAULT-ON but SHADERS-GATED. isShaderpackPortalViewsActive(isShaders()) is true when a
        // shaderpack is actually running (the default-ON flag) OR the JVM lever forces it (dev
        // proof rows, both shader states). CRITICAL (recon §4.5): passing isShaders() into the
        // flag path is what keeps the default-ON flip's blast radius to shaderpack-ON users only —
        // shaders-OFF / no-pack / plain fall THROUGH this block to the pre-IS1 selection (the
        // stencil family), byte-identical to before the flip. renderMode=none stays the master
        // off-switch (falls through → dummy). renderMode mapping: debug → debugModeInstance, else
        // → instance. The suite runs iris-ABSENT (isShaders()==false) so it is unaffected by the
        // flip and stays a valid gate for the unchanged paths.
        if (IPGlobal.isShaderpackPortalViewsActive(IrisInterface.invoker.isShaders())
            && IPGlobal.renderMode != IPGlobal.RenderMode.none
        ) {
            if (IrisInterface.invoker.isShaders() && !shaderpackViewsExperimentNotified) {
                shaderpackViewsExperimentNotified = true;
                // IS3 §4.4: clipping is now real (the dest terrain clips at the portal plane
                // through the shaderpack), so the old no-clip / expect-artifacts clause is dropped.
                CHelper.printChat(Component.literal(
                    "[Seamless Portals] Experimental shaderpack portal views are ON — portals "
                        + "render through your shaderpack (one recursion layer). Expect some added "
                        + "frame cost while portals are on screen."
                ).withStyle(net.minecraft.ChatFormatting.GOLD));
            }
            switchRenderer(
                IPGlobal.renderMode == IPGlobal.RenderMode.debug
                    ? qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatOn262Renderer.debugModeInstance
                    : qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatOn262Renderer.instance
            );
            return;
        }

        if (IrisInterface.invoker.isIrisPresent()) {
            if (IrisInterface.invoker.isShaders()) {
                // ===== C2-4 D8 NAMED DEVIATION — honest shaders-ON routing =====================
                // IP 1.21.3 selected its iris renderer family here (experimental flag ->
                // ExperimentalIrisPortalRenderer.instance; else renderMode: normal ->
                // IrisPortalRenderer.instance, compatibility -> IrisCompatibilityPortalRenderer
                // .instance, debug -> .debugModeInstance, none -> rendererDummy). All three iris
                // renderers assume IP's recursive full-pipeline renderLevel; our dest path is the
                // decomposed no-framegraph renderDestWorld (S18), so they are DEFER-DORMANT held
                // sources (design §2 files 23/24/25, D9) until the shaders-ON re-expression
                // (user checkpoint at C2-5). Until then a shaderpack-ON frame routes to
                // rendererDummy: portals render as pass-through (visible geometry, no views) —
                // strictly better than the pre-C2-4 blanket iris force-off, and LOUD via the
                // one-shot notice below. Shaders OFF falls through to the normal vanilla-path
                // selection: full stencil-direct portal views with the sodium chains active
                // underneath (iris requires sodium) — IP's exact shaders-OFF arrangement.
                if (!shadersOnPassThroughNotified) {
                    shadersOnPassThroughNotified = true;
                    CHelper.printChat(Component.literal(
                        "[Seamless Portals] Shaderpack portal views are not yet supported — "
                            + "portals render as pass-through. Disable shaders to see portal views."
                    ).withStyle(net.minecraft.ChatFormatting.GOLD));
                }
                switchRenderer(IPCGlobal.rendererDummy);
                return;
            }
        }

        switch (IPGlobal.renderMode) {
            case normal -> switchRenderer(IPCGlobal.rendererUsingStencil);
            case compatibility -> switchRenderer(IPCGlobal.rendererUsingFrameBuffer);
            case debug -> switchRenderer(IPCGlobal.rendererDebug);
            case none -> switchRenderer(IPCGlobal.rendererDummy);
        }
    }

    private static void switchRenderer(PortalRenderer renderer) {
        if (IPCGlobal.renderer != renderer) {
            Helper.log("switched to renderer " + renderer.getClass());
            PortalRenderer oldRenderer = IPCGlobal.renderer;
            IPCGlobal.renderer = renderer;

            // IS1 (mining §8-20 family): evict the compat renderer's deferred buffer when
            // routing AWAY from that family (pack-off/renderMode change under the lever).
            // instanceof does NOT class-initialize IrisCompatOn262Renderer, and oldRenderer can
            // only BE one after an armed route — byte-inert at the committed default.
            if (oldRenderer instanceof qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatOn262Renderer
                && !(renderer instanceof qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatOn262Renderer)
            ) {
                qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatOn262Renderer.onSwitchedAway();
            }

            if (IrisInterface.invoker.isShaders()) {
                // C2-4 verify lens A CORRECTION (the D8 timing sub-fix): IP fired this from
                // BEFORE renderLevel (MixinGameRenderer.onBeforeRenderingCenter), so its
                // destroyPipeline always preceded the frame's iris$setupPipeline. OUR per-frame
                // caller is the re-homed AFTER_TRANSLUCENT_TERRAIN driver — MID-renderLevel —
                // so a direct reload here on the pack-ON transition frame would destroy the
                // pipeline iris$endLevelRender still uses at renderLevel TAIL (finalize + hand
                // rendering on deleted GL objects). DEFER to the next frame's pre-render slot
                // (PRE_GAME_RENDER_TASK_LIST — the frame-pump fires it before gameRenderer
                // .render, i.e. before iris$setupPipeline), restoring IP's destroy-before-
                // prepare ordering exactly. One-frame delay is benign: the renderer switch
                // itself took effect above; iris keeps its current pipeline for the remainder
                // of this frame, exactly as it would have under IP's pre-frame switch timing.
                IPGlobal.PRE_GAME_RENDER_TASK_LIST.addTask(qouteall.q_misc_util.my_util.MyTaskList.oneShotTask(
                    () -> IrisInterface.invoker.reloadPipelines()
                ));
            }
        }
    }
}
