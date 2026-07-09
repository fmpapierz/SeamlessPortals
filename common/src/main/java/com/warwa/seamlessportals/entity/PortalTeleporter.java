package com.warwa.seamlessportals.entity;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public class PortalTeleporter {

    /**
     * Teleport a non-player entity (item, mob, animal) or run the player
     * server-first fallback. {@code moveFrom}/{@code moveTo} are the movement
     * segment that detected the crossing (EntityMixin plane-segment detection),
     * used to sign the exit exactly as the player path does.
     */
    public static boolean teleportEntity(Entity entity, PortalLink link, Vec3 moveFrom, Vec3 moveTo) {
        if (entity.level().isClientSide()) return false;

        PortalInfo destPortal = link.getDestination();
        ResourceKey<Level> destDim = destPortal.getDimension();

        MinecraftServer server = entity.level().getServer();
        if (server == null) return false;

        ServerLevel destLevel = server.getLevel(destDim);
        if (destLevel == null) {
            SeamlessPortalsConstants.LOGGER.warn("Destination level {} not found", destDim.identifier());
            return false;
        }

        if (entity instanceof ServerPlayer player) {
            return teleportPlayer(player, link);
        }

        // NON-PLAYER LANDING (2026-07-08, the "thrown items vanish / burn in
        // lava / can't be picked up" fix). Route items and mobs through the
        // EXACT same motion-signed landing the player uses, instead of the bare
        // depth-negating single-arg transformTeleportPosition + depth-negated
        // transformVelocity this method used before. That old pairing landed a
        // crossed entity ~0.2 blocks PAST the plane on the side OPPOSITE the
        // player's emergence (behind the destination portal-block column + its
        // wall — physically unreachable) with velocity pointing back through the
        // frame, so items drifted into the adjacent lava lake and burned (census:
        // ~59 of one burst destroyed) while survivors sat where the player could
        // never reach them. The player path (SeamlessServerTeleport.performCrossing)
        // does: exitSign = crossingDepthSign(segment); pos =
        // transformTeleportPosition(src, exitSign) [overshoot PAST the plane on the
        // motion side]; vel = transformVelocityMotion(vel) [same-sign, moving AWAY
        // from the plane]. Mirroring it makes a thrown item emerge exactly where
        // the player would — same reachable side, moving into the destination
        // (away from the portal-frame lava) — the IP-faithful behavior (IP applies
        // an exit overshoot along the motion direction to every regular entity).
        double exitSign = link.crossingDepthSign(moveFrom, moveTo);
        if (exitSign == 0.0 || Double.isNaN(exitSign)) {
            exitSign = link.crossingDepthSignFromState(entity.position(), entity.getDeltaMovement());
        }
        Vec3 destPos = link.transformTeleportPosition(entity.position(), exitSign);
        Vec3 destVelocity = link.transformVelocityMotion(entity.getDeltaMovement());
        return teleportNonPlayer(entity, destLevel, destPos, destVelocity);
    }

    private static boolean teleportPlayer(ServerPlayer player, PortalLink link) {
        // IP-style: server-first fallback path (when client-initiated crossing
        // hasn't fired yet). Delegates to SeamlessServerTeleport which uses
        // vanilla teleportTo + sends ClientboundSeamlessMovePayload for
        // reconciliation. The client-first path goes directly from
        // SeamlessClientTeleport to the server via ClientPortalCrossingPayload.
        SeamlessServerTeleport.performCrossing(player, link);
        return true;
    }

    private static boolean teleportNonPlayer(Entity entity, ServerLevel destLevel,
                                              Vec3 destPos, Vec3 destVelocity) {
        TeleportTransition transition = new TeleportTransition(
            destLevel, destPos, destVelocity,
            entity.getYRot(), entity.getXRot(),
            TeleportTransition.DO_NOTHING
        );

        Entity newEntity = entity.teleport(transition);
        if (newEntity != null) {
            preserveTransientHurtState(entity, newEntity);
            // 2-tick anti-jitter dedup (2026-07-08 — was 300 ticks). The old
            // 15s cooldown existed as anti-loop armor for CONTAINMENT
            // detection (a recreated entity spawns inside the dest portal
            // volume and would re-fire every tick); EntityMixin now detects
            // by PLANE-SEGMENT crossing, and the recreated entity starts
            // with a null segment — it structurally cannot re-fire unless it
            // actually moves back through the plane, which is a legitimate
            // crossing. Two ticks only absorbs plane-straddling jitter (an
            // entity resting exactly on the plane), mirroring IP's 1-tick
            // teleportation dedup. This also ends the 15-second window in
            // which every freshly-crossed entity was INVISIBLE through the
            // portal (PortalEntityTracker skips cooldown-flagged entities
            // from the mirror) — the user-visible "items thrown through the
            // portal disappear".
            newEntity.setPortalCooldown(2);
        }
        return newEntity != null;
    }

    /**
     * Carry a mob's TRANSIENT hurt/panic state across the crossing (2026-07-08,
     * the "shot animal forgets it was hurt and stops running frantically after
     * being knocked through the portal" fix). A cross-dim {@code entity.teleport}
     * is a vanilla remove + recreate + NBT {@code restoreFrom}; fields with no
     * save codec reset to default on the new object. The critical one is
     * {@code lastDamageSource} (+ its {@code lastDamageStamp} 40-tick window):
     * {@code PanicGoal.shouldPanic()} reads {@code getLastDamageSource() != null}
     * (LivingEntity.java:1419), so on the recreated mob it is null → panic never
     * starts → the animal stops fleeing the instant it crosses. Copy it (plus
     * the i-frame timers and the brain HURT_BY memory for brain-based mobs) from
     * the old object onto the new one — both are the same {@code LivingEntity}
     * subtype (getType().create), and the new entity is fully added but has NOT
     * ticked yet, so these plain field writes are race-free. IP-faithful: IP
     * likewise recreates the entity, then patches the specific state it cares
     * about rather than doing a same-object move for AI mobs.
     *
     * <p>Notes: {@code lastDamageStamp} is compared against the SHARED server
     * {@code getGameTime()}, so copy it RAW (no rebase). A stale cross-dim entity
     * ref inside {@code lastDamageSource} is benign — {@code PanicGoal} only reads
     * the source's damage-TYPE tag, never dereferences the entity. Knockback
     * velocity is already carried by the motion-signed exit velocity above; do
     * NOT touch it. Items/XP/projectiles have none of this and are skipped by the
     * type guard.
     */
    private static void preserveTransientHurtState(Entity oldEntity, Entity newEntity) {
        if (!(oldEntity instanceof net.minecraft.world.entity.LivingEntity oldL)
                || !(newEntity instanceof net.minecraft.world.entity.LivingEntity newL)) {
            return;
        }
        com.warwa.seamlessportals.mixin.LivingEntityHurtAccessor oldA =
            (com.warwa.seamlessportals.mixin.LivingEntityHurtAccessor) oldL;
        com.warwa.seamlessportals.mixin.LivingEntityHurtAccessor newA =
            (com.warwa.seamlessportals.mixin.LivingEntityHurtAccessor) newL;
        // The panic trigger (the actual reported bug).
        newA.seamlessportals$setLastDamageSource(oldA.seamlessportals$getLastDamageSource());
        newA.seamlessportals$setLastDamageStamp(oldA.seamlessportals$getLastDamageStamp());
        // Completeness: i-frames + the red-hurt flash stay consistent.
        newA.seamlessportals$setLastHurt(oldA.seamlessportals$getLastHurt());
        newL.hurtTime = oldL.hurtTime;          // public; already survives NBT, kept paired
        newL.hurtDuration = oldL.hurtDuration;  // public; not serialized
        newL.invulnerableTime = oldL.invulnerableTime; // public (Entity); not serialized
        // Brain-based panic (villagers, axolotls, ...): HURT_BY has no codec, so
        // the persisted-memory NBT round-trip drops it — copy it live. MUST guard
        // with hasMemoryValue: Brain.getMemory THROWS IllegalStateException on an
        // UNREGISTERED slot (Brain.java:144), and plain goal-AI farm animals (cow,
        // chicken, pig, sheep — exactly the mobs this fix targets) use the default
        // empty brain with no HURT_BY slot, so an unguarded getMemory would crash
        // the crossing tick for them. hasMemoryValue returns false (no throw) when
        // the slot is absent, so getMemory only runs when it is registered-and-set;
        // the new entity is the same type so its brain has the slot too.
        if (oldL.getBrain().hasMemoryValue(
                net.minecraft.world.entity.ai.memory.MemoryModuleType.HURT_BY)) {
            oldL.getBrain()
                .getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HURT_BY)
                .ifPresent(src -> newL.getBrain()
                    .setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HURT_BY, src));
        }
    }
}
