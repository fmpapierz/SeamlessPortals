package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * THE SEAM REGISTRY — the live index of which aperture cells are bound across a portal, and the query
 * surface that sub-features (b) rail connection, (c) redstone bridging and (d) minecart traversal all
 * consume. Spec: {@code migration/REDSTONE_A_SPEC.md} §2.4 / §3.4.
 *
 * <p>{@link SeamMap} answers "given a portal and a column, which cells pair up?" — pure arithmetic.
 * This class answers the question the game actually asks, which is the inverse and by position:
 * "I am about to change the block at this position — is it bound to anything?"
 *
 * <p><b>Bindings are derived, never persisted.</b> Portal geometry is already saved and synced as
 * entity data, so re-deriving the index at runtime cannot drift from the portals themselves. The one
 * thing that does need persisting is provenance and pending clears — see
 * {@link SeamIndexHolder#seamlessportals$mirrorCreatedCells()} — because those are facts about blocks,
 * not about geometry, and cannot be recomputed.
 *
 * <p><b>Two bindings per cell.</b> An obsidian frame produces FOUR portal entities — two coincident
 * opposite-normal ones per side (confirmed live: ids 13+14 at identical overworld coordinates). Both
 * faces bind the same aperture cell, so a cell carries up to two bindings and a driver that fires
 * per-portal would fire twice per side unless it deduplicates. {@link SeamCell} models that directly
 * rather than pretending a cell has one binding.
 */
public final class SeamRegistry {

    private SeamRegistry() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One direction of a seam: "from this cell, facing this way, you arrive there".
     *
     * <p>{@code destDim}/{@code destPos} are null for a portal that fails {@link SeamMap#isMirrorable}
     * — a scaled, off-axis or non-quarter-turn portal has no well-defined cell counterpart. Such a
     * portal is still indexed, so (b)/(c)/(d) can see that a seam exists and decline gracefully,
     * rather than silently treating the cell as ordinary.
     */
    public record SeamBinding(
        Direction srcFacing,
        @Nullable ResourceKey<Level> destDim,
        @Nullable BlockPos destPos,
        Rotation stateRotation,
        UUID portalUuid
    ) {
        public boolean isMirrorable() {
            return destDim != null && destPos != null;
        }
    }

    /** The bindings at one cell — up to two, one per portal face. */
    public record SeamCell(@Nullable SeamBinding a, @Nullable SeamBinding b) {
        public List<SeamBinding> bindings() {
            List<SeamBinding> out = new ArrayList<>(2);
            if (a != null) out.add(a);
            if (b != null) out.add(b);
            return out;
        }

        public SeamCell with(SeamBinding binding) {
            if (a == null) return new SeamCell(binding, b);
            if (a.portalUuid().equals(binding.portalUuid())) return new SeamCell(binding, b);
            if (b == null || b.portalUuid().equals(binding.portalUuid())) return new SeamCell(a, binding);
            return this;   // already two faces bound; a third portal over the same cell is ignored
        }

        public SeamCell without(UUID portalUuid) {
            SeamBinding na = (a != null && a.portalUuid().equals(portalUuid)) ? null : a;
            SeamBinding nb = (b != null && b.portalUuid().equals(portalUuid)) ? null : b;
            return (na == null && nb == null) ? null : new SeamCell(na, nb);
        }
    }

    // =============================================================================================
    // HOT PATH
    // =============================================================================================

    /**
     * The {@code LevelChunk.setBlockState} gate. One field read plus one {@code contains} on a
     * usually-empty set — see {@link SeamIndexHolder} for why this is not a static map lookup.
     */
    public static boolean sectionHasSeam(Level level, BlockPos pos) {
        return !((SeamIndexHolder) level).seamlessportals$sectionsWithSeams().isEmpty()
            && ((SeamIndexHolder) level).seamlessportals$sectionsWithSeams()
                .contains(SectionPos.asLong(pos));
    }

    @Nullable
    public static SeamCell lookup(Level level, BlockPos pos) {
        return ((SeamIndexHolder) level).seamlessportals$seamCells().get(pos.asLong());
    }

    public static boolean isSeamCell(Level level, BlockPos pos) {
        return lookup(level, pos) != null;
    }

    /**
     * THE (b)/(c)/(d) CONTRACT: what lies across the seam from this cell in this direction, or null
     * when the direction does not cross a seam here.
     *
     * <p>A binding faces one way. Asking for the neighbour in the binding's own facing direction is
     * asking to cross; asking for any other direction is an ordinary in-world neighbour and returns
     * null so the caller falls through to vanilla.
     */
    @Nullable
    public static GlobalPos lookupAcross(Level level, BlockPos pos, Direction dir) {
        SeamCell cell = lookup(level, pos);
        if (cell == null) {
            return null;
        }
        for (SeamBinding b : cell.bindings()) {
            if (b.srcFacing() == dir && b.isMirrorable()) {
                return GlobalPos.of(b.destDim(), b.destPos());
            }
        }
        return null;
    }

    /** Map a direction through a seam, for callers that must reorient a block state or a motion. */
    public static Direction mapDir(SeamBinding binding, Direction dir) {
        return binding.stateRotation().rotate(dir);
    }

    // =============================================================================================
    // SEEDING / TEARDOWN
    // =============================================================================================

    /**
     * (Re)bind every aperture cell of this portal. Idempotent — re-binding the same portal at the same
     * geometry rewrites identical entries, which is what makes it safe to call from the per-tick
     * portal signal without tracking dirty state.
     */
    public static void bind(Portal portal) {
        Level level = portal.level();
        if (level == null) {
            return;
        }
        boolean mirrorable = SeamMap.isMirrorable(portal);
        Rotation rotation = mirrorable ? SeamMap.blockRotationOf(portal) : null;
        if (mirrorable && rotation == null) {
            mirrorable = false;   // states cannot be carried; index it query-only
        }

        Vec3 normal = portal.getNormal();
        Direction facing = Direction.getApproximateNearest(normal.x, normal.y, normal.z);
        ResourceKey<Level> destDim = mirrorable ? portal.getDestDim() : null;

        SeamIndexHolder holder = (SeamIndexHolder) level;
        int bound = 0;
        for (Vec3 column : SeamMap.enumerateColumns(portal)) {
            BlockPos src = SeamMap.seamCell(portal, column);
            BlockPos dst = mirrorable ? SeamMap.mirrorCell(portal, column) : null;

            SeamBinding binding = new SeamBinding(
                facing, destDim, dst, rotation == null ? Rotation.NONE : rotation, portal.getUUID());

            long key = src.asLong();
            SeamCell existing = holder.seamlessportals$seamCells().get(key);
            SeamCell updated = existing == null ? new SeamCell(binding, null) : existing.with(binding);
            holder.seamlessportals$seamCells().put(key, updated);
            holder.seamlessportals$sectionsWithSeams().add(SectionPos.asLong(src));
            bound++;
        }

        if (AperturePassthroughLever.SEAM_RECONCILE_PROBE) {
            LOGGER.info("[RS-SEAM-REGISTRY] bound portal id={} uuid={} dim={} cells={} mirrorable={}"
                    + " facing={} rot={}",
                portal.getId(), portal.getUUID(), level.dimension().identifier(), bound, mirrorable,
                facing, rotation);
        }
    }

    /** Drop every binding owned by this portal. Sections are only dropped when they empty out. */
    public static void unbind(Portal portal) {
        Level level = portal.level();
        if (level == null) {
            return;
        }
        SeamIndexHolder holder = (SeamIndexHolder) level;
        UUID uuid = portal.getUUID();

        List<Long> emptied = new ArrayList<>();
        int removed = 0;
        for (Vec3 column : SeamMap.enumerateColumns(portal)) {
            long key = SeamMap.seamCell(portal, column).asLong();
            SeamCell existing = holder.seamlessportals$seamCells().get(key);
            if (existing == null) {
                continue;
            }
            SeamCell updated = existing.without(uuid);
            if (updated == null) {
                holder.seamlessportals$seamCells().remove(key);
                emptied.add(key);
            }
            else {
                holder.seamlessportals$seamCells().put(key, updated);
            }
            removed++;
        }

        // A section stays flagged while ANY cell in it is still bound — the other face of the same
        // frame, or an overlapping portal. Recomputing membership per emptied section is what keeps
        // the hot-path set from accumulating dead entries over a long session.
        for (long cellKey : emptied) {
            long section = SectionPos.asLong(BlockPos.of(cellKey));
            boolean stillUsed = false;
            for (long remaining : holder.seamlessportals$seamCells().keySet()) {
                if (SectionPos.asLong(BlockPos.of(remaining)) == section) {
                    stillUsed = true;
                    break;
                }
            }
            if (!stillUsed) {
                holder.seamlessportals$sectionsWithSeams().remove(section);
            }
        }

        if (AperturePassthroughLever.SEAM_RECONCILE_PROBE) {
            LOGGER.info("[RS-SEAM-REGISTRY] unbound portal id={} uuid={} dim={} cells={} sectionsFreed={}",
                portal.getId(), portal.getUUID(), level.dimension().identifier(), removed, emptied.size());
        }
    }

    /** Total bound cells in a level — probe/test accounting only. */
    public static int boundCellCount(Level level) {
        return ((SeamIndexHolder) level).seamlessportals$seamCells().size();
    }
}
