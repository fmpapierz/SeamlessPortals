// F11 DimLib compileOnly stub (entity-portal migration; migration/EXECUTION_PLAN.md §3 S10 +
// register F11; port-note S10A-loader-seam.md §5; S04-ducks-roots-facade.md §5). NOT IP source,
// NOT shipped. DimLib genuinely has NO MC 26.2 form and NO source in the IP tree, so — unlike the
// net.fabricmc.* loader-facade shells (which the real fabric-api provides at S13) — a stub is the
// correct resolution on BOTH the :common probe AND the loader classpath (this shell lives in the
// ipStubs source set, wired to both, exactly like the sodium/iris/gravity F21 shells).
//
// SELF-CONTAINED by design: the event holders take/return only vanilla + Identifier types, so this
// stub has NO net.fabricmc dependency (the net.fabricmc.* shells are a SEPARATE :common-only source
// set). DimensionIntId still passes net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE (an Identifier)
// into addPhaseOrdering — that constant comes from the fabricStubs Event shell on :common.
//
// NEVER-FIRING (S04 §5): with STATIC dimensions the ordering invariant DimensionIntId.init() wires
// (dim-int-id updates before global-portal storage) is preserved by the A1 init order, not by these
// events, so the faithful static stub accepts registration + phase ordering but never fires.
//
// S13 HANDOFF: this stub is compileOnly (never shipped), but DimensionIntId.init()/GlobalPortalStorage
// .init()/EntitySync.init()/... call DimensionAPI.*.register(...) at RUNTIME. Since real DimLib does
// not exist on 26.2, at S13 DimensionAPI must move to SHIPPED in-tree source (a real never-firing
// implementation) or the init() calls NoClassDefFoundError. Recorded in the port-note. Removed at S20.
package qouteall.dimlib.api;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Set;

public class DimensionAPI {
    public static final DimensionDynamicUpdateEvent SERVER_DIMENSION_DYNAMIC_UPDATE_EVENT = null;
    public static final DimensionRemoveEvent SERVER_PRE_REMOVE_DIMENSION_EVENT = null;

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

    @FunctionalInterface
    public interface ServerDimensionsUpdateCallback {
        void run(MinecraftServer server, Set<ResourceKey<Level>> dimensions);
    }

    @FunctionalInterface
    public interface BeforeRemovingDimensionCallback {
        void run(ServerLevel world);
    }
}
