package com.warwa.seamlessportals.mixin.passthrough;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code MinecartBehavior.minecart} to (d)'s rail bridge, which needs the cart itself for
 * the straddle test (a read cannot be shown to be a crossing without knowing who is asking).
 *
 * <p>An accessor rather than a {@code @Shadow} in {@code MixinOldMinecartBehaviorSeamRail}:
 * the field is declared on the SUPERCLASS ({@code MinecartBehavior}, javap-verified
 * {@code protected final AbstractMinecart minecart}) and Mixin resolves {@code @Shadow} fields
 * against the target class ONLY — the shadow attempt failed at apply time with
 * "@Shadow field minecart was not located in the target class OldMinecartBehavior".
 */
@Mixin(MinecartBehavior.class)
public interface MinecartBehaviorAccessor {

    @Accessor("minecart")
    AbstractMinecart seamlessportals$minecart();
}
