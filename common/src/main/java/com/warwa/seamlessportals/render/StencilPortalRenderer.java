package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.portal.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.*;

/**
 * Stencil-based portal rendering following the Immersive Portals architecture.
 *
 * BATCHED approach: draw ALL portal shapes to stencil FIRST (covering the
 * full portal opening), then draw destination blocks through the combined mask.
 *
 * This handles multi-plane portals (e.g., 2-wide portal = 2 portal planes)
 * by writing stencil=1 for ALL planes before drawing destination content.
 */
public class StencilPortalRenderer {

    private static int framesRendered = 0;

    public static void renderPortals() {
        // Recursion guard: renderLevel() on secondary renderer triggers AFTER_TRANSLUCENT_TERRAIN
        // which calls this method again. Match IP's PortalRendering.isRendering() check.
        if (PortalContextSwitch.isRenderingPortal) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        ResourceKey<Level> currentDim = mc.level.dimension();
        PortalManager pm = PortalManager.getClientInstance();
        PortalTracker tracker = pm.getTracker(currentDim);

        BlockPos playerPos = mc.player.blockPosition();
        double range = SeamlessPortalsConfig.get().getPortalRenderDistance() * 16.0;
        List<PortalInfo> nearbyPortals = tracker.getPortalsInRange(playerPos, range);

        framesRendered++;
        if (nearbyPortals.isEmpty()) return;

        // Collect ALL linked portals (don't deduplicate - we want all planes)
        List<PortalInfo> linkedPortals = new ArrayList<>();
        PortalLink firstLink = null;

        for (PortalInfo portal : nearbyPortals) {
            if (!SeamlessPortalsConfig.shouldRenderThrough(portal.getType())) continue;
            Optional<PortalLink> linkOpt = pm.getLinkForPortal(portal.getPortalId());
            if (linkOpt.isEmpty()) continue;
            linkedPortals.add(portal);
            if (firstLink == null) firstLink = linkOpt.get();
        }

        if (linkedPortals.isEmpty() || firstLink == null) return;

        renderBatchedPortals(linkedPortals, firstLink, camera);

        if (framesRendered % 200 == 1) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS STENCIL] Frame {}: {} portal planes, dim={}",
                framesRendered, linkedPortals.size(), currentDim.identifier()
            );
        }
    }

    /**
     * Render all portal planes in a single batched stencil pass:
     * 1. Enable stencil, discover FBO, clear stencil
     * 2. Draw ALL portal shapes → stencil=1 covers entire portal opening
     * 3. Set stencil test EQUAL(1)
     * 4. Draw destination blocks → clipped to combined portal shape
     * 5. Reset stencil, disable
     */
    private static void renderBatchedPortals(List<PortalInfo> portals, PortalLink link, Camera camera) {
        GL11.glEnable(GL11.GL_STENCIL_TEST);

        // ===== STEP 1: Discover FBO via dummy draw, then clear stencil =====
        GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_ZERO);
        GL11.glStencilMask(0xFF);
        PortalShapeRenderer.drawPortalShape(portals.get(0), camera);
        int renderFbo = StencilState.lastBoundFbo;

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, renderFbo);
        GL11.glClearStencil(0);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        // ===== STEP 2: Draw ONE MERGED portal shape → write stencil=1 =====
        // Uses depth-tested variant so obsidian frame occludes the stencil mask.
        // glStencilOp(KEEP, KEEP, REPLACE): depth fail → KEEP (obsidian blocks stencil),
        // depth pass → REPLACE=1 (portal opening gets stencil).
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        GL11.glStencilMask(0xFF);

        // Draw one merged quad with LEQUAL depth test - obsidian occludes stencil write
        PortalShapeRenderer.drawMergedPortalShapeWithDepthTest(portals, camera);
        if (framesRendered <= 5) {
            int stencilWriteFbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS DEBUG] Stencil write on FBO={}", stencilWriteFbo);
        }

        // ===== STEP 3: Set stencil to only pass where value == 1 =====
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        GL11.glStencilMask(0x00);

        // ===== STEP 3.5: Write near-plane depth inside portal shape =====
        // Write depth=0.0 (near plane) inside the stencil mask.
        // This prevents ALL later passes (clouds, weather, translucent terrain)
        // from overdrawing the portal content after FBO composite.
        //
        // glDepthRange(0,0) forces all depth writes to 0.0.
        // The ALWAYS_PASS depth test ensures the write always succeeds.
        // The FBO composite (TRACY_BLIT) has no depth test/write, so it
        // ignores this depth and writes color through the stencil mask.
        // After composite, depth=0.0 remains, blocking clouds/weather.
        //
        // NOTE: IP wrote depth=1.0 (far) here because IP did direct rendering
        // (not FBO composite). With FBO composite that doesn't write depth,
        // we need 0.0 (near) to block subsequent passes.
        GL11.glDepthRange(0, 0);
        PortalShapeRenderer.drawMergedPortalShapeWithDepthClear(portals, camera);
        GL11.glDepthRange(0, 1); // Restore normal depth range

        // ===== STEP 4: Render destination world to FBO, composite through stencil =====
        // renderLevel() renders full world (sky + terrain + entities) to secondary FBO.
        // compositePortalFbo() draws it onto main screen through stencil EQUAL(1).
        // No background fill needed (sky fills FBO). No depth shield needed (FBO composite
        // writes to depth buffer, blocking clouds/weather).
        PortalContextSwitch.renderDestinationWorld(portals.get(0), link, camera);

        // ===== STEP 5: Reset stencil and disable =====
        GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        GL11.glStencilMask(0xFF);
        PortalShapeRenderer.drawMergedPortalShape(portals, camera);

        GL11.glStencilMask(0xFF);
        GL11.glDisable(GL11.GL_STENCIL_TEST);

        if (framesRendered <= 2) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS STENCIL] Rendered {} portal planes batched on FBO {}",
                portals.size(), renderFbo
            );
        }
    }

    public static void cleanup() {
        framesRendered = 0;
    }
}
