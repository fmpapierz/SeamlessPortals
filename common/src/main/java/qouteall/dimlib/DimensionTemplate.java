// F11 DimLib — SHIPPED never-firing shell (entity-portal migration; S13A-peripheral.md; promoted from
// the compileOnly ipStubs shell at S13-B alongside qouteall.dimlib.api.DimensionAPI). Referenced only
// by the alternate_dimension runtime (AlternateDimensions.java), which is C1/S19-gated — so it is
// COMPILED into the shipped jar but never LOADED at S13 (alt-dims are not wired until S19). It ships
// (rather than staying a compileOnly stub) so the shipped qouteall tree is self-contained on the
// loader classpath; its bodies stay inert until the S19 dynamic-dimension design lands. Deleted at S20.
//
// SURFACE (grep-verified against the S13 alt-dim callers): AlternateDimensions.java —
//   `new DimensionTemplate(ResourceKey<DimensionType>, (server, dimensionTypeHolder) -> LevelStem)`,
//   `DimensionTemplate.registerDimensionTemplate(String, DimensionTemplate)`,
//   `DimensionTemplate.VOID_TEMPLATE.createLevelStem(server)`, `<TEMPLATE>.createLevelStem(server)`.
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
