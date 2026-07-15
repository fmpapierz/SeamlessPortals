// F11 DimLib compileOnly stub (entity-portal migration; migration/EXECUTION_PLAN.md §3 S13 — the
// dim_stack/alternate_dimension compile shell; register F11; port-note S13A-peripheral.md). NOT IP
// source, NOT shipped. DimLib genuinely has NO MC 26.2 form and NO source in the IP tree, so — like
// the sibling qouteall.dimlib.api.DimensionAPI shell — a stub is the correct resolution on BOTH the
// :common probe AND the loader classpath (this shell lives in the ipStubs source set, wired to both,
// exactly like the sodium/iris/gravity F21 shells and the DimensionAPI shell).
//
// SURFACE (derived from the S13 dim_stack/alt-dim callers, grep-verified):
//   AlternateDimensions.java — `new DimensionTemplate(ResourceKey<DimensionType>, (server,
//     dimensionTypeHolder) -> LevelStem)` (:70-107), `DimensionTemplate.registerDimensionTemplate(
//     String, DimensionTemplate)` (:123-134), `DimensionTemplate.VOID_TEMPLATE.createLevelStem(server)`
//     (:172), `<TEMPLATE>.createLevelStem(server)` (:148,:157,:165,:181).
// The factory lambda's 2nd param is used as `new LevelStem(dimensionTypeHolder, chunkGen)`, so the
// BiFunction's 2nd type argument is Holder<DimensionType> (LevelStem's first ctor param type).
//
// COMPILE SHELL: bodies are inert (never-firing under static dimensions, S04 §5); the real
// never-firing runtime implementation (or the loader-neutral dynamic-dimension design, R13g) is
// C1/S19-gated. Removed at S20.
package qouteall.dimlib;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.function.BiFunction;

public class DimensionTemplate {
    public static final DimensionTemplate VOID_TEMPLATE = null;

    public DimensionTemplate(
        ResourceKey<DimensionType> dimensionType,
        BiFunction<MinecraftServer, Holder<DimensionType>, LevelStem> levelStemFactory
    ) {
    }

    public static void registerDimensionTemplate(String name, DimensionTemplate template) {
    }

    public LevelStem createLevelStem(MinecraftServer server) {
        return null;
    }
}
