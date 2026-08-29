package com.warwa.seamlessportals.passthrough;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.client.PortalWorldManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * ★ CLIENT-SIDE APPLICATION OF OWNER-HALF OCCUPANCY — {@code FRACTIONAL_DESIGN.md} §2a.0/§3.
 *
 * <h2>Why a packet exists for this and for nothing else in the seam</h2>
 *
 * <p>Every other piece of seam state is a pure function of portal geometry. Both sides already have
 * that geometry as synced entity data, so each recomputes its own index from its own tick signal and
 * no packet is needed — {@code AperturePassthroughInit} deliberately registers the same bind handler
 * on both {@code SERVER_PORTAL_TICK_SIGNAL} and {@code CLIENT_PORTAL_TICK_SIGNAL} for exactly that
 * reason.
 *
 * <p>Occupancy is the exception, because it is not derived from geometry at all: it records where a
 * player's crosshair ray hit when they placed the block, and <b>nothing in the world can be
 * inspected to recover it</b> — both fractions of a split block are the same block.
 *
 * <p>A player's own placement reaches both sides for free, since {@code BlockItem.place} runs on the
 * client for prediction as well as on the server. The CROSSING half does not: {@code SeamMirror} is
 * server-only. Measured live 2026-08-02 — one {@code CROSS} line on the server thread, none on the
 * client, and consequently a whole block visible on both destination sides because the shape hook
 * correctly refuses to guess a half it has no record of.
 *
 * <p>⚠ This covers the LIVE stream only. Occupancy still dies with the {@code Level}, so a relog or
 * a chunk that was never watched shows whole blocks until the {@code SavedData} in §3 lands.
 */
public final class SeamOccupancyClient {

    private SeamOccupancyClient() {}

    /**
     * Apply an authoritative occupancy mask for one cell.
     *
     * <p>The mask REPLACES rather than merges: the server is the authority on how many halves are
     * owned, and merging would make a release (break one of two objects) impossible to express.
     */
    /**
     * ⚠ Occupancy that arrived before its dimension's ClientLevel existed. Flushed on the next
     * apply() for any packet — the window is tiny (a packet for a dim the client has never rendered)
     * and the next placement self-heals it. Static, so it survives level swaps; bounded by the
     * number of seam cells a server can have live, which is human-placement-bounded.
     */
    private static final java.util.Map<ResourceKey<Level>, java.util.concurrent.ConcurrentHashMap<Long, Pending>>
        PENDING = new java.util.concurrent.ConcurrentHashMap<>();

    public static void apply(String dimensionId, long packedPos, byte mask) {
        apply(dimensionId, packedPos, mask, -1, (byte) 0);
    }

    public static void apply(String dimensionId, long packedPos, byte mask,
        int secondaryStateId, byte secondaryHalf) {
        ResourceKey<Level> dim = parseDimensionKey(dimensionId);
        if (dim == null) {
            return;
        }
        BlockPos pos = BlockPos.of(packedPos);
        SeamOccupancy.Secondary secondary = secondaryStateId < 0 ? null
            : new SeamOccupancy.Secondary(
                net.minecraft.world.level.block.Block.stateById(secondaryStateId), secondaryHalf);
        // Older stashes first, so a fresher mask for the same cell wins below.
        flushPending();

        // ★ RESOLVE THE LEVEL THE FLAG-ON WAY. The 2026-08-02 live round #4 found the packet
        // ARRIVING (no unknown-payload warning) and the occupancy still 0 on the client two seconds
        // later: this method resolved the destination through mc.level (the player was in the
        // overworld — skip) and PortalWorldManager (the BLOCK-ERA cache, empty flag-ON), so the byte
        // went nowhere. Flag-ON, per-dimension client levels live in IP's ClientWorldLoader; the
        // portal view the player is looking through IS one of its worlds.
        //
        // ★★ peekWorld, NEVER getOptionalWorld (relog round, client-assert gate red 2026-08-03):
        // getOptionalWorld CREATES the ClientLevel for any dim the server declared. The JOIN burst
        // arrives before mc.level exists, so resolving through it force-created a parallel
        // overworld, applied every record to THAT instance, and reported success — while the level
        // the game actually plays in arrived later with zero records. peekWorld only ever returns
        // a world something else already made real; anything earlier parks in PENDING and the
        // END_CLIENT_TICK flush lands it on mc.level once it exists.
        boolean applied = false;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null && mc.level.dimension().equals(dim)) {
            put(mc.level, pos, mask, secondary);
            applied = true;
        }
        ClientLevel ipWorld = qouteall.imm_ptl.core.ClientWorldLoader.peekWorld(dim);
        if (ipWorld != null && ipWorld != mc.level) {
            put(ipWorld, pos, mask, secondary);
            applied = true;
        }
        ClientLevel cached = PortalWorldManager.getLevel(dim);
        if (cached != null && cached != mc.level && cached != ipWorld) {
            put(cached, pos, mask, secondary);
            applied = true;
        }
        if (!applied) {
            PENDING.computeIfAbsent(dim, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(packedPos, new Pending(mask, secondary));
        }
        if (com.warwa.seamlessportals.passthrough.AperturePassthroughLever.SEAM_FRACTIONAL_PROBE) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAM FRAC] occupancy packet {} @ {} mask={} -> {}",
                dimensionId, pos, mask, applied ? "APPLIED" : "PENDING (no client level yet)");
        }
    }

    /**
     * TEST/PROBE ACCESSOR — is this cell's occupancy known client-side, either applied to a live
     * ClientLevel or parked in {@link #PENDING}? Lets the end-to-end gate assert the WHOLE pipe
     * (place → mirror → packet → apply) rather than stopping at "the server sent it", which is the
     * exact one-step-short failure that cost four live rounds on 2026-08-02.
     */
    public static boolean isRecordedClientSide(ResourceKey<Level> dim, long packedPos) {
        return clientRecordOf(dim, packedPos).mask() != 0;
    }

    /**
     * ★ THE DIMENSION-CORRECT CLIENT VIEW of one cell — what the client knows and WHERE it knows it.
     *
     * <p>Exists because the relog gate's first client assert read {@code mc.level} for overworld
     * cells and went red twice on a working pipe (2026-08-03, churn4/churn5): the player had logged
     * out in the NETHER (leg 4's pearl), so post-relog {@code mc.level} was the nether and the
     * overworld records — correctly applied to the loader's secondary overworld, the instance every
     * portal-view consumer reads — were invisible to the instrument. The resolution order here IS
     * the consumers' order: the played level when the dimension matches, else the loader's world
     * for that dimension, else the PENDING stash (records that land the moment the level exists).
     */
    public static ClientRecord clientRecordOf(ResourceKey<Level> dim, long packedPos) {
        BlockPos pos = BlockPos.of(packedPos);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null && mc.level.dimension().equals(dim)) {
            byte mask = SeamOccupancy.occupancyOf(mc.level, pos);
            if (mask != 0 || SeamOccupancy.secondaryOf(mc.level, pos) != null) {
                return new ClientRecord(mask, SeamOccupancy.secondaryOf(mc.level, pos), "mc.level");
            }
        }
        ClientLevel lvl = qouteall.imm_ptl.core.ClientWorldLoader.peekWorld(dim);
        if (lvl != null) {
            byte mask = SeamOccupancy.occupancyOf(lvl, pos);
            if (mask != 0 || SeamOccupancy.secondaryOf(lvl, pos) != null) {
                return new ClientRecord(mask, SeamOccupancy.secondaryOf(lvl, pos), "secondary-level");
            }
        }
        var pending = PENDING.get(dim);
        Pending p = pending == null ? null : pending.get(packedPos);
        if (p != null) {
            return new ClientRecord(p.mask(), p.secondary(), "pending");
        }
        return new ClientRecord((byte) 0, null, "absent");
    }

    /** One cell as the client sees it: the mask, the second object, and which store answered. */
    public record ClientRecord(byte mask,
        @org.jetbrains.annotations.Nullable SeamOccupancy.Secondary secondary, String source) {}

    /**
     * ★ TICK-DRIVEN FLUSH (the user's live relog round, root-caused via the green server-side
     * relog gate): the JOIN burst arrives BEFORE the joining client's ClientLevel exists, so every
     * entry parks in {@link #PENDING} — and flushing only ran on the NEXT apply(), which after a
     * relog with no further placements never comes. The client then renders every seam cell as its
     * whole blockstate: "side b and dest side a replaced by source side a", and every downstream
     * symptom follows. Registered on END_CLIENT_TICK; the empty-map early-out makes the quiet case
     * one branch.
     */
    public static void flushPendingTick() {
        flushPending();
    }

    private static void flushPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        for (var e : PENDING.entrySet()) {
            ClientLevel lvl = qouteall.imm_ptl.core.ClientWorldLoader.peekWorld(e.getKey());
            if (lvl == null && mc.level != null && mc.level.dimension().equals(e.getKey())) {
                lvl = mc.level;   // early-join window: mc.level exists before the loader registers it
            }
            if (lvl == null) {
                continue;
            }
            for (var cell : e.getValue().entrySet()) {
                put(lvl, BlockPos.of(cell.getKey()), cell.getValue().mask(),
                    cell.getValue().secondary());
            }
            PENDING.remove(e.getKey());
        }
    }

    private static void put(Level level, BlockPos pos, byte mask,
        @org.jetbrains.annotations.Nullable SeamOccupancy.Secondary secondary) {
        if (mask == 0) {
            SeamOccupancy.clear(level, pos);
        } else {
            SeamOccupancy.set(level, pos, mask);
        }
        SeamOccupancy.setSecondary(level, pos, secondary);
    }

    /** A stashed packet for a dimension whose ClientLevel does not exist yet. */
    private record Pending(byte mask, @org.jetbrains.annotations.Nullable
        SeamOccupancy.Secondary secondary) {}

    private static ResourceKey<Level> parseDimensionKey(String dimensionId) {
        try {
            Identifier id = Identifier.parse(dimensionId);
            return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id);
        } catch (Exception e) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAM FRAC] unparseable dimension id in occupancy payload: {}", dimensionId);
            return null;
        }
    }
}
