package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.client.SeamlessClientTeleport;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.entity.EntityPortalCollision;
import com.warwa.seamlessportals.portal.PortalLink;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * IP-style client-initiated seamless teleport detection.
 *
 * <p>Runs once per client tick at the very start of {@code LocalPlayer.tick},
 * before any vanilla movement / portal-handling code. If the local player is
 * inside a portal that has a known {@link PortalLink}, trigger the
 * client-first visual swap via {@link SeamlessClientTeleport#performCrossing}
 * — which also notifies the server so it can do its authoritative cross-dim
 * move.
 *
 * <p>The server-side fallback in {@code EntityMixin.tick} still runs for all
 * entities; for the local player specifically, the client-first detection
 * should always win the race because it runs earlier in the frame and
 * doesn't wait for a packet round-trip. If the client misses the detection
 * (e.g. {@link PortalLink} hasn't been synced yet), the server falls back
 * ~50 ms later and the client performs a deferred swap on receiving
 * {@code ClientboundSeamlessMovePayload}.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void seamlessportals$clientPortalCrossingCheck(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;

        if (!SeamlessPortalsConfig.get().isSeamlessTeleportation()) return;

        // Build this tick's movement segment (prev end -> current) for PLANE-
        // CROSSING detection, then advance the stored origin for next tick.
        net.minecraft.world.phys.Vec3 currentPos = self.position();
        net.minecraft.world.phys.Vec3 lastPos = SeamlessClientTeleport.lastClientPos;
        SeamlessClientTeleport.lastClientPos = currentPos;

        // Post-swap cooldown: suppress detection for ~500ms after the last swap.
        // This avoids the rapid-back-and-forth "Network Protocol Error" where
        // stale chunk packets from the old dim overrun the reader of the freshly
        // swapped new-dim level. See SeamlessClientTeleport.POST_SWAP_COOLDOWN_NANOS.
        // It also covers the first post-swap tick, whose stale old-dim->new-dim
        // segment must not be evaluated.
        long sinceSwap = System.nanoTime() - SeamlessClientTeleport.lastSwapMonotonicNanos;
        if (sinceSwap < SeamlessClientTeleport.POST_SWAP_COOLDOWN_NANOS) {
            return;
        }

        if (lastPos == null) return; // first tick — no movement segment yet

        // IP-style PLANE-CROSSING detection (replaces bounding-box containment).
        // A player who lands embedded in the destination portal after a crossing
        // is NOT straddling a portal plane between ticks, so this does not re-fire
        // — which is what kills the infinite overworld<->nether teleport
        // oscillation (the freeze). The player can still immediately walk back
        // through (crossing the plane again) to return.
        Optional<PortalLink> linkOpt =
            EntityPortalCollision.findPortalCrossing(self, lastPos, currentPos);
        if (linkOpt.isEmpty()) return;

        // Crossing-flash tracer: mark detection + arm the post-crossing trace dump.
        com.warwa.seamlessportals.render.CrossingTracer.event(String.format(
            "DETECT plane-crossing portal=%s from=(%.2f,%.2f,%.2f) to=(%.2f,%.2f,%.2f)",
            linkOpt.get().getSource().getOrigin().toShortString(),
            lastPos.x, lastPos.y, lastPos.z, currentPos.x, currentPos.y, currentPos.z));
        com.warwa.seamlessportals.render.CrossingTracer.armDump();

        SeamlessClientTeleport.performCrossing(linkOpt.get());
        // Start next tick's segment from the post-swap position (defensive — also
        // done in doVisualSwap) so the teleport jump is not a "movement" crossing.
        SeamlessClientTeleport.lastClientPos = self.position();
    }
}
