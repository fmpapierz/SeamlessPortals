package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★ DURABLE OWNER-HALF OCCUPANCY — the {@code SavedData} the fractional model was missing
 * ({@code FRACTIONAL_DESIGN.md} §3; user order 2026-08-03: "do the saveddata thing. do not defer
 * anything").
 *
 * <p>Occupancy and secondary occupants are the ONLY seam state that is not derivable — they record
 * placements — and until this class they died with the {@code Level}: every relog silently turned
 * cut blocks whole and erased second objects. Same defect family as the (already-known)
 * non-persisted {@code mirrorCreatedCells}.
 *
 * <p>House pattern per {@link SeamJournal}: one instance per {@code ServerLevel} via
 * {@code getDataStorage().computeIfAbsent(TYPE)}, codec-based {@link SavedDataType},
 * {@code setDirty()} on every mutation.
 *
 * <p>⚠ THE SECONDARY'S STATE SERIALIZES VIA {@link BlockState#CODEC} — registry name + properties —
 * and deliberately NOT the numeric state id the network payload uses: state ids are assigned at
 * registry freeze and are NOT stable across restarts or mod-set changes. Saving ids is how a
 * reloaded world turns wool into furnaces.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li><b>Hydrate:</b> once per load, the END_SERVER_TICK loop calls {@link #hydrateOnce}, which
 *       copies the persisted maps into the level's live duck maps and broadcasts every entry to
 *       online clients. The once-flag lives on THIS instance — fresh per world load by
 *       construction, so there is no stale-static trap across reloads.</li>
 *   <li><b>Write-through:</b> every server-side mutation in {@link SeamOccupancy} mirrors here and
 *       marks dirty.</li>
 *   <li><b>Late joiners:</b> the fabric JOIN hook sends all entries of every level to the joining
 *       player (entries are human-placement-bounded — a handful per portal).</li>
 * </ul>
 */
public class SeamOccupancySavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One persisted cell: owner-half mask, plus the optional second object (state + half). */
    public record Entry(long pos, int mask, Optional<BlockState> secondaryState, int secondaryHalf) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(
            i -> i.group(
                Codec.LONG.fieldOf("pos").forGetter(Entry::pos),
                Codec.INT.fieldOf("mask").forGetter(Entry::mask),
                // ★ LENIENT — one removed mod must not kill the whole save. SavedDataStorage nulls
                // the ENTIRE .dat on any codec error and a strict list fails on one bad element;
                // lenientOptionalFieldOf turns an unparseable state into empty, losing ONE object
                // instead of every seam record in the dimension.
                BlockState.CODEC.lenientOptionalFieldOf("secondary")
                    .forGetter(Entry::secondaryState),
                Codec.INT.optionalFieldOf("secondaryHalf", 0).forGetter(Entry::secondaryHalf)
            ).apply(i, Entry::new)
        );
    }

    public static final Codec<SeamOccupancySavedData> CODEC = RecordCodecBuilder.create(
        i -> i.group(
            Entry.CODEC.listOf().fieldOf("cells").forGetter(SeamOccupancySavedData::toEntries),
            // ★ mirrorCreatedCells rides along — the standing "seam provenance not persisted" live
            // defect has the identical lifecycle (per-level, placement-derived, dies with the
            // Level), so it persists here rather than growing a fourth store. Closes the defect the
            // frame-break rule has carried since (a) shipped.
            Codec.LONG.listOf().optionalFieldOf("mirrorCreated", List.of())
                .forGetter(d -> new ArrayList<>(d.mirrorCreated))
        ).apply(i, SeamOccupancySavedData::new)
    );

    public static final SavedDataType<SeamOccupancySavedData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath("seamlessportals", "seam_occupancy"),
        SeamOccupancySavedData::new, CODEC, DataFixTypes.LEVEL
    );

    /** House cap, per SeamFrameLink: WARN and refuse rather than grow the save unbounded. */
    private static final int MAX_ENTRIES = 16384;

    private final Long2ByteOpenHashMap masks = new Long2ByteOpenHashMap();
    private final Map<Long, SeamOccupancy.Secondary> secondaries = new HashMap<>();
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet mirrorCreated =
        new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /** Transient by design: a fresh instance loads per world start, so this resets with it. */
    private boolean hydrated = false;

    public SeamOccupancySavedData() {}

    private SeamOccupancySavedData(List<Entry> entries, List<Long> mirrorCreatedList) {
        for (Entry e : entries) {
            if (e.mask() != 0) {
                masks.put(e.pos(), (byte) e.mask());
            }
            // A lenient-decoded (removed-mod) secondary arrives as AIR: skip it — that ONE object
            // is lost, everything else survives.
            if (e.secondaryState().isPresent() && !e.secondaryState().get().isAir()
                && e.secondaryHalf() != 0) {
                secondaries.put(e.pos(), new SeamOccupancy.Secondary(
                    e.secondaryState().get(), (byte) e.secondaryHalf()));
            }
        }
        mirrorCreated.addAll(mirrorCreatedList);
    }

    private List<Entry> toEntries() {
        List<Entry> out = new ArrayList<>(masks.size() + secondaries.size());
        it.unimi.dsi.fastutil.longs.LongOpenHashSet keys =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet(masks.keySet());
        keys.addAll(secondaries.keySet().stream().mapToLong(Long::longValue)
            .collect(it.unimi.dsi.fastutil.longs.LongOpenHashSet::new,
                it.unimi.dsi.fastutil.longs.LongOpenHashSet::add,
                it.unimi.dsi.fastutil.longs.LongOpenHashSet::addAll));
        for (long pos : keys) {
            SeamOccupancy.Secondary sec = secondaries.get(pos);
            out.add(new Entry(pos, masks.get(pos),
                sec == null ? Optional.empty() : Optional.of(sec.state()),
                sec == null ? 0 : sec.half()));
        }
        return out;
    }

    public static SeamOccupancySavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Write-through from {@link SeamOccupancy} — server side only, marks dirty. */
    public void recordMask(long pos, byte mask) {
        if (mask == 0) {
            masks.remove(pos);
        } else {
            if (masks.size() >= MAX_ENTRIES && !masks.containsKey(pos)) {
                LOGGER.warn("[SEAM FRAC] occupancy store at the {}-entry cap — DROPPING persistence"
                    + " for {} (live state unaffected). Investigate rather than raising the cap.",
                    MAX_ENTRIES, BlockPos.of(pos));
                return;
            }
            masks.put(pos, mask);
        }
        setDirty();
    }

    /** Write-through for the second object — server side only, marks dirty. */
    public void recordSecondary(long pos, SeamOccupancy.Secondary secondary) {
        if (secondary == null) {
            secondaries.remove(pos);
        } else {
            if (secondaries.size() >= MAX_ENTRIES && !secondaries.containsKey(pos)) {
                LOGGER.warn("[SEAM FRAC] secondary store at the {}-entry cap — DROPPING persistence"
                    + " for {} (live state unaffected).", MAX_ENTRIES, BlockPos.of(pos));
                return;
            }
            secondaries.put(pos, secondary);
        }
        setDirty();
    }

    /**
     * Convenience for {@link SeamMirror}'s five provenance mutation sites: guard + record in one
     * call, so no site can forget the instanceof.
     */
    public static void persistMirrorCreated(
        net.minecraft.world.level.Level level, long pos, boolean created
    ) {
        if (level instanceof ServerLevel sl) {
            get(sl).recordMirrorCreated(pos, created);
        }
    }

    /** Write-through for mirror provenance — closes the "provenance not persisted" defect. */
    public void recordMirrorCreated(long pos, boolean created) {
        if (created) {
            if (mirrorCreated.size() >= MAX_ENTRIES && !mirrorCreated.contains(pos)) {
                LOGGER.warn("[SEAM FRAC] provenance store at the {}-entry cap — DROPPING"
                    + " persistence for {}.", MAX_ENTRIES, BlockPos.of(pos));
                return;
            }
            mirrorCreated.add(pos);
        } else {
            mirrorCreated.remove(pos);
        }
        setDirty();
    }

    /**
     * Copy persisted state into the level's live duck maps and broadcast it, once per load. Safe to
     * call every tick — the flag is on this instance, which is fresh per world load. Writes the
     * DUCK MAPS DIRECTLY, deliberately bypassing {@link SeamOccupancy}'s mutators: those now
     * write-through to this store, and hydrating through them would re-dirty an unchanged file on
     * every boot.
     */
    public static void hydrateOnce(ServerLevel level) {
        SeamOccupancySavedData data = get(level);
        if (data.hydrated) {
            return;
        }
        data.hydrated = true;
        if (data.masks.isEmpty() && data.secondaries.isEmpty() && data.mirrorCreated.isEmpty()) {
            return;
        }
        var holder = (SeamOccupancy.SeamOccupancyHolder) level;
        var indexHolder = (SeamIndexHolder) level;
        int n = 0;
        for (var e : data.masks.long2ByteEntrySet()) {
            holder.seamlessportals$seamOccupancy().put(e.getLongKey(), e.getByteValue());
            n++;
        }
        for (var e : data.secondaries.entrySet()) {
            holder.seamlessportals$seamSecondary().put(e.getKey(), e.getValue());
        }
        for (long pos : data.mirrorCreated) {
            indexHolder.seamlessportals$mirrorCreatedCells().add(pos);
        }
        // One broadcast per cell AFTER both maps are live, so a cell with mask+secondary goes out
        // as one coherent payload rather than two partials.
        it.unimi.dsi.fastutil.longs.LongOpenHashSet keys =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet(data.masks.keySet());
        data.secondaries.keySet().forEach(keys::add);
        for (long pos : keys) {
            SeamOccupancy.broadcast(level, BlockPos.of(pos));
        }
        LOGGER.info("[SEAM FRAC] hydrated {} persisted occupancy entries for {}",
            n + data.secondaries.size(), level.dimension().identifier());
    }

    /** Diagnostic: every persisted record as {@code pos:mask[/secondary@half]}, for gates. */
    public String debugDump() {
        StringBuilder sb = new StringBuilder("[");
        it.unimi.dsi.fastutil.longs.LongOpenHashSet keys =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet(masks.keySet());
        secondaries.keySet().forEach(keys::add);
        for (long pos : keys) {
            SeamOccupancy.Secondary sec = secondaries.get(pos);
            sb.append(pos).append(':').append(masks.get(pos));
            if (sec != null) {
                sb.append('/').append(sec.state().getBlock()).append('@').append(sec.half());
            }
            sb.append(' ');
        }
        return sb.append(']').toString();
    }

    /**
     * Send EVERY level's persisted entries to one player. Called on join and on every world
     * change: a dimension change runs the client's {@code ClientWorldLoader.cleanUp()}, which
     * discards every per-dim ClientLevel and the occupancy duck maps with them — for ALL
     * dimensions, not just the one left (RS-SEAM-EMPTINESS discriminator, 2026-08-03). Masks
     * REPLACE on apply, so calling this twice for one hop is harmless.
     */
    public static void resendAllToPlayer(net.minecraft.server.level.ServerPlayer player) {
        // 26.2: ServerPlayer has no getServer(); route through the level, like broadcast().
        net.minecraft.server.MinecraftServer server =
            player.level() instanceof ServerLevel sl ? sl.getServer() : null;
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            sendAllTo(level, player);
        }
    }

    /** Send every persisted entry of this level to ONE player — the late-join sync. */
    public static void sendAllTo(ServerLevel level, net.minecraft.server.level.ServerPlayer player) {
        SeamOccupancySavedData data = level.getDataStorage().get(TYPE);
        if (data == null || (data.masks.isEmpty() && data.secondaries.isEmpty())) {
            return;
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet keys =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet(data.masks.keySet());
        data.secondaries.keySet().forEach(keys::add);
        for (long posLong : keys) {
            BlockPos pos = BlockPos.of(posLong);
            SeamOccupancy.Secondary sec = data.secondaries.get(posLong);
            var payload = new com.warwa.seamlessportals.network.ModPayloads.SeamOccupancyPayload(
                level.dimension().identifier().toString(), posLong, data.masks.get(posLong),
                sec == null ? -1 : net.minecraft.world.level.block.Block.getId(sec.state()),
                sec == null ? 0 : sec.half());
            try {
                com.warwa.seamlessportals.network.PlatformHelper.getInstance()
                    .sendToClient(player, payload);
            } catch (Throwable t) {
                // Display concern; never break a join.
            }
        }
    }
}
