package qouteall.imm_ptl.core.mixin.client.accessor;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// S12-B port disposition: PORTS-CLEAN (mixin-client.md §2). 26.2 target unchanged —
// `ClientLevel.ClientLevelData.isFlat` is `private final boolean isFlat` (26.2:ClientLevel.java:1151,
// ctor :1157). Verbatim IP accessor. Used by ClientWorldLoader when cloning level data for secondary
// worlds. Held/unregistered until S13.
@Mixin(ClientLevel.ClientLevelData.class)
public interface IEClientLevelData {
    @Accessor("isFlat")
    public boolean ip_getIsFlat();
}
