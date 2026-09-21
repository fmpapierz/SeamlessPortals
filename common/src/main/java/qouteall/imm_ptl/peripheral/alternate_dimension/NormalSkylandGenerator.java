package qouteall.imm_ptl.peripheral.alternate_dimension;

import com.google.common.base.Suppliers;
import com.google.common.collect.Sets;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.Zone;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction; // 26.3: moved from levelgen (mc263-ref levelgen/densityfunction/DensityFunction.java:1)
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions; // 26.3: moved from levelgen (mc263-ref levelgen/densityfunction/DensityFunctions.java:1)
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IEChunkAccess_AlternateDim;
import qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IEChunkGenerator_AlternateDim;
import qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IENoiseRouterData;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    // 26.3: NormalNoise.NoiseParameters is gone; NormalNoise itself is the Registries.NOISE value
    // (mc263-ref NormalNoise.java:25,34-35; Registries.java:296; Noises.java:12).
    private final HolderGetter<NormalNoise> noiseParametersHolderGetter;
    private final long seed;
    
    public NormalSkylandGenerator(
        BiomeSource biomeSource,
        Holder<NoiseGeneratorSettings> noiseGeneratorSettings,
        
        NoiseBasedChunkGenerator delegate,
        
        HolderGetter<Biome> biomeHolderGetter,
        HolderGetter<DensityFunction> densityFunctionHolderGetter,
        HolderGetter<NormalNoise> noiseParametersHolderGetter,
        long seed
    ) {
        super(biomeSource, noiseGeneratorSettings);
        
        this.delegate = delegate;
        this.biomeHolderGetter = biomeHolderGetter;
        this.densityFunctionHolderGetter = densityFunctionHolderGetter;
        this.noiseParametersHolderGetter = noiseParametersHolderGetter;
        this.seed = seed;
        
        // 26.3: RandomState.create argument order is now (noises, seed, settings)
        // (mc263-ref RandomState.java:41; was (settings, noises, seed), mc262-ref RandomState.java:31).
        this.delegatedRandomState = RandomState.create(
            ((HolderLookup.RegistryLookup<NormalNoise>) noiseParametersHolderGetter),
            seed,
            (delegate.generatorSettings().value())
        );
    }
    
    public static NormalSkylandGenerator create(
        HolderGetter<Biome> biomeHolderGetter,
        HolderGetter<DensityFunction> densityFunctionHolderGetter,
        HolderGetter<NormalNoise> noiseParametersHolderGetter,
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
            // 26.3: record component surfaceRule -> Holder<MaterialRule> materialRule
            // (mc263-ref NoiseGeneratorSettings.java:29; mc262-ref :24).
            intrinsicSkylandNGS.materialRule(),
            intrinsicSkylandNGS.spawnTarget(),
            0, // overwrite seaLevel
            intrinsicSkylandNGS.disableMobGeneration(),
            // 26.3: boolean aquifersEnabled -> Optional<Aquifer.Config> aquifers (mc263-ref :33; mc262-ref :28).
            intrinsicSkylandNGS.aquifers(),
            // 26.3: boolean oreVeinsEnabled (mc262-ref :29) no longer exists — ore veins are OreVeinRule entries
            // of the material rule passed through above (mc263-ref OverworldMaterialRules.java:460,471-487).
            intrinsicSkylandNGS.useLegacyRandomSource(),
            // 26.3: new trailing record component debugFunctions (mc263-ref :35), passed through like the rest.
            intrinsicSkylandNGS.debugFunctions()
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
        
        // 26.3: through the Platform seam — MinecraftForge retypes ChunkGenerator.featuresPerStep, so the accessor below
        // cannot bind (or even be loaded) there. Fabric/Quilt and NeoForge inherit the seam's default body, which IS
        // this exact accessor call (Platform.setChunkGeneratorFeaturesPerStep has the full note).
        //   (26.2) ((IEChunkGenerator_AlternateDim) result).ip_setFeaturesPerStep(
        com.warwa.seamlessportals.platform.Platform.get().setChunkGeneratorFeaturesPerStep(result,
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
        HolderGetter<NormalNoise> noiseParameters,
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
        // 26.3: (a) postProcess now takes the interpolation cell size explicitly. 26.2 took it from the skyland's
        // NoiseSettings = FLOATING_ISLANDS create(0, 256, 2, 1) -> cell width 8 / height 4 (mc262-ref
        // NoiseSettings.java:26,46-52); 26.3 NoiseSettings has no size fields and vanilla's own floatingIslands
        // passes (8, 4) (mc263-ref NoiseRouterData.java:498). (b) 26.2's NoiseChunk added the beardifier on top of
        // every router's finalDensity (mc262-ref NoiseChunk.java:156-158); 26.3 samples finalDensity as-is
        // (mc263-ref NoiseBasedChunkGenerator.java:409), so the router must add it itself, exactly as vanilla's
        // nether/caves/floatingIslands/end were ported (mc263-ref NoiseRouterData.java:478,493,498,507).
        DensityFunction finalDensity = DensityFunctions.add(
            IENoiseRouterData.ip_postProcess(postProcessorInput, 8, 4), DensityFunctions.beardifier()
        );
        // 26.3: NoiseRouter shrank from 15 to 8 components (mc263-ref NoiseRouter.java:9-18; mc262-ref :7-23):
        // barrier/floodedness/spread/lava moved into Aquifer.Config (mc263-ref NoiseRouterData.java:384-395),
        // veinToggle/veinRidged/veinGap into the material rule's OreVeinRule — all were zero() here;
        // preliminarySurfaceLevel is now named chunkSurfaceLevel (mc263-ref NoiseRouter.java:16).
        return new NoiseRouter(
            temperature,
            vegetation,
            DensityFunctions.zero(), // continents
            DensityFunctions.zero(), // erosion
            DensityFunctions.zero(), // depth
            DensityFunctions.zero(), // ridges
            DensityFunctions.zero(), // preliminarySurfaceLevel
            finalDensity
        );
    }

    private final NoiseBasedChunkGenerator delegate;
    
    @Override
    protected @NotNull MapCodec<? extends ChunkGenerator> codec() {
        return MAP_CODEC;
    }
    
    // 26.3: fillFromNoise/buildSurface/applyCarvers are ONE ChunkGenerator.buildTerrain(...) now (mc263-ref
    // ChunkGenerator.java:668-676; mc262-ref :123-125,430-432,637-639; statuses NOISE/SURFACE/CARVERS -> TERRAIN,
    // mc263-ref ChunkStatus.java:25). 26.2 overrode ONLY the NOISE phase (delegate + delegatedRandomState) and left
    // SURFACE + CARVERS to the inherited vanilla code on `this` (overworld settings + the level's RandomState). That
    // split is kept: the body is vanilla's own buildTerrain sequence, line for line and in vanilla's order (mc263-ref
    // NoiseBasedChunkGenerator.java:354-401; `this.settings` is private, read through generatorSettings(), :127-129),
    // with ONLY the fill phase taken from the delegate. The four pieces it calls are private in vanilla (:99, :212,
    // :226, :403) and widened by the 26.3 seamlessportals.accesswidener / accesstransformer.cfg entries.
    // NoiseChunk mapping: 26.2 filled with the DELEGATE's NoiseChunk (delegate.doFill -> getOrCreateNoiseChunk ->
    // delegate.createNoiseChunk, mc262-ref NoiseBasedChunkGenerator.java:378), and because this override nulled the
    // chunk's cached NoiseChunk before and after that fill, SURFACE then created a fresh one from `this` (mc262-ref
    // :295) which CARVERS reused from the cache (mc262-ref :312). 26.3 has no per-chunk cache — ChunkAccess.noiseChunk
    // is gone (mc262-ref ChunkAccess.java:71,415-421), so the two ip_setNoiseChunk(null) resets that stood here are
    // deleted — and the same two NoiseChunks are created explicitly instead: the delegate's for the fill, `this`'s
    // threaded through surface -> carvers exactly as vanilla threads its own.
    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(
        ChunkAccess chunkAccess, Blender blender, RandomState pRandomState,
        StructureManager structureManager, BiomeManager biomeManager,
        @Nullable WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes
    ) {
        NoiseSettings noiseSettings = this.generatorSettings().value().noiseSettings().clampToHeightAccessor(chunkAccess.getHeightAccessorForGeneration());
        // 26.3: the delegate's own clamped NoiseSettings — what delegate.fillFromNoise derived for its fill in 26.2
        // (mc262-ref NoiseBasedChunkGenerator.java:350) and what vanilla derives the same way now (mc263-ref :364).
        NoiseSettings delegateNoiseSettings = delegate.generatorSettings().value().noiseSettings().clampToHeightAccessor(chunkAccess.getHeightAccessorForGeneration());
        return noiseSettings.height() > 0 && !SharedConstants.debugVoidTerrain(chunkAccess.getPos()) ? CompletableFuture.supplyAsync(() -> {
            ProfilerFiller profiler = Profiler.get();

            // NOISE — the one phase the 26.2 override customised: the delegate's NoiseChunk (skyland settings +
            // delegatedRandomState) and the delegate's doFill. The height check is the 26.3 form of the delegate's own
            // 26.2 fill guard (`cellCountY <= 0 ? completedFuture`, mc262-ref :354 -> `height() > 0`, mc263-ref :365).
            if (delegateNoiseSettings.height() > 0) {
                try (NoiseChunk delegateNoiseChunk = delegate.createNoiseChunk(chunkAccess, structureManager, blender, delegatedRandomState, delegateNoiseSettings)) {
                    DensityVolume volume = delegateNoiseChunk.volume();
                    int topSectionIndex = chunkAccess.getSectionIndex(volume.maxBlockY());
                    int bottomSectionIndex = chunkAccess.getSectionIndex(volume.minBlockY());
                    Set<LevelChunkSection> sections = Sets.newHashSet();

                    for (int sectionIndex = topSectionIndex; sectionIndex >= bottomSectionIndex; sectionIndex--) {
                        LevelChunkSection section = chunkAccess.getSection(sectionIndex);
                        section.acquire();
                        sections.add(section);
                    }

                    try (Zone ignored = profiler.zone("doFill")) {
                        delegate.doFill(delegateNoiseChunk, chunkAccess);
                    } finally {
                        for (LevelChunkSection section : sections) {
                            section.release();
                        }
                    }
                }
            }

            // SURFACE + CARVERS — never overridden in 26.2, so exactly vanilla's lines for `this`: one fresh NoiseChunk
            // from `this` (overworld settings + the level's RandomState) threaded through both (mc263-ref :368,388-396).
            try (NoiseChunk noiseChunk = this.createNoiseChunk(chunkAccess, structureManager, blender, pRandomState, noiseSettings)) {
                MaterialRule materialRule = this.generatorSettings().value().materialRule().value();

                try (Zone ignored = profiler.zone("buildSurface")) {
                    this.buildSurface(chunkAccess, noiseChunk, pRandomState, biomeManager, possibleBiomes, materialRule);
                }

                try (Zone ignored = profiler.zone("generateCarvers")) {
                    this.generateCarvers(chunkAccess, blender, noiseChunk, pRandomState, biomeManager, carverBiomeRegion, materialRule);
                }

                return chunkAccess;
            }
        }, Util.backgroundExecutor().forName("buildTerrain")) : CompletableFuture.completedFuture(chunkAccess);
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
