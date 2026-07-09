package com.warwa.seamlessportals.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessors for {@code LivingEntity}'s TRANSIENT hurt state that the vanilla
 * cross-dimension teleport (remove + recreate + NBT restoreFrom) DROPS because
 * these fields have no save codec:
 * <ul>
 *   <li>{@code lastDamageSource} (LivingEntity.java:269, private) — THE panic
 *       trigger. {@code PanicGoal.shouldPanic()} reads
 *       {@code getLastDamageSource() != null}; on the recreated mob it is null,
 *       so a shot animal stops fleeing the instant it crosses a portal.</li>
 *   <li>{@code lastDamageStamp} (LivingEntity.java:270, private) — the 40-tick
 *       validity window for the above, compared against the SHARED server
 *       {@code getGameTime()} (LivingEntity.java:1420), so the raw stamp is
 *       copied verbatim across dimensions (do NOT rebase).</li>
 *   <li>{@code lastHurt} (LivingEntity.java:247, protected) — the i-frame
 *       partial-damage compare, copied for hurt-response consistency.</li>
 * </ul>
 *
 * <p>Used by {@link com.warwa.seamlessportals.entity.PortalTeleporter} to copy
 * this state from the old entity onto the recreated one right after
 * {@code entity.teleport(...)}, so a mob keeps panicking/fleeing across a
 * crossing. {@code hurtTime}/{@code hurtDuration} (public, :230-231) and
 * {@code invulnerableTime} (public on Entity, :252) need no accessor.
 */
@Mixin(LivingEntity.class)
public interface LivingEntityHurtAccessor {

    @Accessor("lastDamageSource")
    DamageSource seamlessportals$getLastDamageSource();

    @Accessor("lastDamageSource")
    void seamlessportals$setLastDamageSource(DamageSource source);

    @Accessor("lastDamageStamp")
    long seamlessportals$getLastDamageStamp();

    @Accessor("lastDamageStamp")
    void seamlessportals$setLastDamageStamp(long stamp);

    @Accessor("lastHurt")
    float seamlessportals$getLastHurt();

    @Accessor("lastHurt")
    void seamlessportals$setLastHurt(float lastHurt);
}
