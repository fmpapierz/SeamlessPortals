package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * C2-0 probe P7a — {@code migration/C2_DESIGN.md} §4 P7(a), stage C2-0 deliverable 4(i).
 *
 * <p>SIBLING of {@code com.warwa.seamlessportals.mixin.client.ShaderManagerCompilationCacheMixin}
 * (the vanilla shader-source clip transport). It targets the SAME seam —
 * {@code ShaderManager$CompilationCache.getShaderSource(Identifier, ShaderType)} at RETURN — but
 * does NOTHING except record which {@code (namespace, path, type)} identifiers flow through it
 * WHILE SODIUM IS LOADED. The question it answers: do {@code sodium:} namespaced shaders resolve
 * through Mojang's blaze3d source seam (expected YES — Sodium 0.9.1 hands
 * {@code Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque")} to Mojang's
 * pipeline), which decides whether the C2-2 clip transport can reuse this exact seam for the
 * sodium namespace ({@code C2_091_MAP.md} §C).
 *
 * <p>Gated behind {@code -Dseamlessportals.compatProbe=true}, {@code require = 0} (a diagnostic
 * must never gate the boot), LOG-ONLY, ONE-SHOT per distinct id (shader compilation is naturally
 * low-frequency, so no time throttle is needed — the set-guard bounds the log volume). The vanilla
 * mixin is untouched; this is a pure observer. The class name carries {@code Sodium} so the
 * composed plugin's gate 1 weaves it only when Sodium is present.
 */
@Mixin(targets = "net/minecraft/client/renderer/ShaderManager$CompilationCache")
public abstract class MixinSodiumProbe_ShaderSources {

    private static final boolean seamlessportals$probe = Boolean.getBoolean("seamlessportals.compatProbe");

    /** Distinct {@code namespace|path|type} keys already logged. */
    private static final Set<String> seamlessportals$seenShaderIds =
        Collections.synchronizedSet(new HashSet<>());

    @Inject(
        method = "getShaderSource(Lnet/minecraft/resources/Identifier;Lcom/mojang/blaze3d/shaders/ShaderType;)Ljava/lang/String;",
        at = @At("RETURN"),
        require = 0
    )
    private void seamlessportals$probeShaderSource(
            Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
        if (!seamlessportals$probe) {
            return;
        }
        if (id == null || type == null) {
            return;
        }
        String key = id.getNamespace() + "|" + id.getPath() + "|" + type;
        if (!seamlessportals$seenShaderIds.add(key)) {
            return; // one-shot per distinct id
        }
        com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.info(
            "[COMPAT PROBE P7a] shader-source seam id: namespace={} path={} type={} sourcePresent={}",
            id.getNamespace(), id.getPath(), type,
            cir.getReturnValue() != null);
    }
}
