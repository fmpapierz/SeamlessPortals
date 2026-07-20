package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumClipShaderPatch;

/**
 * C2-2 D3 (M1) — the sodium clip-plane SOURCE PATCH (design {@code migration/C2_DESIGN.md}
 * §1 C2-2 deliverable 1 / §3.3.2; probe answer P7a in port-note {@code C2-sodium-iris.md} §3.1:
 * {@code sodium:blocks/block_layer_opaque} VERTEX+FRAGMENT DO flow through this exact seam).
 *
 * <p>SIBLING of the shipped vanilla transport
 * {@code com.warwa.seamlessportals.mixin.client.ShaderManagerCompilationCacheMixin} — same target
 * class, same method, same {@code @At("RETURN")}; the vanilla mixin is UNTOUCHED (it filters
 * {@code "minecraft"}-namespaced ids and early-returns for {@code sodium:} ids, so the two
 * handlers are branch-disjoint regardless of handler order). This class branches STRICTLY on
 * {@code "sodium".equals(namespace) && path.startsWith("blocks/block_layer_") && type == VERTEX}
 * — structurally inert for every other id, which is the §6.2 flag-agnostic-weave discipline
 * (formally: it is additionally sodium-present + flag-ON gated by the compat plugin, unlike the
 * vanilla sibling).
 *
 * <p>The FRAGMENT type is deliberately NOT patched: {@code gl_ClipDistance} is a vertex-stage
 * output consumed by fixed-function clipping before rasterization; the 0.9.1
 * {@code block_layer_opaque.fsh} needs (and receives) nothing.
 *
 * <p>The patch body, anchors (unzip-verified), guards (double-patch/P11, anchor fail-safe), the
 * D4 VK self-gate, and the {@code -Dseamlessportals.compatProbe=true} transformed-source dump all
 * live in {@link SodiumClipShaderPatch} — see its javadoc for the evidence trail.
 *
 * <p>The class name carries {@code Sodium} for the compat plugin's substring gate (the documented
 * footgun). {@code require = 1}: a drift of this vanilla seam must fail LOUD — the sodium clip
 * transport silently not weaving would resurrect the undefined-behavior class the retired C2-1
 * interim bracket existed to prevent.
 */
@Mixin(targets = "net/minecraft/client/renderer/ShaderManager$CompilationCache")
public abstract class MixinSodiumShaderManagerCompilationCache_ClipSourcePatch {

    @Inject(
        method = "getShaderSource(Lnet/minecraft/resources/Identifier;Lcom/mojang/blaze3d/shaders/ShaderType;)Ljava/lang/String;",
        at = @At("RETURN"),
        cancellable = true,
        require = 1
    )
    private void ip_patchSodiumTerrainVertexSource(
        Identifier id, ShaderType type, CallbackInfoReturnable<String> cir
    ) {
        if (type != ShaderType.VERTEX) {
            return;
        }
        if (id == null || !"sodium".equals(id.getNamespace())) {
            return;
        }
        if (!id.getPath().startsWith("blocks/block_layer_")) {
            return;
        }
        String source = cir.getReturnValue();
        if (source == null) {
            return;
        }
        // D4 VK self-gate — lazy, evaluated at the first sodium terrain-shader compile (device
        // active by then; mixins weave far earlier, so an install-time check is impossible).
        if (!SodiumClipShaderPatch.isGlBackend()) {
            return;
        }
        String patched = SodiumClipShaderPatch.patchTerrainVertex(source, id.toString());
        if (patched != source) {
            cir.setReturnValue(patched);
        }
    }
}
