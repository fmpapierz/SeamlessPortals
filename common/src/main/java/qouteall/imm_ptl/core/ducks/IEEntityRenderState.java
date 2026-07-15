package qouteall.imm_ptl.core.ducks;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * S12-A (Slice C) — the R3 per-entity clip-context tag duck (S11-R3-clip-bracketing.md §3.2). A mixin
 * ({@code MixinEntityRenderState}) adds two frame-scoped fields to {@code EntityRenderState}; the
 * {@code LevelExtractor} extract inject ({@code MixinLevelExtractor}) SETS them when an entity is colliding a
 * portal, and the submit-side {@code @WrapOperation} ({@code MixinLevelRenderer_CrossPortalEntity}) READS them
 * to route collided entities to {@code CrossPortalEntityRenderer.submitMainPassEntity(...)}.
 *
 * <p><b>Why the tag exists.</b> 26.2 detached {@code EntityRenderState}s do NOT carry the {@code Entity}
 * reference, but the per-entity clip decision needs the Entity (its {@code PortalCollisionHandler} / colliding
 * portals). IP had the Entity in hand at its immediate-mode {@code renderEntity} boundary; on 26.2 the Entity
 * is available only at EXTRACT, and the clip effect happens at DRAW — so the tag carries the Entity across the
 * extract→submit phase boundary (the extract-vs-render phase assignment, CUTOVER_SPEC §4.2). The tag is
 * set-or-CLEARED on every extraction, so a reused/pooled render state never carries a stale clip context.
 */
public interface IEEntityRenderState {

    /**
     * Set (or CLEAR with {@code (null, null)}) this render state's clip context. Called from the extract
     * inject: non-null iff the entity is colliding a portal this frame.
     */
    void ip_setClipContext(@Nullable Entity collidedEntity, @Nullable Portal collidingPortal);

    /** The colliding entity this state was tagged with, or {@code null} if not a collided-entity state. */
    @Nullable
    Entity ip_getClipContextEntity();

    /** The colliding portal recorded at extract (informational — the submit path re-derives last-wins). */
    @Nullable
    Portal ip_getClipContextPortal();
}
