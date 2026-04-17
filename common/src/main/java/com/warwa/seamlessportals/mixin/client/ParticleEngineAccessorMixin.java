package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Direct field access for ParticleEngine.level, bypassing the public
 * {@code setLevel(ClientLevel)} method which calls {@code clearParticles()}
 * and clears {@code trackingEmitters} as a side effect.
 *
 * <p>{@code setLevel} is the right call when actually changing worlds
 * (e.g. {@link com.warwa.seamlessportals.mixin.client.HandleRespawnMixin}).
 * For the per-frame swap done in
 * {@link com.warwa.seamlessportals.render.PortalContextSwitch#withSwitchedWorld},
 * we need a setter that just rebinds the field without wiping the
 * source level's particles every frame.
 *
 * <p>Mirrors IP's {@code IEParticleManager.setWorld_} pattern (the trailing
 * underscore in IP signals "raw, no side effects").
 *
 * <p>Target field (verified from ParticleEngine bytecode in 26.1.2):
 *   {@code protected ClientLevel level}
 */
@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessorMixin {

    @Accessor("level")
    ClientLevel seamlessportals$getLevel();

    @Accessor("level")
    @Mutable
    void seamlessportals$setLevel(ClientLevel level);
}
