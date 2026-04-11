package com.warwa.seamlessportals.api;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SeamlessPortalsAPI {
    private static final Map<Identifier, IPortalDefinition> registeredTypes = new ConcurrentHashMap<>();

    /**
     * Register a custom portal type with Seamless Portals.
     * Once registered, the portal type will be handled by all systems:
     * rendering, teleportation, and projectile pass-through.
     *
     * @param definition The portal definition describing the custom portal behavior
     */
    public static void registerPortalType(IPortalDefinition definition) {
        Identifier id = definition.getId();
        if (registeredTypes.containsKey(id)) {
            SeamlessPortalsConstants.LOGGER.warn("Portal type {} already registered, replacing", id);
        }
        registeredTypes.put(id, definition);
        SeamlessPortalsConstants.LOGGER.info("Registered custom portal type: {}", id);
    }

    /**
     * Unregister a previously registered custom portal type.
     *
     * @param id The Identifier of the portal type to remove
     */
    public static void unregisterPortalType(Identifier id) {
        if (registeredTypes.remove(id) != null) {
            SeamlessPortalsConstants.LOGGER.info("Unregistered custom portal type: {}", id);
        }
    }

    /**
     * Get a registered portal definition by ID.
     */
    public static Optional<IPortalDefinition> getPortalType(Identifier id) {
        return Optional.ofNullable(registeredTypes.get(id));
    }

    /**
     * Get all registered custom portal types.
     */
    public static Collection<IPortalDefinition> getAllCustomTypes() {
        return Collections.unmodifiableCollection(registeredTypes.values());
    }

    /**
     * Check if there is an active portal link at a given position.
     */
    public static Optional<PortalLink> getPortalAt(Level level, BlockPos pos) {
        PortalManager manager = level.isClientSide()
            ? PortalManager.getClientInstance()
            : PortalManager.getServerInstance();
        return manager.getLinkAt(level.dimension(), pos);
    }

    /**
     * Check if a block position is part of any tracked portal.
     */
    public static boolean isPortalBlock(Level level, BlockPos pos) {
        PortalManager manager = level.isClientSide()
            ? PortalManager.getClientInstance()
            : PortalManager.getServerInstance();
        return manager.getTracker(level.dimension()).getPortalAt(pos).isPresent();
    }

    /**
     * Get all portal links within a range of a position.
     */
    public static List<PortalLink> getPortalsInRange(Level level, BlockPos center, double range) {
        PortalManager manager = level.isClientSide()
            ? PortalManager.getClientInstance()
            : PortalManager.getServerInstance();
        return manager.getLinksInRange(level.dimension(), center, range);
    }
}
