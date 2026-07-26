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
     * <p><b>Kept per SIDE, and that is load-bearing.</b> A portal exists on both the client and the
     * server with the SAME UUID, so a single UUID-keyed map lets whichever side ticks first record a
     * fingerprint that suppresses the other side's bind entirely — the client index would silently
     * stay empty while looking perfectly healthy. Two maps, one per side.
     */
    private static final Map<UUID, Long> SERVER_FINGERPRINTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CLIENT_FINGERPRINTS = new ConcurrentHashMap<>();

    private static Map<UUID, Long> fingerprintsFor(Portal portal) {
        return portal.level() != null && portal.level().isClientSide()
            ? CLIENT_FINGERPRINTS : SERVER_FINGERPRINTS;
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

        LOGGER.info("[RS-SEAM-REGISTRY] aperture passthrough initialised (disabled={})",
            AperturePassthroughLever.DISABLED);
    }

    private static void onPortalTick(Portal portal) {
        if (AperturePassthroughLever.DISABLED) {
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
        }
        catch (Throwable t) {
            LOGGER.warn("[RS-SEAM-REGISTRY] unbind failed for portal {}", portal.getUUID(), t);
        }
        finally {
            fingerprintsFor(portal).remove(portal.getUUID());
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

    /** Test/probe accounting: how many portals currently hold a binding fingerprint, per side. */
    public static int trackedPortalCount(boolean clientSide) {
        return (clientSide ? CLIENT_FINGERPRINTS : SERVER_FINGERPRINTS).size();
    }
}
