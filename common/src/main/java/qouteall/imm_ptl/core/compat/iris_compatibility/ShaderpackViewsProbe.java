package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.imm_ptl.core.render.DrawCallTrace;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * IS0 — THE LEVER-GATED PROBE SUITE (iris shaders-ON engagement;
 * {@code migration/IRIS_SHADERS_ON_DESIGN.md} §1 IS0 deliverable 3, as amended by port-note
 * {@code migration/port-notes/IS-iris-shaders-on.md} §1.3 — where they disagree, the port-note
 * wins).
 *
 * <p>Armed ONLY by {@code -Dseamlessportals.shaderpackViewsProbe=true}
 * ({@link ShaderpackViewsProbeLever#PROBE_ENABLED} — hosted on the minimal lever HOLDER so
 * the anchor's per-frame check cannot class-initialize THIS class; Lens-B IS0 verify
 * correction); with the property absent every method here is unreachable (the anchor mixin's
 * dispatch folds on the holder's static-final false and this class never loads). Dispatched
 * EXCLUSIVELY from the IS0 post-main anchor
 * ({@code MixinGameRenderer_IPPostLevelAnchor} — port-note §1.2), i.e. at mc262
 * GameRenderer.renderLevel:566: after iris finalize, scene depth valid, world projection
 * active, before hand.
 *
 * <p><b>Three one-shot legs</b> (all logging {@code [IS0-PROBE]}-prefixed, latched booleans,
 * plain LOGGER at INFO — never per-frame; the log4j-stall rule, mining §8-16):
 * <ol>
 *   <li><b>P-OQ4</b> (design §1 IS0 3c; port-note §1.3-1 RE-DECIDED expected-POSITIVE): a
 *       one-time 16x16 centered {@code glReadPixels} DEPTH_COMPONENT readback of the main
 *       target AT the :566 anchor, FBO id resolved LIVE via
 *       {@code frameBufferCache().getFbo(...)} (never a captured id — mining §8-8).
 *       Pre-registered: non-trivial depth = CONFIRMED; all-FAR (reversed-Z FAR=0.0) =
 *       'mixin landed at the wrong site'.</li>
 *   <li><b>P-alpha/gamma</b> (design §1 IS0 3a; port-note §1.3-3): ONE commanded decomposed
 *       dest render (the landed {@code renderWorldNew}/{@code renderDestWorld} machinery,
 *       driven exactly like {@code PortalRenderer.renderPortalContent}) under a pack, logging
 *       GL_CURRENT_PROGRAM + GL_DRAW_FRAMEBUFFER around it — documentation of the (alpha)
 *       rejection, not a gate. Waits a bounded window for a portal in view; logs the
 *       documented no-portal fallback line otherwise.</li>
 *   <li><b>P-B2 — THE DECISIVE PROBE</b> (design §1 IS0 3b + §2.1 the borrowed bracket shape;
 *       port-note §1.3-2): snapshot main color+depth into a probe-local {@link TextureTarget}
 *       (straight device copies — NOT {@code blitAndBlendToTexture}, which is ALPHA-BLEND per
 *       the settled OQ5) → ONE direct 8-arg {@code LevelRenderer.render(...)} of the SOURCE
 *       world (same dim, no cross-dim variables) on
 *       {@code ClientWorldLoader.getWorldRenderer(sourceDim)} — clip + stencil neutralized —
 *       → restore the snapshot in a finally, exact reverse order. Answers: does iris 1.11.2
 *       tolerate a SECOND full pipeline lifecycle (double finalize, SystemTimeUniforms,
 *       isBeforeTranslucent, shadow/temporal) in ONE frame at the post-main anchor?
 *       Catches EVERYTHING; never rethrows; the probe must never crash the client.</li>
 * </ol>
 *
 * <p><b>Bracket note (P-B2).</b> The existing world-switch bracket
 * ({@code MyGameRenderer.switchAndRenderTheWorld}) is private and its INVOKE body is fixed to
 * the decomposed {@code renderDestWorld} — it cannot be borrowed for a direct render() call
 * without editing it (forbidden: the D1/serial/repoint/endFrame seams are READ-ONLY, and the
 * bracket itself must not drift). Per the port-note fallback, the probe replicates the
 * save/swap/restore-in-finally set (FBO_PRECEDENT_MINING §4) probe-locally — and because this
 * is a SOURCE-world probe, the swap set degenerates: {@code mc.level} is already the source
 * level, the camera/lightmap/particles/fog are already the source dim's, and
 * {@code ClientWorldLoader.getWorldRenderer(sourceDim)} is {@code mc.levelRenderer} ITSELF
 * (the promoted-identity invariant seeds the active dim's map entry with the main renderer,
 * ClientWorldLoader:675) — so the ONE potentially-load-bearing swap field,
 * {@code mc.levelRenderer} (sodium resolves it at call time), is saved/swapped/restored only
 * in the never-expected secondary case, and every other §4 field is identity by construction.
 * Each is NOTED in the HEAD log.
 *
 * <p><b>FramePass evidence</b> (port-note §1.3-2d): the frame BEFORE the P-B2 frame arms the
 * existing one-frame {@link DrawCallTrace} (S14.31 — every render pass label + pipeline bind,
 * dumped as a single log write at frame tail), so the P-B2 frame's full pass list — iris's
 * FramePasses included — lands in the log with zero new instrumentation.
 *
 * <p><b>Lazy-classload discipline</b> (the {@code SodiumCompatProbe} pattern): this top-level
 * class references NO iris type — every {@code net.irisshaders.*} reference lives in the
 * nested {@link IrisSide}, whose methods run only after the
 * {@code IrisInterface.invoker.isIrisPresent() && isShaders()} gate passes, so the nested
 * class (a separate {@code .class} file) is never loaded when iris is absent.
 *
 * <p>26.2 facts honored: mid-packet frames skip (never assert) — including the MISMATCH frame
 * class ({@code mc.player.level() != mc.level}, the exact predicate the landed code uses,
 * e.g. CrossPortalViewRendering:59-62; Lens-A IS0 verify correction — the null branch alone
 * is nearly dead since vanilla renderLevel dereferences the player); reversed-Z (FAR = 0.0);
 * GlStateManager-cached states never raw-GL'd (clip neutralize goes through
 * {@link FrontClipping#disableClipping()}, the single-writer cached owner; stencil is raw-GL
 * — vanilla 26.2 has no stencil cache, the mod's own idiom).
 *
 * <p><b>S20 sweep note</b>: this probe imports the {@code com.warwa}
 * GameRendererAccessorMixin (the fog accessor) — matching the existing qouteall-side
 * precedent (SecondaryWorldRenderCore:12, ClientWorldLoader:36, MyGameRenderer:289); the
 * zero-com.warwa rule binds the ANCHOR MIXIN only, which is clean.
 */
@Environment(EnvType.CLIENT)
public final class ShaderpackViewsProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderpackViewsProbe.class);

    private static final String P = "[IS0-PROBE] ";

    /** World-entry settle: legs arm only from this anchor-frame count on (port-note §1.3). */
    private static final int SETTLE_FRAMES = 200;
    /** P-alpha: bounded portal-in-view wait before the no-portal fallback line — anchored to
     * {@link #firstArmedFrame} (the frame the pack gate FIRST passed), NOT the absolute frame
     * counter (Lens-A IS0 verify correction: enabling the pack via the Iris UI routinely takes
     * past frame 800, which would have zeroed an absolute-anchored window). */
    private static final int ALPHA_PORTAL_WAIT_FRAMES = 600;
    /** P-B2 runs this many frames after the alpha leg resolved (never the same frame —
     * the commanded alpha draw may dirty the presented frame; B2's snapshot must not
     * embalm that). */
    private static final int B2_DELAY_FRAMES = 40;

    // one-shot latches (render-thread only)
    private static int frameCount = 0;
    /** First anchor frame at which the iris-present+pack gate passed; -1 = not yet. */
    private static int firstArmedFrame = -1;
    private static boolean waitingLogged = false;
    private static boolean oq4Done = false;
    private static boolean alphaResolved = false;
    private static int alphaResolvedFrame = -1;
    private static boolean b2TraceArmed = false;
    private static boolean b2Done = false;
    private static boolean b2NextFrameMarkerPending = false;
    private static boolean probeCrashed = false;

    private ShaderpackViewsProbe() {}

    /**
     * The single entry point, called from the anchor mixin under
     * {@link ShaderpackViewsProbeLever#PROBE_ENABLED} only.
     * Never throws (catch-everything, one-shot latched error, probe self-disables).
     */
    public static void onPostLevelAnchor(GameRenderer gameRenderer, DeltaTracker deltaTracker) {
        if (probeCrashed) {
            return;
        }
        try {
            frameCount++;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || mc.player.level() != mc.level) {
                // 26.2 renders frames mid-packet — skip, never assert. The MISMATCH frame
                // (player.level() != mc.level — the exact predicate the landed code uses,
                // CrossPortalViewRendering:59-62) is the real 26.2 mid-packet class; the null
                // branch alone is nearly dead (vanilla renderLevel dereferences the player),
                // and dimension-switch mismatch frames occur exactly at portal proximity —
                // where the alpha leg triggers (Lens-A IS0 verify correction).
                return;
            }
            if (b2NextFrameMarkerPending) {
                b2NextFrameMarkerPending = false;
                LOGGER.info(P + "P-B2 frame N+1 marker (anchorFrame=" + frameCount
                    + "): judge THIS frame's visuals against the verdict block's discriminators"
                    + " (clean -> GO; corrupt -> restore hole / lifecycle intolerance)."
                    + " glGetError=" + GL11.glGetError());
            }
            if (b2Done) {
                return; // all legs complete
            }
            if (frameCount < SETTLE_FRAMES) {
                return;
            }
            if (!IrisInterface.invoker.isIrisPresent() || !IrisInterface.invoker.isShaders()) {
                if (!waitingLogged) {
                    waitingLogged = true;
                    LOGGER.info(P + "probe lever ON but no active shaderpack"
                        + " (irisPresent=" + IrisInterface.invoker.isIrisPresent()
                        + "); all legs require a pack — waiting (enable a pack to run the round)");
                }
                return;
            }
            if (firstArmedFrame < 0) {
                // The pack gate first passed THIS frame — the alpha portal-wait window is
                // anchored here, not to the absolute counter (Lens-A IS0 verify correction).
                firstArmedFrame = frameCount;
            }
            if (!oq4Done) {
                oq4Done = true;
                runOq4DepthReadback(gameRenderer);
            }
            if (!alphaResolved) {
                if (tryAlphaLeg(gameRenderer)) {
                    alphaResolved = true;
                    alphaResolvedFrame = frameCount;
                }
                return; // P-B2 never shares the alpha frame (see B2_DELAY_FRAMES)
            }
            if (!b2TraceArmed) {
                if (frameCount >= alphaResolvedFrame + B2_DELAY_FRAMES - 1) {
                    b2TraceArmed = true;
                    // FramePass evidence: capture the WHOLE next frame (= the P-B2 frame)
                    // with the existing one-frame draw tracer — single log write at its tail.
                    DrawCallTrace.armed = true;
                    LOGGER.info(P + "P-B2: DrawCallTrace armed — the next CAPTURED frame is"
                        + " the probe frame (B2 verifies the capture is live at dispatch and"
                        + " re-arms if it was spent on a skipped frame); its full pass list"
                        + " (iris FramePasses included) dumps at that frame's tail.");
                }
                return;
            }
            // FramePass-evidence coupling (Lens-A IS0 verify correction): B2 must run INSIDE
            // a captured frame. If the armed one-frame capture was spent on a frame this
            // dispatcher skipped (a mid-packet mismatch frame, a pack toggled off for a
            // frame), DrawCallTrace.capturing is false HERE — re-arm and retry next frame
            // rather than running the decisive probe untraced. capturing is render-thread
            // state set at renderLevel HEAD (MixinGameRenderer:127) and cleared at
            // GameRenderer.render TAIL, so it is true at the anchor of any captured frame.
            if (!DrawCallTrace.capturing) {
                DrawCallTrace.armed = true;
                LOGGER.info(P + "P-B2: the armed draw-trace was spent on a skipped frame —"
                    + " re-armed; B2 retries next frame (must run inside a captured frame).");
                return;
            }
            b2Done = true;
            runB2HybridSourceRender(gameRenderer, deltaTracker);
            b2NextFrameMarkerPending = true;
        }
        catch (Throwable t) {
            probeCrashed = true;
            LOGGER.error(P + "PROBE CRASHED (one-shot; probe self-disabled for this session —"
                + " the probe must never crash the client)", t);
        }
    }

    // ===== P-OQ4 — one-shot main-target depth readback AT the :566 anchor ====================
    // Port-note §1.3-1: run AT the anchor (NOT "post-main" generically — a post-:571 sample is
    // all-cleared by construction). Expected POSITIVE; all-FAR now discriminates "mixin landed
    // at the wrong site", not "depth unusable".
    private static void runOq4DepthReadback(GameRenderer gameRenderer) {
        RenderTarget main = gameRenderer.mainRenderTarget();
        if (!(RenderSystem.getDevice().backend instanceof GlDevice glDevice)
            || !(main.getColorTextureView() instanceof GlTextureView colorView)
            || !(main.getDepthTextureView() instanceof GlTextureView depthView)
        ) {
            LOGGER.info(P + "P-OQ4: SKIPPED — non-GL backend or missing main texture views"
                + " (the FBO id must be resolved live via frameBufferCache; ids are never"
                + " captured — mining §8-8)");
            return;
        }
        // The S14.21-proven live resolver: the SAME (color view, depth view) key every
        // main-pass createRenderPass computes — a cache HIT returns the exact FBO in use.
        int mainFbo = glDevice.frameBufferCache().getFbo(
            glDevice.directStateAccess(), List.of(colorView), depthView
        );
        int prevRead = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, mainFbo);

        int rw = Math.min(16, main.width);
        int rh = Math.min(16, main.height);
        int rx = Math.max(0, main.width / 2 - rw / 2);
        int ry = Math.max(0, main.height / 2 - rh / 2);
        // PACK-STATE BRACKET (the IS0 probe-round double-crash fix, hs_err_pid248404/130364):
        // glReadPixels into CLIENT memory obeys GL_PACK_* state, and 26.2's
        // GlCommandEncoder.copyTextureToBuffer (:346) sets GL_PACK_ROW_LENGTH = width and
        // NEVER resets it (_pixelStore is a raw uncached passthrough; vanilla survives only
        // because its own readbacks always target bound PBOs). With a stale row length > rw,
        // the driver writes rh strided rows far past the FloatBuffer -> native heap
        // corruption -> delayed EXCEPTION_ACCESS_VIOLATION on whichever JVM housekeeping
        // thread (C2 compiler / GC worker) touches the trampled region first. Save, force
        // tight packing, read, restore-in-exact-reverse. The stale value is LOGGED as the
        // in-run evidence of the mechanism.
        int prevPackRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int prevPackSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int prevPackSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int prevPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
        FloatBuffer depths = BufferUtils.createFloatBuffer(rw * rh);
        GL11.glReadPixels(rx, ry, rw, rh, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depths);
        int glErr = GL11.glGetError();
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, prevPackSkipPixels);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, prevPackSkipRows);
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, prevPackRowLength);

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);

        LOGGER.info(P + "P-OQ4 pack-state at readback (pre-bracket): GL_PACK_ROW_LENGTH="
            + prevPackRowLength + " skipRows=" + prevPackSkipRows
            + " skipPixels=" + prevPackSkipPixels + " alignment=" + prevPackAlignment
            + (prevPackRowLength != 0
                ? " -> STALE ROW LENGTH CONFIRMED (the copyTextureToBuffer residue; the"
                    + " un-bracketed readback wrote strided rows over the native heap —"
                    + " the double-crash mechanism)"
                : " -> tight (no stale pack state this run)"));

        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        double sum = 0.0;
        int n = rw * rh;
        for (int i = 0; i < n; i++) {
            float d = depths.get(i);
            min = Math.min(min, d);
            max = Math.max(max, d);
            sum += d;
        }
        // reversed-Z: FAR = 0.0 (cleared/sky-far reads 0.0; scene geometry reads > 0).
        boolean allFar = max <= 1.0e-6f;
        LOGGER.info(P + "P-OQ4 depth readback AT the :566 anchor (" + rw + "x" + rh
            + " centered, fbo=" + mainFbo + ", reversed-Z FAR=0.0):"
            + " min=" + min + " max=" + max + " mean=" + (sum / n)
            + " glGetError=" + glErr + " -> "
            + (allFar
                ? "ALL-FAR: pre-registered verdict = 'mixin landed at the WRONG site'"
                    + " (depth is statically VALID at :566, before the :571 clear —"
                    + " port-note §1.3-1) — OR the readback center was OPEN SKY (reversed-Z"
                    + " sky legitimately reads 0.0): re-run looking at terrain before"
                    + " concluding"
                : "non-trivial scene depth: CONFIRMED — the occlusion query + the stamp's"
                    + " depth test have main-target depth to work with at this slot"));
    }

    // ===== P-alpha/gamma — ONE commanded decomposed dest render under a pack =================
    // Documentation of the (alpha) rejection (design §0.3), not a gate. Returns true when
    // resolved (leg ran, or the bounded portal wait expired -> fallback line).
    private static boolean tryAlphaLeg(GameRenderer gameRenderer) {
        Minecraft mc = Minecraft.getInstance();
        Portal portal = findNearestRenderablePortal(mc);
        if (portal == null) {
            // Window anchored to firstArmedFrame (the frame the pack gate first passed), so
            // enabling the pack late via the Iris UI still gets the FULL wait (Lens-A
            // correction — the absolute-counter form zeroed the window past frame 800).
            if (frameCount >= firstArmedFrame + ALPHA_PORTAL_WAIT_FRAMES) {
                LOGGER.info(P + "P-alpha/gamma: NO PORTAL IN VIEW within the wait window —"
                    + " leg NOT run (documented fallback; the leg is documentation, not a"
                    + " gate — port-note §1.1 reconciliation). Re-run the probe round"
                    + " standing near a portal to capture the commanded-draw program/FBO"
                    + " log. P-B2 proceeds regardless.");
                return true;
            }
            return false; // keep waiting for a portal
        }

        int progBefore = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int fboBefore = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        LOGGER.info(P + "P-alpha/gamma HEAD: commanding ONE decomposed dest render"
            + " (portal=" + portal.getDiscriminator()
            + " destDim=" + portal.getDestDim().identifier()
            + ") under pack '" + IrisInterface.invoker.getShaderpackName() + "'."
            + " GL_CURRENT_PROGRAM=" + progBefore + " GL_DRAW_FRAMEBUFFER=" + fboBefore + "."
            + " Expected context (port-note §1.3-3): mid-frame decomposed Step-10 draws run"
            + " isRenderingWorld=true/isMainBound=true, so iris's GlDevice substitution IS"
            + " live for vanilla-pipeline draws there, and under a pack sodium's ONE compiled"
            + " terrain program set IS the patchSodium-patched set — expect iris/patched"
            + " program ids (that IS the alpha documentation). At THIS post-main slot iris"
            + " finalize already ran, so the commanded draws execute with NO iris lifecycle"
            + " scoped to them — the (alpha) rejection's exact shape, observed live.");

        ClientLevel destWorld = ClientWorldLoader.getWorld(portal.getDestDim());
        // The renderPortalContent choreography, replicated (PortalRenderer.java:285-335):
        // push layer -> begin -> renderWorldNew(decomposed) -> end -> pop (finally-balanced,
        // the S14.29 hardening shape) -> depth-test re-enable + viewport restore.
        PortalRendering.pushPortalLayer(portal);
        try {
            PortalRendering.onBeginPortalWorldRendering();
            MyGameRenderer.renderWorldNew(
                new WorldRenderInfo.Builder()
                    .setWorld(destWorld)
                    .setCameraPos(PortalRendering.getRenderingCameraPos())
                    .setCameraTransformation(portal.getAdditionalCameraTransformation())
                    .setOverwriteCameraTransformation(false)
                    .setDescription(portal.getDiscriminator())
                    // getEffectiveRenderDistance, NOT the original's
                    // getPortalRenderDistance(portal) (scaled-portal /
                    // reducedPortalRendering branches) — acceptable for this
                    // documentation-only leg; switch for exactness if the leg is ever
                    // promoted beyond documentation (Lens-B IS0 verify note).
                    .setRenderDistance(mc.options.getEffectiveRenderDistance())
                    .setDoRenderHand(false)
                    .setEnableViewBobbing(true)
                    .setDoRenderSky(!portal.isFuseView())
                    .build(),
                Runnable::run
            );
            PortalRendering.onEndPortalWorldRendering();
        }
        finally {
            PortalRendering.popPortalLayer();
            GlStateManager._enableDepthTest();
            MyRenderHelper.restoreViewPort();
        }

        int progAfter = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int fboAfter = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        LOGGER.info(P + "P-alpha/gamma TAIL: decomposed dest render returned."
            + " GL_CURRENT_PROGRAM=" + progAfter
            + " (the last program the dest draws bound)"
            + " GL_DRAW_FRAMEBUFFER=" + fboAfter
            + " glGetError=" + GL11.glGetError() + "."
            + " NOTE: this frame's presented image may show unmasked dest-world draws"
            + " (one-shot, lever-only). Gamma re-entry stays D22-conditional: a USABLE image"
            + " here + user election.");
        return true;
    }

    private static Portal findNearestRenderablePortal(Minecraft mc) {
        Vec3 cameraPos = mc.gameRenderer.mainCamera().position();
        double range = mc.options.getEffectiveRenderDistance() * 16.0;
        Portal best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Portal portal : GlobalPortalStorage.getGlobalPortals(mc.level)) {
            double d = portalDistanceIfRenderable(portal, cameraPos, range);
            if (d < bestDistance) {
                bestDistance = d;
                best = portal;
            }
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Portal portal) {
                double d = portalDistanceIfRenderable(portal, cameraPos, range);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = portal;
                }
            }
        }
        return best;
    }

    private static double portalDistanceIfRenderable(Portal portal, Vec3 cameraPos, double range) {
        if (!portal.isPortalValid() || !portal.isVisible()
            || !portal.isRoughlyVisibleTo(cameraPos)
        ) {
            return Double.MAX_VALUE;
        }
        double d = portal.getDistanceToNearestPointInPortal(cameraPos);
        return d <= range ? d : Double.MAX_VALUE;
    }

    // ===== P-B2 — the decisive one-shot source-world hybrid render ===========================
    private static void runB2HybridSourceRender(GameRenderer gameRenderer, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        ResourceKey<Level> sourceDim = mc.level.dimension();
        RenderTarget main = gameRenderer.mainRenderTarget();
        if (main.getColorTexture() == null || main.getDepthTexture() == null) {
            LOGGER.info(P + "P-B2: SKIPPED — main render target has no color/depth texture");
            return;
        }

        // The port-note-named existing API: create-or-get the source dim's map renderer.
        LevelRenderer targetRenderer = ClientWorldLoader.getWorldRenderer(sourceDim);
        boolean isMainRenderer = (targetRenderer == mc.levelRenderer);

        // (i) SNAPSHOT — probe-local TextureTarget sized to main (constructed AT main's size,
        // so the resize-before-copyDepthFrom ordering constraint holds by construction; both
        // targets own depth textures — the copyDepthFrom preconditions, mining §2/§3 via
        // port-note §1-E OQ5). Color via the straight device copy (copyTextureToTexture =
        // REPLACE — the same primitive copyDepthFrom uses), NOT blitAndBlendToTexture
        // (ALPHA-BLEND, settled OQ5); no alpha caveat needed.
        TextureTarget snapshot = new TextureTarget(
            "seamlessportals_is0_probe_snapshot",
            main.width, main.height, true, main.getColorTexture().getFormat()
        );
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
            main.getColorTexture(), snapshot.getColorTexture(),
            0, 0, 0, 0, 0, main.width, main.height
        );
        snapshot.copyDepthFrom(main);

        LOGGER.info(P + "P-B2 HEAD (anchorFrame=" + frameCount + "):"
            + " sourceDim=" + sourceDim.identifier()
            + " renderer=" + (isMainRenderer
                ? "MAIN (WORLD_RENDERER_MAP[sourceDim] IS mc.levelRenderer — the"
                    + " promoted-identity invariant; the active dim has no separate secondary)"
                : ("SECONDARY " + identityString(targetRenderer)))
            + " snapshot=" + main.width + "x" + main.height + "."
            + " Probe-local bracket note (FBO_PRECEDENT §4 fallback, each field NOTED):"
            + " SOURCE-world probe => mc.level/camera/lightmap/particles/fog/hitResult/"
            + "noPhysics/renderBuffers are ALL identity (already the source dim's);"
            + " mc.levelRenderer is the one load-bearing swap (sodium call-time resolution)"
            + " and is " + (isMainRenderer ? "identity too — no swap performed"
                : "swapped/restored around the render") + ".");
        IrisSide.logHead(targetRenderer);

        // (ii) neutralize: stencil raw-GL (vanilla 26.2 caches no stencil state — the mod
        // idiom); clip through its cached single-writer owner (never raw-GL a
        // GlStateManager-cached/mirrored state — GL_CLIP_DISTANCE0's owner is the
        // FrontClipping store).
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        FrontClipping.disableClipping();

        LevelRenderer savedRenderer = mc.levelRenderer;
        Throwable thrown = null;
        long startNanos = System.nanoTime();
        try {
            if (!isMainRenderer) {
                ((IEMinecraftClient) mc).ip_setWorldRenderer(targetRenderer);
            }
            CameraRenderState cameraState =
                gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            // This frame's WORLD fog slice, read back from the main render's own FogRenderer
            // (the accessor the context switch already uses). Deliberately NO updateBuffer —
            // the fog ring buffer is shared frame state (mining §8-3a: never write it for a
            // secondary pass).
            FogRenderer fogRenderer =
                ((com.warwa.seamlessportals.mixin.client.GameRendererAccessorMixin) gameRenderer)
                    .seamlessportals$getFogRenderer();
            GpuBufferSlice worldFog = fogRenderer.getBuffer(FogRenderer.FogMode.WORLD);
            // The direct 8-arg render (design D16; the block-era-proven call shape) — INTO
            // THE MAIN TARGET (already snapshotted; the clobber is the mechanism under test).
            //
            // EXPECTED IN THE B2 DRAW TRACE (Lens-A IS0 verify note): this nested render
            // re-fires the flag-ON Fabric AFTER_TRANSLUCENT_TERRAIN listener a SECOND time
            // this frame with PortalRendering.isRendering()==false (B2 pushes no portal
            // layer), so the whole F1 driver body (switchToCorrectRenderer/prepareRendering/
            // onBeforeTranslucentRendering/finishRendering) runs inside it — benign at IS0
            // ONLY because isShaders()==true routes to rendererDummy via D8 (all no-ops),
            // i.e. the safety is inherited from the very deviation this engagement replaces.
            // CONSTRAINT if this probe pattern is re-run post-IS1 with a real shaders-ON
            // renderer live: push a portal layer (or assert isRendering()) around the nested
            // render, per the IS1 bracket inventory — else it recurses the real driver.
            targetRenderer.render(
                GraphicsResourceAllocator.UNPOOLED,
                deltaTracker,
                false, // renderOutline=FALSE — doubly load-bearing (port-note §1-E: fidelity
                       // AND neutralizes M11 + the Fabric block-outline event)
                cameraState,
                cameraState.viewRotationMatrix,
                worldFog,
                cameraState.fogData.color,
                true   // shouldRenderSky — vanilla sources !bossOverlay.shouldCreateWorldFog()
                       // (GameRenderer:562-565); under an active boss world-fog the probe
                       // diverges from the frame's real arg (sky pass runs where vanilla
                       // suppressed it). Immaterial for a one-shot snapshotted/restored
                       // probe; ledgered for arg-sourcing completeness (Lens-A note).
            );
        }
        catch (Throwable t) {
            thrown = t; // logged in the verdict block; NEVER rethrown
        }
        finally {
            try {
                if (!isMainRenderer) {
                    ((IEMinecraftClient) mc).ip_setWorldRenderer(savedRenderer);
                }
            }
            catch (Throwable t) {
                LOGGER.error(P + "P-B2 renderer restore failed", t);
            }
            // (iii) RESTORE in exact reverse of the snapshot order (color was copied first,
            // depth second -> restore depth first, color last).
            try {
                main.copyDepthFrom(snapshot);
                RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                    snapshot.getColorTexture(), main.getColorTexture(),
                    0, 0, 0, 0, 0, main.width, main.height
                );
            }
            catch (Throwable t) {
                LOGGER.error(P + "P-B2 snapshot RESTORE FAILED — expect a corrupt frame"
                    + " (restore-hole discriminator, not lifecycle evidence)", t);
            }
            try {
                snapshot.destroyBuffers();
            }
            catch (Throwable t) {
                LOGGER.error(P + "P-B2 snapshot teardown failed (one-shot leak)", t);
            }
            // re-assert the neutral belts (the render may have touched either)
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            FrontClipping.disableClipping();
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        int glErr = GL11.glGetError();

        IrisSide.logTail(targetRenderer);
        LOGGER.info(P + "P-B2 VERDICT BLOCK (frame N = anchorFrame " + frameCount + "):"
            + " renderReturned=" + (thrown == null)
            + " exception=" + (thrown == null ? "none" : thrown.toString())
            + " elapsedMs=" + elapsedMs
            + " glGetError=" + glErr
            + " dim=" + sourceDim.identifier() + "."
            + " Pre-registered discriminators (design §1-IS0): frame N+1 clean -> GO;"
            + " main-frame corruption post-restore -> restore hole (fix bracket, re-probe);"
            + " shadow/temporal flicker only -> D21 envelope, GO with notice;"
            + " crash inside an iris$ hook -> anchor/pipeline fork re-decide (P-A1/P-B1);"
            + " output never lands in main -> presentation-contract STOP class."
            + " CONFOUND (Lens-A note): transient chunk-mesh pop/holes on frame N+1 that"
            + " self-heal within a few frames = the one-shot double compile-drain (this"
            + " second render() re-ran compileSections/uploadTerrainBuffers — the"
            + " FBO_PRECEDENT §8-13 class), NOT lifecycle evidence; only PERSISTENT or"
            + " full-frame corruption discriminates."
            + " FramePass evidence = this frame's DRAW TRACE dump (capture verified live at"
            + " dispatch)."
            + " NOTE on a thrown render: LevelRenderer.render pushes the global model-view"
            + " stack and pops at its tail — a mid-render throw leaves one stray push"
            + " (visible as frame-N-only distortion; frame N+1 judges).");
        if (thrown != null) {
            LOGGER.error(P + "P-B2 nested render threw (caught; never rethrown)", thrown);
        }
    }

    private static String identityString(Object o) {
        return o == null ? "null"
            : (o.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(o)));
    }

    /**
     * ALL {@code net.irisshaders.*} references live here (separate class file; loaded only
     * after the presence+pack gate — the SodiumCompatProbe lazy-classload pattern). Reads are
     * exactly the port-note §1.3-2 sanctioned surface: {@code Iris.getCurrentDimension()},
     * {@code PipelineManager.preparePipeline/getPipelineNullable} (all PUBLIC on 1.11.2 —
     * no new reflection), {@code HandRenderer.INSTANCE} flags, plus the existing
     * {@code IrisInterface.invoker.getPipeline} facade read of the woven renderer field.
     */
    private static final class IrisSide {

        /** The pipeline object observed at HEAD — compared at TAIL (§1.3-2c). */
        private static Object headPipeline = null;

        static void logHead(LevelRenderer targetRenderer) {
            net.irisshaders.iris.shaderpack.materialmap.NamespacedId dim =
                net.irisshaders.iris.Iris.getCurrentDimension();
            net.irisshaders.iris.pipeline.PipelineManager pm =
                net.irisshaders.iris.Iris.getPipelineManager();
            Object nullable = pm.getPipelineNullable();
            // Source-world probe: the current dim's pipeline already exists (the main render
            // just used it) — preparePipeline must return it WITHOUT a create (§1.3-2a:
            // expect source dim, existing pipeline, NO create, NO SystemTimeUniforms reset).
            Object prepared = pm.preparePipeline(dim);
            headPipeline = prepared;
            LOGGER.info(P + "P-B2 iris HEAD: Iris.getCurrentDimension=" + dim
                + " getPipelineNullable=" + identityString(nullable)
                + " preparePipeline(currentDim)=" + identityString(prepared)
                + " sameObject=" + (nullable == prepared)
                + " (expected: source dim, EXISTING pipeline, no create — port-note §1.3-2a)"
                + " rendererPipelineField=" + identityString(
                    IrisInterface.invoker.getPipeline(targetRenderer))
                + " HandRenderer.isActive=" + net.irisshaders.iris.pathways.HandRenderer.INSTANCE.isActive()
                + " isRenderingSolid=" + net.irisshaders.iris.pathways.HandRenderer.INSTANCE.isRenderingSolid());
        }

        static void logTail(LevelRenderer targetRenderer) {
            Object nullable =
                net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
            LOGGER.info(P + "P-B2 iris TAIL: getPipelineNullable=" + identityString(nullable)
                + " sameAsHeadPipeline=" + (nullable == headPipeline)
                + " (expected TRUE: the manager slot stays at the probed pipeline until the"
                + " next main render HEAD — transient, self-corrects; §1.3-2c)"
                + " rendererPipelineField(post)=" + identityString(
                    IrisInterface.invoker.getPipeline(targetRenderer))
                + " (expected null — iris$endLevelRender nulls the scratch field at the"
                + " 8-arg render's own tail; port-note §1-B)"
                + " HandRenderer.isActive=" + net.irisshaders.iris.pathways.HandRenderer.INSTANCE.isActive()
                + " isRenderingSolid=" + net.irisshaders.iris.pathways.HandRenderer.INSTANCE.isRenderingSolid()
                + " (the §1-F-2 hand-inside-nested-render observable cannot be sampled"
                + " mid-call at IS0 — before/after flags logged; whether iris's own hand drew"
                + " inside the nested render rides the visual judgment + the IS2 round)");
        }
    }
}
