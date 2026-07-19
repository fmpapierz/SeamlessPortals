package qouteall.imm_ptl.peripheral.alternate_dimension;

import com.google.common.base.Suppliers;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jetbrains.annotations.NotNull;
import qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IEChunkAccess_AlternateDim;
import qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IEChunkGenerator_AlternateDim;
import qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IENoiseRouterData;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * It extends NoiseBasedChunkGenerator, because in
 * {@link ChunkMap#ChunkMap(ServerLevel, LevelStorageSource.LevelStorageAccess, DataFixer, StructureTemplateManager, Executor, BlockableEventLoop, LightChunkGetter, ChunkGenerator, ChunkProgressListener, ChunkStatusUpdateListener, Supplier, int, boolean)}
 * it uses instanceof to initialize random source.
 */
public class NormalSkylandGenerator extends NoiseBasedChunkGenerator {
    
    public static final MapCodec<NormalSkylandGenerator> MAP_CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
                RegistryOps.retrieveGetter(Registries.BIOME),
                RegistryOps.retrieveGetter(Registries.DENSITY_FUNCTION),
                RegistryOps.retrieveGetter(Registries.NOISE),
                RegistryOps.retrieveGetter(Registries.NOISE_SETTINGS),
                RegistryOps.retrieveGetter(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST),
                Codec.LONG.optionalFieldOf("seed", 0L).forGetter(g -> g.seed)
            )
            .apply(instance, NormalSkylandGenerator::create)
    );
    
    private RandomState delegatedRandomState;
    
    private final HolderGetter<Biome> biomeHolderGetter;
    private final HolderGetter<DensityFunction> densityFunctionHolderGetter;
    private final HolderGetter<NormalNoise.NoiseParameters> noiseParametersHolderGetter;
    private final long seed;
    
    public NormalSkylandGenerator(
        BiomeSource biomeSource,
        Holder<NoiseGeneratorSettings> noiseGeneratorSettings,
        
        NoiseBasedChunkGenerator delegate,
        
        HolderGetter<Biome> biomeHolderGetter,
        HolderGetter<DensityFunction> densityFunctionHolderGetter,
        HolderGetter<NormalNoise.NoiseParameters> noiseParametersHolderGetter,
        long seed
    ) {
        super(biomeSource, noiseGeneratorSettings);
        
        this.delegate = delegate;
        this.biomeHolderGetter = biomeHolderGetter;
        this.densityFunctionHolderGetter = densityFunctionHolderGetter;
        this.noiseParametersHolderGetter = noiseParametersHolderGetter;
        this.seed = seed;
        
        this.delegatedRandomState = RandomState.create(
            (delegate.generatorSettings().value()),
            ((HolderLookup.RegistryLookup<NormalNoise.NoiseParameters>) noiseParametersHolderGetter),
            seed
        );
    }
    
    public static NormalSkylandGenerator create(
        HolderGetter<Biome> biomeHolderGetter,
        HolderGetter<DensityFunction> densityFunctionHolderGetter,
        HolderGetter<NormalNoise.NoiseParameters> noiseParametersHolderGetter,
        HolderGetter<NoiseGeneratorSettings> noiseGeneratorSettingsHolderGetter,
        HolderGetter<MultiNoiseBiomeSourceParameterList> biomeParamListLookup,
        long seed
    ) {
        Holder.Reference<MultiNoiseBiomeSourceParameterList> overworldBiomeParamList =
            biomeParamListLookup.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        
        MultiNoiseBiomeSource overworldBiomeSource =
            MultiNoiseBiomeSource.createFromPreset(overworldBiomeParamList);
        
        NoiseGeneratorSettings overworldNGS = noiseGeneratorSettingsHolderGetter
            .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        
        NoiseGeneratorSettings intrinsicSkylandNGS = noiseGeneratorSettingsHolderGetter
            .getOrThrow(NoiseGeneratorSettings.FLOATING_ISLANDS).value();

//        NoiseGeneratorSettings endNGS = IENoiseGeneratorSettings.ip_end();
        
        NoiseGeneratorSettings usedSkylandNGS = new NoiseGeneratorSettings(
            intrinsicSkylandNGS.noiseSettings(),
            intrinsicSkylandNGS.defaultBlock(),
            intrinsicSkylandNGS.defaultFluid(),
            // S19-D: re-derived noNewCaves (26.2 deleted NoiseRouterData.noNewCaves — see
            // ip_noNewCaves javadoc below). The postProcessor input is unchanged 1:1 from IP:
            // slideEndLike(getFunction(BASE_3D_NOISE_END), 0, 128).
            ip_noNewCaves(
                densityFunctionHolderGetter,
                noiseParametersHolderGetter,
                IENoiseRouterData.ip_slideEndLike(IENoiseRouterData.ip_getFunction(
                    densityFunctionHolderGetter, IENoiseRouterData.get_BASE_3D_NOISE_END()
                ), 0, 128)
            ),
            intrinsicSkylandNGS.surfaceRule(),
            intrinsicSkylandNGS.spawnTarget(),
            0, // overwrite seaLevel
            intrinsicSkylandNGS.disableMobGeneration(),
            intrinsicSkylandNGS.aquifersEnabled(),
            intrinsicSkylandNGS.oreVeinsEnabled(),
            intrinsicSkylandNGS.useLegacyRandomSource()
        );
        
        NoiseBasedChunkGenerator skylandGenerator = new NoiseBasedChunkGenerator(
            overworldBiomeSource, Holder.direct(usedSkylandNGS)
        );
        
        NormalSkylandGenerator result = new NormalSkylandGenerator(
            overworldBiomeSource,
            Holder.direct(overworldNGS),
            skylandGenerator,
            biomeHolderGetter,
            densityFunctionHolderGetter,
            noiseParametersHolderGetter,
            seed
        );
        
        ((IEChunkGenerator_AlternateDim) result).ip_setFeaturesPerStep(
            Suppliers.memoize(
                () -> FeatureSorter.buildFeaturesPerStep(
                    List.copyOf(overworldBiomeSource.possibleBiomes()),
                    holder -> {
                        Biome biome = holder.value();
                        BiomeGenerationSettings bgs = biome.getGenerationSettings();
                        List<HolderSet<PlacedFeature>> features = bgs.features();
                        // TODO modify feature
                        return features;
                    },
                    true
                )
            )
        );
        
        return result;
    }

    /**
     * S19-D noNewCaves RE-DERIVATION (derivation chain 1.21.3 -> 1.21.11 -> 26.2).
     *
     * <p>IP's skyland {@link NoiseGeneratorSettings} builds its noise router from vanilla
     * {@code NoiseRouterData.noNewCaves(functions, noises, postProcessorInput)}. That private method
     * survived UNCHANGED through 1.21.11 (verified against mc-sources-1.21.11 NoiseRouterData:377-402;
     * the 1.21.11 IP port kept the {@code @Invoker("noNewCaves")} verbatim). 26.2 DELETED it: the
     * noNewCaves callers were refactored so {@code caves()}/{@code floatingIslands()} now go through
     * {@code simpleRouter(...)} — which ZEROES temperature &amp; vegetation — and {@code nether()}
     * inlines with the {@code _NETHER} noise variants + zero shift. None of the surviving 26.2 routers
     * reproduce noNewCaves, which sets temperature/vegetation to real {@code SHIFT_X}/{@code SHIFT_Z}
     * -based 2D noise while zeroing everything else except finalDensity.
     *
     * <p>IP's skyland needs those non-zero: its biome source is the OVERWORLD
     * {@code MultiNoiseBiomeSource}, so the router's temperature/vegetation drive biome variety
     * (with simpleRouter's zeros the whole skyland would collapse to a single climate point). So we
     * re-derive noNewCaves here 1:1 with the 1.21.11 body, composed from the 26.2 primitives that DO
     * survive (all verified present in mc262-ref NoiseRouterData): {@code SHIFT_X}/{@code SHIFT_Z}
     * keys ({@link IENoiseRouterData#get_SHIFT_X()}/{@link IENoiseRouterData#get_SHIFT_Z()}),
     * {@code getFunction} ({@link IENoiseRouterData#ip_getFunction}), {@code postProcess}
     * ({@link IENoiseRouterData#ip_postProcess}), the 15-arg {@link NoiseRouter} record ctor,
     * {@link DensityFunctions#shiftedNoise2d} and {@link Noises#TEMPERATURE}/{@link Noises#VEGETATION}.
     * Argument order below matches the {@link NoiseRouter} record fields (mc262-ref NoiseRouter:7-23).
     *
     * <p>26.2 DELTA (inherited, toward-vanilla): 26.2's {@code postProcess} reorders
     * {@code interpolated()}/{@code mul()} vs 1.21.11's; using 26.2's own (via the invoker) keeps the
     * skyland terrain consistent with how 26.2 generates its own end/nether terrain.
     */
    private static NoiseRouter ip_noNewCaves(
        HolderGetter<DensityFunction> densityFunctions,
        HolderGetter<NormalNoise.NoiseParameters> noiseParameters,
        DensityFunction postProcessorInput
    ) {
        DensityFunction shiftX = IENoiseRouterData.ip_getFunction(
            densityFunctions, IENoiseRouterData.get_SHIFT_X()
        );
        DensityFunction shiftZ = IENoiseRouterData.ip_getFunction(
            densityFunctions, IENoiseRouterData.get_SHIFT_Z()
        );
        DensityFunction temperature = DensityFunctions.shiftedNoise2d(
            shiftX, shiftZ, 0.25, noiseParameters.getOrThrow(Noises.TEMPERATURE)
        );
        DensityFunction vegetation = DensityFunctions.shiftedNoise2d(
            shiftX, shiftZ, 0.25, noiseParameters.getOrThrow(Noises.VEGETATION)
        );
        DensityFunction finalDensity = IENoiseRouterData.ip_postProcess(postProcessorInput);
        return new NoiseRouter(
            DensityFunctions.zero(), // barrierNoise
            DensityFunctions.zero(), // fluidLevelFloodednessNoise
            DensityFunctions.zero(), // fluidLevelSpreadNoise
            DensityFunctions.zero(), // lavaNoise
            temperature,
            vegetation,
            DensityFunctions.zero(), // continents
            DensityFunctions.zero(), // erosion
            DensityFunctions.zero(), // depth
            DensityFunctions.zero(), // ridges
            DensityFunctions.zero(), // preliminarySurfaceLevel
            finalDensity,
            DensityFunctions.zero(), // veinToggle
            DensityFunctions.zero(), // veinRidged
            DensityFunctions.zero()  // veinGap
        );
    }

    private final NoiseBasedChunkGenerator delegate;
    
    @Override
    protected @NotNull MapCodec<? extends ChunkGenerator> codec() {
        return MAP_CODEC;
    }
    
    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
        Blender blender, RandomState pRandomState,
        StructureManager structureManager, ChunkAccess chunkAccess
    ) {
        ((IEChunkAccess_AlternateDim) chunkAccess).ip_setNoiseChunk(null);
        
        return delegate.fillFromNoise(
            blender, delegatedRandomState, structureManager, chunkAccess
        ).thenApply(c -> {
            ((IEChunkAccess_AlternateDim) c).ip_setNoiseChunk(null);
            return c;
        });
    }
    
    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSetLookup, RandomState randomState, long seed) {
        // filter the mineshaft out
        // cannot use HolderLookup.filterElements because it does not provide id in predicate
        HolderLookup<StructureSet> structureSetLookupDelegate = new HolderLookup<StructureSet>() {
            @Override
            public Stream<Holder.Reference<StructureSet>> listElements() {
                return structureSetLookup.listElements().filter(
                    holder -> !holder.key().identifier().getPath().equals("mineshafts")
                );
            }
            
            @Override
            public Stream<HolderSet.Named<StructureSet>> listTags() {
                return structureSetLookup.listTags();
            }
            
            @Override
            public Optional<Holder.Reference<StructureSet>> get(ResourceKey<StructureSet> resourceKey) {
                return structureSetLookup.get(resourceKey);
            }
            
            @Override
            public Optional<HolderSet.Named<StructureSet>> get(TagKey<StructureSet> tagKey) {
                return structureSetLookup.get(tagKey);
            }
        };
        
        return super.createState(structureSetLookupDelegate, randomState, seed);
    }
}
