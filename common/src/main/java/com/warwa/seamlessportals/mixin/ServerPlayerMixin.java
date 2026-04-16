package com.warwa.seamlessportals.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Vanilla portal behavior is suppressed by NetherPortalBlockMixin cancelling
 * entityInside(). No additional tick-level intervention needed.
 *
 * The seamless teleportation is handled entirely by EntityMixin.move().
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
}
