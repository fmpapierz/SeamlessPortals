package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.warwa.seamlessportals.render.PortalRenderTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.q_misc_util.my_util.TriangleConsumer;

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
        CHelper.enableDepthClamp();

        // 26.2 (G9/G6): the GONE portalAreaShader (ShaderInstance) + its MODEL_VIEW/PROJECTION uniform
        // set/apply/clear become a RenderPipeline-backed RenderType carrying the resolved color/depth/
        // cull state. model-view + projection ride RenderSystem for the pass (G9/G27/G28); the passed
        // matrices are the portal-pass matrices the driver installed (modelViewMatrix still feeds the
        // clip-plane setup above). The R5 reversed-Z depth constants live inside the pipeline (S12).
        RenderType renderType = MyRenderHelper.getPortalAreaRenderType(writeColor, writeDepth, doFaceCulling);

        FrontClipping.updateClippingEquationUniformForCurrentShader(false);

        ViewAreaRenderer.buildPortalViewAreaTrianglesBuffer(
            fogColor,
            portal,
            CHelper.getCurrentCameraPos(),
            RenderStates.getPartialTick(),
            renderType
        );

        CHelper.disableDepthClamp();

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

    public static void buildPortalViewAreaTrianglesBuffer(
        Vec3 fogColor, Portal portal,
        Vec3 cameraPos, float partialTick,
        RenderType renderType
    ) {
        // 26.2 (G7/G8): Tesselator/BufferUploader are GONE. Build the POSITION_COLOR TRIANGLES mesh
        // into a growable ByteBufferBuilder and draw it via the mod's immediate-mesh path (drawMesh
        // uploads the vertices to a transient GpuBuffer + fetches the shared sequential index buffer
        // for the mesh topology). Vertices are camera-relative, exactly as IP built them.
        ByteBufferBuilder byteBuffer = new ByteBufferBuilder(
            256 * DefaultVertexFormat.POSITION_COLOR.getVertexSize()
        );
        BufferBuilder bufferBuilder = new BufferBuilder(
            byteBuffer, PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION_COLOR
        );

        Vec3 originRelativeToCamera = portal.getOriginPos().subtract(cameraPos);

        TriangleConsumer vertexOutput = (p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z) -> {
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

        portal.renderViewAreaMesh(originRelativeToCamera, vertexOutput);

        // IP: BufferUploader.draw(Objects.requireNonNull(bufferBuilder.build())). The mandated 26.2
        // translation is BufferUploader.draw -> PortalRenderTypes.drawMesh (render-core G8); the
        // Objects.requireNonNull assertion is VERBATIM IP (a portal view-area mesh always emits
        // geometry, so build() is never null in practice — an empty mesh is an IP-contract violation
        // and stays a hard fail, not a silent skip). drawMesh's try(mesh)+mesh.drawState() would NPE on
        // null anyway, so requireNonNull only sharpens the failure site; no behaviour softened.
        PortalRenderTypes.drawMesh(renderType, Objects.requireNonNull(bufferBuilder.build()));
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
