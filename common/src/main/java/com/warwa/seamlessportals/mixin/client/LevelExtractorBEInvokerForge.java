package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 26.3 FORGE-ONLY ({@code FORGE_ONLY_MIXINS}) loader-shape variant of {@link LevelExtractorBEInvokerVanilla}.
 *
 * <p>MinecraftForge 26.3-66.0.2 replaces vanilla's 3-arg block-entity extract with
 * {@code private void extractVisibleBlockEntities(Camera, float, LevelRenderState, Frustum)} (javap -p on the Forge
 * jar — the 3-arg form is GONE, unlike NeoForge which keeps both). The frustum is NOT optional here: javap -c shows
 * {@code aload 4; aload 19; invokevirtual BlockEntity.getRenderBoundingBox; invokevirtual Frustum.isVisible} with no
 * null test (NeoForge's form is {@code @Nullable} and its 3-arg delegate passes {@code null}). Forge's own
 * {@code extract()} feeds it the SAME frustum local it feeds {@code extractVisibleEntities} (slot 6 at offsets 629 and
 * 651), so callers pass the pass frustum they already give the entity extract — see
 * {@code com.warwa.seamlessportals.render.BlockEntityExtractInvoke}.
 */
@Mixin(LevelExtractor.class)
public interface LevelExtractorBEInvokerForge {

    // remap = false: the 4-arg target exists only in the Forge-patched jar (26.x is unobfuscated — there is nothing to
    // remap on any loader; this only keeps the annotation processor from looking the target up in the vanilla jar).
    @org.spongepowered.asm.mixin.gen.Invoker(value = "extractVisibleBlockEntities", remap = false)
    void seamlessportals$invokeExtractVisibleBlockEntitiesForge(
        net.minecraft.client.Camera camera,
        float deltaPartialTick,
        net.minecraft.client.renderer.state.level.LevelRenderState output,
        net.minecraft.client.renderer.culling.Frustum frustum);
}
