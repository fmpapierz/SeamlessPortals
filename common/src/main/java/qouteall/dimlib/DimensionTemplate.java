// F11 DimLib — S19-D LANDED (was a never-firing shell; migration/port-notes/S19-peripheral-tail.md §6).
// Ported 1:1 from the REAL DimLib v1.1.0+mc1.21.11 source
// (C:\Users\warwa\ModDev\Immersive Portals\ImmersivePortals1.21.11\dimlib-source\...\DimensionTemplate.java),
// re-derived against 26.2 (mc262-ref). The record shape (ResourceKey<DimensionType> + a DimensionFactory)
// and the DIMENSION_TEMPLATES registry are IP-verbatim; only the registry-access API calls in
// createLevelStem + VOID_TEMPLATE change for 26.2 (see the S19-D tags below).
//
// SURFACE (grep-verified against the alt-dim callers): AlternateDimensions.java constructs
//   new DimensionTemplate(ResourceKey<DimensionType>, (server, dimTypeHolder) -> LevelStem),
//   calls DimensionTemplate.registerDimensionTemplate(String, DimensionTemplate),
//   and DimensionTemplate.VOID_TEMPLATE.createLevelStem(server) / <TEMPLATE>.createLevelStem(server).
// Wired flag-ON at S19-D (AlternateDimensions.init registers skyland/bright_skyland/chaos/bright_void;
// createLevelStem runs inside the DimLib load window). Deleted with the migration scaffolding at S20.
package qouteall.dimlib;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record DimensionTemplate(
    ResourceKey<DimensionType> dimensionTypeId,
    DimensionFactory dimensionFactory
) {

    public static interface DimensionFactory {
        LevelStem createLevelStem(
            MinecraftServer server,
            Holder<DimensionType> dimensionTypeHolder
        );
    }

    static final Map<String, DimensionTemplate> DIMENSION_TEMPLATES = new LinkedHashMap<>();

    public static void registerDimensionTemplate(
        String name, DimensionTemplate dimensionTemplate
    ) {
        DIMENSION_TEMPLATES.put(name, dimensionTemplate);
    }

    public LevelStem createLevelStem(MinecraftServer server) {
        // S19-D 26.2-forced: IP's registryAccess().registryOrThrow(DIMENSION_TYPE) +
        // dimensionTypes.getHolderOrThrow(id) became lookupOrThrow(...) + getOrThrow(id) on 26.2
        // (the same rename the shipped AlternateDimensions.createVoidGenerator already uses:
        // rm.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS) -> Holder.Reference).
        Registry<DimensionType> dimensionTypes =
            server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);

        Holder.Reference<DimensionType> holder =
            dimensionTypes.getOrThrow(dimensionTypeId);

        return dimensionFactory.createLevelStem(
            server, holder
        );
    }

    // IP-verbatim: registers the "void" template into DIMENSION_TEMPLATES. Currently UNWIRED
    // (DIMENSION_TEMPLATES is only read by DimLib's `/dims add_dimension` command, which is not
    // ported — the migration exposes templates only through the create-world dim-stack UX, which
    // references VOID_TEMPLATE directly). Kept for fidelity; a future /dims port calls this.
    public static void init() {
        registerDimensionTemplate(
            "void", VOID_TEMPLATE
        );
    }

    public static final DimensionTemplate VOID_TEMPLATE = new DimensionTemplate(
        BuiltinDimensionTypes.OVERWORLD,
        (server, dimTypeHolder) -> {
            // S19-D 26.2-forced: body is byte-for-byte the shipped
            // AlternateDimensions.createVoidGenerator (proven 26.2 flat-void generator), with the
            // IP-faithful registryOrThrow/getHolderOrThrow renamed to lookupOrThrow/getOrThrow.
            RegistryAccess.Frozen registryAccess = server.registryAccess();

            Registry<Biome> biomeRegistry = registryAccess.lookupOrThrow(Registries.BIOME);

            Holder.Reference<Biome> plainsHolder = biomeRegistry.getOrThrow(Biomes.PLAINS);

            FlatLevelGeneratorSettings flatChunkGeneratorConfig =
                new FlatLevelGeneratorSettings(
                    Optional.of(HolderSet.direct()),
                    plainsHolder,
                    List.of()
                );
            flatChunkGeneratorConfig.getLayersInfo().add(new FlatLayerInfo(1, Blocks.AIR));
            flatChunkGeneratorConfig.updateLayers();

            FlatLevelSource chunkGenerator = new FlatLevelSource(flatChunkGeneratorConfig);

            return new LevelStem(dimTypeHolder, chunkGenerator);
        }
    );
}
