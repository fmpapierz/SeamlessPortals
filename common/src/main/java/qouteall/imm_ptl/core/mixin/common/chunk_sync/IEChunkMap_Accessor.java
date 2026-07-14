package qouteall.imm_ptl.core.mixin.common.chunk_sync;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * api-map chunk-loading #55 (GONE): ChunkMap.getChunks() no longer exists - the holder data
 * lives in the visibleChunkMap field. 1.21.3's getChunks() returned visibleChunkMap.values(),
 * so the consumer (the report_chunk_ticket_stat debug command, U11 / S13) reads
 * ip_getVisibleChunkMap().values().
 */
@Mixin(ChunkMap.class)
public interface IEChunkMap_Accessor {
    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> ip_getVisibleChunkMap();
}
