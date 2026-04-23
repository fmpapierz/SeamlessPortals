package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.client.PortalWorldManager;
import com.warwa.seamlessportals.network.ModPayloads;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Client-side applier for entity mirror payloads from {@code PortalEntityTracker}.
 *
 * <p>Apply targets: {@link PortalWorldManager#getLevel(ResourceKey)} for the
 * dim named in the payload — this is the cached {@link ClientLevel} that
 * renders inside portal views. Silently drops updates for dims we don't
 * have a cached level for (no portal to that dim has ever been rendered).
 *
 * <p>Phase 2a MVP: raw adds / moves / removes. Entity data (synced-data,
 * equipment, effects) is deferred to Phase 2b.
 */
public final class RemoteEntityApplier {

    private RemoteEntityApplier() {}

    private static int addCount = 0;
    private static int moveCount = 0;

    public static void applyAdd(ModPayloads.RemoteEntityAddPayload p) {
        ResourceKey<Level> dim = parseDimensionKey(p.dimensionId());
        if (dim == null) return;
        ClientLevel level = PortalWorldManager.getLevel(dim);
        if (level == null) return;

        // If an entity with this id already exists (e.g. stale from a
        // previous visit), replace rather than spawn two. level.removeEntity
        // with DISCARDED reason cleanly evicts it and its render state.
        Entity existing = level.getEntity(p.entityId());
        if (existing != null) {
            level.removeEntity(p.entityId(), Entity.RemovalReason.DISCARDED);
        }

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.byId(p.entityTypeId());
        if (type == null) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS LIVE ENT] Unknown entity type id {} for dim {}",
                p.entityTypeId(), p.dimensionId());
            return;
        }

        Entity entity = type.create(level, EntitySpawnReason.LOAD);
        if (entity == null) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS LIVE ENT] Entity type {} refused to create",
                BuiltInRegistries.ENTITY_TYPE.getKey(type));
            return;
        }

        entity.setId(p.entityId());
        entity.setUUID(p.uuid());
        entity.syncPacketPositionCodec(p.x(), p.y(), p.z());
        entity.absSnapTo(p.x(), p.y(), p.z(), p.yRot(), p.xRot());
        entity.setYHeadRot(p.yHeadRot());
        // Do NOT apply server velocity. If we did, Entity.tick() on the
        // cached level would integrate that velocity every client tick,
        // drifting the mob forward between the 20 Hz Move snapshots and
        // oscillating when each snapshot reverses the drift. Mirror is
        // position-driven, not velocity-driven — server Move payloads at
        // 20 Hz are the sole source of truth for position.
        entity.setDeltaMovement(0, 0, 0);
        // Mark as no-physics so Entity.tick()'s gravity/collision doesn't
        // drift the mirror between server Move snapshots. Without this,
        // each client tick adds -0.08 * dt² to y via gravity, entity
        // moves, next Move snap resets it — observed as random
        // "run-around" and flicker on the mirrored entity.
        entity.noPhysics = true;
        // Ignore incoming velocity payload field:
        if (false) {
            entity.setDeltaMovement(p.vx(), p.vy(), p.vz());
        }

        // addEntity is a ClientLevel hook; in 26.1.2 it's
        // ClientLevel.addEntity(Entity) and registers the entity with the
        // level's entity-getter for lookup by id.
        level.addEntity(entity);

        addCount++;
        if (addCount <= 3 || addCount % 100 == 0) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS LIVE ENT] add #{}: {} id={} uuid={} at ({},{},{}) in {}",
                addCount,
                BuiltInRegistries.ENTITY_TYPE.getKey(type),
                p.entityId(), p.uuid(),
                String.format("%.2f", p.x()), String.format("%.2f", p.y()), String.format("%.2f", p.z()),
                p.dimensionId());
        }
    }

    public static void applyMove(ModPayloads.RemoteEntityMovePayload p) {
        ResourceKey<Level> dim = parseDimensionKey(p.dimensionId());
        if (dim == null) return;
        ClientLevel level = PortalWorldManager.getLevel(dim);
        if (level == null) return;

        Entity entity = level.getEntity(p.entityId());
        if (entity == null) {
            // Move arrived before add — drop silently, next add will set pos.
            return;
        }

        // For a mirrored (non-local) entity, apply position like the client
        // does for remote entities via MoveEntity packets: update the "sent"
        // base position codec + hand off to the interpolation handler so
        // the entity visually glides between server-sent snapshots. Fallback
        // to absSnapTo for entities that expose no InterpolationHandler.
        entity.syncPacketPositionCodec(p.x(), p.y(), p.z());
        net.minecraft.world.entity.InterpolationHandler interp = entity.getInterpolation();
        if (interp != null) {
            interp.interpolateTo(
                new net.minecraft.world.phys.Vec3(p.x(), p.y(), p.z()),
                p.yRot(), p.xRot());
        } else {
            entity.absSnapTo(p.x(), p.y(), p.z(), p.yRot(), p.xRot());
        }
        entity.setYHeadRot(p.yHeadRot());
        entity.setOnGround(p.onGround());
        // Keep velocity at zero — same reason as in applyAdd. Mirror is
        // position-driven; any nonzero velocity would cause Entity.tick()
        // to integrate drift between Move payloads.
        entity.setDeltaMovement(0, 0, 0);

        moveCount++;
        if (moveCount <= 5) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS LIVE ENT] move #{}: id={} to ({},{},{}) yRot={} in {}",
                moveCount, p.entityId(),
                String.format("%.2f", p.x()), String.format("%.2f", p.y()), String.format("%.2f", p.z()),
                String.format("%.1f", p.yRot()), p.dimensionId());
        }
    }

    public static void applyData(ModPayloads.RemoteEntityDataPayload p) {
        ResourceKey<Level> dim = parseDimensionKey(p.dimensionId());
        if (dim == null) return;
        ClientLevel level = PortalWorldManager.getLevel(dim);
        if (level == null) return;

        net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket pkt = p.innerPacket();
        Entity entity = level.getEntity(pkt.id());
        if (entity == null) {
            // Data arrived before add — drop; next Add will carry initial state.
            return;
        }
        try {
            entity.getEntityData().assignValues(pkt.packedItems());
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS LIVE ENT] applyData failed for id={} in {}",
                pkt.id(), p.dimensionId(), t);
        }
    }

    public static void applyEquipment(ModPayloads.RemoteEntityEquipmentPayload p) {
        ResourceKey<Level> dim = parseDimensionKey(p.dimensionId());
        if (dim == null) return;
        ClientLevel level = PortalWorldManager.getLevel(dim);
        if (level == null) return;

        net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket pkt = p.innerPacket();
        Entity entity = level.getEntity(pkt.getEntity());
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity le)) {
            return;
        }
        try {
            StringBuilder slotsSummary = new StringBuilder();
            for (com.mojang.datafixers.util.Pair<
                    net.minecraft.world.entity.EquipmentSlot,
                    net.minecraft.world.item.ItemStack> slot : pkt.getSlots()) {
                le.setItemSlot(slot.getFirst(), slot.getSecond());
                // Readback to confirm the set actually stuck on this
                // specific entity object.
                net.minecraft.world.item.ItemStack readback = le.getItemBySlot(slot.getFirst());
                if (slotsSummary.length() > 0) slotsSummary.append(", ");
                slotsSummary.append(slot.getFirst().getName())
                    .append("=")
                    .append(slot.getSecond().getItem().toString())
                    .append("x").append(slot.getSecond().getCount())
                    .append(" readback=")
                    .append(readback.isEmpty() ? "EMPTY" : readback.getItem().toString());
            }
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS LIVE ENT] applyEquipment id={} type={} [{}] in {}",
                pkt.getEntity(),
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                slotsSummary, p.dimensionId());
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS LIVE ENT] applyEquipment failed for id={} in {}",
                pkt.getEntity(), p.dimensionId(), t);
        }
    }

    public static void applyRemove(ModPayloads.RemoteEntityRemovePayload p) {
        ResourceKey<Level> dim = parseDimensionKey(p.dimensionId());
        if (dim == null) return;
        ClientLevel level = PortalWorldManager.getLevel(dim);
        if (level == null) return;

        List<Integer> ids = p.entityIds();
        for (int id : ids) {
            level.removeEntity(id, Entity.RemovalReason.DISCARDED);
        }
        if (addCount <= 5 || ids.size() > 1) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS LIVE ENT] remove {}: ids={} in {}",
                ids.size(), ids, p.dimensionId());
        }
    }

    private static ResourceKey<Level> parseDimensionKey(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> Level.OVERWORLD;
            case "minecraft:the_nether" -> Level.NETHER;
            case "minecraft:the_end" -> Level.END;
            default -> null;
        };
    }
}
