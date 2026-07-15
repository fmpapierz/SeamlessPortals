package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;

// S12-B port disposition: PORTS-CLEAN (mixin-client.md §1). Empty placeholder targeting
// `net.minecraft.network.Connection` (exists on 26.2). Verbatim IP. Held/unregistered until S13.
@Mixin(Connection.class)
public class MixinClientConnection {

}
