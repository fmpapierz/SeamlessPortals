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

    /** One portal to render this frame, paired with ITS OWN link/destination. */
    private record RenderGroup(PortalInfo portal, PortalLink link) {}

    /**
     * Resolved per-portal render groups (each its OWN destination), near-to-far, plus the
     * camera. Discovered identically by both render phases so phase 1 (heavy dest render) and
     * phase 2 (stencil + composite) operate on the same portals.
     */
    private record RenderTargets(List<RenderGroup> groups, Camera camera) {}

    /** Cap on portals rendered per frame — each is a full destination render (bound the cost). */
    private static final int MAX_PORTALS_RENDERED = 4;

    /**
     * Discover the linked portals near the player. Returns {@code null} if there is nothing to
     * render. Each portal becomes its OWN render group with its OWN link — previously all nearby
     * portals were collapsed into one list + firstLink, so two separate portals showed ONE
     * stretched destination (the two-portals bug). IP renders per-portal the same way.
     *
     * <p>Side-effect free apart from being a pure read of the client portal state.
     */
    private static RenderTargets resolveRenderTargets() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;

        Camera camera = mc.gameRenderer.mainCamera();
        ResourceKey<Level> currentDim = mc.level.dimension();
        PortalManager pm = PortalManager.getClientInstance();
        PortalTracker tracker = pm.getTracker(currentDim);

        BlockPos playerPos = mc.player.blockPosition();
        double range = SeamlessPortalsConfig.get().getPortalRenderDistance() * 16.0;
        List<PortalInfo> nearbyPortals = tracker.getPortalsInRange(playerPos, range);
        if (nearbyPortals.isEmpty()) return null;

        List<RenderGroup> groups = new ArrayList<>();
        for (PortalInfo portal : nearbyPortals) {
            if (!SeamlessPortalsConfig.shouldRenderThrough(portal.getType())) continue;
            Optional<PortalLink> linkOpt = pm.getLinkForPortal(portal.getPortalId());
            if (linkOpt.isEmpty()) continue;
            groups.add(new RenderGroup(portal, linkOpt.get()));
        }
        if (groups.isEmpty()) return null;

        // Sort near-to-far (mirror IP's PortalRenderer sort) so overlapping openings composite
        // correctly, and cap the count — each portal is a full destination render.
        net.minecraft.world.phys.Vec3 camPos = camera.position();
        groups.sort(java.util.Comparator.comparingDouble(
            g -> g.portal().getCenter().distanceToSqr(camPos)));
        if (groups.size() > MAX_PORTALS_RENDERED) {
            groups = new ArrayList<>(groups.subList(0, MAX_PORTALS_RENDERED));
        }
        return new RenderTargets(groups, camera);
    }

    /**
     * PHASE 1 — render the destination world into the secondary FBO.
     *
     * <p>Called from {@code GameRenderer.renderLevel} HEAD (via
     * {@link com.warwa.seamlessportals.mixin.client.GameRendererPortalPrepareMixin}),
     * BEFORE the main frame's level-render framegraph is built/executed. Issuing the
     * heavy nested {@code destRenderer.render(...)} here — rather than from
     * {@code AFTER_TRANSLUCENT_TERRAIN} (which fires mid-main-framegraph) — is what
     * stops the overworld from blanking: the nested deferred render is no longer
     * inside the main frame's in-flight framegraph.
     *
     * <p>This does ONLY the FBO render. The stencil mask + composite stay in phase 2
     * ({@link #renderPortals}) because they need the main framegraph's depth buffer
     * (so the obsidian frame occludes the mask) and run on the screen target.
     */
    public static void prepareDestinationRender() {
        // Reset the phase-1→phase-2 hand-off flag once per main frame,
        // unconditionally (before any guard), so a stale "FBO ready" from a
        // previous frame can never trigger a composite this frame.
        PortalContextSwitch.beginPortalFrame();

        // Recursion guard: while the dest world renders, its own framegraph fires
        // AFTER_TRANSLUCENT_TERRAIN; this also defends against any re-entrant
        // renderLevel. Match IP's PortalRendering.isRendering() check.
        if (PortalContextSwitch.isRenderingPortal) return;

        RenderTargets targets = resolveRenderTargets();
        if (targets == null) {
            // No portals nearby — free ALL pooled FBOs. The per-portal loop + eviction below
            // never runs in this case, so without this the FBOs of portals you lit earlier
            // would never be freed as you walk away (a per-portal GPU-memory leak).
            PortalContextSwitch.evictUnusedPortalFbos(java.util.Collections.emptySet());
            return;
        }

        // Render EACH portal's destination into ITS OWN pooled FBO (no composite — that is
        // phase 2). prepareDestinationWorld repoints the active FBO to this portal and records
        // its per-portal ready flag, which phase 2 reads when compositing that portal.
        java.util.Set<java.util.UUID> rendered = new java.util.HashSet<>();
        for (RenderGroup g : targets.groups()) {
            PortalContextSwitch.prepareDestinationWorld(g.portal(), g.link(), targets.camera());
            rendered.add(g.portal().getPortalId());
        }
        // Free pooled FBOs for portals no longer rendered, so the pool stays bounded.
        PortalContextSwitch.evictUnusedPortalFbos(rendered);
    }

    /**
     * PHASE 2 — stencil mask + composite, from {@code AFTER_TRANSLUCENT_TERRAIN}.
     */
    public static void renderPortals() {
        // Recursion guard: renderLevel() on secondary renderer triggers AFTER_TRANSLUCENT_TERRAIN
        // which calls this method again. Match IP's PortalRendering.isRendering() check.
        if (PortalContextSwitch.isRenderingPortal) return;

        RenderTargets targets = resolveRenderTargets();

        framesRendered++;
        if (targets == null) return;

        Camera camera = targets.camera();
        // Render each portal independently (near-to-far): its OWN stencil mask + its OWN
        // destination FBO. The stencil is cleared per portal so masks never bleed between them.
        for (RenderGroup g : targets.groups()) {
            renderOnePortal(g.portal(), g.link(), camera);
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
    private static void renderOnePortal(PortalInfo portal, PortalLink link, Camera camera) {
        java.util.List<PortalInfo> portals = java.util.List.of(portal);
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

        // Source-sliver fix: clamp depth around the depth-tested stencil write. Without it,
        // at an OCCLUDING block's silhouette the reversed-Z GEQUAL comparison between the
        // block's terrain-pass depth and this flat portal quad is unstable — a 1px ring of
        // opening pixels just outside the block fails GEQUAL, gets stencil=0, and the composite
        // (stencil EQUAL 1) skips them, leaving the SOURCE world showing (the sliver). Depth-
        // clamping stabilises the comparison at the silhouette. Mirrors IP ViewAreaRenderer's
        // enableDepthClamp/disableDepthClamp around its stencil write. try/finally so a thrown
        // draw can't leave clamp enabled for the rest of the frame.
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL32.GL_DEPTH_CLAMP);
        try {
            // Draw one merged quad with depth test - obsidian occludes stencil write
            PortalShapeRenderer.drawMergedPortalShapeWithDepthTest(portals, camera);
        } finally {
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL32.GL_DEPTH_CLAMP);
        }
        if (framesRendered <= 5) {
            int stencilWriteFbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
            SeamlessPortalsConstants.rlog(
                "[SEAMLESS DEBUG] Stencil write on FBO={}", stencilWriteFbo);
        }

        // ===== STEP 3: Set stencil to only pass where value == 1 =====
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        GL11.glStencilMask(0x00);

        // ===== STEP 3.5: Write NEAR-plane depth inside portal shape =====
        // Write the NEAREST depth value inside the stencil mask so ALL later
        // main-frame passes (clouds, weather, translucent terrain) FAIL the depth
        // test there and cannot overdraw the composited portal content.
        //
        // 26.2 uses a REVERSED-Z depth buffer (near = 1.0, far = 0.0) with a
        // GREATER_THAN_OR_EQUAL depth test. To occlude later passes the portal
        // region must hold the NEAREST value, 1.0 — then clouds (depth < 1.0)
        // FAIL GEQUAL and don't draw. glDepthRange(1,1) forces the depth-clear
        // quad to write 1.0. (Was glDepthRange(0,0) = 0.0 = FAR, which made the
        // portal the farthest surface so everything closer — the overworld
        // clouds — PASSED and X-rayed through the nether view.)
        // The PORTAL_DEPTH_CLEAR pipeline's ALWAYS_PASS test makes the write
        // always succeed; the FBO composite (TRACY_BLIT) has no depth test/write
        // so it ignores this depth and writes color through the stencil mask.
        GL11.glDepthRange(1, 1);
        PortalShapeRenderer.drawMergedPortalShapeWithDepthClear(portals, camera);
        GL11.glDepthRange(0, 1); // Restore normal depth range

        // ===== STEP 4: Composite the destination view through the stencil =====
        // The heavy dest-world render into the secondary FBO already happened in
        // PHASE 1 (PortalContextSwitch.prepareDestinationWorld, from renderLevel
        // HEAD — BEFORE this main-frame framegraph). Here in phase 2 we only put it
        // on screen: compositeDestinationWorld() draws the secondary FBO onto the
        // main screen through stencil EQUAL(1) when the FBO is ready, otherwise it
        // takes the colored-block / background fallback (which paints directly to
        // the main FBO through the stencil and so must run here, not in phase 1).
        //
        // This split is the fix for the "overworld blanks" bug: a nested full
        // destRenderer.render(...) issued from this AFTER_TRANSLUCENT_TERRAIN
        // callback re-entered LevelRenderer.render WHILE the main framegraph was
        // executing, disrupting its imported "main" target. The composite below
        // nests no framegraph, so it is safe mid-main-render.
        //
        // No background fill needed when the FBO is ready (sky fills the FBO). No
        // depth shield needed (FBO composite writes depth, blocking clouds/weather).
        PortalContextSwitch.compositeDestinationWorld(portals.get(0), link, camera);

        // ===== STEP 5: Reset stencil and disable =====
        GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        GL11.glStencilMask(0xFF);
        PortalShapeRenderer.drawMergedPortalShape(portals, camera);

        GL11.glStencilMask(0xFF);
        GL11.glDisable(GL11.GL_STENCIL_TEST);

        if (framesRendered <= 2) {
            SeamlessPortalsConstants.rlog(
                "[SEAMLESS STENCIL] Rendered {} portal planes batched on FBO {}",
                portals.size(), renderFbo
            );
        }
    }

    public static void cleanup() {
        framesRendered = 0;
    }
}
