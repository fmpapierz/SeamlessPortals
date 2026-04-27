package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Phase 2a of live-portal-view: mirror entities from a destination
 * dimension to the client's cached ClientLevel so they render inside the
 * portal view and exist in the same coordinate space as the player's own
 * dimension (enabling Phase 2d/2e cross-portal interaction later).
 *
 * <p>Ticked once per server tick from {@code ServerTickEvents.END_SERVER_TICK}
 * alongside {@link PortalChunkTracker}. For each connected player, finds
 * the portal links in-range, and for each link whose destination is in a
 * different dim than the player:
 * <ul>
 *   <li>Queries the destination {@link ServerLevel} for entities inside
 *       an AABB centered on the portal destination, sized by
 *       {@code portalRenderDistance * 16}.</li>
 *   <li>Diffs against the per-player-per-dim set of already-mirrored
 *       entities to produce Add / Remove payloads.</li>
 *   <li>Sends one Move payload per currently-mirrored entity with the
 *       entity's current authoritative position + rotation.</li>
 * </ul>
 *
 * <p>Scoping: a horizontal radial AABB around the destination portal
 * center. The portal link provides the mapping; nothing outside the
 * radius is mirrored. Bandwidth is proportional to (entities in radius) ×
 * 20 Hz — typically well under 1 KiB/s per player even in crowded scenes.
 *
 * <p>Player entities are explicitly excluded — the client's own
 * {@code LocalPlayer} is already handled by the normal login/respawn path,
 * and other players in the far dim would conflict with the vanilla
 * {@code PlayerInfoMap} tracking.
 *
 * <p>Chunk-liveness: this tracker does NOT add chunk tickets — it reads
 * whatever entities happen to be in the dest-dim's loaded chunks at the
 * time of scan. If those chunks aren't ticking (no nearby players /
 * tickets), entities will exist but be motionless. {@link PortalChunkTracker}
 * force-loads chunks for serialization but does not hold them with a
 * ticket. Making portals chunk-load the far side with an entity-ticking
 * ticket is a planned follow-up once Phase 2a visual correctness is
 * confirmed.
 */
public class PortalEntityTracker {

    /** Per-player, per-dim set of entity IDs currently mirrored to that player. */
    private final Map<UUID, Map<ResourceKey<Level>, Set<Integer>>> tracked = new HashMap<>();

    private static int tickCount = 0;

    /**
     * Entity mirror radius, in chunks. Independent of
     * {@code portalRenderDistance} (which can be 8–16 for chunk rendering).
     * A portal frame in vanilla typically sees only a small area around
     * the destination, so 4 chunks (~64 blocks) is enough for entity
     * visibility and bounds tracking bandwidth. Larger radius quickly
     * explodes the entity count and packet volume.
     */
    private static final int ENTITY_RADIUS_CHUNKS = 4;

    public void tick(MinecraftServer server) {
        PortalManager manager = PortalManager.getServerInstance();
        double rangeBlocks = ENTITY_RADIUS_CHUNKS * 16.0;

        tickCount++;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            tickForPlayer(player, server, manager, rangeBlocks);
        }
    }

    private void tickForPlayer(
            ServerPlayer player,
            MinecraftServer server,
            PortalManager manager,
            double rangeBlocks) {
        ResourceKey<Level> playerDim = player.level().dimension();
        List<PortalLink> links = manager.getLinksInRange(
            playerDim, player.blockPosition(), rangeBlocks);
        if (links.isEmpty()) {
            releaseAll(player);
            return;
        }

        // Collect per-dim AABBs from each link's destination portal.
        // If multiple links share a dest dim, union their radii.
        Map<ResourceKey<Level>, List<Vec3>> destCentersByDim = new HashMap<>();
        for (PortalLink link : links) {
            PortalInfo destPortal = link.getDestination();
            ResourceKey<Level> destDim = destPortal.getDimension();
            if (destDim.equals(playerDim)) continue; // same-dim link: no mirror needed
            destCentersByDim.computeIfAbsent(destDim, k -> new ArrayList<>())
                .add(destPortal.getCenter());
        }

        UUID playerId = player.getUUID();
        Map<ResourceKey<Level>, Set<Integer>> playerTracked =
            tracked.computeIfAbsent(playerId, k -> new HashMap<>());

        // Release any dim we previously tracked but no longer have a link to.
        Set<ResourceKey<Level>> staleDims = new HashSet<>(playerTracked.keySet());
        staleDims.removeAll(destCentersByDim.keySet());
        for (ResourceKey<Level> staleDim : staleDims) {
            Set<Integer> ids = playerTracked.remove(staleDim);
            if (ids != null && !ids.isEmpty()) {
                sendRemove(player, staleDim, new ArrayList<>(ids));
            }
        }

        for (Map.Entry<ResourceKey<Level>, List<Vec3>> entry : destCentersByDim.entrySet()) {
            tickForPlayerDim(player, server, playerTracked,
                entry.getKey(), entry.getValue(), rangeBlocks);
        }
    }

    /**
     * Hold the dest-dim chunks around the portal destination loaded and
     * ticking with a {@link TicketType#PORTAL} ticket. Without this, a
     * far-dim chunk has no tickets (the player isn't there), so it
     * unloads between server ticks — {@link ServerLevel#getEntities} then
     * returns empty and we oscillate Add→Remove every tick.
     *
     * <p>PORTAL ticket has {@code timeout=300}, so any tick we pass
     * through here refreshes the ticket for 15 seconds of liveness. We
     * call this from {@link #tickForPlayerDim} for every dest center
     * being scanned.
     *
     * <p>Radius is kept tight (a few chunks around portal center) so we
     * don't force-load a huge bubble of the far dim. The entity query
     * range and the chunk-tick range are independent: chunk-tick only
     * needs to cover what we actually want entities from.
     */
    private static final int CHUNK_TICKET_RADIUS = 3;

    /**
     * Portal-view ticket. Registered via
     * {@link com.warwa.seamlessportals.mixin.TicketTypeInvoker} because
     * 26.1.2 refuses unregistered holders.
     *
     * <p>Flag bits (interpreted by {@link TicketType}):
     * <ul>
     *   <li>bit 0 (value 1) → {@code persist()}</li>
     *   <li>bit 1 (value 2) → {@code doesLoad()}</li>
     *   <li>bit 2 (value 4) → {@code doesSimulate()}</li>
     *   <li>bit 3 (value 8) → {@code shouldKeepDimensionActive()}</li>
     * </ul>
     *
     * <p>We use {@code 0b0110 = 6} — {@code doesLoad} + {@code doesSimulate}
     * but neither {@code persist} nor {@code shouldKeepDimensionActive}.
     * That loads the chunk and ticks entities so mobs animate server-side,
     * but it does not keep the dimension forcibly active (which is the
     * tier that enables natural mob spawning). Using the shipped
     * {@link TicketType#PORTAL} (flags=15) caused the nether to spawn a
     * new piglin every tick and cascade it through the portal back to
     * OW, flooding packets and crashing the client.
     *
     * <p>Timeout 40 ticks (2 s) so re-adding every tick keeps it fresh.
     */
    private static final TicketType MIRROR_VIEW_TICKET =
        com.warwa.seamlessportals.mixin.TicketTypeInvoker
            .seamlessportals$invokeRegister("seamlessportals_mirror_view", 40L, 0b0110);

    private static void keepChunksLoaded(ServerLevel destLevel, Vec3 center) {
        net.minecraft.world.level.ChunkPos centerChunk = new net.minecraft.world.level.ChunkPos(
            net.minecraft.util.Mth.floor(center.x) >> 4,
            net.minecraft.util.Mth.floor(center.z) >> 4);
        destLevel.getChunkSource().addTicketWithRadius(
            MIRROR_VIEW_TICKET, centerChunk, CHUNK_TICKET_RADIUS);
    }

    private void tickForPlayerDim(
            ServerPlayer player,
            MinecraftServer server,
            Map<ResourceKey<Level>, Set<Integer>> playerTracked,
            ResourceKey<Level> destDim,
            List<Vec3> destCenters,
            double rangeBlocks) {
        ServerLevel destLevel = server.getLevel(destDim);
        if (destLevel == null) return;

        // Force-load + tick the dest-dim chunks covering each portal
        // center so getEntities returns stable results across ticks.
        for (Vec3 center : destCenters) {
            keepChunksLoaded(destLevel, center);
        }

        // FLUID-DIAG GATE log was removed 2026-04-26 once the cross-dim
        // fluid-mirror path was confirmed end-to-end. Verified findings:
        //   - All three gates (shouldTickBlocksAt, areEntitiesLoaded,
        //     chunkSource.isPositionTicking) flip to true within ~1 server
        //     tick of MIRROR_VIEW_TICKET being added.
        //   - LevelTicks.sortContainersToTick drains queued fluid ticks
        //     for our portal-mirrored chunks unchanged from vanilla once
        //     the gates pass.
        // The actual freeze the user saw was downstream of fluid sim:
        // sendBlockUpdated wasn't being called for ~99% of fluid setBlock
        // events (Level.markAndNotifyBlock filtering). That was fixed by
        // hooking LevelChunk.setBlockState TAIL instead. See
        // LevelChunkSetBlockStateMixin.

        // Find all entities within any dest-center radius.
        // Use a broad AABB per center then union the hit sets.
        Set<Entity> visible = new HashSet<>();
        for (Vec3 center : destCenters) {
            AABB aabb = AABB.ofSize(center, rangeBlocks * 2, rangeBlocks * 2, rangeBlocks * 2);
            // Horizontal radius check — vertical is permissive to cover tall mobs /
            // ender dragons etc. without a height-specific AABB.
            for (Entity e : destLevel.getEntities((Entity) null, aabb)) {
                if (e instanceof ServerPlayer) continue; // players handled separately
                if (!e.isAlive()) continue;
                // Skip entities that just spawned or are mid-portal-crossing.
                // Vanilla's nether portal logic deletes-and-recreates the
                // entity on every crossing, so an entity caught in a portal
                // loop gets a new id every tick. Mirroring those would
                // flood payloads (observed: hundreds of fake Add/Remove per
                // second for two stationary "piglins" at a portal). A
                // freshness gate drops them until they settle.
                if (e.tickCount < 20) continue;
                if (e.isOnPortalCooldown()) continue;
                double dx = e.getX() - center.x;
                double dz = e.getZ() - center.z;
                if (dx * dx + dz * dz > rangeBlocks * rangeBlocks) continue;
                visible.add(e);
            }
        }

        Set<Integer> prev = playerTracked.computeIfAbsent(destDim, k -> new HashSet<>());
        Set<Integer> currIds = new HashSet<>();
        List<Entity> toAdd = new ArrayList<>();
        for (Entity e : visible) {
            currIds.add(e.getId());
            if (!prev.contains(e.getId())) {
                toAdd.add(e);
            }
        }
        List<Integer> toRemove = new ArrayList<>();
        for (Integer id : prev) {
            if (!currIds.contains(id)) {
                toRemove.add(id);
            }
        }

        // Add new ones
        for (Entity e : toAdd) {
            sendAdd(player, destDim, e);
        }
        // Remove gone ones
        if (!toRemove.isEmpty()) {
            sendRemove(player, destDim, toRemove);
        }
        // Movement update for still-visible (including the just-added, which is
        // redundant but cheap — their next tick will get one normally)
        for (Entity e : visible) {
            sendMove(player, destDim, e);
        }

        playerTracked.put(destDim, currIds);

        if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS LIVE ENT] {} -> {}: +{} -{} visible={} tracked={} centers={} tick={}",
                player.getName().getString(), destDim.identifier(),
                toAdd.size(), toRemove.size(), visible.size(), currIds.size(),
                destCenters.size(), tickCount);
            // Dump a sample of what's visible so we can see exact entity
            // types + positions being tracked vs missing.
            if (toAdd.size() > 0 && toAdd.size() <= 5) {
                for (Entity e : toAdd) {
                    SeamlessPortalsConstants.LOGGER.info(
                        "[SEAMLESS LIVE ENT]   +add {} id={} at ({},{},{})",
                        BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()),
                        e.getId(),
                        String.format("%.1f", e.getX()),
                        String.format("%.1f", e.getY()),
                        String.format("%.1f", e.getZ()));
                }
            }
        }
    }

    private void sendAdd(ServerPlayer player, ResourceKey<Level> dim, Entity e) {
        int typeId = BuiltInRegistries.ENTITY_TYPE.getId(e.getType());
        PlatformHelper.getInstance().sendToClient(player,
            new ModPayloads.RemoteEntityAddPayload(
                dim.identifier().toString(),
                e.getId(),
                e.getUUID(),
                typeId,
                e.getX(), e.getY(), e.getZ(),
                e.getYRot(), e.getXRot(), e.getYHeadRot(),
                e.getDeltaMovement().x, e.getDeltaMovement().y, e.getDeltaMovement().z,
                0 // entity-specific "data" int (vanilla uses for minecart type etc.; 0 for now)
            ));
        // Phase 2b: follow Add with the full non-default SynchedEntityData so
        // pose / baby / glow / custom-name / etc. come in at spawn. packAll
        // would overshoot; getNonDefaultValues is what vanilla's
        // ServerEntity.sendPairingData uses for the initial data snapshot.
        java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> initial =
            e.getEntityData().getNonDefaultValues();
        if (initial != null && !initial.isEmpty()) {
            PlatformHelper.getInstance().sendToClient(player,
                new ModPayloads.RemoteEntityDataPayload(
                    dim.identifier().toString(),
                    new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(
                        e.getId(), initial)));
        }

        // Phase 2b: equipment — for LivingEntity, send each non-empty slot
        // so armor/weapons/shields render correctly on the mirror.
        if (e instanceof net.minecraft.world.entity.LivingEntity le) {
            java.util.List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>> slots =
                new java.util.ArrayList<>();
            for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.VALUES) {
                net.minecraft.world.item.ItemStack stack = le.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    slots.add(com.mojang.datafixers.util.Pair.of(slot, stack.copy()));
                }
            }
            if (!slots.isEmpty()) {
                PlatformHelper.getInstance().sendToClient(player,
                    new ModPayloads.RemoteEntityEquipmentPayload(
                        dim.identifier().toString(),
                        new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(
                            e.getId(), slots)));
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS LIVE ENT] equip {} id={} slots={} in {}",
                    BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()),
                    e.getId(), slots.size(), dim.identifier());
            } else {
                SeamlessPortalsConstants.LOGGER.info(
                    "[SEAMLESS LIVE ENT] equip {} id={} SKIPPED (no non-empty slots) in {}",
                    BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()),
                    e.getId(), dim.identifier());
            }
        }
    }

    private void sendMove(ServerPlayer player, ResourceKey<Level> dim, Entity e) {
        PlatformHelper.getInstance().sendToClient(player,
            new ModPayloads.RemoteEntityMovePayload(
                dim.identifier().toString(),
                e.getId(),
                e.getX(), e.getY(), e.getZ(),
                e.getYRot(), e.getXRot(), e.getYHeadRot(),
                e.onGround()
            ));
    }

    private void sendRemove(ServerPlayer player, ResourceKey<Level> dim, List<Integer> ids) {
        if (ids.isEmpty()) return;
        PlatformHelper.getInstance().sendToClient(player,
            new ModPayloads.RemoteEntityRemovePayload(
                dim.identifier().toString(), ids));
    }

    /** Drop everything we've been tracking for this player across all dims. */
    private void releaseAll(ServerPlayer player) {
        UUID playerId = player.getUUID();
        Map<ResourceKey<Level>, Set<Integer>> playerTracked = tracked.remove(playerId);
        if (playerTracked == null) return;
        for (Map.Entry<ResourceKey<Level>, Set<Integer>> e : playerTracked.entrySet()) {
            if (!e.getValue().isEmpty()) {
                sendRemove(player, e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
    }

    public void onPlayerDisconnect(UUID playerId) {
        tracked.remove(playerId);
    }

    public void clear() {
        tracked.clear();
    }
}
