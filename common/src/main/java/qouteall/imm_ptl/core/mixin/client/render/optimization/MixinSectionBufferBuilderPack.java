package qouteall.imm_ptl.core.mixin.client.render.optimization;

import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.IPGlobal;

/**
 * S12-B (render client-mixin half) — IP {@code MixinSectionBufferBuilderPack}
 * ({@code IP:mixin/client/render/optimization/MixinSectionBufferBuilderPack.java}), 26.2-RETARGETED
 * (mixin-client.md §8 NEEDS-RETARGET).
 *
 * <p><b>R13c — the synthetic lambda anchor is re-derived from the compiled 26.2 jar.</b> IP redirected
 * {@code RenderType.bufferSize()} inside the 1.21.3 field-initializer lambda {@code method_60896}. On 26.2
 * the sizing moved to {@code ChunkSectionLayer.bufferSize()} inside the field-init lambda of
 * {@code Util.makeEnumMap(ChunkSectionLayer.class, layer -> new ByteBufferBuilder(layer.bufferSize()))}
 * ({@code SectionBufferBuilderPack.java:13-15}). The compiled synthetic name is <b>{@code lambda$new$0}</b>
 * (verified via {@code javap -p} on {@code fabric-loom/26.2/minecraft-merged.jar}:
 * {@code private static ByteBufferBuilder lambda$new$0(ChunkSectionLayer)} — Mojang-mapped, NOT the
 * intermediary {@code method_60896}), and the redirect INVOKE target is {@code ChunkSectionLayer.bufferSize()}.
 * Held/UNREGISTERED until S13.
 */
@Mixin(SectionBufferBuilderPack.class)
public class MixinSectionBufferBuilderPack {
    // The buffer can grow size on demand.
    // There is no need to allocate a large buffer at the beginning.
    // That's not an issue of vanilla,
    // but with ImmPtl, each loaded dimension will have some buffer packs,
    // so memory could be exhausted.
    // This mixin will reduce memory usage.
    // The initial size cannot be 0, because it resizes in endVertex(), not before putting data.
    @Redirect(
        method = "lambda$new$0",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;bufferSize()I"
        )
    )
    private static int redirectBufferSize(ChunkSectionLayer instance) {
        if (!IPGlobal.saveMemoryInBufferPack) {
            return instance.bufferSize();
        }

        return Math.min(128, instance.bufferSize());
    }
}
