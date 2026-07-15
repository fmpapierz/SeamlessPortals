package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.warwa.seamlessportals.render.PortalRenderTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.apache.commons.lang3.Validate;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.IntStream;

import static org.lwjgl.opengl.GL11.GL_BACK;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_FRONT;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.glCullFace;
import static org.lwjgl.opengl.GL11.glReadPixels;

// S11-B (Slice A) port disposition: NEW (IP-add, current-mod-render §5; the mod has no MyRenderHelper —
// its analogs are inline on PortalContextSwitch / PortalWorldManager). Pinned held contracts: init()
// (IPModMainClient.java:79) and the public static `client` field (RenderStates.java:113,139,172). Ports
// IP's MyRenderHelper (IP :61) with the mod's PROVEN 26.2 mechanisms transplanted where IP's 1.21.3
// calls are GONE. Held/inert until S13.
//
// SLICE SPLIT (probe-honesty): IP's MyRenderHelper is one 621-line class, but roughly half of it is the
// 1.21.3 immediate-mode SHADER/BLIT draw family — the three CoreShaders (BLIT_SCREEN_NOBLEND /
// PORTAL_AREA / PORTAL_DRAW_FB_IN_AREA), drawPortalAreaWithFramebuffer, the renderScreenTriangle
// overloads, drawScreenFrameBuffer / drawFramebuffer* / drawFramebufferWithCoordinatesAndDimensions,
// and clearAlphaTo1. Every one of those rests on the ENTIRE submit->prepare->execute rewrite that is
// GONE on 26.2 (render-core G6 GL-state, G7 Tesselator, G8 BufferUploader, G9 ShaderProgram/CoreShaders/
// CoreShadersAccessor, G12 RenderTarget.bindWrite/viewWidth, G29 GlStateManager blend enums, G40
// DefaultVertexFormat.BLIT_SCREEN). Their 26.2 form is the mod's PROVEN PortalRenderTypes substrate
// (drawMesh + the blit RenderPipelines, current-mod-render §1.4 KEEP) and their ONLY consumers are the
// still-unauthored held classes GuiPortalRendering / OverlayRendering / RendererUsingFrameBuffer (A1).
// So that draw family is authored WITH those consuming slices (later S11-B/S12 commits), re-expressed
// onto PortalRenderTypes.drawMesh + RenderTarget.blitAndBlendToTexture — NOT half-ported here against
// GONE symbols (which would ADD probe errors instead of reducing them). Deferring adds ZERO forward-ref:
// nothing currently authored calls those methods. This class carries the load-bearing survivors the
// mission names (lateUpdateLight, the endFrame discipline via earlyRemoteUpload's upload pump, the
// extract/compileSections pairing that MyGameRenderer/PortalContextSwitch host) plus the raw-GL /
// pure-logic survivors.
//
// SIGN NOTE (D4.4): nothing here clears depth, sets a depth compare, writes a stencil op, or uploads a
// clip plane, so R5 reversed-Z does not touch this class. glCullFace(GL_FRONT/GL_BACK) is winding-
// neutral face-select for the mirror pass (raw GL, GL-backend only, render-core fact 4) and is ported
// byte-for-byte. render-thread-logging discipline (memory render-thread-logging-log4j-stall): no per-
// frame LOGGER call; the two debug* dumps are gated behind `debugEnabled=false` and never fire in
// steady state.
public class MyRenderHelper {

    // Pinned by RenderStates.java:113/139/172 (client.getCameraEntity / client.gameRenderer / client.gui).
    public static final Minecraft client = Minecraft.getInstance();

    public static void init() {
        // IP's init() body was entirely commented-out 1.21.3 ShaderInstance loading (IP :86-132): the
        // three custom core shaders were declared as static ShaderProgram fields via CoreShadersAccessor
        // (render-core G9). On 26.2 those become mod RenderPipelines registered in the mod's
        // PortalRenderTypes static block (KEEP substrate). Nothing to load here. Kept as the pinned
        // IPModMainClient.java:79 entry point.
    }

    // ===== S11-B FixGaps: the GONE `portalAreaShader` re-expressed as a 26.2 RenderPipeline family =====
    // Resolves the fragments/S11B-viewarea.md §3.1 handoff (ViewAreaRenderer.java:89 -> this method), the
    // one S11-B-internal forward-ref whose documented resolution stage IS this stage (the MyRenderHelper
    // commit) rather than S13+. IP drew the portal view-area mesh with a custom `portalAreaShader`
    // ShaderInstance + per-call GlStateManager color-mask/depth-mask/cull toggles (render-core G6/G9 — all
    // GONE on 26.2's submit->prepare->execute rewrite). The 26.2 re-expression is a RenderType whose baked
    // RenderPipeline carries IP's draw-state DECISIONS, resolved by ViewAreaRenderer into the three
    // booleans (writeColor, writeDepth, doFaceCulling). Built with the SAME reflective
    // RenderPipelines.register + RenderType.create pattern the mod's PROVEN
    // com.warwa...render.PortalRenderTypes uses, reusing its exact 26.2 constants — but with
    // PrimitiveTopology.TRIANGLES (NOT PortalRenderTypes' QUADS): the view-area mesh is triangulated
    // (ViewAreaRenderer.buildPortalViewAreaTrianglesBuffer / outputFullQuad), and drawMesh selects the
    // sequential index buffer off the pipeline topology, which must match the mesh.
    //
    // R5 reversed-Z (S11-B-viewarea §3.1): the depth COMPARE is the 26.2 DEFAULT GREATER_THAN_OR_EQUAL
    // (clear 0.0 = far), the EXACT convention the live PortalRenderTypes already ships
    // (PortalRenderTypes.java:110). writeDepth selects the depth WRITE; writeColor selects
    // ColorTargetState.WRITE_NONE vs the builder's full-write default (omit); doFaceCulling selects
    // withCull(...). No new sign-derivation — reuses proven 26.2 defaults. The mirror-reverse cull stays
    // ViewAreaRenderer's raw glCullFace (applyMirrorFaceCulling) for now; the §3.1 "raw glCullFace is
    // clobbered by applyPipelineState -> make it a front/back pipeline selection" refinement is an S12
    // runtime item, not required to close this forward-ref. Held/inert until S13; the 8 pipelines register
    // on first class-load (S13+, device ready), never under flag-OFF (this held class is not loaded then).
    private static final RenderType[] PORTAL_AREA_TYPES = new RenderType[8];

    // S12-A: the STENCIL_ONLY screen-triangle pipeline (R5 Row 15, clampStencilValue) — a full-screen
    // core/screenquad draw with the depth test DISABLED (Optional.empty()) and color masked OFF
    // (WRITE_NONE). Neither of the proven substrate pipelines fits Row 15 (portalScreenDepthClear writes
    // depth; portalCompositeBlit writes color), so this one purpose-pipeline is added here alongside the
    // consumed substrate ones. Registered device-ready on first class-load (S13+); see renderScreenTriangle.
    private static RenderPipeline SCREEN_TRIANGLE_STENCIL_ONLY;

    private static int portalAreaKey(boolean writeColor, boolean writeDepth, boolean doFaceCulling) {
        return (writeColor ? 4 : 0) | (writeDepth ? 2 : 0) | (doFaceCulling ? 1 : 0);
    }

    static {
        try {
            Method registerMethod =
                RenderPipelines.class.getDeclaredMethod("register", RenderPipeline.class);
            registerMethod.setAccessible(true);
            Method createMethod =
                RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
            createMethod.setAccessible(true);

            for (int key = 0; key < 8; key++) {
                boolean writeColor = (key & 4) != 0;
                boolean writeDepth = (key & 2) != 0;
                boolean doFaceCulling = (key & 1) != 0;

                RenderPipeline.Builder builder = RenderPipeline.builder()
                    .withLocation("seamlessportals/pipeline/portal_area_" + key)
                    .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                    .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                    .withVertexShader("core/position_color")
                    .withFragmentShader("core/position_color")
                    .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    // 26.2 reversed-Z: GEQUAL depth test (was LEQUAL); write = writeDepth (§3.1).
                    .withDepthStencilState(
                        new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, writeDepth))
                    .withCull(doFaceCulling);

                if (!writeColor) {
                    // color-mask OFF (IP's isFuseView()&&maxPortalLayer!=0 OR !doModifyColor case): the
                    // draw still rasterizes (stencil/clip) but writes no color. Full color = builder
                    // default (omit withColorTargetState), matching PortalRenderTypes' full-color pipeline.
                    builder = builder.withColorTargetState(
                        new ColorTargetState(
                            Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE)
                    );
                }

                RenderPipeline pipeline = (RenderPipeline) registerMethod.invoke(null, builder.build());
                PORTAL_AREA_TYPES[key] = (RenderType) createMethod.invoke(null,
                    "seamlessportals_portal_area_" + key,
                    RenderSetup.builder(pipeline).createRenderSetup()
                );
            }

            // R5 Row 15 (clampStencilValue) STENCIL_ONLY: screenquad full-screen draw, depth test OFF,
            // color WRITE_NONE. Same core/screenquad + core/blit_screen + GLOBALS + IN_SAMPLER shape as
            // PortalRenderTypes' composite/depth-clear pipelines (proven), but depth-off + color-off so
            // the pass ONLY triggers the caller's raw glStencilOp(KEEP,REPLACE,REPLACE) clamp (stencil
            // state persists — applyPipelineState never touches stencil).
            RenderPipeline stencilOnlyScreen = RenderPipeline.builder()
                .withLocation("seamlessportals/pipeline/screen_triangle_stencil_only")
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withVertexShader("core/screenquad")
                .withFragmentShader("core/blit_screen")
                .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(
                    new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
                .withDepthStencilState(Optional.empty()) // depth test OFF
                .withCull(false)
                .build();
            SCREEN_TRIANGLE_STENCIL_ONLY = (RenderPipeline) registerMethod.invoke(null, stencilOnlyScreen);
        }
        catch (Exception e) {
            // Robustness parity with PortalRenderTypes' catch: never leave a null RenderType/pipeline.
            for (int key = 0; key < 8; key++) {
                if (PORTAL_AREA_TYPES[key] == null) {
                    PORTAL_AREA_TYPES[key] = RenderTypes.debugQuads();
                }
            }
            if (SCREEN_TRIANGLE_STENCIL_ONLY == null) {
                SCREEN_TRIANGLE_STENCIL_ONLY = RenderPipelines.TRACY_BLIT;
            }
        }
    }

    /**
     * 26.2 re-expression of IP's GONE {@code portalAreaShader} draw-state selection. Returns the
     * {@link RenderType} whose baked {@link RenderPipeline} carries IP's per-call color-mask /
     * depth (mask + GEQUAL reversed-Z test) / face-cull decisions — resolved by {@code ViewAreaRenderer}
     * into the three booleans (ViewAreaRenderer.java:89). See {@code fragments/S11B-viewarea.md} §3.1.
     */
    public static RenderType getPortalAreaRenderType(
        boolean writeColor, boolean writeDepth, boolean doFaceCulling
    ) {
        return PORTAL_AREA_TYPES[portalAreaKey(writeColor, writeDepth, doFaceCulling)];
    }

    // it will remove the light sections that are marked to be removed
    // if not, light data will cause minor memory leak
    // and wrongly remove the light data when the chunks get reloaded to client
    // this should not run before world rendering or the smooth lighting may become abnormal in section edge
    //
    // S11-B carriage (S11-A §3 row lateUpdateLight, flag B7): PORTED VERBATIM from IP. The mod's proven
    // body (PortalWorldManager.lateUpdateSecondaryLight, commit 3a2c14e) is re-sourced onto
    // getClientWorlds() + isDimensionRendered() — which is exactly IP's own form here, because
    // runLightUpdates() survives unchanged on 26.2. The mod-only isDestScopeLive perf gate is DROPPED
    // (B7: IP gates only on isDimensionRendered; isDestScopeLive stays in the block-era path). Stays at
    // frame-END, never mid-tick (IP's own smooth-lighting note; memory portalview-light-engine-half-port).
    public static void lateUpdateLight() {
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }

        ClientWorldLoader.getClientWorlds().forEach(world -> {
            if (!RenderStates.isDimensionRendered(world.dimension())) {
                world.getChunkSource().getLightEngine().runLightUpdates();
            }
        });
    }

    /**
     * If we don't do this
     * the future created in {@link SectionRenderDispatcher#uploadSectionLayer}
     * may never complete
     *
     * <p>S11-B re-expression (render-core G26): IP's 1.21.3
     * {@code SectionRenderDispatcher.uploadAllPendingUploads()} is GONE. 26.2 publishes staged terrain
     * meshes via {@code sectionRenderDispatcher.lock(); uploadTerrainBuffersToGpu(); unlock()} — the
     * exact per-frame pump vanilla runs at {@code LevelRenderer.render} (mc262 LevelRenderer.java:257-264).
     * For every not-currently-rendered secondary dimension we run that pump so async
     * {@code compileAsync} results land. {@code LevelRenderer.getSectionRenderDispatcher()} (public in
     * 1.21.3) became the public {@code sectionRenderDispatcher()} (mc262 LevelRenderer.java:908,
     * {@code @Nullable}). Whether the staging path can still stall without this is an S12/S18 runtime-
     * verification item (G26), not a blocker.
     */
    public static void earlyRemoteUpload() {
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }

        ClientWorldLoader.WORLD_RENDERER_MAP.forEach((dim, worldRenderer) -> {
            Validate.notNull(client.level, "client.level is null");
            if (client.level.dimension() != dim) {
                SectionRenderDispatcher dispatcher = worldRenderer.sectionRenderDispatcher();
                if (dispatcher != null) {
                    dispatcher.lock();
                    try {
                        dispatcher.uploadTerrainBuffersToGpu();
                    }
                    finally {
                        dispatcher.unlock();
                    }
                }
            }
        });
    }

    public static void applyMirrorFaceCulling() {
        glCullFace(GL_FRONT);
    }

    public static void recoverFaceCulling() {
        glCullFace(GL_BACK);
    }

    public static void restoreViewPort() {
        Minecraft client = Minecraft.getInstance();
        GlStateManager._viewport(
            0,
            0,
            client.getWindow().getWidth(),
            client.getWindow().getHeight()
        );
    }

    public static float transformFogDistance(float value) {
        if (!WorldRenderInfo.isFogEnabled()) {
            return value * 23333;
        }

        // just disable fog for fuse-view portals for now
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();

            if (renderingPortal.isFuseView()) {
                return value * 23333;
            }
        }

        // as non-fuse-view portals does not apply scale transformation to modelview,
        // there is no need to transform fog distance (both with and without sodium)

        return value;
    }

    private static boolean debugEnabled = false;

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    public static void debugFramebufferDepth() {
        if (!debugEnabled) {
            return;
        }
        debugEnabled = false;

        // 26.2: Minecraft.getMainRenderTarget() is GONE; the target lives on GameRenderer
        // (render-core G14 -> gameRenderer.mainRenderTarget()). RenderTarget.width/height are public.
        int width = client.gameRenderer.mainRenderTarget().width;
        int height = client.gameRenderer.mainRenderTarget().height;


        ByteBuffer directBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);

        FloatBuffer floatBuffer = directBuffer.asFloatBuffer();

        glReadPixels(
            0, 0, width, height,
            GL_DEPTH_COMPONENT, GL_FLOAT, floatBuffer
        );

        float[] data = new float[width * height];

        floatBuffer.rewind();
        floatBuffer.get(data);

        float maxValue = (float) IntStream.range(0, data.length)
            .mapToDouble(i -> data[i]).max().getAsDouble();
        float minValue = (float) IntStream.range(0, data.length)
            .mapToDouble(i -> data[i]).min().getAsDouble();

        byte[] grayData = new byte[width * height];
        for (int i = 0; i < data.length; i++) {
            float datum = data[i];

            datum = (datum - minValue) / (maxValue - minValue);

            grayData[i] = (byte) (datum * 255);
        }

        BufferedImage bufferedImage =
            new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        bufferedImage.setData(
            Raster.createRaster(
                bufferedImage.getSampleModel(),
                new DataBufferByte(grayData, grayData.length), new Point()
            )
        );

        System.out.println("oops");
    }

    public static void debugFramebufferColorRed() {
        if (!debugEnabled) {
            return;
        }
        debugEnabled = false;

        int width = client.gameRenderer.mainRenderTarget().width;
        int height = client.gameRenderer.mainRenderTarget().height;


        ByteBuffer directBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);

        FloatBuffer floatBuffer = directBuffer.asFloatBuffer();

        glReadPixels(
            0, 0, width, height,
            GL_RED, GL_FLOAT, floatBuffer
        );

        float[] data = new float[width * height];

        floatBuffer.rewind();
        floatBuffer.get(data);

        float maxValue = (float) IntStream.range(0, data.length)
            .mapToDouble(i -> data[i]).max().getAsDouble();
        float minValue = (float) IntStream.range(0, data.length)
            .mapToDouble(i -> data[i]).min().getAsDouble();

        byte[] grayData = new byte[width * height];
        for (int i = 0; i < data.length; i++) {
            float datum = data[i];

            datum = (datum - minValue) / (maxValue - minValue);

            grayData[i] = (byte) (datum * 255);
        }

        BufferedImage bufferedImage =
            new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        bufferedImage.setData(
            Raster.createRaster(
                bufferedImage.getSampleModel(),
                new DataBufferByte(grayData, grayData.length), new Point()
            )
        );

        System.out.println("oops");
    }

    // ===== S12-A (U10 A1 slice): the deferred immediate-mode blit-draw family =====
    // S11-B DEFERRED this family (S11B-render-drivers.md §1.2): IP's drawPortalAreaWithFramebuffer,
    // drawScreenFrameBuffer, renderScreenTriangle* all rest on the 1.21.3 submit->prepare->execute /
    // CoreShaders / RenderTarget.bindWrite path that is GONE on 26.2 (render-core G6/G7/G8/G9/G12/G29/G40),
    // to be "authored WITH those consuming slices ... re-expressed onto PortalRenderTypes.drawMesh +
    // RenderTarget.blitAndBlendToTexture substrate". This IS the consuming slice: RendererUsingFrameBuffer
    // (A1, reachable via renderMode=compatibility) consumes drawPortalAreaWithFramebuffer; drawScreenFrameBuffer
    // is consumed by the never-loaded Iris shells (IrisPortalRenderer/IrisCompatibility — no Iris build on 26.2,
    // IrisInterface.isIrisPresent()=false forever, so their classes never load). **renderScreenTriangle is NOT
    // Iris-only** — its PRIMARY consumer is RendererUsingStencil (the S13 cutover-core stencil driver,
    // renderMode=normal DEFAULT), which draws it at all three choreography sites (replaceFrameBufferClearing
    // Row 16 / clearDepthOfThePortalViewArea Row 7 / clampStencilValue Row 15); the never-loaded
    // ExperimentalIrisPortalRenderer also consumes it (Rows 7/15). So renderScreenTriangle is a LIVE cutover-core
    // draw, re-expressed onto the proven per-purpose screenquad pipelines below (NOT deferred to the S18 Iris
    // gap). Held/inert until S13; drawPortalAreaWithFramebuffer/drawScreenFrameBuffer stay A1 PERIPHERY,
    // runtime-verified at S18 (CUTOVER_SPEC §6.5).
    //
    // These re-express IP's LOGIC onto the mod's PROVEN KEEP substrate (PortalRenderTypes.drawMesh /
    // portalCompositeBlit — CONSUMED, never duplicated) + vanilla RenderTarget.blitAndBlendToTexture. The
    // exact GPU-level fidelity of the portal-AREA screen-space FBO sampling (IP's GONE PORTAL_DRAW_FB_IN_AREA
    // custom shader, render-core G9 — no 26.2 core-profile analog) and the per-call blend/alpha-mode selection
    // (GONE BLIT_SCREEN shaders, G29/G40) is the documented S18/A1 GPU-refinement; no IP LOGIC in the consuming
    // renderer classes is deviated.
    //
    // SIGN NOTE (D4.4 / R5, CUTOVER_SPEC row 12 FBO-mode): drawPortalAreaWithFramebuffer is the FBO-mode
    // composite of row 12. IP's op #12 (re-render the view-area mesh at its real projected depth to restore a
    // depth shield) has, in the mod's stencil-direct driver, the STEP-3.5 NEAR-shield form (glDepthRange(1,1),
    // StencilPortalRenderer:408) written BEFORE the composite. The FBO-mode composite here uses the mod's
    // portalCompositeBlit pipeline (Optional.empty() depth = _disableDepthTest, PortalRenderTypes.java:199),
    // so the composite is NOT depth-gated (no reversed-Z compare to flip) and no separate NEAR shield is
    // written on this path — the FBO's own depth already carries the dest scene. This is the row-12 FBO-mode
    // STEP-3.5 case: the shield direction question does not arise because the composite pass runs depth-test-off.

    /**
     * IP's {@code drawPortalAreaWithFramebuffer}: composite the SECONDARY FBO's rendered dest world back onto
     * the main frame through the portal area. Consumed by {@code RendererUsingFrameBuffer} (renderMode=
     * compatibility) and the never-loaded {@code IrisCompatibilityPortalRenderer}.
     *
     * <p>26.2 re-expression: IP drew the portal view-area TRIANGLE mesh with a custom
     * {@code PORTAL_DRAW_FB_IN_AREA} shader that sampled {@code textureProvider}'s color texture by
     * screen-space coords (w/h uniforms) — the GONE 1.21.3 shader path (render-core G9). The mod's PROVEN
     * FBO->screen composite is a full-screen screenquad blit through {@code portalCompositeBlit} sampling the
     * FBO color view (the {@code PortalContextSwitch.compositePortalFbo} idiom). {@code RendererUsingFrameBuffer}
     * disables stencil, so this composite covers the whole opening; limiting it to the portal SHAPE
     * (screen-space UV clipped to the view-area mesh) is the S18/A1 GPU-refinement. The {@code modelViewMatrix}
     * / {@code projectionMatrix} params are vestigial on this full-screen path (IP used them to place the
     * mesh); kept for IP API-shape parity.
     */
    public static void drawPortalAreaWithFramebuffer(
        Portal portal,
        RenderTarget textureProvider,
        Matrix4f modelViewMatrix,
        Matrix4f projectionMatrix
    ) {
        if (textureProvider.getColorTextureView() == null) {
            return;
        }
        RenderTarget mainRt = client.gameRenderer.mainRenderTarget();
        if (mainRt.getColorTextureView() == null) {
            return;
        }

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "seamlessportals_fbo_area_composite",
            mainRt.getColorTextureView(),
            Optional.empty(),
            mainRt.getDepthTextureView(),
            OptionalDouble.empty(),
            new RenderPass.RenderArea(0, 0, mainRt.width, mainRt.height)
        )) {
            // portalCompositeBlit: Optional.empty() depth state -> depth-test OFF (PortalRenderTypes.java:199),
            // so the composite is never reversed-Z GEQUAL-gated by leftover portal-plane depth (the mod's
            // curtain fix). Full-screen screenquad triangle sampling the FBO color view.
            pass.setPipeline(PortalRenderTypes.portalCompositeBlit());
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture(
                "InSampler", textureProvider.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            );
            pass.draw(3, 1, 0, 0);
        }
    }

    /**
     * IP's {@code drawScreenFrameBuffer}: blit a source FBO's color onto the currently-bound target as a
     * full-screen quad, with optional alpha-blend / alpha-write control. Consumed ONLY by the never-loaded
     * Iris shells ({@code IrisPortalRenderer} / {@code IrisCompatibilityPortalRenderer}).
     *
     * <p>26.2 re-expression: the 1.21.3 {@code BLIT_SCREEN}/{@code BLIT_SCREEN_NOBLEND} CoreShaders + the
     * per-call {@code blendFuncSeparate} (render-core G29/G40) and the bound-FBO target concept (render-sub G8)
     * are GONE. {@code RenderTarget.blitAndBlendToTexture} (RenderTarget.java:97 — the vanilla FBO->texture
     * blit) is the closest proven analog; it targets the main render target's views. The blend/alpha-mode
     * booleans and the Iris deferred-FBO target (which has no 26.2 bound-target analog) are the documented
     * S18/never-run Iris gap — these renderers never load on 26.2 (no Iris build).
     */
    public static void drawScreenFrameBuffer(
        RenderTarget textureProvider,
        boolean doUseAlphaBlend,
        boolean doEnableModifyAlpha
    ) {
        RenderTarget mainRt = client.gameRenderer.mainRenderTarget();
        if (textureProvider.getColorTextureView() == null
            || mainRt.getColorTextureView() == null
            || mainRt.getDepthTextureView() == null) {
            return;
        }
        textureProvider.blitAndBlendToTexture(
            mainRt.getColorTextureView(), mainRt.getDepthTextureView()
        );
    }

    // ===== S12-A: the per-purpose full-screen screen-triangle re-expression (renderScreenTriangle) =====
    // IP's renderScreenTriangle drew a POSITION_COLOR full-screen NDC triangle whose pixels were shaped
    // ENTIRELY by the CALLER's ambient raw-GL depth/stencil/color state (render-core G6/G7/G8/G9 — that
    // identity-ortho immediate shader draw is GONE on 26.2; and a POSITION_COLOR mesh drawn through the
    // world projection would be mis-transformed). The PROVEN 26.2 re-expression is the mod's stencil-gated
    // full-screen SCREENQUAD draw (core/screenquad generates the 3 full-screen positions from gl_VertexID —
    // no vertex buffer, no matrices, so the NDC-vs-world-projection question never arises — exactly what
    // StencilPortalRenderer.drawScreenFillStencilGated / drawScreenDepthClearStencilGated prove at runtime).
    // Because 26.2 pipelines DICTATE depth/color state (applyPipelineState), each caller passes its INTENT as
    // a ScreenTrianglePurpose that selects the correct per-purpose pipeline — restoring the exact per-caller
    // effect IP got from ambient GL state (CUTOVER_SPEC §2.1 rows):
    //
    //   * DEPTH_CLEAR  (R5 Row 7, clearDepthOfThePortalViewArea): ALWAYS_PASS depth test + depth WRITE +
    //       color OFF. Consumes PortalRenderTypes.portalScreenDepthClear() (the proven substrate pipeline:
    //       ALWAYS_PASS + write + WRITE_NONE). The written depth VALUE comes from the caller's
    //       glDepthRange(0,0) = reversed-Z FAR — applyPipelineState never touches glDepthRange, so the FAR
    //       value lands, pushing the opening to FAR so the dest terrain (drawn next under GEQUAL) all passes.
    //   * STENCIL_ONLY (R5 Row 15, clampStencilValue): depth test DISABLED + color OFF. A stencil-only pass:
    //       the caller's raw glStencilFunc(GL_LESS,…)+glStencilOp(KEEP,REPLACE,REPLACE) persists
    //       (applyPipelineState never touches stencil) and REPLACEs the clamped stencil values; no depth
    //       compare (nothing to reversed-Z flip), no color splat.
    //   * COLOR_FILL   (R5 Row 16, replaceFrameBufferClearing): depth test DISABLED + FULL color write.
    //       Consumes PortalRenderTypes.portalCompositeBlit() (depth-off), so the dest sky/fog fill is
    //       depth-INDEPENDENT (never reversed-Z GEQUAL-gated by leftover portal-plane depth); the fog COLOR
    //       rides a 1x1 texture through core/blit_screen (the proven substrate's color mechanism, mirroring
    //       StencilPortalRenderer.ensureScreenFillTexture).
    //
    // The caller's raw glStencilFunc EQUAL(layer) (set in the choreography) SURVIVES into every purpose draw
    // (applyPipelineState never touches stencil), so all three stay gated to the portal opening — exactly as
    // IP relied on the ambient stencil test.
    //
    // CONSUMERS (correcting the earlier "Iris shells only" prose): RendererUsingStencil — the S13 cutover-core
    // stencil driver, renderMode=normal DEFAULT — calls all three purposes; the never-loaded
    // ExperimentalIrisPortalRenderer calls DEPTH_CLEAR + STENCIL_ONLY.

    /** Screen-triangle intent — selects the per-purpose 26.2 pipeline that carries the depth/color state IP
     *  got from ambient raw-GL. See the class-comment block above for each row's derivation. */
    public enum ScreenTrianglePurpose {
        /** R5 Row 7: ALWAYS_PASS + depth WRITE + color OFF (the depth VALUE comes from caller glDepthRange). */
        DEPTH_CLEAR,
        /** R5 Row 15: depth test OFF + color OFF (stencil-only clamp pass). */
        STENCIL_ONLY,
        /** R5 Row 16: depth test OFF + FULL color write (depth-independent dest fog fill). */
        COLOR_FILL
    }

    // 1x1 fill texture feeding core/blit_screen the screen-triangle color (mirrors
    // StencilPortalRenderer.ensureScreenFillTexture). Only COLOR_FILL needs a real color; DEPTH_CLEAR /
    // STENCIL_ONLY mask color off, so they reuse whatever is current (no needless re-upload). Lazily created
    // device-ready (S13+).
    private static GpuTexture screenTriTexture;
    private static GpuTextureView screenTriTextureView;
    private static int screenTriTextureArgb;

    private static GpuTextureView ensureScreenTriTexture(int argb) {
        GpuDevice device = RenderSystem.getDevice();
        if (screenTriTexture == null) {
            screenTriTexture = device.createTexture(
                "seamlessportals screen triangle fill",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
            screenTriTextureView = device.createTextureView(screenTriTexture);
            screenTriTextureArgb = ~argb; // force the first upload
        }
        if (screenTriTextureArgb != argb) {
            // RGBA8_UNORM uploads as GL_RGBA + GL_UNSIGNED_BYTE -> byte order R,G,B,A.
            ByteBuffer px = ByteBuffer.allocateDirect(4);
            px.put((byte) ((argb >> 16) & 0xFF));
            px.put((byte) ((argb >> 8) & 0xFF));
            px.put((byte) (argb & 0xFF));
            px.put((byte) ((argb >> 24) & 0xFF));
            px.flip();
            device.createCommandEncoder().writeToTexture(screenTriTexture, px, 0, 0, 0, 0, 1, 1);
            screenTriTextureArgb = argb;
        }
        return screenTriTextureView;
    }

    public static void renderScreenTriangle(ScreenTrianglePurpose purpose) {
        renderScreenTriangle(255, 255, 255, 255, purpose);
    }

    public static void renderScreenTriangle(Vec3 color, ScreenTrianglePurpose purpose) {
        renderScreenTriangle(
            (int) (color.x * 255),
            (int) (color.y * 255),
            (int) (color.z * 255),
            255,
            purpose
        );
    }

    /**
     * IP's full-screen color triangle, re-expressed onto the proven stencil-gated screenquad draw with a
     * per-purpose pipeline (see the class-comment block above for each R5 row). Consumed by
     * {@code RendererUsingStencil} (the S13 cutover-core stencil driver, renderMode=normal DEFAULT) at all
     * three choreography sites and by the never-loaded {@code ExperimentalIrisPortalRenderer}
     * (DEPTH_CLEAR + STENCIL_ONLY). The caller's raw {@code glStencilFunc EQUAL(layer)} + {@code glDepthRange}
     * survive into the draw (applyPipelineState touches neither stencil nor depth-range), so the fill stays
     * gated to the portal opening and Row-7's FAR depth value lands.
     */
    public static void renderScreenTriangle(int r, int g, int b, int a, ScreenTrianglePurpose purpose) {
        RenderTarget mainRt = client.gameRenderer.mainRenderTarget();
        if (mainRt.getColorTextureView() == null || mainRt.getDepthTextureView() == null) {
            return;
        }

        // COLOR_FILL carries a real color (the dest fog); the masked purposes reuse the current fill (mirrors
        // StencilPortalRenderer.drawScreenDepthClearStencilGated — avoids a needless 1x1 re-upload each frame).
        int argb = (a << 24) | (r << 16) | (g << 8) | b;
        GpuTextureView texView = (purpose == ScreenTrianglePurpose.COLOR_FILL)
            ? ensureScreenTriTexture(argb)
            : ensureScreenTriTexture(screenTriTexture == null ? 0xFF000000 : screenTriTextureArgb);
        if (texView == null) {
            return;
        }

        RenderPipeline pipeline;
        if (purpose == ScreenTrianglePurpose.DEPTH_CLEAR) {
            pipeline = PortalRenderTypes.portalScreenDepthClear();
        }
        else if (purpose == ScreenTrianglePurpose.COLOR_FILL) {
            pipeline = PortalRenderTypes.portalCompositeBlit();
        }
        else {
            pipeline = SCREEN_TRIANGLE_STENCIL_ONLY;
        }

        // Raw-GL backstops for the applyPipelineState short-circuit (skips state re-apply when lastPipeline is
        // unchanged) — the exact discipline StencilPortalRenderer's stencil-gated draws use. They AGREE with
        // the selected pipeline (DEPTH_CLEAR needs the depth test ENABLED for its write; the depth-off purposes
        // need it DISABLED), which is the only case a raw backstop is sound (it never contradicts the pipeline).
        GL11.glDisable(GL11.GL_BLEND);
        if (purpose == ScreenTrianglePurpose.DEPTH_CLEAR) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
        else {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "seamlessportals_screen_triangle",
            mainRt.getColorTextureView(),
            Optional.empty(),
            mainRt.getDepthTextureView(),
            OptionalDouble.empty(),
            new RenderPass.RenderArea(0, 0, mainRt.width, mainRt.height)
        )) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture(
                "InSampler", texView,
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            );
            pass.draw(3, 1, 0, 0);
        }

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }
}
