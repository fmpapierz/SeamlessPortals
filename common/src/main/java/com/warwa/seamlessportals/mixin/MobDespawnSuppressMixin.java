package com.warwa.seamlessportals.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.warwa.seamlessportals.entity.PortalTicketDespawnSuppressor;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * §2g PORTAL-TICKET DESPAWN SUPPRESS (2026-07-25) — user-requested deviation-from-IP
 * (upstream 1.21.3 has the identical bug: zero despawn handling, dual-tree proven). The mod's
 * portal tickets hold dest chunks ENTITY_TICKING (ImmPtlChunkTickets: FLAG_LOADING|SIMULATION,
 * NO_TIMEOUT, per-chunk addTicketWithRadius(2) => level 31; same-dim included), and 26.2
 * ServerLevel.java:425-431 runs checkDespawn for EVERY entityTickList member UNGATED (only
 * tickNonPassenger at :433-445 is re-gated by inEntityTickingRange) — so a portal-held mob
 * >128 from every player is DISCARDED (save=false, permanent) where no-mod vanilla would
 * have left the chunk UNLOADED and the mob SAVED. Fix restores that exact outcome: wrap
 * BOTH removeWhenFarAway call sites in checkDespawn (Mob.java:696 hard leg, :702 band leg;
 * javap offsets 99/163 — no ordinal, both matched) and return false — the vanilla
 * passive-animal value — only when the mob's chunk is PORTAL-FED in our ticket bookkeeping
 * (NOT raw membership — the player's own view square is also in the map; suppressing there
 * would disable vanilla's >128 monster churn around every player, the verify-fold FIX-1
 * catch) and no player is within the category despawn distance. Peaceful discard and the
 * <32 noActionTime reset run untouched; any player inside 128 makes every leg byte-vanilla
 * (arithmetic guard exits before any lookup). On ticket drop the chunk unloads =>
 * UNLOADED_TO_CHUNK (shouldSave()=true) — the no-mod parity outcome; the drop window is
 * proven ZERO-TICK (purge and entityTickList removal complete before the entity loop of the
 * next tick). checkDespawn overrides are only WitherBoss/EnderDragon (deliberate no-despawn)
 * + ShulkerBullet (not a Mob) — symptom-class coverage complete. Weaves in BOTH flag states
 * (flag-OFF the map is empty => byte-vanilla); client-side inert (suppressor instanceof
 * ServerLevel guard).
 */
@Mixin(Mob.class)
public abstract class MobDespawnSuppressMixin {

    @WrapOperation(
        method = "checkDespawn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;removeWhenFarAway(D)Z"
        )
    )
    private boolean seamlessportals$suppressPortalTicketDespawn(
        Mob mob, double distSqr, Operation<Boolean> original
    ) {
        if (PortalTicketDespawnSuppressor.shouldSuppress(mob, distSqr)) {
            return false;
        }
        return original.call(mob, distSqr);
    }
}
