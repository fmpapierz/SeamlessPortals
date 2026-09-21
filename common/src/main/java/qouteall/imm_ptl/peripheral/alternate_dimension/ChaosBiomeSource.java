package qouteall.imm_ptl.peripheral.alternate_dimension;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.jetbrains.annotations.NotNull;
import qouteall.imm_ptl.core.McHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

// 26.3: BiomeSource no longer implements BiomeResolver and its abstract getNoiseBiome(x, y, z, Climate.Sampler) became
// abstract createResolver(Climate.Sampler) (mc263-ref BiomeSource.java:28,156; mc262-ref :27,171). Ported the way vanilla
// ported its own sampler-independent sources: implement BiomeResolver and hand out `this`
// (mc263-ref CheckerboardColumnBiomeSource.java:10,32-35,43).
public class ChaosBiomeSource extends BiomeSource implements BiomeResolver {

    public static final String[] vanillaBiomes = new String[]{
        "minecraft:savanna_plateau",
        "minecraft:taiga",
        "minecraft:savanna",
        "minecraft:dripstone_caves",
        "minecraft:swamp",
        "minecraft:basalt_deltas",
        "minecraft:ice_spikes",
        "minecraft:crimson_forest",
        "minecraft:frozen_peaks",
        "minecraft:dark_forest",
        "minecraft:lush_caves",
        "minecraft:old_growth_spruce_taiga",
        "minecraft:deep_dark",
        "minecraft:frozen_river",
        "minecraft:lukewarm_ocean",
        "minecraft:mushroom_fields",
        "minecraft:warm_ocean",
        "minecraft:forest",
        "minecraft:end_midlands",
        "minecraft:windswept_forest",
        "minecraft:deep_ocean",
        "minecraft:sunflower_plains",
        "minecraft:stony_peaks",
        "minecraft:stony_shore",
        "minecraft:nether_wastes",
        "minecraft:deep_lukewarm_ocean",
        "minecraft:flower_forest",
        "minecraft:old_growth_birch_forest",
        "minecraft:desert",
        "minecraft:snowy_taiga",
        "minecraft:beach",
        "minecraft:grove",
        "minecraft:deep_frozen_ocean",
        "minecraft:river",
        "minecraft:old_growth_pine_taiga",
        "minecraft:the_void",
        "minecraft:deep_cold_ocean",
        "minecraft:windswept_gravelly_hills",
        "minecraft:snowy_plains",
        "minecraft:end_highlands",
        "minecraft:jagged_peaks",
        "minecraft:eroded_badlands",
        "minecraft:bamboo_jungle",
        "minecraft:end_barrens",
        "minecraft:plains",
        "minecraft:small_end_islands",
        "minecraft:meadow",
        "minecraft:the_end",
        "minecraft:snowy_beach",
        "minecraft:sparse_jungle",
        "minecraft:jungle",
        "minecraft:snowy_slopes",
        "minecraft:birch_forest",
        "minecraft:mangrove_swamp",
        "minecraft:ocean",
        "minecraft:cold_ocean",
        "minecraft:soul_sand_valley",
        "minecraft:warped_forest",
        "minecraft:badlands",
        "minecraft:windswept_hills",
        "minecraft:windswept_savanna",
        "minecraft:wooded_badlands"
    };
    
    public static final MapCodec<ChaosBiomeSource> MAP_CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(
                Biome.LIST_CODEC.fieldOf("biomes")
                    .forGetter(checkerboardColumnBiomeSource -> checkerboardColumnBiomeSource.allowedBiomes)
            )
            .apply(instance, ChaosBiomeSource::new)
    );
    private final HolderSet<Biome> allowedBiomes;
    
    public ChaosBiomeSource(HolderSet<Biome> holderSet) {
        super();
        this.allowedBiomes = holderSet;
    }
    
    @NotNull
    public static ChaosBiomeSource createChaosBiomeSource(HolderGetter<Biome> biomeHolderGetter) {
        List<Holder<Biome>> holders = new ArrayList<>();
    
        for (String vanillaBiomeId : vanillaBiomes) {
            biomeHolderGetter.get(ResourceKey.create(
                Registries.BIOME,
                McHelper.newResourceLocation(vanillaBiomeId)
            )).ifPresent(holders::add);
        }
        
        ChaosBiomeSource chaosBiomeSource = new ChaosBiomeSource(
            HolderSet.direct(holders)
        );
        return chaosBiomeSource;
    }
    
    private Holder<Biome> getRandomBiome(int x, int z) {
        int biomeNum = allowedBiomes.size();
        
        int index = (Math.abs((int) LinearCongruentialGenerator.next(x / 5, z / 5))) % biomeNum;
        return allowedBiomes.get(index);
    }
    
    // 26.3: new abstract BiomeSource.createResolver (mc263-ref BiomeSource.java:156).
    @Override
    public BiomeResolver createResolver(Climate.Sampler sampler) {
        return this;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return MAP_CODEC;
    }
    
    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return allowedBiomes.stream();
    }
    
    // 26.3: BiomeResolver.getNoiseBiome lost its Climate.Sampler parameter (mc263-ref BiomeResolver.java:10).
    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        return getRandomBiome(x, z);
    }
    
}
