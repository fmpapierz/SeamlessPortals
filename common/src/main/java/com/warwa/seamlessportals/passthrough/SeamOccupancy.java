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

    /**
     * ★ WRITE-THROUGH TO THE SAVEDDATA — server side only, at every mask mutation. The
     * {@code instanceof ServerLevel} guard (not {@code !isClientSide}) is deliberate and matches
     * {@link #broadcast}: it is what keeps client packet-application from ever touching the store.
     */
    private static void persistMask(Level level, long key) {
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            SeamOccupancySavedData.get(sl).recordMask(key, map(level).get(key));
        }
    }

    /** Record that an object occupies {@code half} of {@code cell}. Idempotent. */
    public static void claim(Level level, BlockPos cell, byte half) {
        Long2ByteMap m = map(level);
        long key = cell.asLong();
        m.put(key, (byte) (m.get(key) | half));
        persistMask(level, key);
        relight(level, cell);
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
        persistMask(level, key);
        relight(level, cell);
    }

    /** Forget this cell entirely — the block was broken or replaced wholesale. */
    public static void clear(Level level, BlockPos cell) {
        map(level).remove(cell.asLong());
        persistMask(level, cell.asLong());
        relight(level, cell);
    }

    /**
     * REPLACE a cell's mask outright. For the client applying an authoritative server value: the
     * server owns how many halves exist, and merging would make a release impossible to express.
     */
    public static void set(Level level, BlockPos cell, byte mask) {
        if (mask == 0) {
            map(level).remove(cell.asLong());
        } else {
            map(level).put(cell.asLong(), mask);
        }
        persistMask(level, cell.asLong());
        relight(level, cell);
    }

    /**
     * ★ RE-LIGHT ON EVERY MASK MUTATION — the lighting arm ({@code
     * LightEngineSeamTransparencyMixin}) changes what the light engine sees for this cell as a
     * FUNCTION OF THE MASK, but the engine only re-reads a cell when something schedules a check.
     * Vanilla schedules on blockstate changes; a mask change with the blockstate untouched (the
     * claim of a crossing half, a client packet apply, a release) would otherwise leave stale
     * darkness until an unrelated neighbour update. Fail-soft: light is cosmetic and a missing
     * relight must never break a write that already succeeded.
     */
    public static void relight(Level level, BlockPos cell) {
        try {
            level.getChunkSource().getLightEngine().checkBlock(cell.immutable());
        } catch (Throwable ignored) {
        }
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

    /**
     * ★ PUSH a cell's occupancy to every online player.
     *
     * <p>Required because occupancy is the one piece of seam state that is NOT derivable from portal
     * geometry — it records where a placement's crosshair ray hit, and both fractions of a split
     * block are the same block, so nothing in the world can be inspected to recover it. A player's
     * own placement reaches both sides for free ({@code BlockItem.place} runs client and server), but
     * the CROSSING half is written by {@code SeamMirror}, which is server-only. Without this push the
     * client draws the destination cell whole — measured live 2026-08-02.
     *
     * <p>Broadcast to all players rather than to a tracking set: a seam cell is visible through a
     * portal from arbitrary distance in another dimension, so "who can see this" is not answerable
     * from the cell's own position. The volume is bounded by placements at seams, which is a human
     * action, not a per-tick one.
     */
    public static void broadcast(Level level, BlockPos cell) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        net.minecraft.server.MinecraftServer server = serverLevel.getServer();
        if (server == null) {
            return;
        }
        Secondary sec = secondaryOf(level, cell);
        var payload = new com.warwa.seamlessportals.network.ModPayloads.SeamOccupancyPayload(
            level.dimension().identifier().toString(), cell.asLong(), occupancyOf(level, cell),
            sec == null ? -1 : net.minecraft.world.level.block.Block.getId(sec.state()),
            sec == null ? 0 : sec.half());
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                com.warwa.seamlessportals.network.PlatformHelper.getInstance()
                    .sendToClient(player, payload);
            } catch (Throwable t) {
                // Never let a display concern break a write that already succeeded.
            }
        }
    }

    // =============================================================================================
    // ★ THE SECONDARY OCCUPANT — user non-negotiables, live round 8 (2026-08-02):
    //   "i must be able to place ANY block i want in the empty half"
    //   "when i break/punch it should not replace the original block EVER, completely separate"
    //
    // Vanilla stores exactly ONE BlockState per cell, so a second, possibly different-type object
    // sharing a seam cell cannot live in the chunk. It lives HERE: state + half, with its own
    // placement, rendering, collision, targeting and breaking. The same-type completion gesture
    // routes through this too — a second object always has its own identity, which is precisely
    // what makes its breaking independent of the original's.
    // =============================================================================================

    /** The second object in a cell: its state and the single half it occupies. */
    public record Secondary(net.minecraft.world.level.block.state.BlockState state, byte half) {}

    private static java.util.Map<Long, Secondary> secondaryMap(Level level) {
        return ((SeamOccupancyHolder) level).seamlessportals$seamSecondary();
    }

    @org.jetbrains.annotations.Nullable
    public static Secondary secondaryOf(Level level, BlockPos cell) {
        return secondaryMap(level).get(cell.asLong());
    }

    public static void setSecondary(Level level, BlockPos cell,
        @org.jetbrains.annotations.Nullable Secondary secondary) {
        if (secondary == null) {
            secondaryMap(level).remove(cell.asLong());
        } else {
            secondaryMap(level).put(cell.asLong(), secondary);
        }
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            SeamOccupancySavedData.get(sl).recordSecondary(cell.asLong(), secondary);
        }
    }

    /**
     * The half an entity's EYES are on — the targeting rule's discriminator. An entity only sees,
     * outlines and punches the half of a seam cell on ITS side of the plane; the other half is the
     * other dimension's business, reachable only through the window.
     */
    public static byte halfOfEye(net.minecraft.world.entity.Entity entity, BlockPos cell,
        Direction.Axis axis, double planeOffset) {
        double eye = switch (axis) {
            case X -> entity.getX() - cell.getX();
            case Y -> entity.getEyeY() - cell.getY();
            case Z -> entity.getZ() - cell.getZ();
        };
        return eye >= planeOffset ? HALF_POSITIVE : HALF_NEGATIVE;
    }

    /** The complement of a single half; 0 for anything else. */
    public static byte otherHalf(byte half) {
        return half == HALF_POSITIVE ? HALF_NEGATIVE
            : half == HALF_NEGATIVE ? HALF_POSITIVE : 0;
    }

    /** Duck interface on {@code Level}, alongside the other per-level seam indices. */
    public interface SeamOccupancyHolder {
        Long2ByteOpenHashMap seamlessportals$seamOccupancy();

        java.util.Map<Long, Secondary> seamlessportals$seamSecondary();
    }
}
