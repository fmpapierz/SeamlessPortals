// F11 DimLib — SHIPPED never-firing implementation (entity-portal migration; S10A-loader-seam.md §5
// S13 HANDOFF; EXECUTION_PLAN §3 S13 step 4). PROMOTED from the compileOnly ipStubs shell to shipped
// in-tree source at S13-B because IP's init sequence (DimensionIntId.init / GlobalPortalStorage.init /
// EntitySync.init / ServerTeleportationManager.init / ImmPtlChunkTracking.init / ImmPtlChunkTickets.init /
// DimStackManagement.init) calls DimensionAPI.*.register(...) at RUNTIME behind the entityPortals flag,
// and DimLib genuinely has NO real MC 26.2 provider (unlike the net.fabricmc.* facade, which real
// fabric-api supplies). A compileOnly stub would NoClassDefFoundError / NPE at flag-ON runtime.
//
// NEVER-FIRING (S04 §5): with STATIC dimensions the ordering invariant DimensionIntId.init() wires
// (dim-int-id updates before global-portal storage) is preserved by the A1 init order, not by these
// events. The event holders are real objects so register()/addPhaseOrdering() are safe no-ops; they
// simply never fire. Loading a dynamic-dimension provider (R13g) is C1/S19-gated.
//
// NOT loaded at all when entityPortals is OFF (nothing references it until IP init is wired flag-ON),
// so shipping it is inert for the block-era baseline. Deleted with the migration scaffolding at S20.
package qouteall.dimlib.api;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.Set;
import java.util.function.Supplier;

public class DimensionAPI {
    public static final DimensionDynamicUpdateEvent SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT =
        new DimensionDynamicUpdateEvent();
    public static final DimensionRemoveEvent SERVER_PRE_REMOVE_DIMENSION_EVENT =
        new DimensionRemoveEvent();
    // The CLIENT-side event (ClientWorldLoader.init registers on it to dispose dynamically-removed
    // dimensions client-side). Never-firing under static dimensions.
    public static final ClientDimensionUpdateEvent CLIENT_DIMENSION_UPDATE_EVENT =
        new ClientDimensionUpdateEvent();
    // DimStackManagement.init registers on it to apply the pending dim-stack the first time server
    // dimensions finish loading (DimStackManagement.java:48). Never-firing under static dimensions.
    public static final ServerDimensionsLoadEvent SERVER_DIMENSIONS_LOAD_EVENT =
        new ServerDimensionsLoadEvent();

    /** Holder for the dynamic dimension-set update event (DimensionIntId, GlobalPortalStorage). */
    public static class DimensionDynamicUpdateEvent {
        public void register(ServerDimensionsUpdateCallback listener) {
        }

        public void register(Identifier phase, ServerDimensionsUpdateCallback listener) {
        }

        public void addPhaseOrdering(Identifier firstPhase, Identifier secondPhase) {
        }
    }

    /** Holder for the pre-remove-dimension event (EntitySync, ImmPtlChunkTickets, ...). */
    public static class DimensionRemoveEvent {
        public void register(BeforeRemovingDimensionCallback listener) {
        }
    }

    /** Holder for the client-side dimension-set update event (ClientWorldLoader.init). */
    public static class ClientDimensionUpdateEvent {
        public void register(ClientDimensionsUpdateCallback listener) {
        }
    }

    /** Holder for the server-dimensions-loaded event (DimStackManagement.init). */
    public static class ServerDimensionsLoadEvent {
        public void register(ServerDimensionsLoadCallback listener) {
        }
    }

    // AlternateDimensions.addAltDimsIfUsedInDimStack adds an alt-dimension level stem on demand if it
    // is not already present (AlternateDimensions.java:145-181). Never-firing under static dimensions
    // (the alternate_dimension runtime is C1/S19-gated).
    public static void addDimensionIfNotExists(
        MinecraftServer server, Identifier dimensionId, Supplier<LevelStem> levelStemSupplier
    ) {
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

    @FunctionalInterface
    public interface ServerDimensionsLoadCallback {
        void run(MinecraftServer server);
    }
}
