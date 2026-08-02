package com.warwa.seamlessportals.passthrough;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * ★ WHICH HALF OF A SEAM CELL EACH OBJECT OWNS — {@code FRACTIONAL_DESIGN.md} §2a.0.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>The owner half <b>cannot be derived from the binding</b>. An obsidian portal is BI-FACED: two
 * portal entities share one plane with opposite normals, both in the same dimension, so a seam cell
 * carries two bindings whose facings disagree about which side is "ours". The first build picked
 * whichever binding was stored first, and the live round caught it immediately — a block placed from
 * the north approach side kept the SOUTH half, so from the north it appeared to occupy the far half.
 *
 * <p>The owner half is a property of <b>the object</b>, decided when it is placed, so it has to be
 * recorded. That is this class.
 *
 * <h2>Two owners per cell</h2>
 *
 * <p>User decision 2026-08-02: both halves may be occupied by <b>independent objects</b>, each with
 * its own material across the seam, each breaking separately. So this is keyed by
 * <b>(cell, half)</b> — a bare per-cell set could not express it, which is why
 * {@code mirrorCreatedCells} is not the right home. Each cell gets a bitmask: bit 0 = the half on
 * the axis-NEGATIVE side of the plane, bit 1 = the axis-POSITIVE side.
 *
 * <p>An empty mask means "no object here" and the cell behaves as ordinary terrain. Both bits set
 * means the cell is materially whole again — two objects meeting at the plane — and the shape hook
 * correctly returns an uncut cube.
 *
 * <p>⚠ NOT DERIVABLE, therefore NOT FREE. Unlike the cut geometry (a pure function of portal planes,
 * which both sides recompute from synced entity data), occupancy comes from a placement. It needs a
 * packet and a {@code SavedData} to survive relog and reload; neither exists yet, and until they do
 * this is best-effort per-session state written on whichever side saw the placement.
 */
public final class SeamOccupancy {

    private SeamOccupancy() {}

    /** Half on the axis-NEGATIVE side of the plane (lower coordinate). */
    public static final byte HALF_NEGATIVE = 0b01;
    /** Half on the axis-POSITIVE side of the plane (higher coordinate). */
    public static final byte HALF_POSITIVE = 0b10;
    /** Both halves occupied — two objects meeting at the plane; materially a whole cube. */
    public static final byte BOTH = HALF_NEGATIVE | HALF_POSITIVE;

    private static Long2ByteMap map(Level level) {
        return ((SeamOccupancyHolder) level).seamlessportals$seamOccupancy();
    }

    /**
     * ★ THE PLACEMENT RULE — which half a placement claims, from the point the crosshair ray struck.
     *
     * <p>User decision 2026-08-02: the hit POINT, not the player's eyes (leaning through the portal
     * would flip it) and not the clicked block (the floor beneath an aperture and the frame itself
     * both straddle the plane, so neither can answer).
     *
     * @param hit the exact world point the ray struck
     * @param cell the seam cell being filled
     * @param axis the plane's axis
     * @param planeOffset the plane's offset inside {@code cell} along {@code axis}, in [0,1]
     */
    public static byte halfFromHit(Vec3 hit, BlockPos cell, Direction.Axis axis, double planeOffset) {
        double local = switch (axis) {
            case X -> hit.x - cell.getX();
            case Y -> hit.y - cell.getY();
            case Z -> hit.z - cell.getZ();
        };
        return local >= planeOffset ? HALF_POSITIVE : HALF_NEGATIVE;
    }

    /** Record that an object occupies {@code half} of {@code cell}. Idempotent. */
    public static void claim(Level level, BlockPos cell, byte half) {
        Long2ByteMap m = map(level);
        long key = cell.asLong();
        m.put(key, (byte) (m.get(key) | half));
    }

    /** Release one half. Removes the entry entirely once neither half is owned. */
    public static void release(Level level, BlockPos cell, byte half) {
        Long2ByteMap m = map(level);
        long key = cell.asLong();
        byte now = (byte) (m.get(key) & ~half);
        if (now == 0) {
            m.remove(key);
        } else {
            m.put(key, now);
        }
    }

    /** Forget this cell entirely — the block was broken or replaced wholesale. */
    public static void clear(Level level, BlockPos cell) {
        map(level).remove(cell.asLong());
    }

    /** The occupancy mask for a cell: 0 when nothing here, else some combination of the two halves. */
    public static byte occupancyOf(Level level, BlockPos cell) {
        return map(level).get(cell.asLong());
    }

    /**
     * The half a binding's facing points at — i.e. which bit that binding would call "ours".
     * Used to translate between a binding's {@code srcFacing} and this class's axis-relative bits.
     */
    public static byte halfOf(Direction facing) {
        return facing.getAxisDirection() == Direction.AxisDirection.POSITIVE
            ? HALF_POSITIVE : HALF_NEGATIVE;
    }

    /** Duck interface on {@code Level}, alongside the other per-level seam indices. */
    public interface SeamOccupancyHolder {
        Long2ByteOpenHashMap seamlessportals$seamOccupancy();
    }
}
