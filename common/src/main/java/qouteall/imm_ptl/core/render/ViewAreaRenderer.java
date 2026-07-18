package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.warwa.seamlessportals.render.PortalRenderTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.q_misc_util.my_util.TriangleConsumer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Draws the portal view-area mesh (the shape-polymorphic portal opening; RECTANGULAR/BOX/SPECIAL_FLAT
 * via {@link Portal#renderViewAreaMesh}). Re-expressed onto 26.2's submit->prepare->execute render
 * rewrite (MIGRATION_API_MAP headline): the removed immediate-mode stack
 * ({@code Tesselator}/{@code BufferUploader}/{@code ShaderInstance}/{@code RenderSystem.setShader} —
 * render-core G5/G7/G8/G9) becomes a {@link BufferBuilder}-built {@link MeshData} drawn through the
 * mod's proven {@link PortalRenderTypes#drawMesh(RenderType, MeshData)} immediate-draw path.
 *
 * <p><b>Draw-state (render-core G6):</b> the global GL state IP toggled per call
 * ({@code GlStateManager._colorMask}/{@code _depthMask}/{@code _enableCull}/{@code _enableDepthTest})
 * is now baked into the selected {@link RenderType}'s {@code RenderPipeline}. IP's flag DECISIONS are
 * preserved verbatim and resolved into {@code writeColor}/{@code writeDepth}/{@code doFaceCulling},
 * which pick the pipeline via {@code MyRenderHelper} (the 26.2 re-expression of the GONE
 * {@code portalAreaShader}). The raw-GL clip ({@link FrontClipping}) and depth-clamp
 * ({@code CHelper}) paths stay (R6 — raw GL survives on the GL backend). See
 * {@code fragments/S11B-viewarea.md} (§ViewAreaRenderer) for the pipeline-family handoff to the
 * MyRenderHelper commit + the R5 reversed-Z depth-state note.
 */
public class ViewAreaRenderer {

    public static void renderPortalArea(
        Portal portal, Vec3 fogColor,
        Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
        boolean doFaceCulling, boolean doModifyColor,
        boolean doModifyDepth, boolean doClip
    ) {
        renderPortalArea(
            portal, fogColor, modelViewMatrix, projectionMatrix,
            doFaceCulling, doModifyColor, doModifyDepth, doClip,
            false
        );
    }

    /**
     * S13-H W1 overload (parent ruling 1 / S13H-driver-core-design.md §4-W1). {@code alwaysPassDepth}
     * selects the ALWAYS_PASS depth-compare pipeline variant (see
     * {@link MyRenderHelper#getPortalAreaRenderType(boolean, boolean, boolean, boolean)}). Only
     * {@code RendererUsingStencil.restoreDepthOfPortalViewArea} (the Row-11/12 exact-projected-depth
     * restore, drawn under {@code glDepthFunc(GL_ALWAYS)}) passes {@code true}; the 8-arg overload above
     * delegates with {@code false}, so all other callers keep the UNCHANGED GEQUAL pipeline.
     */
    public static void renderPortalArea(
        Portal portal, Vec3 fogColor,
        Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
        boolean doFaceCulling, boolean doModifyColor,
        boolean doModifyDepth, boolean doClip,
        boolean alwaysPassDepth
    ) {

        // ---- resolve IP's per-call GL state into pipeline-selection booleans (verbatim decisions) ----
        // color mask: fuse-view (with layers) OR !doModifyColor -> no color; else color (IP :39-49)
        boolean writeColor = !(portal.isFuseView() && IPGlobal.maxPortalLayer != 0) && doModifyColor;
        // depth mask: doModifyDepth ? (fuse-view ? no-write : write) : no-write (IP :51-61)
        boolean writeDepth;
        if (doModifyDepth) {
            if (portal.isFuseView()) {
                writeDepth = false;
            }
            else {
                writeDepth = true;
            }
        }
        else {
            writeDepth = false;
        }

        boolean shouldReverseCull = PortalRendering.isRenderingOddNumberOfMirrors();
        if (shouldReverseCull) {
            MyRenderHelper.applyMirrorFaceCulling();
        }

        if (doClip) {
            if (PortalRendering.isRendering()) {
                FrontClipping.setupInnerClipping(
                    PortalRendering.getActiveClippingPlane(),
                    modelViewMatrix, 0  // don't do adjustment
                );
            }
        }
        else {
            FrontClipping.disableClipping();
        }

        // 26.2 has no pipeline-level depth clamp — raw GL (GL32.GL_DEPTH_CLAMP) via CHelper (R6).
        // S14.27 L2 lever (debug_no_aperture_depth_clamp, default OFF): skipping the clamp is the
        // round-2 geometry-root discriminator — if the sky-wide sector wedges collapse to the true
        // portal quads with the clamp off, the footprint corruption is depth-clamp rasterization of
        // triangles crossing w<=0 (the 2A fix branch: CPU near-plane clip, clamp kept).
        if (!qouteall.imm_ptl.core.IPGlobal.debugNoApertureDepthClamp) {
            CHelper.enableDepthClamp();
        }

        // 26.2 (G9/G6): the GONE portalAreaShader (ShaderInstance) + its MODEL_VIEW/PROJECTION uniform
        // set/apply/clear become a RenderPipeline-backed RenderType carrying the resolved color/depth/
        // cull state. model-view + projection ride RenderSystem for the pass (G9/G27/G28); the passed
        // matrices are installed on RenderSystem around the draw below (the re-expression of IP's per-call
        // shader.MODEL_VIEW/PROJECTION.set — see that block; modelViewMatrix still feeds the clip-plane
        // setup above). The R5 reversed-Z depth constants live inside the pipeline (S12).
        RenderType renderType = MyRenderHelper.getPortalAreaRenderType(
            writeColor, writeDepth, doFaceCulling, alwaysPassDepth);

        FrontClipping.updateClippingEquationUniformForCurrentShader(false);

        // 26.2 (G9/G6) re-expression of IP's explicit shader.MODEL_VIEW_MATRIX.set /
        // shader.PROJECTION_MATRIX.set (IP ViewAreaRenderer.java:87-88). The GONE portalAreaShader carried
        // those two uniforms and set them per call; on 26.2 the POSITION_COLOR pipeline family reads them
        // from RenderSystem instead — RenderType.prepare() snapshots the AMBIENT model-view
        // (getModelViewMatrixCopy -> the DynamicTransforms UBO, mc262 RenderType.java:64) and
        // PreparedRenderType.drawFromBuffer binds the AMBIENT projection (bindDefaultUniforms ->
        // getProjectionMatrixBuffer, mc262 PreparedRenderType.java:45 / RenderSystem.java:276-280) at draw
        // time. IP set BOTH explicitly per call so the view-area quad rasterizes with the portal-pass
        // matrices REGARDLESS of what the surrounding passes (translucent terrain, entities, or a nested
        // layer's identity model-view bracket) left ambient. Re-express that verbatim: install the PASSED
        // matrices on RenderSystem around the draw and restore both after (recursion-safe per-call locals,
        // like MyGameRenderer's projection bracket). The INSTALL is what guarantees correctness: the passed
        // projection is getCurrentProjectionMatrix(), which since S13-M P1 returns the live main-pass DRAW
        // projection (base*bob*spin, scaled to the layer's accumulated portal scale), so the aperture quad
        // bobs in LOCK with the frame and the dest content. (Before S13-M P1 the passed projection was the
        // UNBOBBED base, so this install OVERWROTE the ambient bobbed projection with an unbobbed one and
        // the aperture WOBBLED whenever bob!=0 — the earlier "NO-OP at the outer site" note was wrong. At
        // the outer site the passed projection now genuinely equals the ambient, but the explicit install
        // still MATTERS at nested layers.) REQUIRED at nested layers (>=2): SecondaryWorldRenderCore Step
        // 10.10 runs onBeforeTranslucentRendering INSIDE MyGameRenderer.switchAndRenderTheWorld's identity
        // model-view bracket (MyGameRenderer.java:317-318), so without this the nested view-area mesh would
        // snapshot IDENTITY and rasterize untransformed (the eye-level sliver genre); the passed projection
        // there is that layer's dest DRAW projection (getCurrentProjectionMatrix at the layer's scaling).
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        GpuBufferSlice savedProjectionBuffer = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType savedProjectionType = RenderSystem.getProjectionType();
        modelViewStack.pushMatrix();
        modelViewStack.set(modelViewMatrix);
        // Only the matrix VALUE is overridden (IP's shader set only the matrix); keep the ambient
        // ProjectionType so the projection's non-matrix semantics are untouched.
        RenderSystem.setProjectionMatrix(writeProjectionSlice(projectionMatrix), savedProjectionType);
        try {
            ViewAreaRenderer.buildPortalViewAreaTrianglesBuffer(
                fogColor,
                portal,
                CHelper.getCurrentCameraPos(),
                RenderStates.getPartialTick(),
                renderType,
                modelViewMatrix // S14.36: near-plane clip basis
            );
        }
        finally {
            modelViewStack.popMatrix();
            RenderSystem.setProjectionMatrix(savedProjectionBuffer, savedProjectionType);
        }

        if (!qouteall.imm_ptl.core.IPGlobal.debugNoApertureDepthClamp) {
            CHelper.disableDepthClamp();
        }

        // 26.2: no global GL state to restore — each drawMesh pass sets its own pipeline state (G6),
        // so IP's _enableCull/_colorMask(true..)/_depthMask(true) restores are unnecessary.

        if (shouldReverseCull) {
            MyRenderHelper.recoverFaceCulling();
        }

        if (PortalRendering.isRendering()) {
            FrontClipping.disableClipping();
        }

        CHelper.checkGlError();
    }

    // S13-I nested-layer fix: standalone projection UBO for the portal-area draw, the 26.2 re-expression
    // of IP's shader.PROJECTION_MATRIX.set (IP ViewAreaRenderer.java:88).
    // S14.30: the old "GC reclaims the native handle" retention static was FALSE (blaze3d has no
    // Cleaner — un-closed buffers leak forever) and is replaced by the frame-transient UBO ledger
    // (registerFrameTransientUbo, drained at GameRenderer.render TAIL). Recursion safety is
    // preserved BY the ledger design: each call still creates a DISTINCT buffer (nested draws save
    // + restore slice references), only the close is deferred to frame end — vanilla's
    // DynamicUniformStorage.endFrame discipline.
    private static GpuBufferSlice writeProjectionSlice(Matrix4f matrix) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            ByteBuffer buf = com.mojang.blaze3d.buffers.Std140Builder.onStack(stack, 64)
                .putMat4f(matrix).get();
            return SecondaryWorldRenderCore.registerFrameTransientUbo(
                RenderSystem.getDevice().createBuffer(
                    () -> "seamlessportals_viewarea_proj", GpuBuffer.USAGE_UNIFORM, buf
                )
            );
        }
    }

    public static void buildPortalViewAreaTrianglesBuffer(
        Vec3 fogColor, Portal portal,
        Vec3 cameraPos, float partialTick,
        RenderType renderType,
        Matrix4f modelViewMatrix
    ) {
        // 26.2 (G7/G8): Tesselator/BufferUploader are GONE. Build the POSITION_COLOR TRIANGLES mesh
        // into a growable ByteBufferBuilder and draw it via the mod's immediate-mesh path (drawMesh
        // uploads the vertices to a transient GpuBuffer + fetches the shared sequential index buffer
        // for the mesh topology). Vertices are camera-relative, exactly as IP built them.
        // S14.30: try-with-resources — the builder's malloc block was NEVER freed (~4KB+ leaked per
        // aperture draw, 2 draws/layer/frame ≈ 1.7 GB/h native RSS growth; invisible to the Java
        // heap AND to GL). Vanilla's own discipline for the identical transient-builder use is
        // try-with-resources (SkyRenderer). Safe ordering: drawMesh copies the vertex bytes into
        // the GL store at createBuffer and draws synchronously; MeshData.close only releases its
        // result ref — the builder close after it is the sole ALLOCATOR.free.
        try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(
            256 * DefaultVertexFormat.POSITION_COLOR.getVertexSize()
        )) {
            BufferBuilder bufferBuilder = new BufferBuilder(
                byteBuffer, PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION_COLOR
            );

            Vec3 originRelativeToCamera = portal.getOriginPos().subtract(cameraPos);

            TriangleConsumer rawOutput = (p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z) -> {
                bufferBuilder
                    .addVertex((float) p0x, (float) p0y, (float) p0z)
                    .setColor((float) fogColor.x, (float) fogColor.y, (float) fogColor.z, 1.0f);
                bufferBuilder
                    .addVertex((float) p1x, (float) p1y, (float) p1z)
                    .setColor((float) fogColor.x, (float) fogColor.y, (float) fogColor.z, 1.0f);
                bufferBuilder
                    .addVertex((float) p2x, (float) p2y, (float) p2z)
                    .setColor((float) fogColor.x, (float) fogColor.y, (float) fogColor.z, 1.0f);
            };

            // S14.36 — THE WEDGE FIX (painter user-confirmed via debug_skip_aperture_incr: the
            // aperture mesh draw causes the sky wedges; its dyed color never showed, so the
            // damage is its rasterized FOOTPRINT — triangles crossing the camera plane (w<=0)
            // rasterize "external" wedge regions spanning the sky). CPU near-plane clip: vertices
            // are CAMERA-RELATIVE, so view-space depth of p is row-2 of the view matrix dotted
            // with p (JOML column-major: m02,m12,m22); "safely in front" means viewZ < -EPS
            // (OpenGL looks down -Z). Triangles fully in front pass through BIT-IDENTICAL;
            // fully behind are dropped (they could never contribute visible aperture); straddlers
            // are Sutherland-Hodgman clipped (1 behind -> 2 tris, 2 behind -> 1 tri). Depth clamp
            // stays ENABLED (IP's near/far fragment semantics preserved — the clip only removes
            // what 26.2 rasterizes as external wedges). Capture-time vertex logging per triangle
            // (bounded) preserves the raw data per the NO-GUESSING rule.
            double nx = modelViewMatrix.m02();
            double ny = modelViewMatrix.m12();
            double nz = modelViewMatrix.m22();
            final double EPS = 1.0e-4;

            TriangleConsumer vertexOutput = (p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z) -> {
                double z0 = nx * p0x + ny * p0y + nz * p0z;
                double z1 = nx * p1x + ny * p1y + nz * p1z;
                double z2 = nx * p2x + ny * p2y + nz * p2z;
                boolean in0 = z0 < -EPS;
                boolean in1 = z1 < -EPS;
                boolean in2 = z2 < -EPS;

                if (DrawCallTrace.capturing) {
                    DrawCallTrace.record(String.format(
                        "   [aperture tri] vz=(%.3f,%.3f,%.3f) in=(%b,%b,%b)", z0, z1, z2, in0, in1, in2));
                }

                if (in0 && in1 && in2) {
                    rawOutput.accept(p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z);
                    return;
                }
                if (!in0 && !in1 && !in2) {
                    return; // fully behind the camera plane — can never be visible aperture
                }

                // Sutherland-Hodgman clip against viewZ = -EPS, keeping the in-front side.
                double[][] src = {
                    {p0x, p0y, p0z, z0}, {p1x, p1y, p1z, z1}, {p2x, p2y, p2z, z2}
                };
                double[][] poly = new double[4][3];
                int n = 0;
                for (int i = 0; i < 3; i++) {
                    double[] cur = src[i];
                    double[] nxt = src[(i + 1) % 3];
                    boolean curIn = cur[3] < -EPS;
                    boolean nxtIn = nxt[3] < -EPS;
                    if (curIn) {
                        poly[n][0] = cur[0]; poly[n][1] = cur[1]; poly[n][2] = cur[2]; n++;
                    }
                    if (curIn != nxtIn) {
                        double t = (-EPS - cur[3]) / (nxt[3] - cur[3]);
                        poly[n][0] = cur[0] + (nxt[0] - cur[0]) * t;
                        poly[n][1] = cur[1] + (nxt[1] - cur[1]) * t;
                        poly[n][2] = cur[2] + (nxt[2] - cur[2]) * t;
                        n++;
                    }
                }
                if (n >= 3) {
                    rawOutput.accept(
                        poly[0][0], poly[0][1], poly[0][2],
                        poly[1][0], poly[1][1], poly[1][2],
                        poly[2][0], poly[2][1], poly[2][2]);
                    if (n == 4) {
                        rawOutput.accept(
                            poly[0][0], poly[0][1], poly[0][2],
                            poly[2][0], poly[2][1], poly[2][2],
                            poly[3][0], poly[3][1], poly[3][2]);
                    }
                }
            };

            portal.renderViewAreaMesh(originRelativeToCamera, vertexOutput);

            // IP: BufferUploader.draw(Objects.requireNonNull(bufferBuilder.build())) — the 26.2
            // translation is PortalRenderTypes.drawMesh (render-core G8). S14.36 amendment: an
            // EMPTY mesh is now a LEGITIMATE outcome (every aperture triangle clipped away when
            // the quad sits behind the camera plane), so null build() skips the draw instead of
            // hard-failing; any other emptiness still surfaces via the skipped aperture (blank
            // window), not a crash.
            var apertureMesh = bufferBuilder.build();
            if (apertureMesh != null) {
                PortalRenderTypes.drawMesh(renderType, apertureMesh);
            }
        }
    }

    public static void outputTriangle(
        TriangleConsumer vertexOutput, Vec3 center,
        Vec3 localXAxis, Vec3 localYAxis,
        double p0x, double p0y, double p1x, double p1y, double p2x, double p2y
    ) {
        vertexOutput.accept(
            center.x + p0x * localXAxis.x() + p0y * localYAxis.x(),
            center.y + p0x * localXAxis.y() + p0y * localYAxis.y(),
            center.z + p0x * localXAxis.z() + p0y * localYAxis.z(),
            center.x + p1x * localXAxis.x() + p1y * localYAxis.x(),
            center.y + p1x * localXAxis.y() + p1y * localYAxis.y(),
            center.z + p1x * localXAxis.z() + p1y * localYAxis.z(),
            center.x + p2x * localXAxis.x() + p2y * localYAxis.x(),
            center.y + p2x * localXAxis.y() + p2y * localYAxis.y(),
            center.z + p2x * localXAxis.z() + p2y * localYAxis.z()
        );
    }

    @Deprecated
    private static void generateTriangleForNormalShape(
        TriangleConsumer vertexOutput,
        Portal portal,
        Vec3 posInPlayerCoordinate
    ) {
        //avoid floating point error for converted global portal
        final double w = Math.min(portal.getWidth(), 23333);
        final double h = Math.min(portal.getHeight(), 23333);

        Vec3 localXAxis = portal.getAxisW().scale(w / 2);
        Vec3 localYAxis = portal.getAxisH().scale(h / 2);

        outputFullQuad(vertexOutput, posInPlayerCoordinate, localXAxis, localYAxis);

    }

    @Deprecated
    private static void generateTriangleForGlobalPortal(
        TriangleConsumer vertexOutput,
        Portal portal,
        Vec3 portalOriginLocal
    ) {
        Vec3 cameraPosFromPortalOrigin = portalOriginLocal.scale(-1);

        Vec3 cameraPosFromPortalOriginProjected =
            portal.getLocalVecProjectedToPlane(cameraPosFromPortalOrigin);

        Vec3 localCenter = portalOriginLocal.add(cameraPosFromPortalOriginProjected);

        double r = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16 - 16;
        if (TransformationManager.isIsometricView) {
            r *= 2;
        }

        double distance = Math.abs(cameraPosFromPortalOrigin.dot(portal.getNormal()));
        if (distance > 200) {
            r = r * 200 / distance;
        }

        Vec3 localXAxis = portal.getAxisW().scale(r);
        Vec3 localYAxis = portal.getAxisH().scale(r);

        outputFullQuad(vertexOutput, localCenter, localXAxis, localYAxis);
    }

    public static void outputFullQuad(
        TriangleConsumer vertexOutput, Vec3 posInPlayerCoordinate,
        Vec3 localXAxis, Vec3 localYAxis
    ) {
        outputTriangle(
            vertexOutput, posInPlayerCoordinate,
            localXAxis, localYAxis, 1, 1, -1, 1, 1, -1
        );
        outputTriangle(
            vertexOutput, posInPlayerCoordinate,
            localXAxis, localYAxis, -1, 1, -1, -1, 1, -1
        );
    }
}
