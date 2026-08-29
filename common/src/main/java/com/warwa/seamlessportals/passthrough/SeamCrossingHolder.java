package com.warwa.seamlessportals.passthrough;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * ENGINE STAGE 2a (SEAM_ENTITY_ENGINE_DESIGN §3.1 layer 1): the crossing's single stored
 * history bit — the ANCHOR — held on the entity itself (the cache-outlives-subject lesson:
 * state lives ON the object it describes). Implemented by {@code MixinEntity}; every entity is
 * a holder, non-crossing entities hold null.
 *
 * <p>The anchor is the crossing's identity: which face the unit is crossing, set at the FLIP
 * (arrival) — epoch-guarded so duplicate/late/rider-echo RPC applications are structurally
 * no-ops. Riders continuously inherit the unit root's anchor ({@link SeamCrossingRule#tickAnchor}),
 * so a mid-crossing dismount orphan keeps its crossing (design §3.2 inheritance — never a
 * re-derivation, which anchors a majority-crossed orphan to the wrong face).
 */
public interface SeamCrossingHolder {

    @Nullable
    Portal seamlessportals$getAnchorFace();

    void seamlessportals$setAnchorFace(@Nullable Portal face);

    int seamlessportals$getAnchorEpoch();

    void seamlessportals$setAnchorEpoch(int epoch);

    static SeamCrossingHolder of(Entity entity) {
        return (SeamCrossingHolder) entity;
    }
}
