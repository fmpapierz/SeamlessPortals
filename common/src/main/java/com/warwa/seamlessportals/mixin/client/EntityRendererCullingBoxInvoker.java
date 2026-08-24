package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * ★ ROUND 35 — access to vanilla's own FRUSTUM-CULL BOX, the only contractual upper bound on an
 * entity's DRAWN extent that 26.2 exposes.
 *
 * <p><b>Why this is needed.</b> The seam clips the drawn MODEL with a hardware plane, while every
 * seam predicate measures the COLLISION BOX — and the gap between them is per entity type. A cow's
 * muzzle reaches 0.9375 blocks from its origin against a 0.45 box half; a minecart's shell only
 * 0.625. The user's requirement is that every fix work for "every single type of rider, entity,
 * literally everything, not just cows", so the correction must be DERIVED PER ENTITY at runtime,
 * never hardcoded from whichever fixture happened to be on the test track.
 *
 * <p><b>Why this API and not another.</b> A survey of 26.2 (javap against the loom deobf jar)
 * found no true model-extent API: {@code EntityRenderState} carries only collision-derived sizes;
 * {@code Entity} has no {@code getBoundingBoxForCulling} at all in 26.2 (only {@code Display}
 * does); {@code ModelPart.getExtentsForGui} needs a posed model that boats, item frames, falling
 * blocks and display entities never have, and excludes every RenderLayer (elytra, banners, held
 * items, armour). What remains is this: the box vanilla itself frustum-tests in
 * {@code shouldRender}. Frustum culling is only CORRECT if nothing of the entity draws outside it,
 * so it is a contractual upper bound on the drawn model for every renderable entity — vanilla or
 * modded — and Mojang authors real per-type widenings into it (Illusioner +3.0 lateral, Sniffer
 * +0.6, dragon-head hat +0.5, minecart display block, HappyGhast −Y, ThrownTrident +1.5, Display
 * forwarding to its own). That per-species variation is exactly what a hardcoded constant is blind
 * to.
 *
 * <p>javap-verified before first launch, per the house rule:
 * {@code protected net.minecraft.world.phys.AABB getBoundingBoxForCulling(T)} — protected, hence
 * this invoker; not synthetic-bridged.
 *
 * @see com.warwa.seamlessportals.passthrough.SeamRenderExtent
 */
@Mixin(EntityRenderer.class)
public interface EntityRendererCullingBoxInvoker {

    @Invoker("getBoundingBoxForCulling")
    AABB seamlessportals$getBoundingBoxForCulling(Entity entity);
}
