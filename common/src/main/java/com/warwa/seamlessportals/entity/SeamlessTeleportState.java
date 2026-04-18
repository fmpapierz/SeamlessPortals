package com.warwa.seamlessportals.entity;

/**
 * Interface implemented by {@code EntityMixin} so other code can read/write
 * the entity-local {@code justTeleported} flag without routing through a
 * static map or reflection.
 *
 * Cast any {@link net.minecraft.world.entity.Entity} instance to this type
 * at runtime — the mixin installs the implementation.
 */
public interface SeamlessTeleportState {
    boolean seamlessportals$isJustTeleported();
    void seamlessportals$setJustTeleported(boolean value);
}
