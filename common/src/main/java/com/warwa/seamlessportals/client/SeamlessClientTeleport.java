package com.warwa.seamlessportals.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.mixin.EntityLevelAccessorMixin;
import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import com.warwa.seamlessportals.render.PortalContextSwitch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * Client-side half of the IP-style client-initiated seamless teleport.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #performCrossing(PortalLink)} — called synchronously from
 *       {@code LocalPlayerMixin.tick} HEAD the instant the client detects
 *       its own crossing of a portal plane. Does the visual swap (promote
 *       cached renderer, re-level player, demote outgoing) and sends
 *       {@link ModPayloads.ClientPortalCrossingPayload} to the server.</li>
 *   <li>{@link #handleServerReconcile} — called when
 *       {@link ModPayloads.ClientboundSeamlessMovePayload} arrives from the
 *       server. Hot path: confirms our client-first swap was correct.
 *       Fallback path: if client never detected the crossing (no cached
 *       renderer for the dest dim yet, or portal link not synced), the
 *       visual swap is deferred to the packet handler and runs now.</li>
 * </ul>
 *
 * <p>Equivalent to what {@code HandleRespawnMixin.seamlessportals$redirectSetLevel}
 * does inside {@code handleRespawn}, but independent of the respawn-packet
 * flow. The old mixin remains in place for non-seamless cross-dim respawns
 * (death, command teleport, etc.) but is no longer exercised for portal
 * crossings once both ends of this pipeline are in place.
 */
public final class SeamlessClientTeleport {

    private SeamlessClientTeleport() {}

    /**
     * Set to {@code true} the frame we perform a client-first visual swap.
     * Cleared when the server reconciliation packet arrives or the player
     * steps out of any portal bounding box. Guards against re-entering the
     * crossing detector on the next client tick while still inside the
     * destination portal.
     */
    public static volatile boolean justTeleportedClient = false;

    /**
     * The local player's position at the END of the previous client tick, used
     * by {@link com.warwa.seamlessportals.mixin.client.LocalPlayerMixin} to build
     * the per-tick movement segment for IP-style PLANE-CROSSING crossing detection
     * (cross only when the segment passes THROUGH a portal plane, not when the
     * player merely overlaps the portal box). Reset to the post-swap position by
     * {@link #doVisualSwap} so the teleport jump itself is never seen as a crossing.
     */
    public static volatile Vec3 lastClientPos = null;

    /**
     * Record of the last client-first crossing's destination, used by
     * {@link #handleServerReconcile} to detect whether the arriving packet
     * matches what we already did.
     */
    private static volatile ResourceKey<Level> lastClientSwapDim = null;

    /**
     * Client-first path. We already know the link (detected locally); perform
     * the visual swap immediately. The server-side teleport will follow when
     * the server receives {@link ModPayloads.ClientPortalCrossingPayload}; its
     * reconciliation packet will confirm position.
     */
    /**
     * Last frame's interpolated CAMERA position, for the per-frame crossing check.
     * Null = re-prime next frame (after a swap, or when the check is suspended).
     */
    private static Vec3 lastCameraPos = null;

    /**
     * Monotonic sequence number of client-first swaps. Sent with each
     * {@code ClientPortalCrossingPayload}; the server echoes it in the reconcile.
     * {@link #handleServerReconcile} IGNORES reconciles whose echoed seq is older than the
     * latest swap — so a rapid re-cross can never be yanked back by a late reconcile from the
     * previous crossing. This sequence-hardening is what made removing the post-swap crossing
     * cooldown safe (full IP parity: IP has no cooldown; its per-frame continuous tracking +
     * combo limit are the only guards).
     */
    private static int swapSeqCounter = 0;

    /**
     * PER-FRAME camera-crossing detection — the fix for the momentary source-dim flash
     * on teleport, proven by the [SEAMLESS XTRACE] traces: the camera interpolates
     * per-frame and crossed the portal plane up to ~45ms BEFORE the 20Hz
     * {@code LocalPlayer.tick} detector ran, so 1-3 frames rendered the SOURCE world
     * from beyond the plane (trace: pd flipped sign at -6.4ms, dim still overworld,
     * swap at +21.3ms). IP avoids this by checking teleportation every FRAME with the
     * camera position; this is that check.
     *
     * <p>Called from {@code GameRenderer.renderLevel} HEAD (via
     * {@code StencilPortalRenderer.prepareDestinationRender}), BEFORE the frame's camera
     * is set up — so we compute THIS frame's camera x/z ourselves (first-person camera =
     * player position lerped by the partial tick; only x/z matter for the vertical portal
     * planes, so third-person/bob offsets are irrelevant to the plane test). If the
     * segment last-frame-camera → this-frame-camera crosses a linked portal plane, the
     * visual swap runs NOW — the crossing frame renders the DEST dim. No source frame
     * past the plane, no flash.
     *
     * <p>The 20Hz {@code LocalPlayerMixin} tick detector stays as a fallback (e.g. first
     * frame after priming); double-firing is prevented by both detectors resetting their
     * movement segments on swap (a crossing consumes the segment that produced it).
     */
    public static void checkCameraCrossingPerFrame() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            com.warwa.seamlessportals.render.CrossingTracer.frameDetState = 0;
            return;
        }
        if (!com.warwa.seamlessportals.config.SeamlessPortalsConfig.get().isSeamlessTeleportation()) {
            lastCameraPos = null;
            com.warwa.seamlessportals.render.CrossingTracer.frameDetState = 0;
            return;
        }
        // Compute THIS frame's camera position (first-person camera = player pos lerped by the
        // partial tick + eye height) and keep the segment TRACKING alive in every state below —
        // IP tracks lastPlayerEyePos continuously (it even TRANSFORMS it through the portal on
        // teleport, ClientTeleportationManager:306); an earlier version nulled it during the
        // post-swap cooldown, leaving a priming gap right when rapid re-crossings happen.
        double pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        double cx = net.minecraft.util.Mth.lerp(pt, player.xo, player.getX());
        double cy = net.minecraft.util.Mth.lerp(pt, player.yo, player.getY()) + player.getEyeHeight();
        double cz = net.minecraft.util.Mth.lerp(pt, player.zo, player.getZ());
        Vec3 current = new Vec3(cx, cy, cz);
        Vec3 last = lastCameraPos;
        lastCameraPos = current;

        // NO post-swap cooldown (full IP parity, 2026-07-04): the stale-chunk decode race is
        // handled non-fatally by ChunkPacketGuardMixin (1342 drops / 0 disconnects in the rapid-
        // teleport test), and late reconciles from superseded crossings are ignored via swapSeq
        // (see handleServerReconcile). detState 1 ("cool") is retired.
        if (last == null) {
            com.warwa.seamlessportals.render.CrossingTracer.frameDetState = 2; // priming
            return;
        }
        // IP-style sanity: a >40-block frame jump is not a walk (dim change, /tp) — re-prime.
        if (last.distanceToSqr(current) > 1600.0) {
            com.warwa.seamlessportals.render.CrossingTracer.frameDetState = 2;
            return;
        }

        java.util.Optional<PortalLink> linkOpt =
            com.warwa.seamlessportals.entity.EntityPortalCollision.findPortalCrossing(player, last, current);
        if (linkOpt.isEmpty()) {
            com.warwa.seamlessportals.render.CrossingTracer.frameDetState = 3; // checked, no cross
            return;
        }

        com.warwa.seamlessportals.render.CrossingTracer.frameDetState = 4; // FIRED
        com.warwa.seamlessportals.render.CrossingTracer.event(String.format(
            "DETECT-FRAME camera crossed portal=%s cam=(%.2f,%.2f,%.2f)->(%.2f,%.2f,%.2f)",
            linkOpt.get().getSource().getOrigin().toShortString(),
            last.x, last.y, last.z, current.x, current.y, current.z));
        com.warwa.seamlessportals.render.CrossingTracer.armDump();
        performCrossing(linkOpt.get());
        lastCameraPos = null;
    }

    public static boolean performCrossing(PortalLink link) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return false;

        Vec3 srcPos = player.position();
        float destYaw = link.transformYaw(player.getYRot());
        // Exit clearance (same as the server's authoritative landing) on the side the player
        // FACES, so the provisional swap also places them in front of the dest portal facing
        // away — no instant re-cross.
        Vec3 destPos = link.transformTeleportPosition(srcPos, destYaw);
        Vec3 destVel = link.transformVelocityFacing(player.getDeltaMovement(), destYaw);
        float destPitch = player.getXRot();

        com.warwa.seamlessportals.render.CrossingTracer.event(String.format(
            "PERFORM src=(%.2f,%.2f,%.2f) dest=(%.2f,%.2f,%.2f) yaw=%.1f",
            srcPos.x, srcPos.y, srcPos.z, destPos.x, destPos.y, destPos.z, destYaw));

        boolean swapped = doVisualSwap(link.getDestination().getDimension(),
            destPos, destVel, destYaw, destPitch);
        if (!swapped) return false;

        // New client-first swap — bump the sequence so any still-in-flight reconcile from a
        // PREVIOUS crossing is recognized as stale and ignored (see handleServerReconcile).
        int seq = ++swapSeqCounter;

        // Tell the server to perform its authoritative teleport. Using the
        // source portal id so the server can validate + look up the same
        // PortalLink on its side; the seq comes back in the reconcile.
        PlatformHelper.getInstance().sendToServer(new ModPayloads.ClientPortalCrossingPayload(
            link.getSource().getPortalId().toString(), seq));

        justTeleportedClient = true;
        lastClientSwapDim = link.getDestination().getDimension();

        // DIAG: count client-initiated crossings via the PerfTimers count column (off-thread).
        // In a "stand still after teleport" test, a rising clientCrossing count = residual auto-
        // oscillation (the exit clearance wasn't enough); a flat count = the few-seconds-blank is
        // a passive render/streaming bug, not the teleport re-firing.
        com.warwa.seamlessportals.render.PerfTimers.add(
            "clientCrossing/" + link.getDestination().getDimension().identifier().getPath(), 0L);

        SeamlessPortalsConstants.rlog(
            "[SEAMLESS CLIENT-CROSSING] client-first swap → {} at ({},{},{})",
            link.getDestination().getDimension().identifier(),
            String.format("%.2f", destPos.x),
            String.format("%.2f", destPos.y),
            String.format("%.2f", destPos.z));
        return true;
    }

    /**
     * Fallback / reconcile path. Called when
     * {@link ModPayloads.ClientboundSeamlessMovePayload} arrives.
     *
     * <p>If we already swapped client-first to this dim: reconcile position
     * against server's authoritative value (position delta should be very
     * small — same transform on both sides, same pre-cross position within
     * one tick). We treat tiny delta as no-op.
     *
     * <p>If we did NOT already swap (client missed detection — portal link
     * wasn't synced, player moved through in a weird way, etc.): do the
     * visual swap now. This reintroduces the old round-trip flash for that
     * one crossing but is still correct.
     */
    public static void handleServerReconcile(ModPayloads.ClientboundSeamlessMovePayload payload) {
        com.warwa.seamlessportals.render.CrossingTracer.event(String.format(
            "RECONCILE seq=%d dim=%s pos=(%.2f,%.2f,%.2f)",
            payload.swapSeq(), payload.destDimension(), payload.x(), payload.y(), payload.z()));
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;

        // Sequence-hardening (replaces the post-swap cooldown): a reconcile echoing a seq older
        // than our latest client-first swap belongs to a SUPERSEDED crossing — the player has
        // already crossed again. Acting on it (position snap or the forced-swap fallback below)
        // would yank the player back across the portal. Ignore it; the reconcile for the latest
        // crossing is right behind it (server processes our crossing packets in order).
        // swapSeq == -1 marks a genuine server-initiated teleport — always honored.
        if (payload.swapSeq() >= 0 && payload.swapSeq() < swapSeqCounter) {
            com.warwa.seamlessportals.render.CrossingTracer.event(String.format(
                "RECONCILE STALE ignored (seq=%d < current=%d)", payload.swapSeq(), swapSeqCounter));
            return;
        }

        ResourceKey<Level> payloadDim = parseDim(payload.destDimension());
        if (payloadDim == null) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS RECONCILE] Unknown dimension in payload: {}", payload.destDimension());
            return;
        }

        ResourceKey<Level> currentDim = mc.level.dimension();
        Vec3 destPos = new Vec3(payload.x(), payload.y(), payload.z());
        Vec3 destVel = new Vec3(payload.vx(), payload.vy(), payload.vz());

        if (currentDim.equals(payloadDim)) {
            // Hot path: we already swapped (either client-first via
            // performCrossing, or server-first via vanilla handleRespawn).
            //
            // ACK-ONLY unless genuinely desynced. The echo's position is the
            // server's transform of ITS view of the player at crossing receipt —
            // STALE by one round-trip. A walking player covers 0.1-0.5 blocks in
            // that window (XTRACE 2026-07-05: every echo landed 28-84ms after
            // SWAP carrying the original crossing pos), so the old 0.22-block
            // threshold snapped the player BACKWARD on almost every crossing —
            // an instant view pop the user perceives as the hand lurching
            // off-center and re-settling. IP has no echo snap at all (client-
            // authoritative crossing; the server adopts the client position via
            // normal movement packets — ServerTeleportationManager validates
            // rather than corrects). Keep only a large-desync safety net at 4
            // blocks, far above RTT walking drift but small enough to repair a
            // genuine divergence (vanilla-style rubber-band, rare by design).
            double dx = player.getX() - destPos.x;
            double dy = player.getY() - destPos.y;
            double dz = player.getZ() - destPos.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > 16.0) {
                player.setPos(destPos.x, destPos.y, destPos.z);
                player.xo = destPos.x; player.yo = destPos.y; player.zo = destPos.z;
                player.xOld = destPos.x; player.yOld = destPos.y; player.zOld = destPos.z;
                com.warwa.seamlessportals.render.CrossingTracer.event(String.format(
                    "RECONCILE DESYNC snap applied d=%.2f blocks", Math.sqrt(d2)));
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS RECONCILE] Desync corrected: d={} blocks", Math.sqrt(d2));
            } else {
                com.warwa.seamlessportals.render.CrossingTracer.event(String.format(
                    "RECONCILE ACK-only (drift d=%.3f, no snap)", Math.sqrt(d2)));
            }
            // Block the client-first detector from re-firing while the
            // player is still inside the dest portal's bounding box. This
            // matters for the server-first path (vanilla handleRespawn ran,
            // client-first never did, so performCrossing never set the flag).
            justTeleportedClient = true;
            lastClientSwapDim = payloadDim;
            return;
        }

        // Fallback: server-first detection. Client never did visual swap.
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS RECONCILE] Fallback — deferred visual swap to {} at ({},{},{})",
            payloadDim.identifier(),
            String.format("%.2f", destPos.x),
            String.format("%.2f", destPos.y),
            String.format("%.2f", destPos.z));

        // Look up the link by portal id so we can use the same swap logic.
        // If the link isn't available client-side (e.g. portal data never
        // synced to this client for this dim), we still need to do the swap.
        // The portalId gives us the source portal; we can look up the link
        // directly.
        UUID portalId;
        try {
            portalId = UUID.fromString(payload.portalId());
        } catch (IllegalArgumentException e) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS RECONCILE] Invalid portalId in payload: {}", payload.portalId());
            return;
        }
        Optional<PortalLink> linkOpt = PortalManager.getClientInstance().getLinkForPortal(portalId);
        // Link may be unavailable on the client — still do the swap using
        // the payload's destDim. Demote/promote only needs the dim key.
        doVisualSwap(payloadDim, destPos, destVel, payload.yaw(), payload.pitch());
        justTeleportedClient = true;
        lastClientSwapDim = payloadDim;
    }

    /**
     * Core swap: promote cached renderer+level for the dest dim, re-level
     * the local player, move the player to dest position, demote the
     * outgoing primary.
     *
     * @return {@code true} if the swap happened; {@code false} if there is
     *     no cached renderer for the dest dim (nothing to promote).
     */
    private static boolean doVisualSwap(ResourceKey<Level> destDim,
            Vec3 destPos, Vec3 destVel, float destYaw, float destPitch) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return false;

        if (mc.level.dimension().equals(destDim)) {
            // Already on dest dim — nothing to do visually. Just snap player.
            // Shift the lagged rotation fields by the delta too (same treatment as
            // the full swap below): a server-initiated rotation correction must not
            // make the hand chase the change. Deltas are ~0 for the common
            // reconcile-echo case, so this is a no-op there.
            float sameDimYawDelta = destYaw - player.getYRot();
            float sameDimPitchDelta = destPitch - player.getXRot();
            player.setPos(destPos.x, destPos.y, destPos.z);
            player.setDeltaMovement(destVel);
            player.setYRot(destYaw);
            player.setXRot(destPitch);
            player.yRotO += sameDimYawDelta;
            player.xRotO += sameDimPitchDelta;
            player.yBob += sameDimYawDelta;
            player.yBobO += sameDimYawDelta;
            player.xBob += sameDimPitchDelta;
            player.xBobO += sameDimPitchDelta;
            return true;
        }

        com.warwa.seamlessportals.render.CrossingTracer.event(
            "SWAP begin " + mc.level.dimension().identifier().getPath()
            + " -> " + destDim.identifier().getPath());
        LevelRenderState sharedState = mc.gameRenderer.gameRenderState().levelRenderState;
        PortalWorldManager.Promotion promotion = PortalWorldManager.promoteToMain(destDim, sharedState);
        if (promotion == null) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS CLIENT-CROSSING] No cached renderer for {} — cannot perform seamless swap",
                destDim.identifier());
            return false;
        }

        int swapId = ++diagSwapSeq;
        LevelRenderer oldRenderer = mc.levelRenderer;
        ClientLevel oldLevel = mc.level;
        ResourceKey<Level> oldDim = oldLevel.dimension();

        // DIAG: visibleSections state BEFORE swap from both renderers.
        int oldVSBefore = oldRenderer != null
            ? ((LevelRendererAccessorMixin)(Object) oldRenderer).seamlessportals$getVisibleSections().size() : -1;
        int newVSBefore = ((LevelRendererAccessorMixin)(Object) promotion.renderer())
            .seamlessportals$getVisibleSections().size();
        SeamlessPortalsConstants.rlog(
            "[SEAMLESS DIAG #{}] PRE-swap: oldRenderer({}).visibleSections={}, promoted({}).visibleSections={}",
            swapId, oldDim.identifier(), oldVSBefore, destDim.identifier(), newVSBefore);

        // 1. Install promoted renderer + level as new primary.
        ((MinecraftAccessorMixin) mc).seamlessportals$setLevelRenderer(promotion.renderer());
        mc.level = promotion.level();
        com.warwa.seamlessportals.render.CrossingTracer.event("SWAP level+renderer installed");

        // 2. Seed the promoted renderer's ViewArea center to the destination
        // section so the first vanilla repositionCamera is a no-op and doesn't
        // wipe ViewArea meshes (matches HandleRespawnMixin rationale:
        // viewarea_reposition_mesh_loss).
        //
        // 26.2: the {@code lastCameraSectionX/Y/Z} int fields were removed from
        // LevelRenderer; the camera-section gate now lives inside
        // {@code ViewArea.repositionCamera(SectionPos)} (returns true iff the
        // grid actually moved). We seed the center directly via that public
        // method. If the ViewArea is already centered on this section
        // (common — the cached renderer for destDim was last positioned here),
        // repositionCenter returns false and nothing is relocated/wiped.
        //
        // SEAMLESS-26.2-TODO: unlike the old pure field-write seed, calling
        // repositionCamera here WILL relocate slots (and reset their meshes) if
        // the cached ViewArea center differs from destPos's section. Verify at
        // runtime that promotion leaves the ViewArea centered at destPos's
        // section (no first-frame mesh wipe). If a flash reappears, the seed may
        // need to pre-set the RotatingSectionStorage center without the reset.
        LevelRendererAccessorMixin rAcc = (LevelRendererAccessorMixin) (Object) promotion.renderer();
        net.minecraft.client.renderer.ViewArea rViewArea = rAcc.seamlessportals$getViewArea();
        if (rViewArea != null) {
            rViewArea.repositionCamera(SectionPos.of(destPos));
        }

        // 2b. OPTION 1 — eliminate the first-main-extract createRegion storm.
        // The single mc.levelExtractor (now driving the promoted dim) runs extract() next
        // frame, which UNCONDITIONALLY repositions its SectionUpdateTracker to the camera
        // section (LevelExtractor.extract:101). That tracker's center is the through-portal
        // VIRTUAL camera (≠ this landing section), so the reposition relocates every grid
        // slot and SectionDirtyState.setSectionNode re-dirties them → cache.createRegion()
        // fires for EVERY visible section (~1ms each = the 150-250ms stall) even though the
        // ViewArea PRESERVED their compiled meshes (the seed above is moved=false). Fix:
        //   (1) re-center the tracker onto the landing section NOW, so the first extract's
        //       repositionCamera is a true no-op (RotatingSectionStorage.repositionCenter
        //       early-returns when the center is unchanged); then
        //   (2) clear the dirty bit ONLY on sections whose mesh is ALREADY compiled (the
        //       redundant re-mesh), leaving genuinely-UNCOMPILED sections dirty so they still
        //       mesh — so no terrain is blanked. The compiled sections render from the meshes
        //       the ViewArea kept (moved=false), so clearing their dirty is safe.
        if (rViewArea != null) {
            net.minecraft.client.SectionUpdateTracker tracker =
                ((com.warwa.seamlessportals.mixin.client.LevelExtractorAccessor) (Object) mc.levelExtractor)
                    .seamlessportals$getSectionUpdateTracker();
            if (tracker != null) {
                tracker.repositionCamera(net.minecraft.core.SectionPos.of(destPos));
                for (net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection sec
                        : rViewArea.sections) {
                    if (sec == null) continue;
                    net.minecraft.client.SectionUpdateTracker.SectionDirtyState ds =
                        tracker.getDirtyState(sec.getSectionNode());
                    if (ds == null || !ds.isDirty()) continue;
                    if (sec.sectionMesh.get()
                            != net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED) {
                        ds.setNotDirty();   // already compiled → re-mesh would be redundant
                    }
                    // else: genuinely uncompiled → leave dirty so it meshes (no blank terrain)
                }
            }
        }

        // 3. Demote outgoing primary.
        if (oldRenderer != null && oldRenderer != promotion.renderer()) {
            PortalWorldManager.demoteFromMain(oldDim, oldRenderer, oldLevel);
        }

        // 4. Replay minimal mc.setLevel side-effects (match HandleRespawnMixin).
        mc.particleEngine.setLevel(promotion.level());
        mc.gameRenderer.setLevel(promotion.level());

        // 4b. Swap mc.gameRenderer.lightmap to the destination dim's cached
        // Lightmap. Without this, the first few frames post-swap render with
        // the OLD dim's lightmap texture (nether warm-red applied to the
        // overworld, or vice-versa) — visible in the screen-recording as
        // the 1-2 frames of "wrong fog/sky color" immediately following
        // the 2 blank terrain frames. Mirrors IP's per-dim Lightmap
        // (Phase X1 Commit B already established the swap mechanism).
        try {
            com.warwa.seamlessportals.render.DimensionRenderHelper destHelper =
                com.warwa.seamlessportals.render.DimensionRenderHelper.getOrCreate(destDim);
            ((com.warwa.seamlessportals.mixin.client.GameRendererAccessorMixin) mc.gameRenderer)
                .seamlessportals$setLightmap(destHelper.getLightmap());
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS CLIENT-CROSSING] Failed to swap lightmap to {}: {}",
                destDim.identifier(), e.getMessage());
        }

        // 5. Transfer the LocalPlayer between ClientLevel entity-lists and
        // re-point its level field to the destination.
        try {
            oldLevel.removeEntity(player.getId(), Entity.RemovalReason.CHANGED_DIMENSION);
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.debug(
                "[SEAMLESS CLIENT-CROSSING] oldLevel.removeEntity failed (ok): {}", e.getMessage());
        }
        ((EntityLevelAccessorMixin) player).seamlessportals$invokeSetLevel(promotion.level());
        ((EntityLevelAccessorMixin) player).seamlessportals$invokeUnsetRemoved();
        try {
            promotion.level().addEntity(player);
        } catch (Exception e) {
            // addEntity may throw if id collision; benign for us since we
            // re-point the existing instance. Log and continue.
            SeamlessPortalsConstants.LOGGER.debug(
                "[SEAMLESS CLIENT-CROSSING] newLevel.addEntity warning: {}", e.getMessage());
        }

        // 6. Move the player to the authoritative dest position/rotation.
        player.setPos(destPos.x, destPos.y, destPos.z);
        player.xo = destPos.x; player.yo = destPos.y; player.zo = destPos.z;
        player.xOld = destPos.x; player.yOld = destPos.y; player.zOld = destPos.z;
        player.setDeltaMovement(destVel);
        // Rotation: shift EVERY lagged rotation field by the same delta so the
        // first-person hand and camera stay rotation-continuous across the swap.
        //
        // transformYaw rotates yaw ±90° when the two portals' axes differ. The
        // hand-sway fields (LocalPlayer.yBob/xBob + *O, public; ticked with a 0.5
        // lerp toward rotation, LocalPlayer:701-704) feed ItemInHandRenderer:340-343
        // which rotates the hand by (viewRot - bob) * 0.1 — left unadjusted they
        // CHASE the yaw delta over the next ~6 ticks, so the hand swung off-center
        // and recentered on every axis-mismatched crossing. IP's parity treatment
        // (TransformationManager.managePlayerRotationAndChangeGravity:195-203) sets
        // yRotO/xRotO/yBob/xBob/yBobO/xBobO to the final rotation; shifting them BY
        // THE DELTA is identical when the player isn't turning (all fields equal
        // rotation at rest) and additionally preserves the sway/lerp offsets of an
        // in-flight pan, so a moving hand keeps moving instead of snapping to
        // center. Differences between the fields are preserved, so lerp direction
        // and sway magnitude are unchanged regardless of yaw wrapping.
        float yawDelta = destYaw - player.getYRot();
        float pitchDelta = destPitch - player.getXRot();
        player.setYRot(destYaw);
        player.setXRot(destPitch);
        player.yRotO += yawDelta;
        player.xRotO += pitchDelta;
        player.yBob += yawDelta;
        player.yBobO += yawDelta;
        player.xBob += pitchDelta;
        player.xBobO += pitchDelta;
        com.warwa.seamlessportals.render.CrossingTracer.event(String.format(
            "SWAP setPos done pos=(%.2f,%.2f,%.2f) prevs synced", destPos.x, destPos.y, destPos.z));

        // Warm-up REMOVED after diagnostic run (2026-04-17 22:32): it forces
        // LevelRenderer.update → applyFrustum → clearVisibleSections BEFORE
        // SectionOcclusionGraph has had a chance to propagate for the new
        // main-camera direction. On first-ever teleports to a dim (SOG fresh
        // from portal-view state only), the clear + re-apply produces
        // visibleSections=1 (just the player's own section) and stays stuck
        // there for the 2-3 frames the flash covers. Skipping the warm-up
        // leaves the stale portal-view visibleSections populated (thousands of
        // sections, mostly wrong direction) but at least some render, and
        // SOG propagates naturally over the next frames.
        //
        // IP's ClientWorldLoader.withSwitchedWorld similarly does not pre-warm.

        // THE FLASH FIX (2026-04-24):
        //
        // MC's {@code EnvironmentAttributeProbe} (on the main Camera) lerps
        // fog/sky color over 1 tick (~50ms = 1–2 frames at typical FPS)
        // when the camera transitions between environments. On a cross-dim
        // teleport the probe's {@code lastValue} holds the previous dim's
        // fog color (e.g. OW blue) while the next tick's {@code newValue}
        // computes to the destination dim's fog color (e.g. nether red) —
        // for those 1–2 frames, {@code get()} returns a lerp like
        // {@code (0.46, 0.44, 0.53)} which produces the visible flash in
        // the sky/fog.
        //
        // Fix: reset AND immediately re-tick the probe with the destination
        // level + destination position. This does two things:
        //   1. reset() clears the stale valueProbes (which were lerping
        //      from the old dim's values).
        //   2. tick(destLevel, destPos) re-populates probe.level +
        //      probe.position, so the next get() constructs a fresh
        //      ValueProbe with lastValue = newValue = dest fog. No lerp.
        //
        // Just calling reset() was NOT enough: with probe.level left null,
        // any get() call before the next Camera.tick() returns
        // {@code attribute.defaultValue()} (not the dest dim's fog) — a
        // different, default-colored flash. The explicit re-tick here
        // ensures the first render frame post-swap sees correct dest fog.
        try {
            net.minecraft.client.Camera mainCamera = mc.gameRenderer.mainCamera();
            if (mainCamera != null) {
                mainCamera.attributeProbe().reset();
                // Populate with destination values so no lerp and no
                // default-fog frame. destPos is the just-assigned player
                // position (post setPos above).
                mainCamera.attributeProbe().tick(promotion.level(), destPos);
            }
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS CLIENT-CROSSING] attributeProbe reset failed: {}",
                e.getMessage());
        }

        // Reset portal-view cache for the new dim + request portal data
        // (mirrors HandleRespawnMixin.afterRespawn).
        PortalContextSwitch.resetChunkFedState(destDim);
        String dimId = destDim.identifier().toString();
        PlatformHelper.getInstance().sendToServer(new ModPayloads.RequestPortalDataPayload(dimId));

        // Swap timestamp — diagnostics only since the cooldown removal (the decode race is
        // handled by ChunkPacketGuardMixin; superseded reconciles by the swapSeq guard).
        lastSwapMonotonicNanos = System.nanoTime();

        // Reset the plane-crossing segment origin to the post-swap position so the
        // teleport jump (old-dim pos -> new-dim pos) is never evaluated as a
        // movement that crosses a portal plane (which would spuriously re-fire).
        Minecraft mcNow = Minecraft.getInstance();
        if (mcNow.player != null) {
            lastClientPos = mcNow.player.position();
        }
        // Same reset for the per-frame CAMERA detector: the swap teleports the camera
        // across the world — that jump must never be evaluated as a crossing segment.
        lastCameraPos = null;

        // Sprint continuity: something in the first post-swap aiStep cancels a held
        // sprint (see tickSprintKeeper). Arm the keeper so it is re-asserted.
        if (player.isSprinting()) {
            armSprintKeeper();
        }

        com.warwa.seamlessportals.render.CrossingTracer.event("SWAP done");
        return true;
    }

    /**
     * Monotonic timestamp of the last client-first swap. Read by
     * LocalPlayerMixin to throttle the portal-containment detector so
     * rapid repeat crossings can't issue a second swap before the
     * previous respawn packet + in-flight chunk queue has drained.
     */
    public static volatile long lastSwapMonotonicNanos = 0L;

    /**
     * Within the short window after a visual swap where the server's OLD-dim
     * teardown packets are still in flight. Used by
     * {@code ClientPacketListenerForgetGuardMixin}: vanilla drops every old-dim
     * chunk via ClientboundForgetLevelChunkPacket at the teleport, but the
     * client is already in the NEW dim — those forgets apply to the active
     * level, and old-dim chunk coordinates can ALIAS new-dim positions
     * (OW x/8 ≈ nether x), wrongly unloading freshly-promoted chunks. With the
     * vanilla re-send now suppressed for client-held chunks, such a wrong drop
     * would no longer be masked by an immediate re-send — so the forgets are
     * absorbed during this window. A legitimately-forgotten chunk self-heals:
     * vanilla re-marks it pending when its view re-enters.
     */
    public static boolean isInPostSwapWindow() {
        return System.nanoTime() - lastSwapMonotonicNanos < 3_000_000_000L;
    }

    // ===== Sprint keeper: crossing must not cancel a held sprint =====
    //
    // XTRACE (2026-07-05 sprint test): sprint speed (vH 0.153) survives the swap
    // frame, then decays to walk speed starting the FIRST tick after the swap —
    // something in that tick's aiStep calls setSprinting(false) (the exact code
    // path is logged by LivingEntitySprintCancelDiagMixin). Rather than guess the
    // condition, re-assert: if the player was sprinting at the swap and is still
    // holding forward (not sneaking, enough food), re-enable sprint at tick end
    // for up to 10 ticks. Runs AFTER aiStep's cancel in the same tick, so the
    // speed modifier is restored before the next frame — at most a one-tick
    // ~0.01-block speed dip. A genuine cancel (released W, sneak, hunger) stops
    // the re-assertion naturally via the conditions.

    private static int sprintKeeperTicks = 0;

    /** Called at the end of doVisualSwap when the player was sprinting going in. */
    private static void armSprintKeeper() {
        // 40 ticks (2s): the observed cancels hit up to ~16 ticks post-swap (they
        // fire 1-2 ticks after LANDING when the crossing includes a fall — the
        // player sprint-jumps out of the portal and drops to the ground in front).
        sprintKeeperTicks = 40;
    }

    /**
     * Called from LocalPlayerMixin at tick TAIL (after aiStep's potential cancel).
     *
     * <p>Stays armed for the whole window — a v1 bug disarmed it the first tick
     * sprint was still ON ("restored — done"), which also matched "not cancelled
     * YET", so the real cancel at tick ~13 (post-landing) found no keeper.
     */
    public static void tickSprintKeeper(LocalPlayer player) {
        if (sprintKeeperTicks <= 0) return;
        sprintKeeperTicks--;
        if (player.isSprinting()) return; // still sprinting — stay armed, nothing to do
        boolean stillWantsSprint = player.input != null
            && player.input.hasForwardImpulse()
            && !player.isShiftKeyDown()
            && player.getFoodData().getFoodLevel() > 6;
        if (stillWantsSprint) {
            player.setSprinting(true);
            com.warwa.seamlessportals.render.CrossingTracer.event(
                "SPRINT re-asserted post-swap (keeper, " + sprintKeeperTicks + " ticks left)");
        } else {
            sprintKeeperTicks = 0; // genuine stop condition — respect it
        }
    }

    // POST_SWAP_COOLDOWN_NANOS: REMOVED 2026-07-04 (full IP parity — IP has no cooldown).
    // History: 500ms → 150ms → gone. Each of its jobs has a dedicated replacement:
    //   * stale cross-dim chunk packets → ChunkPacketGuardMixin (drop, not disconnect;
    //     1342 drops / 0 disconnects in the rapid-teleport stress test);
    //   * late reconciles of superseded crossings → swapSeq stale-guard in handleServerReconcile;
    //   * post-swap teleport-jump segments → lastClientPos/lastCameraPos reset in doVisualSwap.

    /**
     * Diagnostic counter decremented by the LevelRenderer.update injection
     * in {@code LevelRendererDiagMixin}. Set to a small integer (e.g. 12)
     * at the end of {@link #doVisualSwap} to have the next N update() calls
     * log visibleSections.size(). Helps pinpoint when visibleSections gets
     * populated post-swap (the 2-3 blank frames we're investigating).
     */
    public static volatile int diagLogUpdatesRemaining = 0;

    /**
     * Increments every doVisualSwap so diagnostic log lines include a
     * session-unique id (useful when there are multiple swaps per test run).
     */
    public static volatile int diagSwapSeq = 0;

    private static ResourceKey<Level> parseDim(String id) {
        return switch (id) {
            case "minecraft:overworld" -> Level.OVERWORLD;
            case "minecraft:the_nether" -> Level.NETHER;
            case "minecraft:the_end" -> Level.END;
            default -> null;
        };
    }
}
