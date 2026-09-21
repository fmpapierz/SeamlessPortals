package com.warwa.seamlessportals.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Render types for stencil portal rendering.
 *
 * PORTAL_STENCIL_ONLY: Writes to stencil ONLY. No color, no depth.
 *   Used for: drawing portal shape to write stencil mask.
 *   ColorTargetState.writeMask=0 prevents color output.
 *   DepthStencilState=null disables depth test.
 *   Alpha=1 prevents fragment shader discard.
 *
 * PORTAL_NO_DEPTH_COLOR: Full color, no depth test.
 *   Used for: drawing destination blocks through stencil mask.
 */
public class PortalRenderTypes {

    private static RenderType PORTAL_STENCIL_ONLY;
    private static RenderType PORTAL_STENCIL_WITH_DEPTH;
    private static RenderType PORTAL_NO_DEPTH_COLOR;
    private static RenderType PORTAL_DEPTH_CLEAR;
    private static RenderType PORTAL_FBO_COMPOSITE;
    /**
     * RenderPipeline (not a RenderType) for the final FBO→screen composite,
     * used directly via {@code pass.setPipeline(...)} in
     * {@link PortalContextSwitch#compositePortalFbo()}. It is a clone of vanilla
     * {@code RenderPipelines.TRACY_BLIT} (core/screenquad + core/blit_screen +
     * GLOBALS + IN_SAMPLER) but with an EXPLICIT {@code ALWAYS_PASS} depth state.
     *
     * <p>Why: TRACY_BLIT declares {@code depthStencilState=Optional.empty()}. When
     * driven through {@code createCommandEncoder().createRenderPass(...)} WITH a
     * depth attachment (we need it for the stencil, which shares the depth-stencil
     * texture), the GL backend applies the reversed-Z default {@code GREATER_THAN_
     * OR_EQUAL} depth test. The composite's full-screen triangle sits at depth ~0.5,
     * and our depth-shield quad leaves the portal-PLANE depth in the opening (near
     * at the top when looking up/down) — so the upper opening FAILS GEQUAL and the
     * composite is skipped there, letting the source sky show through (the proven
     * "blue curtain", DIAG-PRE: depthTest=true, top bands depth≈0.85). An explicit
     * ALWAYS_PASS test makes the composite write the full stencil opening. */
    private static RenderPipeline PORTAL_COMPOSITE_BLIT;
    /**
     * Screenquad pipeline that writes ONLY depth (ALWAYS_PASS + depth write, color
     * masked). Used with glDepthRange + raw-GL stencil EQUAL for the stencil-direct
     * full-screen depth clear (STEP 3.5) — IP's clearDepthOfThePortalViewArea.
     */
    private static RenderPipeline PORTAL_SCREEN_DEPTH_CLEAR;

    static {
        try {
            Method registerMethod = RenderPipelines.class.getDeclaredMethod("register", RenderPipeline.class);
            registerMethod.setAccessible(true);
            Method createMethod = RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
            createMethod.setAccessible(true);

            // Pipeline for STENCIL WRITE ONLY: no color output, no depth test
            // writeMask=0 means glColorMask(false,false,false,false) via pipeline
            RenderPipeline stencilPipeline = RenderPipeline.builder()
                .withLocation("seamlessportals/pipeline/portal_stencil")
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                // 26.3: BindGroupLayouts.MATRICES_PROJECTION (one layout = DynamicTransforms + Projection,
                // mc262-ref BindGroupLayouts.java:13-16) was removed; vanilla pipelines now declare the two
                // separately, in this order (mc263-ref RenderPipelines.java:29-32).
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
                .withDepthStencilState(Optional.empty()) // no depth test
                .withCull(false)
                .build();
            stencilPipeline = (RenderPipeline) registerMethod.invoke(null, stencilPipeline);

            PORTAL_STENCIL_ONLY = (RenderType) createMethod.invoke(null,
                "seamlessportals_stencil",
                RenderSetup.builder(stencilPipeline).createRenderSetup()
            );

            // Pipeline for STENCIL WRITE WITH DEPTH TEST: no color, depth test LEQUAL, no depth write.
            // Used for stencil mask writing so obsidian frame occludes the stencil.
            // glStencilOp(KEEP, KEEP, REPLACE) means: depth fail → KEEP stencil (obsidian blocks it).
            // depth pass → REPLACE stencil=1 (portal opening is visible).
            RenderPipeline stencilDepthPipeline = RenderPipeline.builder()
                .withLocation("seamlessportals/pipeline/portal_stencil_depth")
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                // 26.3: BindGroupLayouts.MATRICES_PROJECTION (one layout = DynamicTransforms + Projection,
                // mc262-ref BindGroupLayouts.java:13-16) was removed; vanilla pipelines now declare the two
                // separately, in this order (mc263-ref RenderPipelines.java:29-32).
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)) // 26.2 reversed-Z: GEQUAL = "in front" (was LEQUAL); no write
                .withCull(false)
                .build();
            stencilDepthPipeline = (RenderPipeline) registerMethod.invoke(null, stencilDepthPipeline);

            PORTAL_STENCIL_WITH_DEPTH = (RenderType) createMethod.invoke(null,
                "seamlessportals_stencil_depth",
                RenderSetup.builder(stencilDepthPipeline).createRenderSetup()
            );

            // Pipeline for DESTINATION BLOCKS: full color, no depth test
            RenderPipeline noDepthPipeline = RenderPipeline.builder()
                .withLocation("seamlessportals/pipeline/portal_nodepth_color")
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                // 26.3: BindGroupLayouts.MATRICES_PROJECTION (one layout = DynamicTransforms + Projection,
                // mc262-ref BindGroupLayouts.java:13-16) was removed; vanilla pipelines now declare the two
                // separately, in this order (mc263-ref RenderPipelines.java:29-32).
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                // 26.3: a builder with NO colour target used to build ONE default target
                // (mc262-ref RenderPipeline.java:370-372 -> {ColorTargetState.DEFAULT}); it now builds ZERO
                // (mc263-ref renderpearl/api/pipeline/RenderPipeline.java:378-380), and FrontendRenderPass.setPipeline
                // (:111-114) throws "Render pass color attachment count must match pipeline color target state
                // count." against any pass that carries the colour attachment. Vanilla made the old default explicit
                // on every one of its own pipelines (mc263-ref RenderPipelines.java:214/341/347/350); same edit,
                // same value (DEFAULT is unchanged: empty blend, RGBA8_UNORM, WRITE_ALL).
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(Optional.empty()) // no depth test
                .withCull(false)
                .build();
            noDepthPipeline = (RenderPipeline) registerMethod.invoke(null, noDepthPipeline);

            PORTAL_NO_DEPTH_COLOR = (RenderType) createMethod.invoke(null,
                "seamlessportals_nodepth_color",
                RenderSetup.builder(noDepthPipeline).createRenderSetup()
            );

            // Pipeline for DEPTH CLEAR: writes depth (ALWAYS pass), no color
            // Used to write maximum depth inside portal shape to prevent
            // overworld sky/clouds from rendering through air gaps.
            // Combined with glDepthRange(1,1) to force depth=1.0
            RenderPipeline depthClearPipeline = RenderPipeline.builder()
                .withLocation("seamlessportals/pipeline/portal_depth_clear")
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                // 26.3: BindGroupLayouts.MATRICES_PROJECTION (one layout = DynamicTransforms + Projection,
                // mc262-ref BindGroupLayouts.java:13-16) was removed; vanilla pipelines now declare the two
                // separately, in this order (mc263-ref RenderPipelines.java:29-32).
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true)) // ALWAYS + write
                .withCull(false)
                .build();
            depthClearPipeline = (RenderPipeline) registerMethod.invoke(null, depthClearPipeline);

            PORTAL_DEPTH_CLEAR = (RenderType) createMethod.invoke(null,
                "seamlessportals_depth_clear",
                RenderSetup.builder(depthClearPipeline).createRenderSetup()
            );

            // Pipeline for FBO COMPOSITE: textured full-screen quad, no depth test
            // Uses position_tex shader to sample the portal FBO texture.
            // Drawn on the game's main FBO where stencil values live.
            // Stencil EQUAL(1) clips to portal area.
            RenderPipeline fboCompositePipeline = RenderPipeline.builder()
                .withLocation("seamlessportals/pipeline/portal_fbo_composite")
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                // 26.3: BindGroupLayouts.MATRICES_PROJECTION (one layout = DynamicTransforms + Projection,
                // mc262-ref BindGroupLayouts.java:13-16) was removed; vanilla pipelines now declare the two
                // separately, in this order (mc263-ref RenderPipelines.java:29-32).
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
                .withVertexShader("core/position_tex")
                .withFragmentShader("core/position_tex")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                // 26.3: the omitted colour target was ONE default target on 26.2 and is ZERO on 26.3 — made explicit,
                // exactly as vanilla did for its own pipelines (see the note on portal_nodepth_color above).
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(Optional.empty()) // no depth test
                .withCull(false)
                .build();
            fboCompositePipeline = (RenderPipeline) registerMethod.invoke(null, fboCompositePipeline);

            PORTAL_FBO_COMPOSITE = (RenderType) createMethod.invoke(null,
                "seamlessportals_fbo_composite",
                RenderSetup.builder(fboCompositePipeline).createRenderSetup()
            );

            // Pipeline for the FINAL FBO→SCREEN COMPOSITE (used via setPipeline,
            // not drawMesh). Clone of vanilla TRACY_BLIT but with ALWAYS_PASS depth
            // so the composite is NOT depth-gated by the leftover portal-plane depth
            // in the opening (the GEQUAL default that produced the blue curtain).
            RenderPipeline compositeBlitPipeline = RenderPipeline.builder()
                .withLocation("seamlessportals/pipeline/portal_composite_blit")
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)       // = TRACY_BLIT's GLOBALS_SNIPPET
                .withVertexShader("core/screenquad")
                .withFragmentShader("core/blit_screen")
                .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                // KEY: Optional.empty() makes applyPipelineState(GlCommandEncoder:786)
                // call _disableDepthTest() — fully off, like the mod's other no-depth
                // pipelines. (ALWAYS_PASS kept the test ENABLED and still gated GEQUAL.)
                .withDepthStencilState(Optional.empty())
                // 26.3: the omitted colour target was ONE default target on 26.2 and is ZERO on 26.3 — made explicit,
                // exactly as vanilla did for TRACY_BLIT itself, the pipeline this one clones (mc262-ref
                // RenderPipelines.java:762-770 has no colour target -> mc263-ref :1160-1169 adds
                // .withColorTargetState(ColorTargetState.DEFAULT); see the note on portal_nodepth_color above).
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withCull(false)
                .build();
            PORTAL_COMPOSITE_BLIT = (RenderPipeline) registerMethod.invoke(null, compositeBlitPipeline);

            // Pipeline for the STENCIL-GATED FULL-SCREEN DEPTH WRITE (stencil-direct
            // STEP 3.5). Same screenquad full-screen triangle as the composite blit,
            // but color-masked off and with ALWAYS_PASS depth + depth WRITE. Driven
            // with glDepthRange(0,0) (reversed-Z FAR) under raw-GL stencil EQUAL(1),
            // it clears depth across EXACTLY the stencil-mask pixels — IP's
            // clearDepthOfThePortalViewArea (a stencil-gated renderScreenTriangle).
            // A full-screen draw cannot mismatch the stencil-write quad's
            // rasterization, unlike re-drawing the portal quad geometry.
            RenderPipeline screenDepthClearPipeline = RenderPipeline.builder()
                .withLocation("seamlessportals/pipeline/portal_screen_depth_clear")
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withVertexShader("core/screenquad")
                .withFragmentShader("core/blit_screen")
                .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                .withCull(false)
                .build();
            PORTAL_SCREEN_DEPTH_CLEAR = (RenderPipeline) registerMethod.invoke(null, screenDepthClearPipeline);

            SeamlessPortalsConstants.LOGGER.info("[SEAMLESS] Portal render types created (stencil-only + stencil-depth + no-depth-color + depth-clear + fbo-composite + composite-blit[ALWAYS] + screen-depth-clear)");
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.error("[SEAMLESS] Failed to create portal render types", e);
            PORTAL_STENCIL_ONLY = net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads();
            PORTAL_STENCIL_WITH_DEPTH = net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads();
            PORTAL_NO_DEPTH_COLOR = net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads();
            PORTAL_DEPTH_CLEAR = net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads();
            // Fallback so compositePortalFbo never sets a null pipeline.
            if (PORTAL_COMPOSITE_BLIT == null) PORTAL_COMPOSITE_BLIT = RenderPipelines.TRACY_BLIT;
            if (PORTAL_SCREEN_DEPTH_CLEAR == null) PORTAL_SCREEN_DEPTH_CLEAR = RenderPipelines.TRACY_BLIT;
        }
    }

    /** Stencil write only - no color, no depth. For stencil reset (step 5). */
    public static RenderType portalStencilOnly() {
        return PORTAL_STENCIL_ONLY;
    }

    /** Stencil write with depth test (LEQUAL) - no color, no depth write.
     *  For stencil mask writing (step 2). Obsidian occludes stencil via depth fail. */
    public static RenderType portalStencilWithDepth() {
        return PORTAL_STENCIL_WITH_DEPTH;
    }

    /** Full color, no depth test. For destination blocks through stencil mask. */
    public static RenderType portalNoDepthColor() {
        return PORTAL_NO_DEPTH_COLOR;
    }

    /** Depth write only (ALWAYS pass), no color. For clearing depth inside portal shape. */
    public static RenderType portalDepthClear() {
        return PORTAL_DEPTH_CLEAR;
    }

    /** Textured quad for compositing FBO through portal geometry. No depth test. */
    public static RenderType portalFboComposite() {
        return PORTAL_FBO_COMPOSITE;
    }

    /** TRACY_BLIT-clone pipeline with ALWAYS_PASS depth for the FBO→screen composite. */
    public static RenderPipeline portalCompositeBlit() {
        return PORTAL_COMPOSITE_BLIT;
    }

    /** Screenquad pipeline: depth write only (ALWAYS), color masked. For the
     *  stencil-gated full-screen depth clear (stencil-direct STEP 3.5). */
    public static RenderPipeline portalScreenDepthClear() {
        return PORTAL_SCREEN_DEPTH_CLEAR;
    }

    /**
     * Immediately draw a {@link MeshData} with the given {@link RenderType}.
     *
     * <p>Replaces the removed 26.1.2 {@code RenderType.draw(MeshData)}. Mirrors
     * vanilla's immediate-draw path (e.g. {@code SkyRenderer}): upload the mesh
     * vertices to a transient GPU buffer, fetch the shared sequential index
     * buffer for the mesh topology (QUADS), then issue the draw through the
     * render type's {@link net.minecraft.client.renderer.rendertype.PreparedRenderType}
     * — which sets the pipeline + binds the default uniforms + DynamicTransforms
     * and targets the render type's output target (the bound main render
     * target). The {@link MeshData} is closed afterwards, matching the old
     * {@code draw} contract (callers relied on draw() closing the mesh).
     */
    public static void drawMesh(RenderType renderType, MeshData mesh) {
        try (mesh) {
            MeshData.DrawState drawState = mesh.drawState();
            int indexCount = drawState.indexCount();
            GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
                () -> "seamlessportals immediate mesh",
                GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
            RenderSystem.AutoStorageIndexBuffer indices =
                RenderSystem.getSequentialBuffer(drawState.primitiveTopology());
            GpuBuffer indexBuffer = indices.getBuffer(indexCount);
            IndexType indexType = indices.type();
            try {
                // 26.3: PreparedRenderType.drawFromBuffer(vertexBuf, indexBuf, indexType, baseVertex, firstIndex,
                // indexCount) — the overload that opened ITS OWN pass on outputTarget.getRenderTarget() — is gone; the
                // one remaining form is drawFromBuffer(StagedVertexBuffer.ExecuteInfo, RenderPass): the caller supplies
                // the pass (mc262-ref PreparedRenderType.java:28-56 -> mc263-ref :28-59; OutputTarget left the record).
                // The pass below is the one 26.2 opened: same label, the MAIN render target (this file's render types
                // never set an output target, so 26.2 resolved the default MAIN_TARGET), colour + depth-if-present, no
                // clear. ExecuteInfo gained a nullable customIndexBuffer + the topology (mc263-ref
                // StagedVertexBuffer.java:278-295); passing the sized buffer fetched above as the custom one makes
                // info.indexBuffer() return exactly it — the six values are the six 26.2 arguments, unchanged.
                com.mojang.blaze3d.pipeline.RenderTarget mainRenderTarget =
                    net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
                try (com.mojang.renderpearl.api.commands.RenderPass renderPass = RenderSystem.getDevice()
                        .createCommandEncoder()
                        .createRenderPass(
                            () -> "Immediate draw with " + renderType,
                            mainRenderTarget.getColorTextureView(), Optional.empty(),
                            mainRenderTarget.hasDepth() ? mainRenderTarget.getDepthTextureView() : null,
                            java.util.OptionalDouble.empty())) {
                    renderType.prepare().drawFromBuffer(
                        new net.minecraft.client.renderer.StagedVertexBuffer.ExecuteInfo(
                            vertexBuffer, indexBuffer, indexType, 0, 0, indexCount, drawState.primitiveTopology()),
                        renderPass);
                }
            } finally {
                vertexBuffer.close();
            }
        }
    }
}
