package com.warwa.seamlessportals.passthrough;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Duck interface implemented onto {@code net.minecraft.world.level.Level} by
 * {@code com.warwa.seamlessportals.mixin.passthrough.SeamIndexHolderMixin}, holding that level's seam
 * index. Same pattern as the {@code qouteall.imm_ptl.core.ducks.IE*} family already in the tree.
 *
 * <p><b>Why the index lives on the Level and not in a static map.</b> The seam lookup sits on
 * {@code LevelChunk.setBlockState}, which is one of the hottest paths in the game — it runs for every
 * block change in every dimension, seam or not. The design panel's first storage plan gated that path
 * behind {@code DISABLED || !ANY_SEAMS}, and a verifier correctly pointed out that this folds nothing:
 * {@code DISABLED} is {@code false} by default and {@code ANY_SEAMS} is {@code true} in any world
 * containing a lit portal, so every {@code setBlockState} in the game paid two hash lookups. Hanging
 * the index off the Level turns the common case into one field read plus one {@code contains} on a
 * usually-empty {@code LongOpenHashSet}, with the per-cell map consulted only after a section hit.
 *
 * <p>Both structures are server-authoritative but exist on client levels too, because (b)/(c)/(d) will
 * want the same "what is across the seam" query for rendering and prediction.
 */
public interface SeamIndexHolder {

    /**
     * Section keys ({@code SectionPos.asLong}) that contain at least one bound seam cell. The hot-path
     * gate: empty in every world without portals, and tiny even in worlds with many.
     */
    LongOpenHashSet seamlessportals$sectionsWithSeams();

    /** Bound seam cells for this level, keyed by {@code BlockPos.asLong}. */
    Long2ObjectOpenHashMap<SeamRegistry.SeamCell> seamlessportals$seamCells();

    /**
     * ★ PARTICLE MARGIN INDEX (round 46) — margin cell ({@code BlockPos.asLong}) → governing
     * aperture cell ({@code BlockPos.asLong}); readers use
     * {@code getOrDefault(key, Long.MIN_VALUE)} (0 is a valid cell). In-plane cells within
     * {@link SeamRegistry#PARTICLE_MARGIN_RADIUS} of an aperture, PARTICLES ONLY: fire smoke
     * wanders blocks from the window before crossing the plane's extension (user live rounds
     * 2026-08-10), and a per-particle neighborhood scan at that radius would be hot-path poison —
     * one map get answers it. Registered by {@code bind}, swept by {@code unbind} on base-cell
     * liveness.
     */
    it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap seamlessportals$particleMargin();

    /**
     * Cells whose current occupant was created by MIRRORING rather than placed by a player, keyed by
     * {@code BlockPos.asLong}.
     *
     * <p>This is the <b>provenance</b> store required by the user's break rule ("frame break clears
     * the destination half"). Without it the rule is undecidable: on a break, both halves of a bound
     * pair look identical, and there is no way to tell which one the player actually built from which
     * one the mirror created — so a break would sometimes delete the half you placed. Note this is
     * per-CELL occupancy state, not geometry, which is why it is NOT a field on
     * {@link SeamRegistry.SeamBinding} as the spec override suggested: a binding is symmetric and
     * outlives any particular block, whereas provenance belongs to whatever currently occupies the
     * cell. Written at step 6 (mirror writes), consumed at step 7 (break handling), and persisted
     * alongside the pending-clear journal.
     */
    LongOpenHashSet seamlessportals$mirrorCreatedCells();

    /**
     * ★ Last-seen bind-geometry fingerprint per portal UUID — PER LEVEL, and that placement is the
     * fix for the far-pair relog corpse (2026-08-04). These fingerprints guard {@code
     * SeamRegistry.bind}'s work on THIS level's index, so their validity dies with the level. The
     * first build kept them in two static UUID-keyed maps; portal UUIDs persist in the save and the
     * geometry never changes across a relog, so after a reopen the tick handler saw "unchanged"
     * and never rebound — into a brand-new EMPTY index. The server escaped by luck (world close
     * fires {@code PORTAL_DISPOSE_SIGNAL} per portal, which removed its entries) while the client's
     * relog teardown fires no per-portal dispose, leaving {@code totalIndexedCells=0} with both
     * portals present and healthy: one-sided outlines, unbreakable far halves, replaces going
     * everywhere. A cache that outlives the thing it guards is the defect; keying it on the level
     * removes the entire class.
     */
    java.util.Map<java.util.UUID, Long> seamlessportals$bindFingerprints();
}
