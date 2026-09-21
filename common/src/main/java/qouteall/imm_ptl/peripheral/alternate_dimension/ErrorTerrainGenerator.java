package qouteall.imm_ptl.peripheral.alternate_dimension;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.util.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.Zone;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import qouteall.q_misc_util.Helper;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ErrorTerrainGenerator extends DelegatedChunkGenerator {
    
    public static final MapCodec<ErrorTerrainGenerator> MAP_CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
                RegistryOps.retrieveGetter(Registries.BIOME),
                RegistryOps.retrieveGetter(Registries.NOISE_SETTINGS)
            )
            .apply(instance, ErrorTerrainGenerator::create)
    );
    
    public static ErrorTerrainGenerator create(
        HolderGetter<Biome> biomeHolderGetter,
        HolderGetter<NoiseGeneratorSettings> noiseGeneratorSettingsHolderGetter
    ) {
        ChaosBiomeSource chaosBiomeSource = ChaosBiomeSource.createChaosBiomeSource(biomeHolderGetter);
        
        NoiseGeneratorSettings skylandSetting = noiseGeneratorSettingsHolderGetter
            .getOrThrow(NoiseGeneratorSettings.FLOATING_ISLANDS).value();
        
        NoiseBasedChunkGenerator islandChunkGenerator = new NoiseBasedChunkGenerator(
            chaosBiomeSource, Holder.direct(skylandSetting)
        );
        
        return new ErrorTerrainGenerator(
            chaosBiomeSource, islandChunkGenerator
        );
    }
    
    public static final int regionChunkNum = 4;
    public static final int averageY = 64;
    public static final int maxY = 128;
    
    private final BlockState air = Blocks.AIR.defaultBlockState();
    private final BlockState defaultBlock = Blocks.STONE.defaultBlockState();
    private final BlockState defaultFluid = Blocks.WATER.defaultBlockState();
    
    private final LoadingCache<ChunkPos, RegionErrorTerrainGenerator> cache;
    
    
    public ErrorTerrainGenerator(
        BiomeSource biomeSource, ChunkGenerator delegate
    ) {
        super(biomeSource, delegate);
        
        cache = CacheBuilder.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build(
                new CacheLoader<ChunkPos, RegionErrorTerrainGenerator>() {
                    public RegionErrorTerrainGenerator load(ChunkPos key) {
                        return new RegionErrorTerrainGenerator(
                            key.x(), key.z(),
                            System.nanoTime()
                            // use the system time as seed
                            // there is no need to keep the error terrain generation consistent
                        );
                    }
                });
    }
    
    @Override
    protected @NotNull MapCodec<? extends ChunkGenerator> codec() {
        return MAP_CODEC;
    }
    
    // 26.3: fillFromNoise/buildSurface/applyCarvers are ONE ChunkGenerator.buildTerrain(...) now (mc263-ref
    // ChunkGenerator.java:668-676; mc262-ref :123-125,430-432,637-639). 26.2 overrode ONLY the NOISE phase with this
    // custom fill; SURFACE + CARVERS ran on `delegate` through the inherited DelegatedChunkGenerator.buildSurface /
    // applyCarvers delegations. That split is kept: the custom fill below is the 26.2 body verbatim, followed by the
    // delegate's surface + carvers exactly as vanilla's buildTerrain sequences them. delegate.buildTerrain(...) cannot
    // stand in for that — it always runs the delegate's own floating-islands fill first (mc263-ref
    // NoiseBasedChunkGenerator.java:381).
    @Override
    public @NotNull CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunkAccess, Blender blender, RandomState randomState, StructureManager structureManager, BiomeManager biomeManager, @Nullable WorldGenRegion worldGenRegion, Set<Holder<Biome>> possibleBiomes) {
        LevelChunkSection[] sectionArray = chunkAccess.getSections();
        ArrayList<LevelChunkSection> locked = new ArrayList<>();
        for (LevelChunkSection chunkSection : sectionArray) {
            if (chunkSection != null) {
                chunkSection.acquire();
                locked.add(chunkSection);
            }
        }
        return CompletableFuture.supplyAsync(() -> {
            doPopulateNoise(chunkAccess);
            return chunkAccess;
        }, Util.backgroundExecutor()).thenApplyAsync((chunkx) -> {
            for (LevelChunkSection chunkSection : locked) {
                chunkSection.release();
            }
            
            // 26.3: SURFACE + CARVERS of the delegate — vanilla's own lines in vanilla's order with `this` -> the delegate
            // (mc263-ref NoiseBasedChunkGenerator.java:364-368,388-396), run after the fill's sections are released just
            // as vanilla releases before them (:382-386). The pieces exist only on NoiseBasedChunkGenerator (private in
            // vanilla, widened by the 26.3 seamlessportals.accesswidener / accesstransformer.cfg entries), hence the
            // cast; create() always passes one. NoiseChunk mapping: in 26.2 the custom fill made no NoiseChunk,
            // delegate.buildSurface created one from the delegate (floating-islands settings + the level's RandomState,
            // mc262-ref NoiseBasedChunkGenerator.java:295) and delegate.applyCarvers reused it from the chunk's cache
            // (mc262-ref :312). The same single delegate NoiseChunk is created here and threaded through both, as
            // vanilla threads its own; the height/debug check is vanilla's guard around it (mc263-ref :365).
            NoiseBasedChunkGenerator noiseDelegate = (NoiseBasedChunkGenerator) delegate;
            NoiseSettings noiseSettings = noiseDelegate.generatorSettings().value().noiseSettings().clampToHeightAccessor(chunkx.getHeightAccessorForGeneration());
            if (noiseSettings.height() > 0 && !SharedConstants.debugVoidTerrain(chunkx.getPos())) {
                ProfilerFiller profiler = Profiler.get();

                try (NoiseChunk noiseChunk = noiseDelegate.createNoiseChunk(chunkx, structureManager, blender, randomState, noiseSettings)) {
                    MaterialRule materialRule = noiseDelegate.generatorSettings().value().materialRule().value();

                    try (Zone ignored = profiler.zone("buildSurface")) {
                        noiseDelegate.buildSurface(chunkx, noiseChunk, randomState, biomeManager, possibleBiomes, materialRule);
                    }

                    try (Zone ignored = profiler.zone("generateCarvers")) {
                        noiseDelegate.generateCarvers(chunkx, blender, noiseChunk, randomState, biomeManager, worldGenRegion, materialRule);
                    }
                }
            }

            return chunkx;
        }, Util.backgroundExecutor());
    }
    
    public void doPopulateNoise(ChunkAccess chunk) {
        ProtoChunk protoChunk = (ProtoChunk) chunk;
        ChunkPos pos = chunk.getPos();
        Heightmap oceanFloorHeightMap = protoChunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surfaceHeightMap = protoChunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        
        int regionX = Math.floorDiv(pos.x(), regionChunkNum);
        int regionZ = Math.floorDiv(pos.z(), regionChunkNum);
        RegionErrorTerrainGenerator generator = Helper.noError(() ->
            cache.get(new ChunkPos(regionX, regionZ))
        );
        
        for (int sectionY = 0; sectionY < 16; sectionY++) {
            LevelChunkSection section = protoChunk.getSection(sectionY);
            
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localY = 0; localY < 16; localY++) {
                        int worldX = pos.x() * 16 + localX;
                        int worldY = sectionY * 16 + localY;
                        int worldZ = pos.z() * 16 + localZ;
                        
                        BlockState currBlockState = generator.getBlockComposition(
                            worldX, worldY, worldZ
                        );
                        
                        if (currBlockState != air) {
                            section.setBlockState(localX, localY, localZ, currBlockState, false);
                            oceanFloorHeightMap.update(localX, worldY, localZ, currBlockState);
                            surfaceHeightMap.update(localX, worldY, localZ, currBlockState);
                        }
                    }
                }
            }
        }
    }
    
}
