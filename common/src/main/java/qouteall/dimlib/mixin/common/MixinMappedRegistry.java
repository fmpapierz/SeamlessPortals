// F11 DimLib — S19-D LANDED (migration/port-notes/S19-peripheral-tail.md §6). Port of the REAL
// DimLib MixinMappedRegistry, trimmed to the load-window need: expose the `frozen` flag so
// DimensionImpl.directlyRegisterLevelStem can temporarily unfreeze the LEVEL_STEM registry to
// register an alt-dim stem, then restore. On 26.2 `frozen` is a NON-FINAL private boolean
// (mc262-ref MappedRegistry:45) — a plain @Shadow read/write suffices; NO @Mutable/@Final,
// NO accesswidener, NO access-transformer. Woven flag-ON only (the qouteall.* gate in
// SeamlessMixinConfigPlugin). Deleted with the scaffolding at S20.
package qouteall.dimlib.mixin.common;

import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.dimlib.ducks.IMappedRegistry;

@Mixin(MappedRegistry.class)
public abstract class MixinMappedRegistry implements IMappedRegistry {

    @Shadow
    private boolean frozen;

    @Override
    public boolean dimlib_getIsFrozen() {
        return frozen;
    }

    @Override
    public void dimlib_setIsFrozen(boolean cond) {
        frozen = cond;
    }
}
