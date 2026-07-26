package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * THE DELIVERY PROBE — where does a mirrored write stop being visible?
 *
 * <p><b>Why this exists.</b> Same-dimension man-made portals do not show mirrored writes live, while
 * obsidian portals and man-made CROSS-dimension portals do. Three fixes were written from three
 * different theories of the cause and all three were wrong; the last made it worse. Every one of them
 * reasoned from the symptom to a mechanism and shipped a change. <b>Nothing has ever measured where
 * the update is actually lost.</b> That is what this does, and it deliberately ships before any
 * fourth fix.
 *
 * <h2>The chain being measured, and its four independent drop points</h2>
 * Verified against {@code REF} this session, not assumed:
 * <ol>
 *   <li><b>WRITE</b> — {@code SeamMirror.applyToDestination} calls {@code ServerLevel.setBlock}.
 *       Recorded with the destination chunk's {@code FullChunkStatus} and the state read back
 *       immediately afterwards, so "the write landed" is evidence rather than an inference.</li>
 *   <li><b>NOTIFY</b> — {@code Level.markAndNotifyBlock} (REF {@code Level.java:238-248}) reaches
 *       {@code sendBlockUpdated} only if the state read back {@code ==} the state asked for, flag 2
 *       is set, <i>and</i> {@code chunk.getFullStatus().isOrAfter(BLOCK_TICKING)}.
 *       <b>DROP POINT 1.</b></li>
 *   <li><b>HOLDER</b> — {@code ServerChunkCache.blockChanged} (REF {@code :458-465}) looks up
 *       {@code getVisibleChunkIfPresent}; a missing holder silently drops the change.
 *       <b>DROP POINT 2.</b> Then {@code ChunkHolder.blockChanged} (REF {@code :123-127}) returns
 *       immediately when {@code getTickingChunk() == null}. <b>DROP POINT 3.</b></li>
 *   <li><b>BROADCAST</b> — {@code ChunkHolder.broadcastChanges} (REF {@code :174-216}) takes its
 *       recipient list from {@code playerProvider.getPlayers}, which IP redirects to
 *       {@code ImmPtlChunkTracking.getPlayersViewingChunk}
 *       ({@code MixinChunkHolder.redirectGetPlayers}). An empty list sends nothing.
 *       <b>DROP POINT 4.</b></li>
 *   <li><b>CLIENT</b> — {@code ClientPacketListener.handleBlockUpdate} /
 *       {@code handleChunkBlocksUpdate}, recorded with the {@code ClientLevel} the update was
 *       actually applied to, because "the packet arrived" and "it reached the level the portal view
 *       renders from" are different claims.</li>
 * </ol>
 *
 * <h2>Coverage is asserted, not assumed</h2>
 * Five instruments gave false readings in this engagement and the pattern was always the same: a
 * stage that never ran read as a stage that ran and answered. So a trace is <b>retired on a timer and
 * printed in full</b>, naming every stage that did NOT fire as {@code NOT-REACHED}, rather than each
 * stage logging only when it happens to fire. A run in which nothing was traced prints
 * {@code ZERO WRITES TRACED} instead of staying silent.
 *
 * <h2>Single-JVM assumption, stated</h2>
 * The client stage matches by position through a static set. That works because a dev client runs the
 * integrated server in the same JVM, which is the only environment this probe is for. On a dedicated
 * server the CLIENT stage will simply always read {@code NOT-REACHED}; the four server-side stages
 * remain valid. This is a diagnostic, never a shipped mechanism.
 */
public final class SeamDeliveryProbe {

    private SeamDeliveryProbe() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long a trace stays open before it is printed. Long enough for the client to answer. */
    private static final int RETIRE_AFTER_TICKS = 40;

    /** Bound on live traces, so a mass placement cannot turn the probe into the problem. */
    private static final int MAX_LIVE_TRACES = 64;

    private static final Map<Long, Trace> LIVE = new ConcurrentHashMap<>();
    private static final AtomicLong TRACED = new AtomicLong();
    private static final AtomicLong DROPPED_OVER_CAP = new AtomicLong();
    private static volatile long tickCounter = 0L;

    /** Key for a trace. Dimension is folded in so two dimensions cannot alias one cell. */
    private static long key(ResourceKey<Level> dim, BlockPos pos) {
        return pos.asLong() * 31L + dim.identifier().hashCode();
    }

    private static final class Trace {
        final String dim;
        final BlockPos pos;
        final long openedAtTick;
        final long serial;

        // stage 1 — the write itself
        String write = "NOT-REACHED";
        // stage 2 — ServerLevel.sendBlockUpdated
        String notify = "NOT-REACHED";
        // stage 3a — ServerChunkCache.blockChanged found a holder
        String holderLookup = "NOT-REACHED";
        // stage 3b — ChunkHolder.blockChanged accepted it
        String holderAccept = "NOT-REACHED";
        // stage 4 — ChunkHolder.broadcastChanges recipients
        String broadcast = "NOT-REACHED";
        // stage 5 — the client
        String client = "NOT-REACHED";
        // stage 6 — the remesh request, the only stage downstream of the client having the data
        String remesh = "NOT-REACHED";

        Trace(String dim, BlockPos pos, long tick, long serial) {
            this.dim = dim;
            this.pos = pos;
            this.openedAtTick = tick;
            this.serial = serial;
        }
    }

    // =============================================================================================
    // STAGE 1 — the mirrored write
    // =============================================================================================

    /**
     * Open a trace <b>BEFORE</b> the write. Returns the serial, or 0 when not tracing.
     *
     * <p><b>The ordering is the whole point and it is not a detail.</b> Stages 2, 3a and 3b all run
     * INSIDE {@code Level.setBlock} (REF {@code Level.java:217-248} — {@code sendBlockUpdated} is
     * called from the body of {@code setBlock} itself). A trace opened after the call returns has
     * missed all three, and reports them {@code NOT-REACHED} whether or not they ran. The first
     * build of this probe did exactly that and produced an impossible reading — every stage before
     * the broadcast "missing" while the client had the block. Instrument ordering is a measurement
     * error like any other, and this one manufactures precisely the conclusion the engagement is
     * trying to avoid drawing.
     */
    public static long beginWrite(
        Level destLevel, BlockPos pos, String wanted, String fullChunkStatus,
        boolean sameLevel, String origin
    ) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return 0L;
        }
        if (LIVE.size() >= MAX_LIVE_TRACES) {
            DROPPED_OVER_CAP.incrementAndGet();
            return 0L;
        }
        long serial = TRACED.incrementAndGet();
        Trace t = new Trace(destLevel.dimension().identifier().toString(), pos.immutable(),
            tickCounter, serial);
        t.write = "IN PROGRESS wanted=" + wanted
            + " destChunkFullStatus=" + fullChunkStatus
            + " sameLevel=" + sameLevel
            + " origin=" + origin;
        LIVE.put(key(destLevel.dimension(), pos), t);
        WATCHED_POSITIONS.add(pos.asLong());
        return serial;
    }

    /** Close out stage 1 with what the write actually did. Paired with {@link #beginWrite}. */
    public static void endWrite(
        Level destLevel, BlockPos pos, boolean setBlockReturned, String wanted, String readBack,
        String fullChunkStatus, boolean sameLevel, String origin
    ) {
        Trace t = find(destLevel, pos);
        if (t == null) {
            return;
        }
        t.write = "setBlock=" + setBlockReturned
            + " wanted=" + wanted
            + " readBack=" + readBack
            + " destChunkFullStatus=" + fullChunkStatus
            + " sameLevel=" + sameLevel
            + " origin=" + origin;
        LOGGER.info("[RS-DELIVERY] #{} 1-WRITE {} in {} — {}", t.serial, pos, t.dim, t.write);
    }

    // =============================================================================================
    // STAGES 2-4 — the server-side delivery chain
    // =============================================================================================

    public static void noteNotify(Level level, BlockPos pos, int updateFlags) {
        Trace t = find(level, pos);
        if (t != null) {
            t.notify = "sendBlockUpdated REACHED (flags=" + updateFlags + ")";
        }
    }

    public static void noteChunkCacheCalled(Level level, BlockPos pos) {
        Trace t = find(level, pos);
        if (t != null) {
            t.holderLookup = "ServerChunkCache.blockChanged REACHED";
        }
    }

    public static void noteHolderAccept(Level level, BlockPos pos, boolean tickingChunkPresent) {
        Trace t = find(level, pos);
        if (t != null) {
            t.holderAccept = tickingChunkPresent
                ? "ChunkHolder.blockChanged accepted (getTickingChunk non-null)"
                : "★ getTickingChunk() == null — change dropped here (REF ChunkHolder:124-127)";
        }
    }

    /**
     * Positions with a live trace inside this chunk, so the per-chunk broadcast hook — which runs
     * for every chunk every tick — can bail on one cheap test.
     */
    public static List<BlockPos> tracedPositionsInChunk(Level level, int chunkX, int chunkZ) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE || LIVE.isEmpty()) {
            return List.of();
        }
        String dim = level.dimension().identifier().toString();
        List<BlockPos> out = null;
        for (Trace t : LIVE.values()) {
            if (t.dim.equals(dim) && (t.pos.getX() >> 4) == chunkX && (t.pos.getZ() >> 4) == chunkZ) {
                if (out == null) out = new ArrayList<>(2);
                out.add(t.pos);
            }
        }
        return out == null ? List.of() : out;
    }

    /**
     * Recorded PER POSITION, not per chunk.
     *
     * <p>The first build stamped every trace in the chunk, which made an unrelated block change
     * elsewhere in the same chunk read as "this write was broadcast". A chunk-granular answer to a
     * cell-granular question is a false positive by construction, and the caller now checks the
     * exact cell against {@code changedBlocksPerSection} — the very set vanilla is about to encode.
     *
     * @param queued     whether THIS cell is in the chunk's pending change set
     * @param recipients how many players {@code getPlayers} returned. Zero is the interesting
     *                   answer and is why it is recorded even when nothing is sent — vanilla skips
     *                   {@code broadcast} entirely on an empty list, so hooking the send itself
     *                   would leave exactly the failing case invisible.
     */
    public static void noteBroadcast(
        Level level, BlockPos pos, boolean queued, int recipients, String detail
    ) {
        Trace t = find(level, pos);
        if (t == null) {
            return;
        }
        if (!queued) {
            t.broadcast = "★ this cell is NOT in the chunk's pending change set — nothing about it"
                + " will be encoded (the chunk broadcast that ran was for other cells) " + detail;
            return;
        }
        t.broadcast = (recipients == 0
            ? "★ 0 RECIPIENTS — ImmPtlChunkTracking lists nobody viewing this chunk"
            : recipients + " recipient(s)") + " " + detail;
    }

    // =============================================================================================
    // STAGE 5 — the client
    // =============================================================================================

    /**
     * Positions with a live trace, for the client handler's cheap test. Deliberately position-only:
     * the client's job is to say WHICH level it applied the update to, so it must not be told which
     * level to expect.
     */
    private static final java.util.Set<Long> WATCHED_POSITIONS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean isWatchedPosition(BlockPos pos) {
        return AperturePassthroughLever.SEAM_DELIVERY_PROBE && WATCHED_POSITIONS.contains(pos.asLong());
    }

    /**
     * Called from the client packet handler. Matched by position only — see the class note.
     *
     * <p>The received STATE is recorded, not merely the fact of receipt: a packet carrying the
     * cell's old state is an arrival, not a delivery, and the two are indistinguishable without it.
     */
    public static void noteClientReceived(
        BlockPos pos, String appliedToDim, String packetKind, String state
    ) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return;
        }
        boolean matched = false;
        for (Trace t : LIVE.values()) {
            if (t.pos.equals(pos)) {
                t.client = packetKind + " state=" + state + " applied to ClientLevel " + appliedToDim;
                matched = true;
            }
        }
        if (!matched) {
            // A client receipt with no open trace is itself evidence — it means the packet arrived
            // late, or for a write this probe never saw.
            LOGGER.info("[RS-DELIVERY] client received {} for {} into {} — NO OPEN TRACE",
                packetKind, pos, appliedToDim);
        }
    }

    // =============================================================================================
    // STAGE 6 — the remesh request
    // =============================================================================================

    /**
     * Whether any live trace sits in this section. {@code setBlockDirty} fans out over a 3×3×3 block
     * neighbourhood (REF {@code LevelExtractor.java:427-435}), so the tracker is hit several times
     * per change and this must stay a cheap test.
     */
    public static boolean isWatchedSection(int sectionX, int sectionY, int sectionZ) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE || LIVE.isEmpty()) {
            return false;
        }
        for (Trace t : LIVE.values()) {
            if ((t.pos.getX() >> 4) == sectionX
                && (t.pos.getY() >> 4) == sectionY
                && (t.pos.getZ() >> 4) == sectionZ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Record the remesh request for the section a traced cell lives in.
     *
     * <p>An ACCEPT overwrites a previous DROP and not the other way round: {@code setBlockDirty}
     * fans out over neighbouring sections and the tracker is called repeatedly, so one accepted
     * request for the cell's own section is the meaningful answer. Recording last-write-wins would
     * let a neighbouring section's drop mask it.
     */
    public static void noteRemeshRequest(
        int sectionX, int sectionY, int sectionZ, boolean accepted, String detail
    ) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return;
        }
        for (Trace t : LIVE.values()) {
            if ((t.pos.getX() >> 4) != sectionX
                || (t.pos.getY() >> 4) != sectionY
                || (t.pos.getZ() >> 4) != sectionZ) {
                continue;
            }
            if (accepted) {
                t.remesh = "ACCEPTED — the section will be rebuilt " + detail;
            }
            else if (t.remesh.startsWith("NOT-REACHED")) {
                t.remesh = "★ DROPPED — section is OUTSIDE the tracker's rotating window, so the"
                    + " client holds the new block and never rebuilds its mesh " + detail;
            }
        }
    }

    // =============================================================================================
    // RETIREMENT — this is where coverage is asserted
    // =============================================================================================

    /** Called once per server tick from {@code AperturePassthroughInit}'s END_SERVER_TICK handler. */
    public static void onServerTickEnd() {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return;
        }
        tickCounter++;
        if (LIVE.isEmpty()) {
            return;
        }
        List<Trace> due = new ArrayList<>();
        for (Map.Entry<Long, Trace> e : LIVE.entrySet()) {
            if (tickCounter - e.getValue().openedAtTick >= RETIRE_AFTER_TICKS) {
                due.add(e.getValue());
                LIVE.remove(e.getKey());
            }
        }
        for (Trace t : due) {
            WATCHED_POSITIONS.remove(t.pos.asLong());
            report(t);
        }
    }

    private static void report(Trace t) {
        String verdict = verdictOf(t);
        LOGGER.info("[RS-DELIVERY] ===== trace #{} {} in {} — {} =====\n"
                + "    1 WRITE     : {}\n"
                + "    2 NOTIFY    : {}\n"
                + "    3a HOLDER   : {}\n"
                + "    3b ACCEPT   : {}\n"
                + "    4 BROADCAST : {}\n"
                + "    5 CLIENT    : {}\n"
                + "    6 REMESH    : {}",
            t.serial, t.pos, t.dim, verdict,
            t.write, t.notify, t.holderLookup, t.holderAccept, t.broadcast, t.client, t.remesh);
    }

    /**
     * The outcome, plus EVERY stage that did not fire.
     *
     * <p>Deliberately not a first-miss chain verdict. The chain is not strictly linear —
     * {@code SeamMirror.forceClientSync} enters at stage 3a on purpose, skipping stage 2 — so
     * "first stage missing" would name a stage that is legitimately absent and stop looking. It
     * reports where things stand and offers no cause: reading a cause out of a stage list is what
     * produced three wrong fixes.
     */
    private static String verdictOf(Trace t) {
        StringBuilder missing = new StringBuilder();
        if (t.write.startsWith("NOT-REACHED") || t.write.startsWith("IN PROGRESS")) missing.append(" 1");
        if (t.notify.startsWith("NOT-REACHED"))       missing.append(" 2");
        if (t.holderLookup.startsWith("NOT-REACHED")) missing.append(" 3a");
        if (t.holderAccept.startsWith("NOT-REACHED")) missing.append(" 3b");
        if (t.holderAccept.startsWith("★"))           missing.append(" 3b(not-ticking)");
        if (t.broadcast.startsWith("NOT-REACHED"))    missing.append(" 4");
        if (t.broadcast.startsWith("★"))              missing.append(" 4(no-recipients)");
        boolean clientOk = !t.client.startsWith("NOT-REACHED");
        if (!clientOk) missing.append(" 5");
        if (t.remesh.startsWith("NOT-REACHED")) missing.append(" 6");
        if (t.remesh.startsWith("★"))           missing.append(" 6(out-of-window)");
        String head;
        if (!clientOk) {
            head = "★ NEVER REACHED THE CLIENT";
        }
        else if (t.remesh.startsWith("ACCEPTED")) {
            head = "DELIVERED AND QUEUED FOR REMESH";
        }
        else {
            // The distinction the first five stages cannot make, and the one the user's report is
            // actually about: correct data, stale picture.
            head = "★ DATA DELIVERED BUT NO REMESH — the client holds the block and will not redraw it";
        }
        return missing.isEmpty()
            ? head + " (every stage fired)"
            : head + " — stages not reached:" + missing;
    }

    private static Trace find(Level level, BlockPos pos) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE || LIVE.isEmpty()) {
            return null;
        }
        return LIVE.get(key(level.dimension(), pos));
    }

    /**
     * Summary for the end of a run or a gametest leg. Reports ZERO explicitly — a probe that traced
     * nothing must say so rather than leaving an empty log to be read as "everything was fine".
     */
    public static String counters() {
        long traced = TRACED.get();
        return traced == 0
            ? "ZERO WRITES TRACED — the probe never fired; any conclusion drawn from this run is unfounded"
            : "traced=" + traced + " live=" + LIVE.size() + " droppedOverCap=" + DROPPED_OVER_CAP.get();
    }
}
