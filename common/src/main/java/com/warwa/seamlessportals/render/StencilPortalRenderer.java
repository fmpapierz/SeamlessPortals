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

    /**
     * Phase 5 master switch. When true, the dest world is drawn DIRECTLY into the main
     * framebuffer masked by the stencil — IP's {@code RendererUsingStencil} model, no
     * mirror-FBO, no composite, fragment cost bounded to the portal opening. When false,
     * the legacy two-phase mirror-FBO path runs unchanged (for A/B against the known-good
     * path). See {@code PHASE5_STENCIL_DIRECT_SPEC.md}.
     *
     * <p>Default true so {@code :fabric:runClient} exercises the rework directly. If the
     * stencil-direct path regresses (e.g. the opening shows the overworld, or the dest
     * doesn't appear), flip this to false to confirm it's the new path and restore the
     * working FBO render while it's debugged.
     */
    public static boolean STENCIL_DIRECT = true;

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
     * Build the MAIN camera's cull frustum at the current projection (the player's view),
     * for the portal-visibility cull. Returns null if the projection isn't available yet,
     * in which case the caller skips the cull (renders all in-range portals — the prior
     * behaviour) rather than risk culling a visible portal to nothing.
     */
    private static net.minecraft.client.renderer.culling.Frustum buildMainFrustum(Camera camera) {
        try {
            Minecraft mc = Minecraft.getInstance();
            var mainCameraState =
                mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            if (mainCameraState == null || mainCameraState.projectionMatrix == null) return null;
            org.joml.Matrix4f proj = new org.joml.Matrix4f(mainCameraState.projectionMatrix);
            org.joml.Matrix4f view = new org.joml.Matrix4f();
            camera.getViewRotationMatrix(view);
            net.minecraft.client.renderer.culling.Frustum f =
                new net.minecraft.client.renderer.culling.Frustum(view, proj);
            net.minecraft.world.phys.Vec3 cp = camera.position();
            f.prepare(cp.x, cp.y, cp.z);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Discover the linked portals near the player. Returns {@code null} if there is nothing to
     * render. Each portal becomes its OWN render group with its OWN link — previously all nearby
     * portals were collapsed into one list + firstLink, so two separate portals showed ONE
     * stretched destination (the two-portals bug). IP renders per-portal the same way.
     *
     * <p>Side-effect free apart from being a pure read of the client portal state.
     */
    /**
     * Cheap per-frame check: is any linked portal within portal-render range of the
     * player (and portal rendering enabled)? Used by
     * {@code LevelRendererBlockOutlineMixin} at SUBMIT time to decide whether the
     * targeted-block outline must be re-bucketed to vanilla's after-terrain phase.
     *
     * <p>Why: the block outline normally draws in the features phase (LevelRenderer
     * main pass :434, {@code shapeOutlines} bucket) with {@code RenderPipelines.LINES}
     * — {@code DepthStencilState.DEFAULT} = GEQUAL + depth WRITE — at ~2px line width.
     * That runs BEFORE our portal render (AFTER_TRANSLUCENT_TERRAIN wraps the
     * translucent renderGroup at :438). When the player targets a frame block, the
     * outline's wide lines overhang the opening and write NEARER depth there, so the
     * depth-tested STEP 2 stencil write fails on those pixels → stencil=0 → the fill
     * skips them → a tiny line-shaped sliver of stale SOURCE colour at the frame edge.
     * Vanilla's own {@code afterTerrain} outline bucket (used for translucent targeted
     * blocks) draws at :440 — immediately AFTER our portal render — where the STEP 3.7
     * NEAR depth shield cleanly clips the overhang at the window edge instead.
     */
    public static boolean anyPortalNearCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        if (!SeamlessPortalsConfig.get().isEnablePortalRendering()) return false;
        PortalTracker tracker = PortalManager.getClientInstance().getTracker(mc.level.dimension());
        double range = SeamlessPortalsConfig.get().getPortalRenderDistance() * 16.0;
        return !tracker.getPortalsInRange(mc.player.blockPosition(), range).isEmpty();
    }

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

        // IP-style portal VISIBILITY cull (RendererUsingFrameBuffer.testShouldRenderPortal):
        // render a portal's destination ONLY when the portal is actually ON SCREEN. Without
        // this we did up to MAX_PORTALS_RENDERED FULL dest-world renders per frame for the
        // nearest portals within range — INCLUDING ones behind the player / off to the side
        // (the per-frame GPU cost that regressed vs the single-portal stutter-free runs).
        // Frustum-cull against the main camera, with an inflated margin so a portal at the
        // screen edge is decided the SAME way in BOTH render phases (no FBO/composite mismatch).
        net.minecraft.client.renderer.culling.Frustum mainFrustum = buildMainFrustum(camera);

        List<RenderGroup> groups = new ArrayList<>();
        for (PortalInfo portal : nearbyPortals) {
            if (!SeamlessPortalsConfig.shouldRenderThrough(portal.getType())) continue;
            if (mainFrustum != null
                    && !mainFrustum.isVisible(portal.getBoundingBox().inflate(8.0))) continue;
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
        // NB the per-frame crossing check used to live here (renderLevel HEAD) — WRONG: in 26.2
        // the frame's camera is positioned in GameRenderer.update BEFORE extract/renderLevel, so
        // a swap here rendered the dest level with the stale source-position camera for one frame
        // (the fog-colored flash, proven by [SEAMLESS XTRACE] pd=-39.9). It now runs at
        // GameRenderer.update HEAD via GameRendererFrameCrossingMixin (IP's placement).

        // Instant-portal-view: flush the dest renderers' staged mesh uploads once per frame at
        // this pre-framegraph point (GPU-upload-safe timing — mid-pass flushing resizes bound
        // buffers and flashes the screen). Freshly compiled dest meshes become drawable
        // immediately → the portal window fills smoothly instead of in staging-overflow bursts.
        com.warwa.seamlessportals.client.PortalWorldManager.flushDestStagedUploads();

        // Phase 5 (stencil-direct): there is NO phase-1 FBO render. The dest world is drawn
        // directly into the main target during phase 2 (renderOnePortal → renderDestWorldDirect),
        // so the rest of this renderLevel-HEAD hook does nothing.
        if (STENCIL_DIRECT) return;

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
        long fboT0 = System.nanoTime();
        for (RenderGroup g : targets.groups()) {
            PortalContextSwitch.prepareDestinationWorld(g.portal(), g.link(), targets.camera());
            rendered.add(g.portal().getPortalId());
        }
        // DIAG: attribute the per-frame dest FBO render cost (≈0 when no portal is in
        // view — confirms whether the "stutters when not looking" cost is here or elsewhere).
        long fboMs = (System.nanoTime() - fboT0) / 1_000_000L;
        PerfTimers.add("fboRender(" + targets.groups().size() + "p)", System.nanoTime() - fboT0);
        RenderSpikeMonitor.recordFbo(fboMs);
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

        // DIAG: off-thread frame-gap + stall-watchdog telemetry. Render-thread stamp only
        // (cheap, no logging); daemon threads emit [SEAMLESS PERF] summaries every 5s and
        // dump the render-thread stack on any >150ms stall ([SEAMLESS STUCK]) — capturing
        // the exact stalling method. Counts MAIN frames only (after the recursion guard).
        RenderSpikeMonitor.onFrame();
        // Crossing-flash tracer: per-frame ring-buffer record (dim, camera, plane distance) —
        // zero logging on the render thread; dumped off-thread after each crossing.
        CrossingTracer.recordFrame();

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
        CrossingTracer.notePortalRendered();
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
        if (STENCIL_DIRECT) {
            // Phase 5: CLEAR the opening's depth to FAR *before* the dest terrain draws, so
            // every dest fragment passes the reversed-Z GEQUAL test inside the opening (IP
            // clearDepthOfThePortalViewArea). 26.2 reversed-Z: FAR = 0.0, written via
            // glDepthRange(0,0). This is the OPPOSITE direction to the FBO path's NEAR write
            // below — there the composite needs protecting from later passes; here the dest
            // terrain must not be z-rejected by the overworld depth already in the opening.
            // (Re-protecting the directly-drawn terrain from clouds/weather is Step 2.)
            //
            // FULL-SCREEN, stencil-gated — exactly IP's clearDepthOfThePortalViewArea
            // (a stencil-gated renderScreenTriangle), NOT a re-draw of the portal quad.
            // A full-screen triangle covers every stencil=1 pixel by construction; a
            // re-rasterized quad can miss boundary pixels the stencil write covered.
            GL11.glDepthRange(0, 0);
            drawScreenDepthClearStencilGated();
            GL11.glDepthRange(0, 1);
        } else {
            // FBO mode: write NEAR (1.0) so later main-frame passes (clouds, weather,
            // translucent terrain) FAIL GEQUAL inside the opening and can't overdraw the
            // composited portal content.
            GL11.glDepthRange(1, 1);
            PortalShapeRenderer.drawMergedPortalShapeWithDepthClear(portals, camera);
            GL11.glDepthRange(0, 1); // Restore normal depth range
        }

        // ===== STEP 3.6 (stencil-direct): fill the opening with the DEST sky/fog colour =====
        // Paint the opening the destination dimension's sky/fog colour BEFORE the dest OPAQUE
        // terrain draws (no depth test/write, so the depth-tested terrain still draws over it).
        // GAPS in the opaque-only terrain then read as dest sky instead of the OTHER dimension's
        // terrain that renderGroup's LOAD leaves in the colour buffer — fixes "nether terrain
        // bleeds into the OW view" / "overworld in the gaps".
        //
        // FULL-SCREEN, stencil-gated — exactly IP's replaceFrameBufferClearing (a
        // stencil-gated renderScreenTriangle in the fog colour), NOT a re-drawn portal
        // quad. The old quad fill (drawPortalBackground) rasterized DIFFERENT geometry
        // than the STEP 2 stencil write (no edge outset) — pixels the stencil covered
        // but the fill missed kept STALE SOURCE colour, and wherever the (inner-clipped)
        // dest draws didn't cover them either, the user saw a very thin see-through
        // sliver between the portal render's edge and the obsidian frame. A full-screen
        // triangle gated purely by the stencil is pixel-exact with the mask by
        // construction, so no fill/stencil mismatch ring can exist.
        if (STENCIL_DIRECT) {
            drawScreenFillStencilGated(resolveDestFillArgb(link.getDestination().getDimension()));
        }

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
        //
        // Phase-5 seam: the dest-world draw goes through renderDestWorldDirect(). Today it
        // delegates to the FBO composite (unchanged behaviour); Step 1 of the stencil-direct
        // rework swaps it for a direct LevelRenderer.prepareChunkRenders + renderGroup draw
        // into the main target under the stencil (no FBO, no nested framegraph). See
        // PHASE5_STENCIL_DIRECT_SPEC.md.
        long destT0 = System.nanoTime();
        renderDestWorldDirect(portals.get(0), link, camera, 1);
        if (STENCIL_DIRECT) {
            // Replaces the phase-1 "fboRender" bucket (now skipped). If the rework works,
            // [SEAMLESS TIMERS] shows stencilDirectRender HERE and fboRender GONE — and this
            // should be far cheaper than fboRender was (fragment cost bounded to the opening,
            // not a full-screen second-world render that scaled with portalRenderDistance²).
            PerfTimers.add("stencilDirectRender", System.nanoTime() - destT0);
            // ===== STEP 3.7 (stencil-direct): depth shield AFTER the dest content =====
            // The opening's depth was cleared to FAR so the dest terrain could draw; now write
            // NEAR (1.0) across the whole opening so later main-frame passes (clouds, weather,
            // translucent terrain) FAIL the reversed-Z GEQUAL test there and cannot draw over the
            // dest view — fixes the main world's clouds showing through the opening's gaps. The
            // dest colour (terrain + the dest-sky fill) is already in the buffer; this only
            // rewrites depth. Mirrors IP restoreDepthOfThePortalViewArea, and must run AFTER the
            // terrain (writing NEAR before it would z-reject the terrain).
            GL11.glDepthRange(1, 1);
            PortalShapeRenderer.drawMergedPortalShapeWithDepthClear(portals, camera);
            GL11.glDepthRange(0, 1);
        }

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

    /**
     * IP {@code RendererUsingStencil.setStencilLimitation(layer)}: constrain every subsequent
     * draw to the pixels whose stencil value equals {@code layer} — the portal opening at this
     * recursion depth — without modifying the stencil (mask 0). This is the test that masks the
     * dest-world draw to the opening, bounding fragment cost to the portal (the IP cheapness).
     *
     * <p>Raw GL by necessity: blaze3d models no stencil ({@code DepthStencilState} is depth-only),
     * and the GL backend's {@code GlCommandEncoder.applyPipelineState} touches depth/cull/blend/
     * colour-mask but NEVER stencil (verified in {@code mc262-ref}). So stencil state set here
     * PERSISTS through the vanilla {@code ChunkSectionsToRender.renderGroup} draws that follow —
     * which is exactly why drawing the dest terrain directly into the main target ends up masked.
     */
    static void setStencilLimitation(int layer) {
        GL11.glStencilFunc(GL11.GL_EQUAL, layer, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GL11.glStencilMask(0x00);
    }

    /**
     * Phase-5 seam for the dest-world draw, called from {@code renderOnePortal} with the stencil
     * already set up (mask written, depth cleared, {@link #setStencilLimitation} pending) so the
     * draw lands only inside the portal opening.
     *
     * <p>TODAY this delegates to the FBO composite — identical behaviour to before the seam
     * existed. STEP 1 of {@code PHASE5_STENCIL_DIRECT_SPEC.md} replaces the body with:
     * {@code setStencilLimitation(layer)} → {@code withSwitchedWorld(dest)} → build dest camera +
     * inner frustum → {@code VisibleSectionDiscovery.discoverAndScheduleForPortalView} → dest
     * {@code LevelRenderer.prepareChunkRenders(destModelView)} → {@code renderGroup(OPAQUE,…)} /
     * {@code renderGroup(TRANSLUCENT,…)} straight into {@code mainRenderTarget()} — no FBO, no
     * nested {@code destRenderer.render(...)} framegraph (the cause of the overworld blanking).
     */
    private static void renderDestWorldDirect(PortalInfo portal, PortalLink link, Camera camera, int layer) {
        if (STENCIL_DIRECT) {
            // Phase 5: draw the dest terrain straight into the main target, masked by the
            // stencil. If the dest isn't drawable yet (chunks still streaming → returns
            // false), paint a solid dimension-coloured background through the stencil so the
            // opening reads as a portal instead of momentarily showing the overworld.
            boolean drawn = PortalContextSwitch.renderDestinationDirect(portal, link, camera, layer);
            if (!drawn) {
                // Same stencil-gated full-screen fill as STEP 3.6 (not a quad re-draw),
                // so cold frames can't show the fill/stencil mismatch sliver either.
                drawScreenFillStencilGated(resolveDestFillArgb(link.getDestination().getDimension()));
            }
            return;
        }
        // Legacy two-phase mirror-FBO path (phase 1 rendered the dest into the secondary FBO
        // at renderLevel HEAD; this composites it onto the screen through the stencil).
        PortalContextSwitch.compositeDestinationWorld(portal, link, camera);
    }

    // ===== Stencil-gated full-screen draws (IP MyRenderHelper.renderScreenTriangle analogs) =====
    //
    // IP never re-rasterizes the portal-quad geometry for the depth clear or the
    // background fill: clearDepthOfThePortalViewArea and replaceFrameBufferClearing are
    // both FULL-SCREEN triangles gated purely by the stencil test, so their pixel
    // coverage is exact w.r.t. the stencil mask by construction. Re-drawing the quad
    // (with any epsilon) can rasterize differently from the STEP 2 stencil write —
    // the mismatch ring showed as a very thin see-through sliver at the frame edge.
    //
    // 26.2 has no matrix-stack identity path for immediate NDC draws, so the
    // full-screen triangle comes from vanilla's core/screenquad vertex shader
    // (positions generated from gl_VertexID — no vertex buffer, no matrices), the
    // same mechanism as the proven FBO composite blit. The solid fill colour is fed
    // through core/blit_screen from a cached 1×1 texture. Raw-GL stencil EQUAL(1)
    // (set in STEP 3) persists into these passes — blaze3d never touches stencil.

    private static com.mojang.blaze3d.textures.GpuTexture screenFillTexture;
    private static com.mojang.blaze3d.textures.GpuTextureView screenFillTextureView;
    private static int screenFillTextureArgb;

    /** Fog/sky ARGB used for the opening fill: the REAL captured dest fog colour when
     *  available, else the per-dimension cold-start fallback (same policy as the old
     *  drawPortalBackground). */
    private static int resolveDestFillArgb(ResourceKey<Level> destDim) {
        Integer realFog = PortalContextSwitch.getDestSkyFogArgb(destDim);
        if (realFog != null) return realFog;
        if (destDim == Level.NETHER) return 0xFF1A0808; // dark nether red
        if (destDim == Level.END) return 0xFF0A0A18;    // dark end purple
        return 0xFF87CEEB;                              // overworld sky blue
    }

    /** Lazily create the 1×1 RGBA8 fill texture and (re)upload when the colour changes. */
    private static com.mojang.blaze3d.textures.GpuTextureView ensureScreenFillTexture(int argb) {
        com.mojang.blaze3d.systems.GpuDevice device =
            com.mojang.blaze3d.systems.RenderSystem.getDevice();
        if (screenFillTexture == null) {
            screenFillTexture = device.createTexture(
                "seamlessportals screen fill",
                com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING
                    | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST,
                com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
            screenFillTextureView = device.createTextureView(screenFillTexture);
            screenFillTextureArgb = ~argb; // force the first upload
        }
        if (screenFillTextureArgb != argb) {
            // RGBA8_UNORM uploads as GL_RGBA + GL_UNSIGNED_BYTE → byte order R,G,B,A.
            java.nio.ByteBuffer px = java.nio.ByteBuffer.allocateDirect(4);
            px.put((byte) ((argb >> 16) & 0xFF));
            px.put((byte) ((argb >> 8) & 0xFF));
            px.put((byte) (argb & 0xFF));
            px.put((byte) ((argb >> 24) & 0xFF));
            px.flip();
            device.createCommandEncoder().writeToTexture(screenFillTexture, px, 0, 0, 0, 0, 1, 1);
            screenFillTextureArgb = argb;
        }
        return screenFillTextureView;
    }

    /**
     * Fill every stencil=1 pixel with a solid colour — IP's replaceFrameBufferClearing
     * (stencil-gated renderScreenTriangle(fogColor)). Caller must have the stencil test
     * enabled with EQUAL(1) (STEP 3 state). No depth test, no depth write.
     */
    private static void drawScreenFillStencilGated(int argb) {
        com.mojang.blaze3d.textures.GpuTextureView texView = ensureScreenFillTexture(argb);
        com.mojang.blaze3d.pipeline.RenderTarget mainRT =
            Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (mainRT == null || texView == null) return;

        // Raw-GL backstops (mirrors compositePortalFbo): the pipeline declares no blend
        // and no depth state, but applyPipelineState short-circuits when lastPipeline is
        // unchanged, so force the GL state we depend on.
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        try (com.mojang.blaze3d.systems.RenderPass pass =
                 com.mojang.blaze3d.systems.RenderSystem.getDevice()
                     .createCommandEncoder().createRenderPass(
                () -> "portal_screen_fill",
                mainRT.getColorTextureView(),
                java.util.Optional.empty(),
                mainRT.getDepthTextureView(),
                java.util.OptionalDouble.empty(),
                new com.mojang.blaze3d.systems.RenderPass.RenderArea(0, 0, mainRT.width, mainRT.height)
        )) {
            pass.setPipeline(PortalRenderTypes.portalCompositeBlit());
            com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", texView,
                com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(
                    com.mojang.blaze3d.textures.FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /**
     * Write depth across every stencil=1 pixel — IP's clearDepthOfThePortalViewArea
     * (stencil-gated renderScreenTriangle with depth ALWAYS + glDepthRange clamp).
     * The written VALUE comes from the caller's glDepthRange: (0,0) = reversed-Z FAR
     * (STEP 3.5 clear before the dest draws). Colour is masked off by the pipeline.
     */
    private static void drawScreenDepthClearStencilGated() {
        com.mojang.blaze3d.textures.GpuTextureView texView =
            ensureScreenFillTexture(screenFillTexture == null ? 0xFF000000 : screenFillTextureArgb);
        com.mojang.blaze3d.pipeline.RenderTarget mainRT =
            Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (mainRT == null || texView == null) return;

        // Depth WRITES require GL_DEPTH_TEST enabled; the pipeline's ALWAYS_PASS+write
        // state sets it via applyPipelineState, but force it as a backstop against the
        // lastPipeline short-circuit.
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        try (com.mojang.blaze3d.systems.RenderPass pass =
                 com.mojang.blaze3d.systems.RenderSystem.getDevice()
                     .createCommandEncoder().createRenderPass(
                () -> "portal_screen_depth_clear",
                mainRT.getColorTextureView(),
                java.util.Optional.empty(),
                mainRT.getDepthTextureView(),
                java.util.OptionalDouble.empty(),
                new com.mojang.blaze3d.systems.RenderPass.RenderArea(0, 0, mainRT.width, mainRT.height)
        )) {
            pass.setPipeline(PortalRenderTypes.portalScreenDepthClear());
            com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture("InSampler", texView,
                com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(
                    com.mojang.blaze3d.textures.FilterMode.NEAREST));
            pass.draw(3, 1, 0, 0);
        }
        GL11.glEnable(GL11.GL_BLEND);
    }

    public static void cleanup() {
        framesRendered = 0;
    }
}
