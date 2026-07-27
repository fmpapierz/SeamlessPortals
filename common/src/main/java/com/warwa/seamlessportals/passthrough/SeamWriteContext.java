package com.warwa.seamlessportals.passthrough;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * WHO IS WRITING, RIGHT NOW — the bridge between an action and the block write it causes.
 *
 * <p>{@code LevelChunk.setBlockState} is the only place that sees every write, and it has no idea
 * what caused any of them. Callers that DO know bracket themselves with {@link #push}/{@link #pop}
 * and the driver reads {@link #sourceFor}.
 *
 * <h2>Two properties that are not optional, and why</h2>
 *
 * <p><b>1. It carries a POSITION, and only that position matches.</b> A bracket alone would be wrong:
 * {@code Level.setBlock} issues synchronous NESTED writes at OTHER positions from inside a player
 * placement. {@code DoubleHighBlockItem.placeBlock} writes the upper cell before calling
 * {@code super} (REF {@code DoubleHighBlockItem.java:20}), and a rail's own {@code onPlace} resolves
 * neighbouring rails. Without the position test, every one of those would inherit
 * {@code PLAYER_PLACE} and mirror as if the player had placed them — which is precisely the class of
 * write this change exists to exclude.
 *
 * <p><b>2. It SAVES AND RESTORES rather than setting and clearing.</b> Nesting is real: a player
 * placement can run block logic that itself brackets a write. A clear-on-exit would leave the outer
 * bracket disarmed for the rest of its own placement. {@link #push} returns the previous value and
 * {@link #pop} puts it back, so the callers form a stack.
 *
 * <p><b>Server-thread confined, deliberately not a ThreadLocal.</b> Every consumer already proves
 * {@code server.isSameThread()} before reading (see the driver's guard in
 * {@code LevelChunkSetBlockStateMixin}), and a plain static is cheaper on a path that runs for every
 * block change in the game. {@link #reset} exists so a thrown exception mid-placement cannot leave a
 * stale source armed for the next unrelated write.
 */
public final class SeamWriteContext {

    private SeamWriteContext() {}

    private static SeamWriteSource currentSource = SeamWriteSource.UNKNOWN;
    private static @Nullable BlockPos currentPos = null;

    /**
     * Arm a source for one position. Returns the previous state so the caller can restore it — always
     * pair with {@link #pop} in a {@code finally}.
     */
    public static Object[] push(SeamWriteSource source, BlockPos pos) {
        Object[] previous = new Object[]{currentSource, currentPos};
        currentSource = source;
        currentPos = pos == null ? null : pos.immutable();
        return previous;
    }

    /** Restore what {@link #push} returned. */
    public static void pop(Object[] previous) {
        if (previous == null) {
            reset();
            return;
        }
        currentSource = (SeamWriteSource) previous[0];
        currentPos = (BlockPos) previous[1];
    }

    /**
     * The source that claimed THIS position, or {@link SeamWriteSource#UNKNOWN}.
     *
     * <p>The position test is the whole point — see property 1 in the class note. A write at any
     * other position during a bracketed action is not the bracketed action.
     */
    public static SeamWriteSource sourceFor(BlockPos pos) {
        if (currentPos == null || pos == null || !currentPos.equals(pos)) {
            return SeamWriteSource.UNKNOWN;
        }
        return currentSource;
    }

    /** Belt and braces: clear any armed source. Called if a bracket unwinds abnormally. */
    public static void reset() {
        currentSource = SeamWriteSource.UNKNOWN;
        currentPos = null;
    }
}
