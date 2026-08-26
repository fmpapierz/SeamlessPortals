package qouteall.imm_ptl.core.portal.global_portals;

import com.mojang.logging.LogUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.ducks.IEClientWorld;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.List;

/**
 * NF-PARITY C3 dist-split (2026-08-25): the CLIENT half of {@link GlobalPortalStorage},
 * physically split out. Verifying the old in-class client methods needed
 * {@code ClientLevel -> Level} assignability proofs ({@code receiveGlobalPortalSync} passing
 * a ClientLevel into {@code getPortalsFromTag(CompoundTag, Level)}; {@code onClientCleanup}'s
 * loop), which force-load client classes when GlobalPortalStorage LINKS — and it links on the
 * dedicated server (it IS the server SavedData). NeoForge has no {@code @Environment} member
 * stripping. Logic verbatim; same package so the package-private
 * {@code getPortalsFromTag} stays reachable.
 */
public class GlobalPortalStorageClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Called from {@code GlobalPortalStorage.init()}'s non-dedicated branch. */
    static void initClient() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(GlobalPortalStorageClient::onClientCleanup);
    }

    private static void onClientCleanup() {
        if (ClientWorldLoader.getIsInitialized()) {
            for (ClientLevel clientWorld : ClientWorldLoader.getClientWorlds()) {
                for (Portal globalPortal : GlobalPortalStorage.getGlobalPortals(clientWorld)) {
                    globalPortal.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                }
            }
        }
    }

    public static void receiveGlobalPortalSync(ResourceKey<Level> dimension, CompoundTag compoundTag) {
        ClientLevel world = ClientWorldLoader.getWorld(dimension);

        List<Portal> oldGlobalPortals = ((IEClientWorld) world).ip_getGlobalPortals();
        if (oldGlobalPortals != null) {
            for (Portal p : oldGlobalPortals) {
                p.remove(Entity.RemovalReason.KILLED);
            }
        }

        List<Portal> newPortals = GlobalPortalStorage.getPortalsFromTag(compoundTag, world);
        for (Portal p : newPortals) {
            p.myUnsetRemoved();
            p.isGlobalPortal = true;

            Validate.isTrue(p.isPortalValid());

            ClientWorldLoader.getWorld(p.getDestDim());
        }

        ((IEClientWorld) world).ip_setGlobalPortals(newPortals);

        LOGGER.info("Global Portals Updated {}", dimension.identifier());
    }
}
