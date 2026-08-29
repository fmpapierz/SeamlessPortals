package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * THE (b) ENTRY POINT. One sentence: <i>"the logic is standing at {@code ownerCell} and is about to
 * look at {@code queryPos}; is {@code queryPos} a window onto another dimension?"</i> Null means no
 * — the caller falls through to vanilla, unchanged.
 *
 * <p>The topology difference (boundary-phase vs mid-block) lives entirely inside
 * {@link SeamRegistry.SeamBinding#continuationToward} and is invisible to callers. No policy, no
 * content test; {@code yWindow} is parameterised because rails probe one Y level either side while
 * (d) minecarts will want more.
 */
public final class SeamShadowBridge {

    private SeamShadowBridge() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    @Nullable
    public static SeamShadow shadowFor(
        Level level,
        @Nullable SeamRegistry.SeamCell owner,
        BlockPos ownerCell,
        BlockPos queryPos,
        int yWindow
    ) {
        return shadowFor(level, owner, ownerCell, queryPos, yWindow, false);
    }

    /**
     * As above, with {@code crossingOnly} restricting the answer to the CANONICAL CROSSING
     * direction ({@code step == binding.crossDir()}).
     *
     * <p>Why (d) needs it and (b)/(c) must not have it. On a COINCIDENT seam
     * {@link SeamRegistry.SeamBinding#continuationToward} deliberately answers BOTH directions
     * along the seam axis: for {@code dir == srcFacing} it returns the far world's cell CO-LOCATED
     * with this side's approach — the fallback a SHAPE resolver consults when the local approach is
     * empty, and a genuine part of (b)'s two-sided view. Read as PHYSICAL PRESENCE by a cart,
     * that same fallback conjures a rail on a cell entirely in FRONT of the plane: a cart rolling
     * out of a portal onto an unrailed near approach would keep resolving "on rails" and levitate
     * one cell past the end of the track (adversarial panel, 2026-07-28, two independent lenses).
     *
     * <p>Cart physics only ever needs the crossing direction, so (d) asks for exactly that. (b)'s
     * shape resolution and (c)'s signal walk keep the unrestricted view they were designed and
     * gated against — this parameter narrows the NEW caller, it does not change theirs.
     */
    @Nullable
    public static SeamShadow shadowFor(
        Level level,
        @Nullable SeamRegistry.SeamCell owner,
        BlockPos ownerCell,
        BlockPos queryPos,
        int yWindow,
        boolean crossingOnly
    ) {
        if (owner == null) {
            return null;
        }
        if (AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SEAM_SHADOW
            || !SeamlessPortalsConfig.isEntityPortals()) {
            return null;
        }
        if (!(level instanceof ServerLevel src)) {
            return null;
        }
        MinecraftServer server = src.getServer();
        if (server == null || !server.isSameThread()) {
            return null;
        }
        int dy = queryPos.getY() - ownerCell.getY();
        if (dy < -yWindow || dy > yWindow) {
            return null;
        }
        // The step that reaches queryPos from ownerCell must be a PURE horizontal axis step — rails
        // only probe straight neighbours (and their above/below); a diagonal is never a window.
        int dx = queryPos.getX() - ownerCell.getX();
        int dz = queryPos.getZ() - ownerCell.getZ();
        Direction step;
        if (dz == 0 && dx != 0) {
            step = dx > 0 ? Direction.EAST : Direction.WEST;
        }
        else if (dx == 0 && dz != 0) {
            step = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        else {
            return null;
        }

        for (SeamRegistry.SeamBinding b : owner.bindings()) {
            if (!b.seamContinuous() || !b.isMirrorable()) {
                continue;
            }
            if (crossingOnly && step != b.crossDir()) {
                continue;
            }
            BlockPos far = b.continuationToward(step);
            if (far == null) {
                continue;
            }
            ServerLevel farLevel = server.getLevel(b.destDim());
            if (farLevel == null) {
                continue;
            }
            SeamShadow shadow = new SeamShadow(
                src, farLevel, ownerCell, step, ownerCell.relative(step), far,
                b.stateRotation(), inverse(b.stateRotation()));
            SeamRailContinuity.crossRead();
            if (AperturePassthroughLever.SEAM_RAIL_PROBE) {
                LOGGER.info("[RS-RAIL] shadow at {} step {} -> {} in {} (phase={}, rot={})",
                    ownerCell, step, far, b.destDim().identifier(), b.phase(), b.stateRotation());
            }
            return shadow;
        }
        return null;
    }

    /** {@code CLOCKWISE_90} &harr; {@code COUNTERCLOCKWISE_90}; the other two are self-inverse. */
    public static Rotation inverse(Rotation r) {
        return switch (r) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> r;
        };
    }
}
