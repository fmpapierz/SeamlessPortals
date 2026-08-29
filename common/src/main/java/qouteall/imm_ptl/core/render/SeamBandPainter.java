package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamCartContinuity;
import com.warwa.seamlessportals.passthrough.SeamCartProbe;
import com.warwa.seamlessportals.passthrough.SeamCrossingRule;
import com.warwa.seamlessportals.passthrough.SeamStraddleBracket;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.collision.PortalCollisionHandler;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.q_misc_util.my_util.Plane;

/**
 * ENGINE STAGE 2b — THE BAND PAINTER (SEAM_ENTITY_ENGINE_DESIGN §2.5), v2: the
 * IDENTICAL-REPAINT mechanism. The ±ADJUSTMENT slab at a straddled seam plane is the region no
 * stenciled painter can own at every view angle (the window's draws are screen-confined;
 * parallax pushes owed band pixels outside — the tail sliver) and no pre-pass painter can own
 * inside the aperture (the pass overdraws — rounds 12/18). This painter runs on the SECOND
 * {@code AFTER_TRANSLUCENT_TERRAIN} registration, after every portal pass of the frame.
 *
 * <p><b>Why no stencil, no masks, no GL state (v1's post-mortem):</b> 26.2 applies per-draw
 * PIPELINE state; raw color-mask suppression around {@code renderAllFeatures} is overridden
 * per draw, so v1's "invisible" mark pass painted the whole behind-half (the live full-model
 * bleed). v2 exploits the seam exact-alignment policy instead: for a TRANSLATION transform,
 * each piece rasterizes IDENTICALLY to what the frame already painted (same camera math, same
 * vertices — the main pass drew the front piece; the in-pass/main-pass projections drew the
 * back piece at the image), so re-drawing a piece clipped to its half-space PLUS the band
 * produces byte-identical fragments where content already exists and fills exactly the band
 * where it is missing. Depth-tested (paints only where visible); depth writes are identical
 * values, harmless. Opaque/cutout bodies repaint invisibly; the Q2 glint-class double-blend
 * residual is the disclosed default.
 *
 * <p><b>Pieces:</b> (1) the real body at entity coordinates, clip keep {@code d ≥ −ADJ} —
 * covers the front piece + the band from the front side; (2) the projected body at the
 * translation image, clip keep {@code d ≤ +ADJ} — covers the image's back piece + the band
 * from the back side, gated by the SAME aperture mask as the main-pass projection (design:
 * never wider than what E3 drew this frame) and the dim check. Non-translation transforms skip
 * piece 2 (logged once). ★ DEFAULT-OFF since round 29 — lever {@code -PenableSeamBandPainter=true};
 * see {@code AperturePassthroughLever.ENABLE_SEAM_BAND_PAINTER} for why the polarity inverted.
 */
@Environment(EnvType.CLIENT)
public class SeamBandPainter {

    private SeamBandPainter() {}

    private static final double ADJ = FrontClipping.ADJUSTMENT;

    private static boolean nonTranslationLoggedOnce = false;

    /** The second AFTER_TRANSLUCENT_TERRAIN hook — runs after the portal driver's, every frame. */
    public static void onAfterPortalPasses() {
        // ★ ROUND 29: DEFAULT-OFF. This painter authored three of the four standing artifacts
        // (both plane slivers and the first-person own-head obstruction — see
        // AperturePassthroughLever.ENABLE_SEAM_BAND_PAINTER for the full evidence), and what
        // shipped here is the half-space whole-piece redraw that design §2.5 marks [PROHIBITED],
        // not §2.5's stencil-intersected slab. Retained for that redesign, not for use.
        if (!AperturePassthroughLever.ENABLE_SEAM_BAND_PAINTER) {
            return;
        }
        if (PortalRendering.isRendering()) {
            return;
        }
        if (IrisInterface.invoker.isIrisPresent()) {
            // Consistent with the per-entity clip's Iris known-open: entity programs are not
            // clip-injected under shaders; the painter would draw uncut bodies.
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.levelRenderer == null) {
            return;
        }
        CameraRenderState cam =
            mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        Vec3 camPos = mc.gameRenderer.mainCamera().position();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();

        for (Entity entity : CrossPortalEntityRenderer.collidedEntitiesView()) {
            if (entity.level() != mc.level) {
                continue;
            }
            PortalCollisionHandler handler = ((IEEntity) entity).ip_getPortalCollisionHandler();
            Portal face = SeamCrossingRule.resolveCrossingFace(entity, handler);
            if (face == null || !SeamCartContinuity.isSeamContinuous(face)) {
                continue;
            }
            // The band exists only during a LITERAL straddle (design §2.5 enumeration).
            // BEACON mode widens to the whole crossing so the beacon persists visibly.
            if (AperturePassthroughLever.SEAM_BAND_BEACON) {
                if (!SeamStraddleBracket.pinned(entity, face)) {
                    continue;
                }
            }
            else if (!SeamStraddleBracket.frontPieceExists(entity, face)
                || !SeamStraddleBracket.backPieceExists(entity, face)) {
                continue;
            }
            drawMemberBand(dispatcher, cam, camPos, entity, face);
        }
    }

    private static long lastGlTruthNanos = 0;

    /** Probe 0 (verdict Part 4.4): the measured GL truth at the painter's slot, ~1/s. */
    private static void glTruthLine(Entity entity) {
        long now = System.nanoTime();
        if (now - lastGlTruthNanos < 1_000_000_000L) {
            return;
        }
        lastGlTruthNanos = now;
        SeamCartProbe.event(entity, "BAND-GL depthFunc=0x"
            + Integer.toHexString(org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL11.GL_DEPTH_FUNC))
            + " depthMask=" + org.lwjgl.opengl.GL11.glGetBoolean(
                org.lwjgl.opengl.GL11.GL_DEPTH_WRITEMASK)
            + " drawFbo=" + org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING)
            + " stencil=" + org.lwjgl.opengl.GL11.glIsEnabled(
                org.lwjgl.opengl.GL11.GL_STENCIL_TEST));
    }

    private static void drawMemberBand(
        EntityRenderDispatcher dispatcher, CameraRenderState cam, Vec3 camPos,
        Entity entity, Portal face
    ) {
        // The stage-3 gate's signature line (design §2.5: the band-drawn probe — a REQUEST
        // line; the outcome lines are per piece in drawPiece).
        SeamCartProbe.event(entity, "BAND drawn face=" + face.getId());
        glTruthLine(entity);
        // THE TIE-BREAK BIAS (beacon-ladder verdict, 2026-08-19): the band's fragments reach
        // the artifact pixels correctly transformed and clipped (both beacons) but LOSE the
        // reversed-Z GEQUAL tie by float rounding against the aperture's restamped quad depth
        // (the same world plane rasterized from different geometry). glDepthRange survives
        // per-draw pipeline application (RendererUsingStencil:447 — applyPipelineState never
        // touches it), so a microscopic forward bias breaks exactly the ties: 1e-5 in window
        // space is orders below any real occluder separation, and the ≤1cm-behind fragments
        // remain losers (their deficit is ~100× the bias at typical view distances).
        //
        // ROUND 27: 1e-5 did NOT close the artifacts — the deficit is angle-scaled geometry,
        // not float ties. The value is now the §4.3 bias LADDER lever (-PseamBandBias, default
        // 1e-5 = unchanged incumbent): diagnostic rungs 1e-3 / 1e-2 bound the deficit in one
        // lap if RenderDoc is unavailable. NEVER shippable above the default.
        GL11.glDepthRange(AperturePassthroughLever.SEAM_BAND_BIAS, 1.0);
        Vec3 o = face.getOriginPos();
        Vec3 n = face.getNormal();
        // Piece 1 clip: keep {d ≥ −ADJ} — plane at o−n·ADJ, normal +n.
        Plane keepFrontPlusBand = new Plane(o.subtract(n.scale(ADJ)), n);
        drawPiece(dispatcher, cam, camPos, entity, Vec3.ZERO, keepFrontPlusBand, "P1");

        // Piece 2: the image's back piece + band, translation-only, aperture-masked.
        if (face.getDestDim() == entity.level().dimension()) {
            Vec3 p0 = entity.position();
            Vec3 delta = face.transformPoint(p0).subtract(p0);
            Vec3 probe = p0.add(1, 0, 0);
            Vec3 delta2 = face.transformPoint(probe).subtract(probe);
            if (delta.distanceToSqr(delta2) < 1.0e-8) {
                if (CrossPortalEntityRenderer.projectionVisibleThroughAperture(entity, face)) {
                    // Image clip: keep {d ≤ +ADJ} at the IMAGE plane — plane at
                    // (o+delta)+n·ADJ, normal −n.
                    Plane keepBackPlusBand =
                        new Plane(o.add(delta).add(n.scale(ADJ)), n.scale(-1));
                    drawPiece(dispatcher, cam, camPos, entity, delta, keepBackPlusBand, "P2");
                }
            }
            else if (!nonTranslationLoggedOnce) {
                nonTranslationLoggedOnce = true;
                SeamCartProbe.rpc("band painter: non-translation seam transform — piece 2 "
                    + "skipped (face " + face.getId() + ")");
            }
        }
        // Restore the default depth range (the bias is scoped to this member's band draws).
        GL11.glDepthRange(0.0, 1.0);
    }

    private static void drawPiece(
        EntityRenderDispatcher dispatcher, CameraRenderState cam, Vec3 camPos,
        Entity entity, Vec3 offset, Plane keep, String pieceName
    ) {
        com.warwa.seamlessportals.render.FrontClipping.Snapshot snapshot;
        if (AperturePassthroughLever.SEAM_BAND_BEACON) {
            // BEACON round 2 (single-variable ladder): the +2Y clip-DISABLED beacon proved the
            // draw path paints. This round ALSO draws a +4Y clip-ENABLED beacon — the real
            // exact-plane snapshot translated to the beacon's offset plane. Upper beacon
            // half-cut = converter correct ⇒ the artifact pixels are depth-rejected ⇒ Tier B
            // escalation. Upper beacon absent = the snapshot converter is the defect.
            Plane liftedKeep = new Plane(keep.pos().add(0, 4, 0), keep.normal());
            var liftedSnap = FrontClipping.captureExactPlane(liftedKeep, cam.viewRotationMatrix);
            if (liftedSnap != null) {
                EntityRenderState upperState =
                    dispatcher.extractEntity(entity, RenderStates.getPartialTick());
                SubmitNodeStorage upperScratch = new SubmitNodeStorage();
                dispatcher.submit(upperState, cam,
                    upperState.x + offset.x - camPos.x,
                    upperState.y + offset.y + 4 - camPos.y,
                    upperState.z + offset.z - camPos.z,
                    new PoseStack(), upperScratch);
                boolean upperDone =
                    PerEntityClipBracket.drawImmediateClipped(upperScratch, liftedSnap);
                SeamCartProbe.event(entity, "BAND-" + pieceName + "-CLIPBEACON"
                    + (upperDone ? " drawn" : " FAILED")
                    + " snap=[" + liftedSnap.x + "," + liftedSnap.y + ","
                    + liftedSnap.z + "," + liftedSnap.w + "," + liftedSnap.enabled + "]");
            }
            // The proven +2Y clip-DISABLED beacon stays as the control.
            offset = offset.add(0, 2, 0);
            snapshot = new com.warwa.seamlessportals.render.FrontClipping.Snapshot(0, 0, 0, 1, false);
        }
        else {
            snapshot = FrontClipping.captureExactPlane(keep, cam.viewRotationMatrix);
        }
        if (snapshot == null) {
            SeamCartProbe.event(entity, "BAND-" + pieceName + " no-snapshot");
            return;
        }
        // TINT (SEAM_BAND_HANDOFF §4.1, diagnostic): P1 (real coords) MAGENTA, P2 (image
        // coords) CYAN — rides the snapshot through drawImmediateClipped's proven bracket.
        snapshot = com.warwa.seamlessportals.render.SeamTint.bandPiece(
            snapshot, "P1".equals(pieceName));
        EntityRenderState state = dispatcher.extractEntity(entity, RenderStates.getPartialTick());
        SubmitNodeStorage scratch = new SubmitNodeStorage();
        dispatcher.submit(state, cam,
            state.x + offset.x - camPos.x,
            state.y + offset.y - camPos.y,
            state.z + offset.z - camPos.z,
            new PoseStack(), scratch);
        boolean completed = PerEntityClipBracket.drawImmediateClipped(scratch, snapshot);
        // OUTCOME probe (verdict Tier A.1 / the assert-the-outcome rule): logged AFTER the
        // draw returns, per piece, with the view-space plane actually fed.
        SeamCartProbe.event(entity, "BAND-" + pieceName
            + (completed ? " drawn" : " FAILED")
            + " snap=[" + snapshot.x + "," + snapshot.y + "," + snapshot.z
            + "," + snapshot.w + "," + snapshot.enabled + "]");
    }
}
