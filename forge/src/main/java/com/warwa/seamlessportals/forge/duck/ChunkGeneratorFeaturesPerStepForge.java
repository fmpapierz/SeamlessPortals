package com.warwa.seamlessportals.forge.duck;

import net.minecraft.world.level.biome.FeatureSorter;

import java.util.List;
import java.util.function.Supplier;

/**
 * 26.3 FORGE — the duck {@code MixinChunkGenerator_AlternateDimForge} puts on {@code ChunkGenerator}: the Forge
 * stand-in for the common accessor mixin {@code IEChunkGenerator_AlternateDim}.
 *
 * <p>Deliberately a PLAIN interface OUTSIDE every mixin package. The common accessor interface cannot be reused on
 * Forge: the mixin plugin vetoes it there (its {@code Supplier}-typed accessor has no field to bind — Forge retypes
 * {@code featuresPerStep} to {@code ClearableLazy}), and a vetoed accessor-mixin interface is not loadable by ordinary
 * code — Mixin registers an accessor as pass-through only if it still has a target after the veto (sponge/upstream
 * {@code MixinConfig.prepareMixins}: {@code onPrepare} inside {@code if (getTargetClasses().size() > 0)}); anything
 * that resolves it gets {@code IllegalClassLoadError}. A mixin that {@code implements} it would make
 * {@code ChunkGenerator} itself unloadable.
 */
public interface ChunkGeneratorFeaturesPerStepForge {

    void seamlessportals$setFeaturesPerStep(Supplier<List<FeatureSorter.StepFeatureData>> arg);
}
