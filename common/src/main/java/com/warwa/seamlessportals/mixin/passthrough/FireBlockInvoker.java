package com.warwa.seamlessportals.mixin.passthrough;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * ★ D1 (dest fire lives, user order 2026-08-10) — access to vanilla's fire tick delay
 * ({@code private static int getFireTickDelay(RandomSource)}, javap-verified). The mirror writes
 * with {@code UPDATE_SKIP_ON_PLACE} (mandatory for mirror authority), which skips
 * {@code FireBlock.onPlace} — the ONLY site that schedules a fire's first tick — so a mirrored
 * fire never ticked, never spread, never aged, in any topology. {@code SeamMirror} schedules the
 * initial tick explicitly after a fire write, using vanilla's own delay.
 */
@Mixin(FireBlock.class)
public interface FireBlockInvoker {

    @Invoker("getFireTickDelay")
    static int seamlessportals$getFireTickDelay(RandomSource random) {
        throw new AssertionError("mixin invoker");
    }
}
