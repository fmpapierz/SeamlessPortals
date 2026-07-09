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
 * <p>The client-first detection here is the ONLY path that crosses the local
 * player: {@code EntityMixin.tick} returns early for {@code ServerPlayer}
 * (line 86) so its server-side detector never runs for players, and vanilla
 * portal travel is suppressed. THERE IS CURRENTLY NO SERVER-SIDE PLAYER
 * FALLBACK (the {@code SeamlessServerTeleport.performCrossing(player, link)}
 * server-first dispatch exists but is unreachable — no code detects a player
 * crossing on the server). So if the client misses the detection — e.g. a
 * hitch, or {@link PortalLink} not yet synced, or the visual swap failing when
 * the dest renderer isn't cached yet ({@code SeamlessClientTeleport} returns
 * before emitting the crossing payload) — the player is SILENTLY NOT
 * teleported (no vanilla purple-overlay fallback either). Adding a debounced
 * server-side player crossing detector that invokes the existing
 * {@code performCrossing} dispatch as a reliability net is a tracked item for
 * the entity-portal migration's crossing rework (task #11). Do NOT rely on a
 * server fallback existing today.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    /**
     * Sprint keeper: runs AFTER the vanilla tick (and thus after aiStep's sprint-stop
     * checks), so a sprint cancelled by the first post-swap tick is re-asserted the
     * same tick — the speed modifier is back before the next frame renders.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void seamlessportals$sprintKeeperTick(CallbackInfo ci) {
        SeamlessClientTeleport.tickSprintKeeper((LocalPlayer) (Object) this);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void seamlessportals$clientPortalCrossingCheck(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;

        if (!SeamlessPortalsConfig.get().isSeamlessTeleportation()) return;

        // Build this tick's movement segment (prev end -> current) for PLANE-
        // CROSSING detection, then advance the stored origin for next tick.
        net.minecraft.world.phys.Vec3 currentPos = self.position();
        net.minecraft.world.phys.Vec3 lastPos = SeamlessClientTeleport.lastClientPos;
        SeamlessClientTeleport.lastClientPos = currentPos;

        // NO post-swap cooldown (full IP parity, 2026-07-04). Its old jobs are covered:
        // the stale-chunk decode crash by ChunkPacketGuardMixin (drop, not disconnect); late
        // reconciles from superseded crossings by the swapSeq stale-guard in
        // handleServerReconcile; and the first post-swap tick's teleport-jump segment by
        // doVisualSwap resetting lastClientPos to the landing position.

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

        SeamlessClientTeleport.performCrossing(linkOpt.get(), lastPos, currentPos);
        // Start next tick's segment from the post-swap position (defensive — also
        // done in doVisualSwap) so the teleport jump is not a "movement" crossing.
        SeamlessClientTeleport.lastClientPos = self.position();
    }
}
