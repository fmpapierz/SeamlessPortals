package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * IP context switch: client.particleEngine.setWorld(newWorld)
 * Particles in the portal view must use the destination dimension's level.
 */
@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessorMixin {

    @Accessor("level")
    ClientLevel seamlessportals$getLevel();

    @Accessor("level")
    void seamlessportals$setLevel(ClientLevel level);
}
