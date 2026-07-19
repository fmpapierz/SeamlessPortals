// F11 DimLib — S19-D LANDED (migration/port-notes/S19-peripheral-tail.md §6). Ported from the REAL
// DimLib v1.1.0+mc1.21.11 DimensionImpl.java, re-derived against 26.2 (mc262-ref MappedRegistry).
// Holds the load-window state (STABLE_NAMESPACES / suppressExperimentalWarning read by scope-3
// consumer mixins) and the direct level-stem registration used inside the SERVER_DIMENSIONS_LOAD
// window. The DYNAMIC half (DynamicDimensionsImpl / addDimensionDynamically / forceRemove) is
// R13g-PHASE-2 (deferred) and intentionally NOT ported. Deleted with the scaffolding at S20.
package qouteall.dimlib;

import com.mojang.logging.LogUtils;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;
import org.slf4j.Logger;
import qouteall.dimlib.ducks.IMappedRegistry;

import java.util.HashSet;

public class DimensionImpl {

    public static final Logger LOGGER = LogUtils.getLogger();

    // Read by scope-3's consumer mixins (the experimental-warning-suppression chain).
    public static final HashSet<String> STABLE_NAMESPACES = new HashSet<>();
    public static boolean suppressExperimentalWarning = false;

    // S19-D SIMPLIFICATION (design-sanctioned, §6): the REAL DimLib keeps
    // ip_canDirectlyRegisterDimension as a per-server field via an IMinecraftServer duck. With only
    // the load-window (static) half ported and no dynamic runtime-add, a plain static latch
    // set/cleared around the SERVER_DIMENSIONS_LOAD invoker (in MixinMinecraftServer_DimLib) is
    // equivalent and simpler — the integrated/dedicated server runs one createLevels at a time and
    // the latch is always reset (try/finally in the mixin) before the pass returns.
    public static volatile boolean canDirectlyRegisterDimension = false;

    public static void directlyRegisterLevelStem(
        MinecraftServer server, Identifier dimensionId, LevelStem levelStem
    ) {
        RegistryAccess.Frozen registryAccess = server.registryAccess();

        // S19-D 26.2-forced: registryAccess.registryOrThrow(LEVEL_STEM) -> lookupOrThrow(LEVEL_STEM)
        // (RegistryAccess rename; returns Registry<LevelStem>, downcast to the mutable MappedRegistry).
        // IP also read WorldData/WorldOptions here but never used them — dropped.
        MappedRegistry<LevelStem> levelStems = (MappedRegistry<LevelStem>)
            registryAccess.lookupOrThrow(Registries.LEVEL_STEM);

        // 26.2 MappedRegistry.containsKey(Identifier) (mc262-ref MappedRegistry:260).
        if (!levelStems.containsKey(dimensionId)) {
            // the vanilla freezing mechanism is used for validating dangling object references;
            // for this API that won't happen, so we temporarily unfreeze to register. On 26.2 the
            // `frozen` field is a non-final private boolean (mc262-ref MappedRegistry:45) — shadow-
            // writable via IMappedRegistry, so NO accesswidener/reflection is needed.
            boolean oldIsFrozen = ((IMappedRegistry) levelStems).dimlib_getIsFrozen();
            ((IMappedRegistry) levelStems).dimlib_setIsFrozen(false);

            try {
                levelStems.register(
                    ResourceKey.create(Registries.LEVEL_STEM, dimensionId),
                    levelStem,
                    RegistrationInfo.BUILT_IN // use built-in registration info for now
                );
            }
            finally {
                ((IMappedRegistry) levelStems).dimlib_setIsFrozen(oldIsFrozen);
            }
        }
        else {
            LOGGER.error(
                "The dimension {} already exists",
                dimensionId,
                new Throwable()
            );
        }
    }

    public static MappedRegistry<LevelStem> getDimensionRegistry(MinecraftServer server) {
        return (MappedRegistry<LevelStem>)
            server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
    }
}
