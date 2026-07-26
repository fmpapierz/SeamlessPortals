// F11 DimLib — S19-D: the load-window half is now LIVE (migration/port-notes/S19-peripheral-tail.md
// §6). PROMOTED from the compileOnly ipStubs shell at S13-B; at S19-D the SERVER_DIMENSIONS_LOAD
// path became REAL (fired by qouteall.dimlib.mixin.common.MixinMinecraftServer_DimLib at
// createLevels HEAD, inside a direct-registration window). IP's init sequence still registers on the
// other three events at flag-ON runtime, but they stay INERT SHELLS: with static dimensions there is
// no dynamic add/remove/client-resync, so they never fire. Loading the dynamic provider (runtime
// dimension add/remove + client resync) is R13g-PHASE-2 (deferred; see §6 "NAMED DEVIATION").
//
// S20 CORRECTION: this note said "NOT loaded at all when entityPortals is OFF ... Deleted at S20."
// Both halves are dead letters — the flag is deleted (there is no OFF state) and the class is NOT
// deleted: it is live IP API reached through IP init. What survives of the old sentence is the
// LOADER half: the load-event mixin weaves on Fabric only (the qouteall.* gate in
// SeamlessMixinConfigPlugin).
package qouteall.dimlib.api;

import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import org.slf4j.Logger;
import qouteall.dimlib.DimensionImpl;

import java.util.Set;
import java.util.function.Supplier;

public class DimensionAPI {
    private static final Logger LOGGER = LogUtils.getLogger();

    // ==== R13g-PHASE-2 INERT SHELLS (deferred) ====
    // The three dynamic-dimension events below are registered on by IP's flag-ON init
    // (DimensionIntId / GlobalPortalStorage / EntitySync / ImmPtlChunkTickets / ImmPtlChunkTracking /
    // ServerTeleportationManager / ClientWorldLoader) but NEVER FIRE under static dimensions. They are
    // kept as hand-rolled no-op holders (rather than fabric Events) precisely because nothing invokes
    // them — the register()/addPhaseOrdering() calls are safe no-ops. They become real when the
    // dynamic dimension provider lands (R13g-PHASE-2).

    public static final DimensionDynamicUpdateEvent SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT =
        new DimensionDynamicUpdateEvent();
    public static final DimensionRemoveEvent SERVER_PRE_REMOVE_DIMENSION_EVENT =
        new DimensionRemoveEvent();
    // The CLIENT-side event (ClientWorldLoader.init registers on it to dispose dynamically-removed
    // dimensions client-side). Never-firing under static dimensions (R13g-PHASE-2).
    public static final ClientDimensionUpdateEvent CLIENT_DIMENSION_UPDATE_EVENT =
        new ClientDimensionUpdateEvent();

    // ==== REAL (S19-D) ====
    // DimStackManagement.init registers on this to apply the pending dim-stack the first time server
    // dimensions load. Fired by MixinMinecraftServer_DimLib at createLevels HEAD, inside the
    // direct-registration window — so listeners may add alt-dim level stems via addDimensionIfNotExists.
    public static final Event<ServerDimensionsLoadCallback> SERVER_DIMENSIONS_LOAD_EVENT =
        EventFactory.createArrayBacked(
            ServerDimensionsLoadCallback.class,
            (listeners) -> ((server) -> {
                for (ServerDimensionsLoadCallback listener : listeners) {
                    try {
                        listener.run(server);
                    }
                    catch (Exception e) {
                        LOGGER.error("Error during server dimensions load event", e);
                    }
                }
            })
        );

    /** Holder for the dynamic dimension-set update event (DimensionIntId, GlobalPortalStorage). R13g-PHASE-2. */
    public static class DimensionDynamicUpdateEvent {
        public void register(ServerDimensionsUpdateCallback listener) {
        }

        public void register(Identifier phase, ServerDimensionsUpdateCallback listener) {
        }

        public void addPhaseOrdering(Identifier firstPhase, Identifier secondPhase) {
        }
    }

    /** Holder for the pre-remove-dimension event (EntitySync, ImmPtlChunkTickets, ...). R13g-PHASE-2. */
    public static class DimensionRemoveEvent {
        public void register(BeforeRemovingDimensionCallback listener) {
        }
    }

    /** Holder for the client-side dimension-set update event (ClientWorldLoader.init). R13g-PHASE-2. */
    public static class ClientDimensionUpdateEvent {
        public void register(ClientDimensionsUpdateCallback listener) {
        }
    }

    /**
     * See {@link DimensionAPI#SERVER_DIMENSIONS_LOAD_EVENT}.
     */
    @FunctionalInterface
    public interface ServerDimensionsLoadCallback {
        void run(MinecraftServer server);
    }

    /**
     * Check if a dimension exists in registry.
     * This can be used when the server worlds are not yet initialized.
     */
    public static boolean dimensionExistsInRegistry(
        MinecraftServer server, Identifier dimensionId
    ) {
        return DimensionImpl.getDimensionRegistry(server).containsKey(dimensionId);
    }

    // AlternateDimensions.addAltDimsIfUsedInDimStack adds an alt-dimension level stem on demand if it
    // is not already present. Under S19-D this fires ONLY inside the SERVER_DIMENSIONS_LOAD window
    // (canDirectlyRegisterDimension == true): direct registration into the LEVEL_STEM registry.
    // Outside the window (or a runtime `/portal dimension_stack` naming a not-yet-existing alt dim)
    // it is a GRACEFUL SKIP — runtime dynamic creation is R13g-PHASE-2 (deferred). The caller's
    // missing-dim guard (DimStackInfo.apply) then aborts with chat feedback; the create-world path,
    // which runs inside the window, works fully.
    public static void addDimensionIfNotExists(
        MinecraftServer server, Identifier dimensionId, Supplier<LevelStem> levelStemSupplier
    ) {
        if (dimensionExistsInRegistry(server, dimensionId)) {
            return;
        }

        if (DimensionImpl.canDirectlyRegisterDimension) {
            DimensionImpl.directlyRegisterLevelStem(server, dimensionId, levelStemSupplier.get());
        }
        else {
            LOGGER.warn(
                "Cannot add dimension {} outside the server-dimensions load window. Runtime " +
                    "dynamic dimension creation is R13g-PHASE-2 (deferred); skipping.",
                dimensionId
            );
        }
    }

    /**
     * Disable the "Worlds using Experimental Settings are not supported" warning screen.
     * This should be called during initialization. Read by the scope-3 consumer mixins.
     */
    public static void suppressExperimentalWarning() {
        DimensionImpl.suppressExperimentalWarning = true;
    }

    /**
     * Mark a namespace as "stable": dimensions with that namespace will not trigger the
     * "Worlds using Experimental Settings are not supported" warning screen. Read by the scope-3
     * consumer mixins (which check {@link DimensionImpl#STABLE_NAMESPACES}). Should be called during
     * initialization.
     */
    public static void suppressExperimentalWarningForNamespace(String namespace) {
        DimensionImpl.STABLE_NAMESPACES.add(namespace);
    }

    @FunctionalInterface
    public interface ServerDimensionsUpdateCallback {
        void run(MinecraftServer server, Set<ResourceKey<Level>> dimensions);
    }

    @FunctionalInterface
    public interface ClientDimensionsUpdateCallback {
        void run(Set<ResourceKey<Level>> dimensions);
    }

    @FunctionalInterface
    public interface BeforeRemovingDimensionCallback {
        void run(ServerLevel world);
    }
}
