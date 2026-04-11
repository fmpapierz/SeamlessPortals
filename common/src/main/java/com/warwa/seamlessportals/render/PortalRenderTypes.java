package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
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
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                .withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_NONE))
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
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                .withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_NONE))
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)) // LEQUAL test, no write
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
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
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
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexShader("core/position_color")
                .withFragmentShader("core/position_color")
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                .withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_NONE))
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true)) // ALWAYS + write
                .withCull(false)
                .build();
            depthClearPipeline = (RenderPipeline) registerMethod.invoke(null, depthClearPipeline);

            PORTAL_DEPTH_CLEAR = (RenderType) createMethod.invoke(null,
                "seamlessportals_depth_clear",
                RenderSetup.builder(depthClearPipeline).createRenderSetup()
            );

            SeamlessPortalsConstants.LOGGER.info("[SEAMLESS] Portal render types created (stencil-only + stencil-depth + no-depth-color + depth-clear)");
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.error("[SEAMLESS] Failed to create portal render types", e);
            PORTAL_STENCIL_ONLY = net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads();
            PORTAL_STENCIL_WITH_DEPTH = net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads();
            PORTAL_NO_DEPTH_COLOR = net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads();
            PORTAL_DEPTH_CLEAR = net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads();
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
}
