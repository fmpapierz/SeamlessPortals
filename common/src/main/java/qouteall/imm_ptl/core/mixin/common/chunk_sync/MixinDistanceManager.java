package qouteall.imm_ptl.core.mixin.common.chunk_sync;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTickets;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.ducks.IEDistanceManager;
import qouteall.imm_ptl.core.platform_specific.IPConfig;

@Mixin(DistanceManager.class)
public abstract class MixinDistanceManager implements IEDistanceManager {
    
    @Shadow
    @Final
    private Long2ObjectMap<ObjectSet<ServerPlayer>> playersPerChunk;
    
    // R10 (api-map chunk-loading #18): ticket storage moved off DistanceManager into a per-level
    // TicketStorage (DistanceManager.getTickets(long) / SortedArraySet<Ticket<?>> are GONE). The
    // IEDistanceManager accessor surface shrinks to this one ticketStorage reach.
    @Shadow
    @Final
    private TicketStorage ticketStorage;
    
    // avoid NPE
    @Inject(method = "Lnet/minecraft/server/level/DistanceManager;removePlayer(Lnet/minecraft/core/SectionPos;Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"))
    private void onHandleChunkLeave(
        SectionPos sectionPos,
        ServerPlayer serverPlayer,
        CallbackInfo ci
    ) {
        long chunkPos = sectionPos.chunk().pack();
        playersPerChunk.computeIfAbsent(chunkPos, k -> new ObjectOpenHashSet<>());
    }
    
    @Inject(
        method = "runAllUpdates",
        at = @At("RETURN")
    )
    private void onRunAllUpdates(ChunkMap chunkManager, CallbackInfoReturnable<Boolean> cir) {
        if (IPConfig.getConfig().enableImmPtlChunkLoading) {
            ServerLevel world = ((IEChunkMap) chunkManager).ip_getWorld();
            ImmPtlChunkTickets.get(world).flushThrottling(world);
        }
    }
    
    @Override
    public TicketStorage ip_getTicketStorage() {
        return ticketStorage;
    }
}
