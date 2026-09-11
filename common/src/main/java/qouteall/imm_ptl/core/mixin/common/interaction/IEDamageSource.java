package qouteall.imm_ptl.core.mixin.common.interaction;

import net.minecraft.core.Holder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * ★ CROSS-PORTAL ENTITY HIT knockback fix (2026-09-10) — constructor {@code @Invoker} for the
 * ALL-FIELDS {@code DamageSource} ctor, the only one that takes an explicit
 * {@code damageSourcePosition} alongside the entities ({@code 26.2:DamageSource.java:34} —
 * private on the loom jar; NeoForge ATs it public, so the invoker is a no-op widening there).
 * {@code getSourcePosition()} prefers the explicit position over {@code directEntity.position()}
 * ({@code :102-108}), which is exactly the seam the knockback fix needs: a melee attack routed
 * through a portal carries the PORTAL-TRANSFORMED attacker position, and every consumer of the
 * source position — base knockback direction ({@code LivingEntity.dealDefaultKnockback:1297}),
 * the damage-tilt indicator, shield blocking angles — reads the frame the hit geometrically came
 * from. Same self-contained idiom as {@link
 * qouteall.imm_ptl.core.mixin.client.interaction.IEUseOnContext}.
 */
@Mixin(DamageSource.class)
public interface IEDamageSource {
    @Invoker("<init>")
    static DamageSource ip_create(
        Holder<DamageType> type,
        @Nullable Entity directEntity,
        @Nullable Entity causingEntity,
        @Nullable Vec3 damageSourcePosition
    ) {
        throw new AssertionError();
    }
}
