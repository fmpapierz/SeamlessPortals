package qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkAccess.class)
public interface IEChunkAccess_AlternateDim {
    // 26.3: ChunkAccess.noiseChunk no longer exists — the per-chunk NoiseChunk cache (field + getOrCreateNoiseChunk,
    // mc262-ref ChunkAccess.java:71,415-421) was deleted; mc263-ref ChunkAccess.java has neither and javap on the real
    // 26.3 jar lists no such field. An @Accessor without a target fails mixin application on ChunkAccess, so it is
    // disabled here. Its only callers are in NormalSkylandGenerator.fillFromNoise (see the 26.3 note there).
//    @Accessor("noiseChunk")
//    void ip_setNoiseChunk(NoiseChunk noiseChunk);
}
