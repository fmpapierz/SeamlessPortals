package com.warwa.seamlessportals.forge.mixin;

import com.warwa.seamlessportals.forge.duck.ChunkGeneratorFeaturesPerStepForge;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.common.util.ClearableLazy;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.function.Supplier;

/**
 * 26.3 FORGE — the MinecraftForge stand-in for the common accessor mixin
 * {@code qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IEChunkGenerator_AlternateDim}.
 *
 * <p>On vanilla/Fabric and NeoForge that interface is itself the mixin: a {@code @Mutable @Accessor("featuresPerStep")}
 * setter taking the field's own type, {@code Supplier<List<FeatureSorter.StepFeatureData>>}. MinecraftForge
 * 26.3-66.0.2 RETYPES the field — javap -p on the Forge jar:
 * {@code private final net.minecraftforge.common.util.ClearableLazy<List<FeatureSorter$StepFeatureData>> featuresPerStep}
 * (it backs Forge's added {@code ChunkGenerator.refreshFeaturesPerStep()} -> {@code featuresPerStep.invalidate()}) — so
 * an accessor with the {@code Supplier} descriptor has no field to bind and would fail the whole mixin (static audit:
 * "@Accessor 'featuresPerStep' type ClearableLazy vs handler Supplier"). The accessor is in the D3 both-flag-states
 * set, i.e. that failure would be a boot failure in every configuration. The mixin plugin therefore SKIPS the accessor
 * on Forge ({@code NON_FORGE_MIXINS}) and this class supplies the same operation on the same target, behind the
 * forge module's own duck ({@link ChunkGeneratorFeaturesPerStepForge} — why not the common interface: see there).
 *
 * <p>It lives in the forge module because it must name a Forge type. The wrap is the one Forge's own
 * {@code ChunkGenerator} constructor uses for this field (javap -c: {@code invokestatic ClearableLazy.concurrentOf
 * (Supplier)} then {@code putfield featuresPerStep}), so the value installed here is the same kind of object Forge
 * would have built from the caller's supplier — and {@code get()} on it is what every Forge-side reader calls, as
 * {@code Supplier.get()} is on the other loaders. One caller: {@code NormalSkylandGenerator}, through the
 * {@code Platform} seam.
 */
@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGenerator_AlternateDimForge implements ChunkGeneratorFeaturesPerStepForge {

    @Shadow @Final @Mutable
    private ClearableLazy<List<FeatureSorter.StepFeatureData>> featuresPerStep;

    @Override
    public void seamlessportals$setFeaturesPerStep(Supplier<List<FeatureSorter.StepFeatureData>> arg) {
        this.featuresPerStep = ClearableLazy.concurrentOf(arg);
    }
}
