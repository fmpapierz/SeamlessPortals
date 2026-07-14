package qouteall.imm_ptl.core.ducks;

import net.minecraft.world.level.TicketStorage;

public interface IEDistanceManager {

    // R10 (api-map chunk-loading #18): 26.2 moved ticket storage off DistanceManager into a
    // per-level TicketStorage; the 1.21.3 portal_getTicketSet(long) -> SortedArraySet<Ticket<?>>
    // path is GONE (Ticket is non-generic). ImmPtlChunkTickets reaches the storage through this
    // one accessor (implemented in mixin/common/chunk_sync/MixinDistanceManager).
    TicketStorage ip_getTicketStorage();
    
    
}
