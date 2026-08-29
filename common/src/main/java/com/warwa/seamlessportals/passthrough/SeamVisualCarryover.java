package com.warwa.seamlessportals.passthrough;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * ★ CART CROSS-DIM SMOOTHNESS (2026-08-24, the round-7 log's verdict) — carry the departing
 * client instance's VISUAL STATE across the dimension switch.
 *
 * <p><b>The measured defect.</b> A same-dim seam crossing is smooth because the client entity
 * OBJECT persists: the F6 rebase in {@code ClientTeleportationManager.updateEntityPos} maps its
 * stored visual (position, last-tick position, lerp target, velocity) through the portal
 * transform and every frame-to-frame delta survives. Cross-dim, the server's
 * {@code changeEntityDimension} recreate arrives at the client as REMOVE(source level) +
 * ADD(dest level, at the arrival point) + the rebase RPC — in that order, same tick. By RPC
 * time the source instance and its whole visual history are gone, the fresh instance's visual
 * IS the arrival point, and the rebase's idempotency guard correctly reports
 * "already carried" and leaves it there (round-7 log: {@code REBASE-SKIP … visual == server}
 * on every cross-dim crossing, while the client's last-rendered source frame sat ~0.9 blocks
 * short of the plane — the user's "touches the seam, disappears, reappears": a freeze, a
 * blink, and a ~1.2-block forward pop).
 *
 * <p><b>The mechanism.</b> The client-side analogue of the server's
 * {@code newEntity.restoreFrom(oldEntity)}: when a seam-engaged entity (colliding with a seam
 * face, or seam-anchored) is removed from a client level, its visual state is stashed by id.
 * When the crossing RPC then finds the fresh destination instance, the stash is transformed
 * through the crossing portal and applied — position and last-tick position continue the
 * departure path exactly, velocity is carried, and the interpolation steers from the carried
 * point to the server's authoritative arrival, so the on-screen path is continuous through the
 * dimension flip just as it already is same-dim. The position CODEC still takes the server's
 * authoritative base (unchanged caller code): only the visual is carried.
 *
 * <p>Consume-on-use: a stash is removed when applied, so a duplicate or rider-echo RPC falls
 * through to the existing idempotency guard (whose "already carried" verdict is then correct).
 * Stale stashes (a despawn that never was a crossing) expire after {@link #STASH_TTL_MS} and
 * are pruned on the next insert. Players are never stashed — the player paths have their own
 * machinery.
 *
 * <p>Lever: {@code -PdisableSeamVisualCarryover} (default ON). With the lever off, every
 * cross-dim crossing degrades to the measured pre-fix pop.
 */
public final class SeamVisualCarryover {

    private SeamVisualCarryover() {}

    /** A despawn stash outlives any legitimate crossing handoff by a wide margin at 1s. */
    private static final long STASH_TTL_MS = 1000L;

    private record Stash(
        ResourceKey<Level> dim,
        Vec3 pos,
        Vec3 lastTickPos,
        Vec3 velocity,
        @Nullable Vec3 lerpTarget,
        long stampMs
    ) {}

    private static final java.util.Map<Integer, Stash> STASHES =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Client-level entity removal hook ({@code ClientLevel.removeEntity} HEAD, every level
     * instance — played and far alike). Stashes only seam-engaged non-player entities: exactly
     * the set that can be mid-crossing when the cross-dim recreate's REMOVE lands.
     */
    public static void onClientEntityRemoved(Entity e) {
        if (AperturePassthroughLever.DISABLE_SEAM_VISUAL_CARRYOVER
            || e instanceof Player
            || e instanceof Portal) {
            return;
        }
        if (((IEEntity) e).ip_getCollidingPortal() == null
            && SeamCrossingRule.anchorOf(e) == null) {
            return;
        }
        long now = System.currentTimeMillis();
        STASHES.values().removeIf(s -> now - s.stampMs() > STASH_TTL_MS);
        InterpolationHandler interp = e.getInterpolation();
        STASHES.put(e.getId(), new Stash(
            e.level().dimension(),
            e.position(),
            McHelper.lastTickPosOf(e),
            McHelper.getWorldVelocity(e),
            (interp != null && interp.hasActiveInterpolation()) ? interp.position() : null,
            now
        ));
        SeamCartProbe.event(e, "CARRY-STASH dim=" + e.level().dimension().identifier()
            + " visual=" + e.position() + " lastTick=" + McHelper.lastTickPosOf(e));
    }

    /**
     * The crossing RPC's cross-dim continuity: if the fresh destination instance has a stashed
     * departure visual from the crossing portal's SOURCE side, map it through the portal and
     * apply. Returns true when applied (the caller then skips the same-dim rebase, whose
     * "already carried" guard would otherwise leave the fresh spawn's zero-history visual).
     */
    public static boolean applyIfStashed(Entity fresh, Portal crossingPortal, Vec3 serverPos) {
        if (AperturePassthroughLever.DISABLE_SEAM_VISUAL_CARRYOVER) {
            return false;
        }
        Stash s = STASHES.remove(fresh.getId());
        if (s == null) {
            return false;
        }
        if (s.dim() != crossingPortal.level().dimension()
            || System.currentTimeMillis() - s.stampMs() > STASH_TTL_MS) {
            SeamCartProbe.event(fresh, "CARRY-DROP (stash dim=" + s.dim().identifier()
                + " vs portal dim=" + crossingPortal.level().dimension().identifier() + ")");
            return false;
        }
        Vec3 mapped = crossingPortal.transformPoint(s.pos());
        Vec3 mappedLast = crossingPortal.transformPoint(s.lastTickPos());
        McHelper.setPosAndLastTickPos(fresh, mapped, mappedLast);
        McHelper.updateBoundingBox(fresh);
        McHelper.setWorldVelocity(fresh, crossingPortal.transformLocalVec(s.velocity()));
        InterpolationHandler interp = fresh.getInterpolation();
        if (interp != null) {
            // ★ SPEED CONTINUITY (2026-08-24, the "slight drag" fix): aim at the CURRENT
            // authoritative server position — never the stashed (one-tick-stale) lerp target.
            // The vanilla lerp's steady state trails the server by stepCount ticks of motion
            // with each tick's step equal to exactly v, PROVIDED each tick's target is the
            // latest server pos; the carried visual is already at that steady-state gap, so
            // this handoff tick becomes cadence-identical to every ordinary tick (measured:
            // server hSpeed monotonic 1.2883→1.3274 straight through the teleport, per-tick
            // z-steps exactly 0.400 — the dip was purely the shortened first lerp aims).
            // Rotation stays the server's (the spawn packet already applied the seam's
            // rotation, if any).
            interp.interpolateTo(serverPos, fresh.getYRot(), fresh.getXRot());
        }
        SeamCartProbe.event(fresh, "CARRY-APPLY via portal " + crossingPortal.getId()
            + " visual=" + mapped + " server=" + serverPos);
        return true;
    }
}
