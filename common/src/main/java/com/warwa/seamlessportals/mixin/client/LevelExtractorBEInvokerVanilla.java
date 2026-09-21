package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 26.3 loader-shape variant ({@code NON_FORGE_MIXINS}: Fabric/Quilt and NeoForge) — the block-entity extract invoker
 * that used to live on {@link LevelExtractorAccessor}, moved out UNCHANGED because MinecraftForge has no such method.
 *
 * <p>Vanilla's private {@code extractVisibleBlockEntities(Camera, float, LevelRenderState)} exists on vanilla/Fabric
 * and on NeoForge (whose patch ADDS a 4-arg {@code (.., @Nullable Frustum)} form and keeps the 3-arg one as a
 * {@code null}-passing delegate — javap -c). MinecraftForge 26.3-66.0.2 REPLACES it: the 4-arg form is the only one
 * (javap -p on the Forge jar), so an {@code @Invoker} with this descriptor cannot resolve there and would fail the
 * whole accessor mixin. The Forge form is {@link LevelExtractorBEInvokerForge}; callers go through
 * {@code com.warwa.seamlessportals.render.BlockEntityExtractInvoke}, which picks whichever interface the extractor
 * actually carries. Everything the original invoker's note said still holds — it is kept on
 * {@link LevelExtractorAccessor} at the invoker's old position.
 */
@Mixin(LevelExtractor.class)
public interface LevelExtractorBEInvokerVanilla {

    @org.spongepowered.asm.mixin.gen.Invoker("extractVisibleBlockEntities")
    void seamlessportals$invokeExtractVisibleBlockEntities(
        net.minecraft.client.Camera camera,
        float deltaPartialTick,
        net.minecraft.client.renderer.state.level.LevelRenderState output);
}
