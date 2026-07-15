package qouteall.imm_ptl.core.mixin.client.multiworld_awareness;

import net.minecraft.client.resources.sounds.BiomeAmbientSoundsHandler;
import org.spongepowered.asm.mixin.Mixin;

/**
 * S12-B port disposition: TARGET-GONE — <b>vanilla now solves IP's problem</b> (mixin-client.md §5).
 *
 * <p>IP 1.21.3 re-pointed a {@code @Mutable @Final biomeManager} field at
 * {@code player.level().getBiomeManager()} every tick, because {@code BiomeAmbientSoundsHandler} was
 * constructed once bound to a single world's biome manager while the player changed dimension without a
 * new handler.
 *
 * <p><b>26.2 reality:</b> the {@code biomeManager} field no longer exists. {@code tick()} now resolves
 * ambience <b>live from the player's current level every tick</b>:
 * {@code Level level = this.player.level(); ... level.environmentAttributes().getValue(
 * EnvironmentAttributes.AMBIENT_SOUNDS, this.player.position())} ({@code 26.2:BiomeAmbientSoundsHandler.java:45-49}).
 * IP's entire purpose is met by vanilla — <b>no injection is needed</b>. The mixin is retained as an
 * empty, inert body (structural fidelity to IP's file / the registered mixin set); re-verify once
 * in-game after a dimension switch (S13+). Held/unregistered until S13.
 */
@Mixin(BiomeAmbientSoundsHandler.class)
public class MixinBiomeAmbientSoundPlayer {
    // 26.2: the once-bound `biomeManager` field is gone; vanilla `tick()` reads ambience live from
    // `this.player.level()` each tick, so the IP re-point injection has nothing to do. Intentionally empty.
}
