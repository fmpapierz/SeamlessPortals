package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.apache.commons.lang3.Validate;
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
        }
        catch (Exception e) {
            // Robustness parity with PortalRenderTypes' catch: never leave a null RenderType.
            for (int key = 0; key < 8; key++) {
                if (PORTAL_AREA_TYPES[key] == null) {
                    PORTAL_AREA_TYPES[key] = RenderTypes.debugQuads();
                }
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
}
