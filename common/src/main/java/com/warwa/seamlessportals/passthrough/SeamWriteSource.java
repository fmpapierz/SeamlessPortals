package com.warwa.seamlessportals.passthrough;

/**
 * WHAT CAUSED THIS BLOCK WRITE — the classification half of the mirror policy.
 *
 * <p>The mirror driver sits on {@code LevelChunk.setBlockState}, which sees every write in the game
 * and knows nothing about who asked for it. This enum is that missing information, and it is kept
 * deliberately separate from the DECISION about which sources mirror ({@link SeamMirrorPolicy}) so
 * that widening the feature later is a policy edit rather than a driver rewrite.
 *
 * <p><b>Today only the player sources mirror</b> (user decision, 2026-07-26). Everything else is
 * classified and declined. The point of naming the declined categories rather than lumping them into
 * one "not a player" bucket is that each becomes a one-line policy change when its turn comes, and
 * each has genuinely different semantics — a piston MOVES a block (two cells, one action), a
 * dispenser places one, fluid spreads continuously, a command may rewrite a whole region at once.
 *
 * <p>{@code REDSTONE_RECON.md} §0.8 previously pinned non-item writes as "ACCEPT BEST-EFFORT". The
 * user narrowed that to player-only on 2026-07-26; the categories below are what makes reversing or
 * widening it cheap.
 */
public enum SeamWriteSource {

    /**
     * A player placing a block from the hand — {@code BlockItem.place(BlockPlaceContext)} with a
     * non-null player. Covers offhand, placement against a portal-adjacent face, powder snow, and
     * multi-cell items; excludes dispensers, which reach the same method with a
     * {@code DirectionalPlaceContext} whose player is null.
     */
    PLAYER_PLACE,

    /**
     * A player breaking a block. Mirrored today because "break one half breaks the other" is a
     * separate user rule ({@code REDSTONE_RECON.md} §0.7) that predates the player-only narrowing and
     * was not withdrawn by it.
     */
    PLAYER_BREAK,

    /** Piston, dispenser, or similar machine-driven write. Classified, not yet mirrored. */
    MACHINE,

    /** {@code /setblock}, {@code /fill}, worldgen, structure placement. Classified, not yet mirrored. */
    COMMAND,

    /** Fluid spread, and anything else that propagates on its own tick. Not yet mirrored. */
    FLUID,

    /**
     * Nothing claimed this write. The default, and what every unbracketed path reports — gravity
     * blocks, block-entity self-updates, mod writes, and the nested writes vanilla issues inside its
     * own placement logic.
     */
    UNKNOWN;

    public boolean isPlayer() {
        return this == PLAYER_PLACE || this == PLAYER_BREAK;
    }
}
