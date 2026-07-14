package qouteall.imm_ptl.core.mixin.common.miscellaneous;

import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(EnderDragonFight.class)
public interface IEEndDragonFight {
    @Accessor("needsStateScanning")
    boolean ip_getNeedsStateScanning();
    
    @Invoker("scanState")
    void ip_scanState();
}
