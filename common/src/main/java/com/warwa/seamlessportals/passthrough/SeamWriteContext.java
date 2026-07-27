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
 * <p><b>3. PER THREAD.</b> This started as a plain static, documented as server-thread confined —
 * which was true while only the server had brackets. Same-frame mirroring added CLIENT brackets
 * ({@code MixinBlockItemPlaceSource} runs on both sides, and the client break bracket is
 * {@code MixinMultiPlayerGameModeBreakSource}), and in single-player the client and the integrated
 * server are DIFFERENT THREADS sharing one JVM. Two threads pushing and popping one static interleave
 * their save/restore and corrupt each other's stack. The position test bounds the damage — a stale
 * arm can only mis-attribute a write at the same position, which for two concurrent player actions
 * would usually be the same answer anyway — but "usually the right answer by luck" is not a property
 * worth keeping. A {@link ThreadLocal} costs one lookup on a path that was already doing a map
 * probe, and removes the race outright.
 */
public final class SeamWriteContext {

    private SeamWriteContext() {}

    private static final ThreadLocal<SeamWriteSource> CURRENT_SOURCE =
        ThreadLocal.withInitial(() -> SeamWriteSource.UNKNOWN);
    private static final ThreadLocal<@Nullable BlockPos> CURRENT_POS = new ThreadLocal<>();

    /**
     * Arm a source for one position. Returns the previous state so the caller can restore it — always
     * pair with {@link #pop} in a {@code finally}.
     */
    public static Object[] push(SeamWriteSource source, BlockPos pos) {
        Object[] previous = new Object[]{CURRENT_SOURCE.get(), CURRENT_POS.get()};
        CURRENT_SOURCE.set(source);
        CURRENT_POS.set(pos == null ? null : pos.immutable());
        return previous;
    }

    /** Restore what {@link #push} returned. */
    public static void pop(Object[] previous) {
        if (previous == null) {
            reset();
            return;
        }
        CURRENT_SOURCE.set((SeamWriteSource) previous[0]);
        CURRENT_POS.set((BlockPos) previous[1]);
    }

    /**
     * The source that claimed THIS position, or {@link SeamWriteSource#UNKNOWN}.
     *
     * <p>The position test is the whole point — see property 1 in the class note. A write at any
     * other position during a bracketed action is not the bracketed action.
     */
    public static SeamWriteSource sourceFor(BlockPos pos) {
        BlockPos armed = CURRENT_POS.get();
        if (armed == null || pos == null || !armed.equals(pos)) {
            return SeamWriteSource.UNKNOWN;
        }
        return CURRENT_SOURCE.get();
    }

    /** Belt and braces: clear any armed source. Called if a bracket unwinds abnormally. */
    public static void reset() {
        CURRENT_SOURCE.set(SeamWriteSource.UNKNOWN);
        CURRENT_POS.set(null);
    }
}
