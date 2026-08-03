package com.warwa.seamlessportals.entity;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.chunk_loading.ChunkVisibility;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTickets;
import qouteall.imm_ptl.core.platform_specific.IPConfig;

/**
 * §2g despawn-suppression decision + the [DESPAWN-PROBE] formatter. SERVER thread only
 * (checkDespawn and the removal funnel both run there; the render-thread log4j rule does not
 * apply — but the probe is event-rate and default-OFF regardless).
 *
 * <p>Cost honesty (verify-fold FIX-3): the wrap also executes every tick for every far
 * non-held non-persistent mob whose removeWhenFarAway vanilla itself calls — chiefly far
 * passives (Animal returns false and re-enters every tick). Each pays the arithmetic guard +
 * lever/config reads + up to 9 map probes on a hoisted manager ≈ 100-300 ns; a pathological
 * 500-passive farm ≈ 125 µs/tick ≈ 0.25% of the tick budget. Zero allocation, O(1).
 */
public final class PortalTicketDespawnSuppressor {

    private static boolean loggedLive = false;

    private PortalTicketDespawnSuppressor() {}

    public static boolean shouldSuppress(Mob mob, double distSqr) {
        // 1. Vanilla-near guard FIRST — arithmetic only, no lookups. Any player inside the
        //    category despawn distance (MONSTER=128) => every leg byte-vanilla. At the band
        //    call site this passes only when the player is >128 (the 32..128 band stays
        //    vanilla).
        int d = mob.getType().getCategory().getDespawnDistance();
        if (distSqr <= (double) d * (double) d) {
            return false;
        }

        // 2. Lever + config, live-read (A/B-OFF pays nothing further).
        if (!IPGlobal.isTicketDespawnSuppressActive()) {
            return false;
        }
        if (!(mob.level() instanceof ServerLevel serverLevel)) {
            return false; // client-side weave inert
        }
        // enableImmPtlChunkLoading=false leaves stale MARKED-but-ticketless map entries
        // (addTicket's early-return) — this gate closes that wrong-suppression state.
        if (!IPConfig.getConfig().enableImmPtlChunkLoading) {
            return false;
        }

        // 3. O(1) PORTAL-FED membership on the mod's own bookkeeping, 3x3-dilated
        //    (verify-fold FIX-1: raw membership would match the player's own view square).
        ChunkPos c = mob.chunkPosition();
        if (!ImmPtlChunkTickets.isChunkPortalFedNear(serverLevel, c.x(), c.z())) {
            return false;
        }

        IPGlobal.ticketDespawnSuppressCount++;
        if (!loggedLive) {
            loggedLive = true; // once-only liveness (server thread, single writer)
            SeamlessPortalsConstants.LOGGER.info(
                "[DESPAWN-SUPPRESS] LIVE: first suppression {} in {} at {} distSqr={}"
                    + " (A/B lever -Dseamlessportals.disableTicketDespawnSuppress)",
                mob.getType(), serverLevel.dimension().identifier(),
                mob.blockPosition(), (long) distSqr);
        }
        return true;
    }

    /**
     * Probe A formatter — called only with -Dseamlessportals.despawnProbe armed, event-rate
     * only (per removal, never per tick). portalDist is SKIPPED for UNLOADED_TO_CHUNK lines
     * (verify-fold FIX-4: a purge wave unloads hundreds of mobs in one tick and the nearby-
     * portal scan is a 33x33-chunk entity walk each — heldNear/portalHeldNear are the causal
     * fields for that family anyway).
     */
    public static void probeLogRemoval(Mob mob, Entity.RemovalReason reason) {
        Level level = mob.level();
        Player nearest = level.getNearestPlayer(mob, -1.0);
        double playerDist = nearest == null ? -1.0 : Math.sqrt(nearest.distanceToSqr(mob));
        boolean heldCenter = false;
        boolean portalFedNear = false;
        double portalDist = -1.0;
        if (level instanceof ServerLevel serverLevel) {
            ChunkPos c = mob.chunkPosition();
            // heldCenter = RAW membership (incl. the player's own view square — plain-play far
            // discards legitimately show heldCenter=true portalFedNear=false = vanilla churn);
            // portalFedNear = the CAUSAL discriminator the fix keys on.
            heldCenter = ImmPtlChunkTickets.isChunkHeld(serverLevel, c.x(), c.z());
            portalFedNear = ImmPtlChunkTickets.isChunkPortalFedNear(serverLevel, c.x(), c.z());
            if (reason == Entity.RemovalReason.DISCARDED) {
                portalDist = ChunkVisibility.getNearbyPortals(
                        serverLevel, mob.position(), p -> true, 16, 16).stream()
                    .mapToDouble(p -> p.getDistanceToNearestPointInPortal(mob.position()))
                    .min().orElse(-1.0); // -1 = no portal within ~16 chunks (one-way far
                                         // sides may show -1; portalFedNear is the causal field)
            }
        }
        SeamlessPortalsConstants.LOGGER.warn(
            "[DESPAWN-PROBE] {} {} id={} dim={} pos={} cat={} persistReq={} customPersist={}"
                + " playerDist={} heldCenter={} portalFedNear={} portalDist={}"
                + " supActive={} supCount={}",
            reason, mob.getType(), mob.getId(), level.dimension().identifier(), mob.position(),
            mob.getType().getCategory(), mob.isPersistenceRequired(),
            mob.requiresCustomPersistence(), playerDist, heldCenter, portalFedNear,
            portalDist, IPGlobal.isTicketDespawnSuppressActive(),
            IPGlobal.ticketDespawnSuppressCount);
    }
}
