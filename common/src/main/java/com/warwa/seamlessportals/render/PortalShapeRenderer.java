package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.vertex.*;
import com.warwa.seamlessportals.portal.PortalInfo;
import net.minecraft.client.Camera;
import com.warwa.seamlessportals.render.PortalRenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the portal face quad geometry using MC's BufferBuilder + RenderType system.
 * Does NOT use GL immediate mode (glBegin/glEnd) which is banned in Core Profile.
 *
 * Used by StencilPortalRenderer to write portal shape to stencil buffer
 * and to manage depth for the portal face.
 */
public class PortalShapeRenderer {

    /**
     * Draw the portal face quad using debugQuads RenderType.
     * Color is white with zero alpha (invisible but writes to stencil/depth).
     */
    public static void drawPortalShape(PortalInfo portal, Camera camera) {
        MeshData mesh = buildPortalQuadMesh(portal, camera, 0x01000000);
        if (mesh != null) {
            // Query FBO BEFORE draw (should be 0 from Fabric callback)
            int fboBefore = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);

            PortalRenderTypes.portalStencilOnly().draw(mesh);

            // Query FBO AFTER draw (should be 0 again after RenderPass closes)
            int fboAfter = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);

            // The FBO that was used DURING draw is unknown from here,
            // but we can detect it by noting that draw() binds FBO then unbinds to 0.
            // We need the FBO that draw() used.
            // Store it for comparison with StencilState.gameFboId
            if (drawCount < 3) {
                com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS STENCIL DIAG] drawPortalShape: fboBefore={}, fboAfter={}, gameFboId={}",
                    fboBefore, fboAfter, StencilState.gameFboId
                );
            }
            drawCount++;
        }
    }

    private static int drawCount = 0;

    /**
     * Draw merged portal shape with DEPTH CLEAR render type.
     * Writes depth=1.0 (via glDepthRange(1,1) set by caller) inside the stencil mask.
     * Uses portalDepthClear: ALWAYS_PASS depth test + depth write ON + no color output.
     */
    public static void drawMergedPortalShapeWithDepthClear(java.util.List<PortalInfo> portals, Camera camera) {
        if (portals.isEmpty()) return;

        Direction.Axis axis = portals.get(0).getAxis();
        Vec3 camPos = camera.position();
        float cx = (float) camPos.x, cy = (float) camPos.y, cz = (float) camPos.z;

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (PortalInfo p : portals) {
            BlockPos o = p.getOrigin();
            int w = p.getWidth();
            int h = p.getHeight();
            minY = Math.min(minY, o.getY());
            maxY = Math.max(maxY, o.getY() + h);
            if (axis == Direction.Axis.X) {
                minX = Math.min(minX, o.getX());
                maxX = Math.max(maxX, o.getX() + w);
                minZ = Math.min(minZ, o.getZ());
                maxZ = Math.max(maxZ, o.getZ() + 1);
            } else {
                minX = Math.min(minX, o.getX());
                maxX = Math.max(maxX, o.getX() + 1);
                minZ = Math.min(minZ, o.getZ());
                maxZ = Math.max(maxZ, o.getZ() + w);
            }
        }

        int color = 0x01000000; // alpha=1, won't be discarded by shader

        com.mojang.blaze3d.vertex.ByteBufferBuilder byteBuf =
            new com.mojang.blaze3d.vertex.ByteBufferBuilder(4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
        com.mojang.blaze3d.vertex.BufferBuilder builder =
            new com.mojang.blaze3d.vertex.BufferBuilder(byteBuf,
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_COLOR);

        if (axis == Direction.Axis.X) {
            float z = ((minZ + maxZ) / 2.0f) - cz;
            builder.addVertex(minX - cx, minY - cy, z).setColor(color);
            builder.addVertex(maxX - cx, minY - cy, z).setColor(color);
            builder.addVertex(maxX - cx, maxY - cy, z).setColor(color);
            builder.addVertex(minX - cx, maxY - cy, z).setColor(color);
        } else {
            float x = ((minX + maxX) / 2.0f) - cx;
            builder.addVertex(x, minY - cy, minZ - cz).setColor(color);
            builder.addVertex(x, minY - cy, maxZ - cz).setColor(color);
            builder.addVertex(x, maxY - cy, maxZ - cz).setColor(color);
            builder.addVertex(x, maxY - cy, minZ - cz).setColor(color);
        }

        com.mojang.blaze3d.vertex.MeshData mesh = builder.build();
        if (mesh != null) {
            PortalRenderTypes.portalDepthClear().draw(mesh);
        }
    }

    /**
     * Draw an opaque background at the portal face to hide the overworld behind.
     * Uses the destination dimension's sky/fog color.
     * Drawn AFTER stencil write, BEFORE destination blocks.
     * Uses portalNoDepthColor (full color, no depth test) so it draws
     * through the stencil mask and covers the overworld view.
     */
    public static void drawPortalBackground(java.util.List<PortalInfo> portals, Camera camera,
                                             net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> destDim) {
        if (portals.isEmpty()) return;

        Direction.Axis axis = portals.get(0).getAxis();
        Vec3 camPos = camera.position();
        float cx = (float) camPos.x, cy = (float) camPos.y, cz = (float) camPos.z;

        // Compute merged bounds
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (PortalInfo p : portals) {
            BlockPos o = p.getOrigin();
            int w = p.getWidth();
            int h = p.getHeight();
            minY = Math.min(minY, o.getY());
            maxY = Math.max(maxY, o.getY() + h);
            if (axis == Direction.Axis.X) {
                minX = Math.min(minX, o.getX());
                maxX = Math.max(maxX, o.getX() + w);
                minZ = Math.min(minZ, o.getZ());
                maxZ = Math.max(maxZ, o.getZ() + 1);
            } else {
                minX = Math.min(minX, o.getX());
                maxX = Math.max(maxX, o.getX() + 1);
                minZ = Math.min(minZ, o.getZ());
                maxZ = Math.max(maxZ, o.getZ() + w);
            }
        }

        // Background color based on destination dimension
        int bgColor;
        if (destDim == net.minecraft.world.level.Level.NETHER) {
            bgColor = 0xFF1A0808; // Dark nether red
        } else if (destDim == net.minecraft.world.level.Level.END) {
            bgColor = 0xFF0A0A18; // Dark end purple
        } else {
            bgColor = 0xFF87CEEB; // Light blue overworld sky
        }

        com.mojang.blaze3d.vertex.ByteBufferBuilder byteBuf =
            new com.mojang.blaze3d.vertex.ByteBufferBuilder(4 * com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR.getVertexSize());
        com.mojang.blaze3d.vertex.BufferBuilder builder =
            new com.mojang.blaze3d.vertex.BufferBuilder(byteBuf,
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);

        if (axis == Direction.Axis.X) {
            float z = ((minZ + maxZ) / 2.0f) - cz;
            builder.addVertex(minX - cx, minY - cy, z).setColor(bgColor);
            builder.addVertex(maxX - cx, minY - cy, z).setColor(bgColor);
            builder.addVertex(maxX - cx, maxY - cy, z).setColor(bgColor);
            builder.addVertex(minX - cx, maxY - cy, z).setColor(bgColor);
        } else {
            float x = ((minX + maxX) / 2.0f) - cx;
            builder.addVertex(x, minY - cy, minZ - cz).setColor(bgColor);
            builder.addVertex(x, minY - cy, maxZ - cz).setColor(bgColor);
            builder.addVertex(x, maxY - cy, maxZ - cz).setColor(bgColor);
            builder.addVertex(x, maxY - cy, minZ - cz).setColor(bgColor);
        }

        com.mojang.blaze3d.vertex.MeshData mesh = builder.build();
        if (mesh != null) {
            PortalRenderTypes.portalNoDepthColor().draw(mesh);
        }
    }

    /**
     * Draw ONE merged quad covering ALL portal planes.
     * Computes bounding box from all portals, draws single quad at center.
     */
    public static void drawMergedPortalShape(java.util.List<com.warwa.seamlessportals.portal.PortalInfo> portals, Camera camera) {
        if (portals.isEmpty()) return;

        Direction.Axis axis = portals.get(0).getAxis();
        Vec3 camPos = camera.position();
        float cx = (float) camPos.x, cy = (float) camPos.y, cz = (float) camPos.z;

        // Compute merged bounds across all portal planes
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (com.warwa.seamlessportals.portal.PortalInfo p : portals) {
            BlockPos o = p.getOrigin();
            int w = p.getWidth();
            int h = p.getHeight();
            minY = Math.min(minY, o.getY());
            maxY = Math.max(maxY, o.getY() + h);
            // axis = WIDTH direction of portal
            // axis=X: width extends along X, thin in Z
            // axis=Z: width extends along Z, thin in X
            if (axis == Direction.Axis.X) {
                minX = Math.min(minX, o.getX());
                maxX = Math.max(maxX, o.getX() + w);
                minZ = Math.min(minZ, o.getZ());
                maxZ = Math.max(maxZ, o.getZ() + 1);
            } else {
                minX = Math.min(minX, o.getX());
                maxX = Math.max(maxX, o.getX() + 1);
                minZ = Math.min(minZ, o.getZ());
                maxZ = Math.max(maxZ, o.getZ() + w);
            }
        }

        int color = 0x01000000; // alpha=1, invisible but won't be discarded

        ByteBufferBuilder byteBuf = new ByteBufferBuilder(4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
        BufferBuilder builder = new BufferBuilder(byteBuf, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // NetherPortalBlock.AXIS = the WIDTH direction of the portal.
        // axis=X: width extends along X, portal face is perpendicular to Z → quad in XY plane
        // axis=Z: width extends along Z, portal face is perpendicular to X → quad in ZY plane

        if (axis == Direction.Axis.X) {
            // Width along X, face perpendicular to Z. Quad in XY plane at fixed Z.
            float z = ((minZ + maxZ) / 2.0f) - cz;
            builder.addVertex(minX - cx, minY - cy, z).setColor(color);
            builder.addVertex(maxX - cx, minY - cy, z).setColor(color);
            builder.addVertex(maxX - cx, maxY - cy, z).setColor(color);
            builder.addVertex(minX - cx, maxY - cy, z).setColor(color);
        } else {
            // Width along Z, face perpendicular to X. Quad in ZY plane at fixed X.
            float x = ((minX + maxX) / 2.0f) - cx;
            builder.addVertex(x, minY - cy, minZ - cz).setColor(color);
            builder.addVertex(x, minY - cy, maxZ - cz).setColor(color);
            builder.addVertex(x, maxY - cy, maxZ - cz).setColor(color);
            builder.addVertex(x, maxY - cy, minZ - cz).setColor(color);
        }

        MeshData mesh = builder.build();
        if (mesh != null) {
            PortalRenderTypes.portalStencilOnly().draw(mesh);
        }
    }

    /**
     * Draw ONE merged quad with DEPTH TEST enabled (LEQUAL, no depth write).
     * Used for stencil write (step 2) so obsidian frame occludes the stencil mask.
     * Identical geometry to drawMergedPortalShape but uses portalStencilWithDepth.
     */
    /**
     * Overload with constrained dimensions. Uses the minimum of source and
     * destination portal sizes so the destination frame isn't visible.
     */
    public static void drawMergedPortalShapeWithDepthTest(
            java.util.List<com.warwa.seamlessportals.portal.PortalInfo> portals, Camera camera,
            int maxWidth, int maxHeight) {
        if (portals.isEmpty()) return;

        Direction.Axis axis = portals.get(0).getAxis();
        Vec3 camPos = camera.position();
        float cx = (float) camPos.x, cy = (float) camPos.y, cz = (float) camPos.z;

        // Compute merged bounds, then constrain to maxWidth/maxHeight centered
        com.warwa.seamlessportals.portal.PortalInfo first = portals.get(0);
        Vec3 center = first.getCenter();

        float halfW = maxWidth / 2.0f;
        float halfH = maxHeight / 2.0f;

        float minX, maxX, minY, maxY, minZ, maxZ;
        minY = (float) center.y - halfH;
        maxY = (float) center.y + halfH;
        if (axis == Direction.Axis.X) {
            minX = (float) center.x - halfW;
            maxX = (float) center.x + halfW;
            minZ = (float) center.z - 0.5f;
            maxZ = (float) center.z + 0.5f;
        } else {
            minX = (float) center.x - 0.5f;
            maxX = (float) center.x + 0.5f;
            minZ = (float) center.z - halfW;
            maxZ = (float) center.z + halfW;
        }

        int color = 0x01000000;
        ByteBufferBuilder byteBuf = new ByteBufferBuilder(4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
        BufferBuilder builder = new BufferBuilder(byteBuf, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        if (axis == Direction.Axis.X) {
            float z = ((minZ + maxZ) / 2.0f) - cz;
            builder.addVertex(minX - cx, minY - cy, z).setColor(color);
            builder.addVertex(maxX - cx, minY - cy, z).setColor(color);
            builder.addVertex(maxX - cx, maxY - cy, z).setColor(color);
            builder.addVertex(minX - cx, maxY - cy, z).setColor(color);
        } else {
            float x = ((minX + maxX) / 2.0f) - cx;
            builder.addVertex(x, minY - cy, minZ - cz).setColor(color);
            builder.addVertex(x, minY - cy, maxZ - cz).setColor(color);
            builder.addVertex(x, maxY - cy, maxZ - cz).setColor(color);
            builder.addVertex(x, maxY - cy, minZ - cz).setColor(color);
        }

        MeshData mesh = builder.build();
        if (mesh != null) {
            PortalRenderTypes.portalStencilWithDepth().draw(mesh);
        }
    }

    public static void drawMergedPortalShapeWithDepthTest(java.util.List<com.warwa.seamlessportals.portal.PortalInfo> portals, Camera camera) {
        if (portals.isEmpty()) return;

        Direction.Axis axis = portals.get(0).getAxis();
        Vec3 camPos = camera.position();
        float cx = (float) camPos.x, cy = (float) camPos.y, cz = (float) camPos.z;

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        for (com.warwa.seamlessportals.portal.PortalInfo p : portals) {
            BlockPos o = p.getOrigin();
            int w = p.getWidth();
            int h = p.getHeight();
            minY = Math.min(minY, o.getY());
            maxY = Math.max(maxY, o.getY() + h);
            if (axis == Direction.Axis.X) {
                minX = Math.min(minX, o.getX());
                maxX = Math.max(maxX, o.getX() + w);
                minZ = Math.min(minZ, o.getZ());
                maxZ = Math.max(maxZ, o.getZ() + 1);
            } else {
                minX = Math.min(minX, o.getX());
                maxX = Math.max(maxX, o.getX() + 1);
                minZ = Math.min(minZ, o.getZ());
                maxZ = Math.max(maxZ, o.getZ() + w);
            }
        }

        int color = 0x01000000;

        ByteBufferBuilder byteBuf = new ByteBufferBuilder(4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
        BufferBuilder builder = new BufferBuilder(byteBuf, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        if (axis == Direction.Axis.X) {
            float z = ((minZ + maxZ) / 2.0f) - cz;
            builder.addVertex(minX - cx, minY - cy, z).setColor(color);
            builder.addVertex(maxX - cx, minY - cy, z).setColor(color);
            builder.addVertex(maxX - cx, maxY - cy, z).setColor(color);
            builder.addVertex(minX - cx, maxY - cy, z).setColor(color);
        } else {
            float x = ((minX + maxX) / 2.0f) - cx;
            builder.addVertex(x, minY - cy, minZ - cz).setColor(color);
            builder.addVertex(x, minY - cy, maxZ - cz).setColor(color);
            builder.addVertex(x, maxY - cy, maxZ - cz).setColor(color);
            builder.addVertex(x, maxY - cy, minZ - cz).setColor(color);
        }

        MeshData mesh = builder.build();
        if (mesh != null) {
            PortalRenderTypes.portalStencilWithDepth().draw(mesh);
        }
    }

    /**
     * Draw the portal face quad WITH depth writing at the portal surface distance.
     */
    public static void drawPortalShapeWithDepth(PortalInfo portal, Camera camera) {
        MeshData mesh = buildPortalQuadMesh(portal, camera, 0x01000000);
        if (mesh != null) {
            PortalRenderTypes.portalStencilOnly().draw(mesh);
        }
    }

    /**
     * Build a MeshData for the portal face quad.
     * Vertices are in camera-relative coordinates with POSITION_COLOR format.
     */
    private static MeshData buildPortalQuadMesh(PortalInfo portal, Camera camera, int color) {
        BlockPos origin = portal.getOrigin();
        int w = portal.getWidth();
        int h = portal.getHeight();
        Direction.Axis axis = portal.getAxis();
        Vec3 camPos = camera.position();

        float cx = (float) camPos.x;
        float cy = (float) camPos.y;
        float cz = (float) camPos.z;

        ByteBufferBuilder byteBuf = new ByteBufferBuilder(4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
        BufferBuilder builder = new BufferBuilder(byteBuf, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        // axis = WIDTH direction. axis=X → width along X, face perp to Z → XY quad
        //                        axis=Z → width along Z, face perp to X → ZY quad
        if (axis == Direction.Axis.X) {
            // Width along X, face perpendicular to Z. Quad in XY plane.
            float z = origin.getZ() + 0.5f - cz;
            float x0 = origin.getX() - cx;
            float x1 = origin.getX() + w - cx;
            float y0 = origin.getY() - cy;
            float y1 = origin.getY() + h - cy;

            builder.addVertex(x0, y0, z).setColor(color);
            builder.addVertex(x1, y0, z).setColor(color);
            builder.addVertex(x1, y1, z).setColor(color);
            builder.addVertex(x0, y1, z).setColor(color);
        } else if (axis == Direction.Axis.Z) {
            // Width along Z, face perpendicular to X. Quad in ZY plane.
            float x = origin.getX() + 0.5f - cx;
            float z0 = origin.getZ() - cz;
            float z1 = origin.getZ() + w - cz;
            float y0 = origin.getY() - cy;
            float y1 = origin.getY() + h - cy;

            builder.addVertex(x, y0, z0).setColor(color);
            builder.addVertex(x, y0, z1).setColor(color);
            builder.addVertex(x, y1, z1).setColor(color);
            builder.addVertex(x, y1, z0).setColor(color);
        }

        // NOTE: byteBuf must NOT be closed here - MeshData references it.
        // RenderType.draw(mesh) will close the MeshData which handles cleanup.
        return builder.build();
    }
}
