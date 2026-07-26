package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * THE SEAM JOURNAL — durable pending mirror writes, per destination level.
 *
 * <h2>Honest status: this defends a state with no known reachable path</h2>
 * The journal exists so a mirror write survives a destination that cannot be written right now.
 * Analysis after live round #3 could not find a way to reach that state:
 * <ul>
 *   <li>Writing INTO a cold destination already works — {@link SeamMirror} force-loads the
 *       destination chunk in both the veto and the driver.</li>
 *   <li>A change ORIGINATING on a cold side cannot happen. A player must be within reach to break a
 *       block, which puts them at that portal, which keeps its chunk ticking and its bindings live.
 *       And a chunk that is not ticking has no pistons firing, no fluid flowing and nothing falling.</li>
 * </ul>
 * It is built anyway, at the user's direction, as insurance: the cost is one small per-level saved
 * data, and the failure it protects against — half a seam surviving alone, permanently, with the
 * other half gone — is silent, persistent and would be very hard to attribute after the fact.
 *
 * <p><b>What it deliberately does NOT do:</b> it is not a second source of truth. Provenance stays on
 * {@link SeamIndexHolder}; the journal only holds writes that have not landed yet, and an entry dies
 * the moment it is applied. If it ever disagrees with the world, the world wins.
 */
public class SeamJournal extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A write that still has to reach this level. {@code air} true = a clear rather than a place. */
    public record Pending(BlockPos pos, boolean air) {
        public static final Codec<Pending> CODEC = RecordCodecBuilder.create(
            i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Pending::pos),
                Codec.BOOL.fieldOf("air").forGetter(Pending::air)
            ).apply(i, Pending::new)
        );
    }

    public static final Codec<SeamJournal> CODEC = RecordCodecBuilder.create(
        i -> i.group(
            Pending.CODEC.listOf().fieldOf("pending").forGetter(j -> j.pending)
        ).apply(i, SeamJournal::new)
    );

    public static final SavedDataType<SeamJournal> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("seamlessportals", "seam_journal"),
        SeamJournal::new, CODEC, DataFixTypes.LEVEL
    );

    /**
     * Bound so a pathological loop cannot grow the save file without limit. If this is ever hit,
     * something is enqueueing far faster than it drains and the WARN is the signal to investigate
     * rather than to raise the cap.
     */
    private static final int MAX_PENDING = 4096;

    private final List<Pending> pending;

    public SeamJournal() {
        this.pending = new ArrayList<>();
    }

    private SeamJournal(List<Pending> pending) {
        this.pending = new ArrayList<>(pending);
    }

    public static SeamJournal get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Record a write that could not be applied to {@code level} now. */
    public static void enqueue(ServerLevel level, BlockPos pos, boolean air) {
        SeamJournal journal = get(level);
        if (journal.pending.size() >= MAX_PENDING) {
            LOGGER.warn("[RS-SEAM-JOURNAL] {} is at the {}-entry cap — DROPPING a pending {} at {}."
                    + " Entries are being enqueued faster than they drain; investigate rather than"
                    + " raising the cap.",
                level.dimension().identifier(), MAX_PENDING, air ? "clear" : "write", pos);
            return;
        }
        journal.pending.add(new Pending(pos.immutable(), air));
        journal.setDirty();
        if (AperturePassthroughLever.SEAM_LEDGER_PROBE) {
            LOGGER.info("[RS-SEAM-JOURNAL] enqueued {} at {} in {} (pending={})",
                air ? "clear" : "write", pos, level.dimension().identifier(), journal.pending.size());
        }
    }

    /**
     * Apply every pending entry whose chunk is now available. Called per server tick per level.
     *
     * <p>Entries whose chunk is still absent are KEPT, not force-loaded: the whole point of an entry
     * being here is that loading was not possible or not wanted at the time. Draining is opportunistic.
     */
    public static void drain(ServerLevel level) {
        if (AperturePassthroughLever.DISABLED) {
            return;
        }
        SeamJournal journal = level.getDataStorage().get(TYPE);
        if (journal == null || journal.pending.isEmpty()) {
            return;
        }
        List<Pending> remaining = new ArrayList<>();
        int applied = 0;
        for (Pending p : journal.pending) {
            if (!level.hasChunkAt(p.pos())) {
                remaining.add(p);
                continue;
            }
            try {
                BlockState target = p.air() ? Blocks.AIR.defaultBlockState() : null;
                if (target == null) {
                    // A deferred PLACE has no state to restore — the source is authoritative and will
                    // re-mirror on its next change. Only clears are meaningful to replay, because a
                    // stale block left behind is the failure this journal exists to prevent.
                    continue;
                }
                if (!level.getBlockState(p.pos()).isAir()) {
                    level.setBlockAndUpdate(p.pos(), target);
                }
                applied++;
            }
            catch (Throwable t) {
                LOGGER.warn("[RS-SEAM-JOURNAL] failed to apply pending entry at {} in {}",
                    p.pos(), level.dimension().identifier(), t);
            }
        }
        if (applied > 0 || remaining.size() != journal.pending.size()) {
            journal.pending.clear();
            journal.pending.addAll(remaining);
            journal.setDirty();
            if (AperturePassthroughLever.SEAM_LEDGER_PROBE) {
                LOGGER.info("[RS-SEAM-JOURNAL] drained {} entry(ies) in {}, {} still pending",
                    applied, level.dimension().identifier(), remaining.size());
            }
        }
    }

    public int pendingCount() {
        return pending.size();
    }
}
