package qouteall.imm_ptl.core.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.Objects;

/**
 * NF-PARITY C3 dist-split (2026-08-25): the CLIENT handler bodies of
 * {@link ImmPtlNetworking}'s payloads, physically split out of the records. Registering a
 * payload TYPE class-initializes (and therefore VERIFIES) the record on the dedicated
 * server; {@code PortalSyncPacket.handle()}'s body passes a {@code ClientLevel} where
 * {@code Level} is declared, and that assignability proof force-loads the client-only
 * {@code ClientLevel} — the measured E0 boot crash at {@code ImmPtlNetworking.init}.
 * NeoForge has no {@code @Environment} member stripping. Logic verbatim (record fields
 * read through their accessors).
 */
public class ImmPtlNetworkingClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * {@code ClientPacketListener#handleAddEntity} analogue — the old
     * {@code PortalSyncPacket.handle()} body.
     */
    public static void handlePortalSync(ImmPtlNetworking.PortalSyncPacket packet) {
        ResourceKey<Level> dimension = PortalAPI.clientIntToDimKey(packet.dimensionId());
        ClientLevel world = ClientWorldLoader.getWorld(dimension);

        Entity existing = world.getEntity(packet.id());

        if (existing instanceof Portal existingPortal) {
            // update existing portal (handles default animation)
            if (!Objects.equals(existingPortal.getUUID(), packet.uuid())) {
                LOGGER.error("UUID mismatch when syncing portal {} {}", existingPortal, packet.uuid());
                return;
            }

            if (existingPortal.getType() != packet.entityType()) {
                LOGGER.error(
                    "Entity type mismatch when syncing portal {} {}", existingPortal, packet.entityType()
                );
                return;
            }

            existingPortal.acceptDataSync(
                new Vec3(packet.x(), packet.y(), packet.z()), packet.extraData());
        }
        else {
            // spawn new portal
            Entity entity = packet.entityType().create(world, EntitySpawnReason.LOAD);
            Validate.notNull(entity, "Entity type is null");

            if (!(entity instanceof Portal portal)) {
                LOGGER.error("Spawned entity is not a portal. {} {}", entity, packet.entityType());
                return;
            }

            entity.setId(packet.id());
            entity.setUUID(packet.uuid());
            entity.syncPacketPositionCodec(packet.x(), packet.y(), packet.z());
            entity.snapTo(packet.x(), packet.y(), packet.z());

            portal.readPortalDataFromNbt(packet.extraData());

            world.addEntity(entity);

            ClientWorldLoader.getWorld(portal.getDestDim());
            Portal.CLIENT_PORTAL_SPAWN_EVENT.invoker().accept(portal);

            if (IPGlobal.clientPortalLoadDebug) {
                LOGGER.info("Portal loaded to client {}", portal);
            }
        }
    }

    /** The old {@code GlobalPortalSyncPacket.handle()} body. */
    public static void handleGlobalPortalSync(ImmPtlNetworking.GlobalPortalSyncPacket packet) {
        ResourceKey<Level> dim = PortalAPI.clientIntToDimKey(packet.dimensionId());
        qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorageClient
            .receiveGlobalPortalSync(dim, packet.data());
    }
}
