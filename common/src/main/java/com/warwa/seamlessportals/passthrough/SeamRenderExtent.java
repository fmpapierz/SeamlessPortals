package com.warwa.seamlessportals.passthrough;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * ★ ROUND 35 — THE RENDER ENVELOPE: a per-entity upper bound on the DRAWN model.
 *
 * <p><b>The problem this exists to solve.</b> Every seam predicate measures
 * {@code entity.getBoundingBox()} — the COLLISION box — while the seam clip is a hardware plane
 * cutting the DRAWN MODEL. Those differ, and they differ BY ENTITY TYPE: a cow's muzzle reaches
 * 0.9375 blocks from its origin against a 0.45 box half; a minecart's shell only 0.625. That gap
 * is the whole cause of the face cut — the physics booking that feeds the projection painter fires
 * at a measured ~0.71 blocks from the plane, so a cow's muzzle crosses ~0.23 blocks BEFORE any
 * painter exists for it, on every crossing, while a minecart's never does. It is also why a
 * hardcoded {@code DRAW_MODEL_MARGIN} was the wrong shape of answer: the user's requirement is
 * that fixes work for "every single type of rider, entity, literally everything, not just cows".
 *
 * <p><b>Why {@code getBoundingBoxForCulling}.</b> 26.2 exposes no true model-extent API (see
 * {@link com.warwa.seamlessportals.mixin.client.EntityRendererCullingBoxInvoker} for the full
 * survey and why each candidate fails). What it does expose is the box vanilla frustum-tests in
 * {@code shouldRender} — and frustum culling is only correct if nothing of the entity draws
 * outside it, which makes it a CONTRACTUAL upper bound on the drawn model for every renderable
 * entity, vanilla or modded. Mojang authors real per-type widenings into it, which is precisely
 * the per-species variation a constant cannot see.
 *
 * <p><b>Server safety.</b> Some consumers of the seam predicates run on a SERVER-reachable tick
 * path, where no renderer exists. Those get a geometric fallback derived from the entity's own
 * dimensions rather than a renderer lookup — never a hard failure, and never a client-only class
 * touched from the server thread.
 *
 * <p>Lever {@code -PdisableSeamRenderEnvelope} reverts every consumer to the plain collision box.
 */
public final class SeamRenderExtent {

    private SeamRenderExtent() {}

    /**
     * Extra headroom beyond vanilla's own {@code inflate(0.5)} frustum slack.
     *
     * <p>Vanilla's 0.5 is sized for a FRUSTUM test, where being slightly wrong costs one frame of
     * a popped-in entity. Here it feeds a HARD CLIP PLANE, where being slightly wrong costs a
     * visibly severed model — and a cow's 0.9375 reach against a 0.45 half-box leaves only 0.0125
     * of margin inside vanilla's 0.5. This is the one documented tunable.
     */
    private static final double SEAM_CLIP_SAFETY = 0.25;

    /** Per-frame single-entry cache: the same entity is asked several times per frame. */
    private static Entity cachedEntity;
    private static AABB cachedBox;
    private static long cachedFrame = Long.MIN_VALUE;
    private static long frameCounter;

    /** Called once per client frame so the cache cannot outlive a frame. */
    public static void onFrameBegin() {
        frameCounter++;
    }

    /**
     * The entity's drawn-model envelope, always a SUPERSET of its collision box.
     *
     * <p>Never throws: a missing renderer, a NaN box or a degenerate box all fall back to a
     * geometric estimate. Callers may treat the result as "no drawn pixel of this entity lies
     * outside this".
     */
    public static AABB envelope(Entity entity) {
        AABB collision = entity.getBoundingBox();
        if (AperturePassthroughLever.DISABLE_SEAM_RENDER_ENVELOPE) {
            return collision;
        }
        if (!entity.level().isClientSide()) {
            // Server tick path: no renderer exists. Estimate from the entity's own dimensions —
            // a model rarely exceeds its box by more than its own width/height.
            return collision.inflate(
                SEAM_CLIP_SAFETY + Math.max(entity.getBbWidth(), entity.getBbHeight()));
        }
        if (cachedEntity == entity && cachedFrame == frameCounter && cachedBox != null) {
            return cachedBox;
        }
        AABB result = computeClientEnvelope(entity, collision);
        cachedEntity = entity;
        cachedBox = result;
        cachedFrame = frameCounter;
        return result;
    }

    private static AABB computeClientEnvelope(Entity entity, AABB collision) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getEntityRenderDispatcher() == null) {
                return collision.inflate(0.5 + SEAM_CLIP_SAFETY);
            }
            EntityRenderer<?, ?> renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
            if (renderer == null) {
                return collision.inflate(0.5 + SEAM_CLIP_SAFETY);
            }
            AABB culling = ((com.warwa.seamlessportals.mixin.client.EntityRendererCullingBoxInvoker)
                renderer).seamlessportals$getBoundingBoxForCulling(entity);
            if (culling == null || culling.hasNaN() || culling.getSize() == 0.0) {
                // Vanilla's own fallback shape for a degenerate culling box.
                culling = new AABB(
                    entity.getX() - 2, entity.getY() - 2, entity.getZ() - 2,
                    entity.getX() + 2, entity.getY() + 2, entity.getZ() + 2);
            }
            // LivingEntity scale multiplies the drawn model but not always the culling box.
            float scale = 1.0f;
            if (entity instanceof LivingEntity living) {
                scale = Math.max(1.0f, living.getScale());
            }
            AABB inflated = culling.inflate((0.5 + SEAM_CLIP_SAFETY) * scale);
            // Union with the collision box: the envelope must never be SMALLER than the box, or
            // a predicate that used to fire would silently stop firing.
            return union(inflated, collision);
        }
        catch (Throwable t) {
            // Never let an envelope lookup break rendering — degrade to the geometric estimate.
            return collision.inflate(0.5 + SEAM_CLIP_SAFETY);
        }
    }

    private static AABB union(AABB a, AABB b) {
        return new AABB(
            Math.min(a.minX, b.minX), Math.min(a.minY, b.minY), Math.min(a.minZ, b.minZ),
            Math.max(a.maxX, b.maxX), Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
    }
}
