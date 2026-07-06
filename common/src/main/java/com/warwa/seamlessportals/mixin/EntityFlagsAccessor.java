package com.warwa.seamlessportals.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Static accessor for {@code Entity.DATA_SHARED_FLAGS_ID} (protected static;
 * Entity.java:255) — the shared-flags byte carrying sneak(1), sprint(3),
 * swim(4), invisible(5), glow(6), fall-flying(7).
 *
 * <p>Used by {@code SeamlessServerTeleport.performCrossing} to force-redirty
 * the flags after a crossing so a guaranteed post-respawn resync packet goes
 * out: the one-shot dirty-sync model means a flag packet lost in the client's
 * crossing window (see ClientPacketListenerLocalPlayerFallbackMixin) would
 * otherwise never be retransmitted.
 */
@Mixin(Entity.class)
public interface EntityFlagsAccessor {

    @Accessor("DATA_SHARED_FLAGS_ID")
    static EntityDataAccessor<Byte> seamlessportals$getSharedFlagsId() {
        throw new AssertionError("mixin accessor not applied");
    }
}
