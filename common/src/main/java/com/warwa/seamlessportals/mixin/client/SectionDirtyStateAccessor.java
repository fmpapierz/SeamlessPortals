package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.SectionUpdateTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * S14.49 (the superflat boundary-shadow hunt): read access to a section's dirty flags so the
 * {@code debug_dump_light_section} lever can report whether the tracker still owes the section a
 * remesh — the light-data-wrong vs mesh-stale-light discriminator.
 */
@Mixin(SectionUpdateTracker.SectionDirtyState.class)
public interface SectionDirtyStateAccessor {

    @Accessor("isDirty")
    boolean seamlessportals$isDirty();

    @Accessor("isDirtyFromPlayer")
    boolean seamlessportals$isDirtyFromPlayer();
}
