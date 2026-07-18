package com.warwa.seamlessportals.mixin;

import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker for {@code FireworkRocketEntity.isAttachedToEntity()} (private,
 * FireworkRocketEntity.java:256) — true for an ELYTRA-BOOST firework, which is
 * an invisible entity glued to the gliding player (set in the boost ctor at
 * :78, never cleared, and deliberately not rendered via
 * {@code shouldRender && !isAttachedToEntity}).
 *
 * <p>Used by {@link com.warwa.seamlessportals.entity.ProjectilePortalHandler}
 * to SKIP crossing an attached boost firework: it follows the player's position
 * each tick, so its per-tick segment crosses the portal plane when the player
 * does — and without this guard the projectile handler recreated it in the
 * destination as a FREE, visible rocket pointing straight up (attachment is not
 * persisted through the cross-dim NBT recreate). Vanilla loses the boost rocket
 * through a portal anyway; the orphan self-explodes harmlessly in the old dim.
 */
@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketEntityAccessor {

    @Invoker("isAttachedToEntity")
    boolean seamlessportals$isAttachedToEntity();

    /**
     * S14.47: the live attached-entity reference (private field, may be null even when the
     * DATA_ATTACHED_TO_TARGET flag is set — vanilla lazily re-resolves it). Used by the
     * player-crossing sweep to find and discard THIS player's boost rocket(s), whose reference
     * survives the cross-dim swap under player reuse.
     */
    @org.spongepowered.asm.mixin.gen.Accessor("attachedToEntity")
    net.minecraft.world.entity.LivingEntity seamlessportals$getAttachedToEntity();
}
