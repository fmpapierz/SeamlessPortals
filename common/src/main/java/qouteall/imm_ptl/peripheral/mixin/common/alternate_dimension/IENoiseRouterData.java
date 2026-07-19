package qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension;

import net.minecraft.core.HolderGetter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NoiseRouterData.class)
public interface IENoiseRouterData {
    @Invoker("end")
    public static NoiseRouter ip_end(HolderGetter<DensityFunction> holderGetter){
        throw new RuntimeException();
    }

    // S19-D (derivation chain 1.21.3 -> 1.21.11 -> 26.2): the 1.21.3/1.21.11 @Invoker("noNewCaves")
    // is REMOVED here because 26.2 DELETED NoiseRouterData.noNewCaves. The private method survived
    // verbatim through 1.21.11 (mc-sources-1.21.11 NoiseRouterData:377-402; the 1.21.11 IP port kept
    // this @Invoker unchanged), but 26.2 refactored every caller off it: caves()/floatingIslands()
    // now route through simpleRouter(...) (which ZEROES temperature+vegetation) and nether() inlines
    // with the _NETHER noise variants + zero shift. No surviving 26.2 router reproduces noNewCaves
    // (which sets temperature/vegetation to real SHIFT_X/SHIFT_Z-based 2D noise while zeroing
    // everything else except finalDensity). The behaviour is re-derived mod-side in
    // NormalSkylandGenerator.ip_noNewCaves(...) from the still-present primitives declared below
    // (SHIFT_X/SHIFT_Z accessors + getFunction + postProcess invokers). See that method's javadoc.

    @Invoker("slideEndLike")
    public static DensityFunction ip_slideEndLike(DensityFunction densityFunction, int i, int j) {
        throw new RuntimeException();
    }

    @Invoker("getFunction")
    public static DensityFunction ip_getFunction(HolderGetter<DensityFunction> holderGetter, ResourceKey<DensityFunction> resourceKey) {
        throw new RuntimeException();
    }

    // S19-D noNewCaves re-derivation dependency: 26.2 NoiseRouterData.postProcess is still present
    // (private static, mc262-ref NoiseRouterData:320). 26.2-DELTA (inherited, toward-vanilla): 26.2's
    // postProcess reorders interpolated()/mul() vs 1.21.11's; using 26.2's own keeps the skyland
    // terrain consistent with how 26.2 generates its end/nether terrain.
    @Invoker("postProcess")
    public static DensityFunction ip_postProcess(DensityFunction densityFunction) {
        throw new RuntimeException();
    }

    // S19-D noNewCaves re-derivation dependency: the SHIFT_X/SHIFT_Z density-function keys are still
    // present in 26.2 (private static final, mc262-ref NoiseRouterData:37-38). noNewCaves fed these
    // through getFunction to build the temperature/vegetation shiftedNoise2d inputs.
    @Accessor("SHIFT_X")
    public static ResourceKey<DensityFunction> get_SHIFT_X(){throw new RuntimeException();}

    @Accessor("SHIFT_Z")
    public static ResourceKey<DensityFunction> get_SHIFT_Z(){throw new RuntimeException();}

    @Accessor("BASE_3D_NOISE_END")
    public static ResourceKey<DensityFunction> get_BASE_3D_NOISE_END(){throw new RuntimeException();}



}
