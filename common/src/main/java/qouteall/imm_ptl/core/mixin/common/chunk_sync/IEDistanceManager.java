package qouteall.imm_ptl.core.mixin.common.chunk_sync;

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Executor;

@Mixin(DistanceManager.class)
public interface IEDistanceManager {
    // R10 (api-map chunk-loading #18/#24): the "tickets" (GONE, moved to per-level TicketStorage)
    // and "ticketThrottler" (GONE, now ThrottlingChunkTaskDispatcher "ticketDispatcher"; unused by
    // IP) accessors are dropped. Only mainThreadExecutor survives (SAME field).
    @Accessor("mainThreadExecutor")
    Executor ip_getMainThreadExecutor();
}
