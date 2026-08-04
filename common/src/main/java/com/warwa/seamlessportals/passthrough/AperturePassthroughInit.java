package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Wires the seam registry to IP's portal lifecycle signals. Called once at mod init.
 *
 * <p><b>Seeding is signal-driven, not scan-driven</b>, because IP already emits exactly the events we
 * need: {@code SERVER_PORTAL_TICK_SIGNAL} / {@code CLIENT_PORTAL_TICK_SIGNAL} per portal per tick
 * ({@code Portal.java:227-229}, invoked at {@code :993} / {@code :1001}) and
 * {@code PORTAL_DISPOSE_SIGNAL} on removal ({@code :232}, invoked at {@code :501}). Binding off the
 * tick signal means a portal is indexed no matter how it came into existence — ignition, command,
 * wand, chunk load, entity sync — with no separate discovery path to keep in step.
 *
 * <p><b>Why a geometry fingerprint.</b> The tick signal fires every tick for every portal, and
 * re-enumerating columns each time would be pure waste. Rebinding happens only when a portal's
 * geometry actually changes, detected by a cheap fingerprint. This also makes moved or resized
 * portals correct for free: the fingerprint changes, the old bindings are dropped and new ones
 * written, with no explicit "portal moved" event to subscribe to.
 */
public final class AperturePassthroughInit {

    private AperturePassthroughInit() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Last-seen geometry fingerprint per portal, so an unchanged portal costs one map lookup.
     *
     * <p><b>★ Kept PER LEVEL (on the {@code SeamIndexHolder} duck), and that is the far-pair
     * relog fix (2026-08-04).</b> The first build kept two static per-SIDE maps — which also
     * solved a real problem (a shared UUID-keyed map let whichever side ticked first suppress the
     * other side's bind), but statics outlive the level whose index they guard: portal UUIDs
     * persist in the save and geometry doesn't change across a relog, so after a reopen the
     * handler saw "unchanged" and never rebound into the brand-new EMPTY index. The server
     * escaped by accident (world close fires {@code PORTAL_DISPOSE_SIGNAL} per portal, clearing
     * its entries); the client's relog teardown fires no per-portal dispose, and the reproduction
     * gate measured {@code portalsNearCell=2 totalIndexedCells=0} — both portals synced and
     * ticking, index empty forever. Per-level maps solve both problems at once: each side's
     * levels are distinct instances, and a new level starts with an empty cache by construction.
     */
    private static Map<UUID, Long> fingerprintsFor(Portal portal) {
        return ((SeamIndexHolder) portal.level()).seamlessportals$bindFingerprints();
    }

    private static boolean initialised = false;

    public static void init() {
        if (initialised) {
            return;
        }
        initialised = true;

        Portal.SERVER_PORTAL_TICK_SIGNAL.register(AperturePassthroughInit::onPortalTick);
        // The CLIENT signal matters too: (b)/(c)/(d) query "what is across the seam" for rendering
        // and prediction, and a client-side index that is never populated would answer "nothing"
        // rather than "unknown". Observed in the step-2 run before this was added — every
        // client-side dispose logged cells=0 because nothing had ever bound on that side.
        Portal.CLIENT_PORTAL_TICK_SIGNAL.register(AperturePassthroughInit::onPortalTick);
        Portal.PORTAL_DISPOSE_SIGNAL.register(AperturePassthroughInit::onPortalDispose);

        // SAME-DIMENSION PORTAL TERRAIN FRESHNESS (com.warwa.seamlessportals.render.SameDimRemesh).
        //
        // A SEPARATE registration on the client tick signal, deliberately NOT folded into
        // onPortalTick above: that handler returns early on an unchanged geometry fingerprint, which
        // for a stable portal is every tick after the first. SameDimRemesh needs the portal EVERY
        // tick — its destination-region list is rebuilt per tick so a removed portal stops
        // qualifying immediately.
        Portal.CLIENT_PORTAL_TICK_SIGNAL.register(
            com.warwa.seamlessportals.render.SameDimRemesh::onClientPortalTick);
        // POST_CLIENT_TICK fires on the main thread after the world tick, never mid-extract or
        // mid-render — the same ordering guarantee SecondaryWorldRenderCore's own per-tick pump
        // relies on, and the reason the drain can append to the main LevelRenderState safely.
        qouteall.imm_ptl.core.IPGlobal.POST_CLIENT_TICK_EVENT.register(
            () -> com.warwa.seamlessportals.render.SameDimRemesh.onEndClientTick(
                net.minecraft.client.Minecraft.getInstance()));
        // SEAM CLIP recompile flush — same ordering guarantee, SEPARATE accounting from
        // SameDimRemesh by design (SEAM_CLIP_DESIGN.md §2: sharing its COMPILED set would have
        // masked the RS-DELIVERY arm-3 verdict).
        qouteall.imm_ptl.core.IPGlobal.POST_CLIENT_TICK_EVENT.register(
            () -> com.warwa.seamlessportals.render.SeamClipRenderer.onEndClientTick(
                net.minecraft.client.Minecraft.getInstance()));
        // (e) DEFECT-B ride sampler. Same ordering guarantee. Costs one boolean test per tick
        // outside a crossing window (SeamRideProbe.windowOpen), and the window is opened only by
        // an actual client-side dimension change and closed after SeamRideProbe.WINDOW_TICKS.
        qouteall.imm_ptl.core.IPGlobal.POST_CLIENT_TICK_EVENT.register(() -> {
            if (!SeamRideProbe.windowOpen()) {
                return;
            }
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.world.entity.Entity player = mc == null ? null : mc.player;
            net.minecraft.world.entity.Entity vehicle = player == null ? null : player.getVehicle();
            int watched = SeamRideProbe.watchedVehicleId();
            SeamRideProbe.onEndClientTick(
                player, vehicle,
                mc == null || mc.level == null
                    ? "null" : mc.level.dimension().identifier().toString(),
                mc != null && mc.level != null && watched >= 0
                    && mc.level.getEntity(watched) != null);
        });

        // Journal drain, once per server tick per level. Opportunistic: entries whose chunk is still
        // absent are kept rather than force-loaded, because an entry only exists BECAUSE loading was
        // not possible at the time.
        // END_SERVER_TICK, iterating levels — the event ServerTaskList.java:11 already proves
        // available in this module (there is no END_WORLD_TICK in this Fabric API version).
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (AperturePassthroughLever.DISABLED) {
                return;
            }
            for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
                SeamJournal.drain(level);
                // Fractional occupancy hydrate — once per level per load (the flag lives on the
                // SavedData instance, fresh per world start). Per-tick placement is deliberate:
                // it also covers dynamically-created levels, which a SERVER_STARTED hook misses.
                SeamOccupancySavedData.hydrateOnce(level);
            }
            // (b) rail continuity: reset the per-tick cross-write budget and serve cold-far-chunk
            // retries that have warmed up.
            SeamRailContinuity.onServerTickEnd(server);
            // (c) signal continuity: reset the per-tick dispatch budget and flush queued
            // cross-seam re-evaluations + cold-far retries that have warmed up.
            SeamSignalContinuity.onServerTickEnd(server);
            // Retire and PRINT any delivery trace that has been open long enough for the client to
            // have answered. Retiring on a timer is what makes a stage that never ran report
            // NOT-REACHED rather than staying silent — see SeamDeliveryProbe's coverage note.
            SeamDeliveryProbe.onServerTickEnd();
            // (d) cart-crossing instrument: one SAMPLE line per watched cart per tick. ⚠ Shares
            // this event with ServerTaskList's teleport execution — registration order decides
            // which sees the teleport tick first; the probe's EVT lines carry the precise instant.
            SeamCartProbe.onServerTickEnd(server);
            // (d) cart continuity: evict stale mid-crossing marks (the ridden-window grace).
            SeamCartContinuity.onServerTickEnd(server);
        });

        LOGGER.info("[RS-SEAM-REGISTRY] aperture passthrough initialised (disabled={})",
            AperturePassthroughLever.DISABLED);
    }

    private static void onPortalTick(Portal portal) {
        if (AperturePassthroughLever.DISABLED || portal.level() == null) {
            return;
        }
        try {
            Map<UUID, Long> fingerprints = fingerprintsFor(portal);
            long fingerprint = fingerprintOf(portal);
            Long previous = fingerprints.get(portal.getUUID());
            if (previous != null && previous == fingerprint) {
                return;   // unchanged — the overwhelmingly common case
            }
            if (previous != null) {
                SeamRegistry.unbind(portal);   // geometry moved: drop the stale cells first
            }
            SeamRegistry.bind(portal);
            fingerprints.put(portal.getUUID(), fingerprint);
            // SEAM CLIP: a client-side (re)bind changes which cells are mesh-excluded — queue the
            // covering sections for a direct recompile (flushed at POST_CLIENT_TICK below).
            if (portal.level().isClientSide()) {
                com.warwa.seamlessportals.render.SeamClipRenderer.onClientPortalIndexChanged(portal);
            }
            // Carry across anything ALREADY sitting in the aperture. Mirroring is change-driven, so a
            // block that predates the portal is never written and therefore never mirrored — the
            // user's "relight with a rail on the portal floor and half the rail gets cut off". Must
            // run AFTER bind, since it consults the registry it just populated.
            if (!portal.level().isClientSide()) {
                SeamMirror.reconcileApertureOnBind(portal);
                // (b): a portal lit over an EXISTING track changes no block, so no rail resolution
                // fires — re-run vanilla shape resolution at the bound cells and their cross
                // counterparts. Must run after bind (it consults the registry) and after
                // reconciliation (a carried-across mirror half is part of what the shapes read).
                SeamRailContinuity.reseedOnBind(portal);
            }
        }
        catch (Throwable t) {
            // A registry failure must never take down the portal tick — the portal itself is still
            // perfectly functional without a seam index; only the passthrough feature degrades.
            LOGGER.warn("[RS-SEAM-REGISTRY] bind failed for portal {} (passthrough degraded, portal"
                + " unaffected)", portal.getUUID(), t);
        }
    }

    private static void onPortalDispose(Portal portal) {
        try {
            SeamRegistry.unbind(portal);
            // SEAM CLIP: cells just stopped being seam cells — their blocks must come back into
            // the section meshes. Dispose fires with geometry intact, so enumeration still works.
            if (portal.level() != null && portal.level().isClientSide()) {
                com.warwa.seamlessportals.render.SeamClipRenderer.onClientPortalIndexChanged(portal);
            }
        }
        catch (Throwable t) {
            LOGGER.warn("[RS-SEAM-REGISTRY] unbind failed for portal {}", portal.getUUID(), t);
        }
        finally {
            if (portal.level() != null) {
                fingerprintsFor(portal).remove(portal.getUUID());
            }
        }
    }

    /**
     * A cheap value-equality fingerprint of everything {@link SeamMap} reads. Deliberately covers
     * destination as well as source geometry: a portal whose destination is retargeted keeps its own
     * position but every mirror cell moves, and a fingerprint over source geometry alone would miss
     * that entirely and leave the index pointing at the old destination.
     */
    private static long fingerprintOf(Portal portal) {
        return Objects.hash(
            portal.getOriginPos(),
            portal.getDestPos(),
            portal.getDestDim(),
            portal.getAxisW(),
            portal.getAxisH(),
            portal.getWidth(),
            portal.getHeight(),
            portal.getScaling(),
            portal.getRotation()
        );
    }

    /** Test/probe accounting: how many portals currently hold a binding fingerprint in a level. */
    public static int trackedPortalCount(net.minecraft.world.level.Level level) {
        return ((SeamIndexHolder) level).seamlessportals$bindFingerprints().size();
    }
}
