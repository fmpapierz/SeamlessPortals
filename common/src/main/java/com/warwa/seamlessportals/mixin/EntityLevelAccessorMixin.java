package com.warwa.seamlessportals.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the protected {@code Entity.setLevel(Level)} so Step 3's LocalPlayer
 * preservation can re-point an outgoing player's level reference to the
 * destination {@link net.minecraft.client.multiplayer.ClientLevel} without
 * allocating a new player instance.
 *
 * Target (verified from Entity.java line 3996):
 *   protected void setLevel(Level level) { this.level = level; }
 */
@Mixin(Entity.class)
public interface EntityLevelAccessorMixin {
    @Invoker("setLevel")
    void seamlessportals$invokeSetLevel(Level level);

    /**
     * Clear the entity's removal reason. When we reuse a LocalPlayer across a
     * dim change, the downstream {@code ClientLevel.addEntity} call in
     * {@code ClientPacketListener.handleRespawn} begins with
     * {@code removeEntity(id, DISCARDED)} which marks our preserved entity as
     * removed. Unsetting the flag immediately after restores it to active.
     *
     * Target (verified from Entity.java line 3876):
     *   protected void unsetRemoved() { this.removalReason = null; }
     */
    @Invoker("unsetRemoved")
    void seamlessportals$invokeUnsetRemoved();
}
