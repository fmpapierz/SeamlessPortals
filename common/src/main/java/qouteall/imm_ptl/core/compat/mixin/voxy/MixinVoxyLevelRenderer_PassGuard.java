package qouteall.imm_ptl.core.compat.mixin.voxy;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.voxy_compatibility.VoxyInterface;

/**
 * Voxy compat — the pass guard (design: {@code migration/VOXY_COMPAT.md};
 * facade: {@link VoxyInterface}).
 *
 * <p>Targets the two {@code IVoxyRenderSystemHolder} methods Voxy's own
 * {@code me.cortex.voxy.client.mixin.minecraft.MixinLevelRenderer} MERGES into
 * {@code LevelRenderer} (voxy-0.2.19-beta, decompiled 2026-10-09). Hence {@code priority = 1500}:
 * Voxy's mixin runs at the default 1000, so by the time this one applies the methods exist on the
 * target. {@code remap = false}: the names are Voxy's, not Mojang's (and this build is remap-free).
 * {@code require} stays the config default (1): if a Voxy update renames either method the boot
 * fails LOUDLY instead of silently shipping an unguarded portal pass.
 *
 * <ul>
 *   <li>{@code voxy$getRenderSystem()} — the single resolution point behind
 *       {@code IVoxyRenderSystemHolder.getNullable()}, which every Voxy hook calls on
 *       {@code Minecraft.getInstance().levelRenderer} at call time. {@link VoxyInterface#shouldHideRenderSystem}
 *       decides: inside a portal pass the renderer driving the pass answers normally when the
 *       dest-LOD feature is on (the sodium cull-stream reset/puts, the CUTOUT LOD draw, the fog
 *       push-out and the upload ingest then all run for the DEST world, with the dest renderer's
 *       own system — the ingest lands in the right LOD store), and every renderer answers
 *       {@code null} otherwise (tier 1). Outside a pass, the non-render secondary contexts hide.
 *       A visible call also performs a PENDING creation (below).</li>
 *   <li>{@code voxy$createRenderer()} — in a secondary context, or while the renderer may not
 *       create under the shaderpack ({@link VoxyInterface.Invoker#canCreateNow}: Iris's current
 *       pipeline is not the renderer's own), the creation is NOT run but marked pending on that
 *       renderer, and performed at its first visible {@code voxy$getRenderSystem} call that may:
 *       for a secondary renderer that is its first portal pass (where {@code mc.level} IS that
 *       dimension, which Voxy's constructor reads for the Y bounds, where Iris has just prepared
 *       the dest pipeline, and where the pass's own cull hooks are the first callers — before any
 *       draw); for the main renderer it is the next main-pass hook. Two main-renderer cases
 *       (both observed 2026-10-09): Iris's dest-dimension pipeline creation reloads the main
 *       extractor from inside the dest bracket; and a between-frames reload (a render-distance
 *       change) runs while Iris still holds the LAST portal pass's dest pipeline as current —
 *       creating there bound the main system to the dest pipeline's data ("Pipeline data already
 *       bound"), upon which Voxy switched the shaders off. Creation sites in a secondary
 *       context: {@code createSecondaryClientWorld}'s setLevel → allChanged, the reload cascade's
 *       {@code withSwitchedWorld} allChanged, that Iris reload. The facade's own creation
 *       ({@link VoxyInterface#creatingNow}) passes through.</li>
 * </ul>
 *
 * <p>Both bodies are a few static reads — zero cost off the portal path. Woven only flag-ON +
 * voxy-present ({@code IPCompatMixinPlugin}: the class name carries {@code Voxy} for the
 * substring gate; it must NOT carry {@code Sodium} or {@code Iris}).
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public abstract class MixinVoxyLevelRenderer_PassGuard {

    /** Voxy asked to create this renderer's system while that was unsafe; done at the next visible call. */
    @Unique
    private boolean ip_voxyPendingCreate = false;

    @Inject(method = "voxy$getRenderSystem", at = @At("HEAD"), cancellable = true, remap = false)
    private void ip_voxyGateRenderSystem(CallbackInfoReturnable<?> cir) {
        LevelRenderer self = (LevelRenderer) (Object) this;
        if (VoxyInterface.shouldHideRenderSystem(self)) {
            cir.setReturnValue(null);
            return;
        }
        if (this.ip_voxyPendingCreate && !VoxyInterface.creatingNow
            && self == net.minecraft.client.Minecraft.getInstance().levelRenderer
            && VoxyInterface.invoker.canCreateNow(self)) {
            // Only the renderer currently driving the frame creates (the main one between passes,
            // a dest one inside its own pass): Voxy's hooks all go through mc.levelRenderer, and
            // a probe of some other renderer (the smoke test's census) must not create anything.
            // Consume the deferral BEFORE the re-entrant call: its HEAD guard runs again and must
            // not re-arm; a creation that Voxy itself declines ("Not creating renderer due to …")
            // is logged once, not every call.
            this.ip_voxyPendingCreate = false;
            VoxyInterface.invoker.createRendererNow(self);
        }
    }

    @Inject(method = "voxy$createRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private void ip_voxyDeferCreation(CallbackInfo ci) {
        LevelRenderer self = (LevelRenderer) (Object) this;
        if (VoxyInterface.creatingNow
            || (!VoxyInterface.isSecondaryContext() && VoxyInterface.invoker.canCreateNow(self))) {
            // A creation that goes through (login, respawn, F3+A, the facade's own) supersedes
            // any pending deferral — a second creation on a live system would throw in Voxy.
            this.ip_voxyPendingCreate = false;
            return;
        }
        // Voxy's own allChanged hook has already shut the old system down (HEAD of allChanged:
        // shutdown, then this), so nothing stale survives the deferral — the renderer simply has
        // no system until its next own pass.
        this.ip_voxyPendingCreate = true;
        ci.cancel();
    }
}
