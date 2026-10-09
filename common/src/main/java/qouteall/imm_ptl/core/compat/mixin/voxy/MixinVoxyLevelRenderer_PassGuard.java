package qouteall.imm_ptl.core.compat.mixin.voxy;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
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
 *       {@code Minecraft.getInstance().levelRenderer} at call time. Answering {@code null} in a
 *       secondary context makes ALL of them inert inside a portal pass: the sodium cull-stream
 *       reset/puts ({@code RenderSectionManager.renderOutOfGraph/readRenderListFromTree},
 *       {@code VisibleChunkCollector.visit}), the CUTOUT LOD draw
 *       ({@code DefaultChunkRenderer.render}), the fog push-out ({@code FogRenderer.setupFog}),
 *       the mesh-upload ingest ({@code RenderSectionManager.updateSectionInfo} — which would
 *       otherwise write the DEST dimension's sections into the MAIN dimension's LOD store on a
 *       cross-dim pass), the fade cancel and the iris viewport apply. Outside a secondary
 *       context it also performs a DEFERRED creation (below) before answering.</li>
 *   <li>{@code voxy$createRenderer()} — in a secondary context: cancelled for a secondary
 *       renderer (so the per-dimension {@code LevelRenderer}s this mod creates never get a
 *       render system — creation runs under {@code createSecondaryClientWorld}'s setLevel →
 *       allChanged and the reload cascade's {@code withSwitchedWorld} allChanged), and DEFERRED
 *       for the MAIN renderer. The main case is real: Iris's pipeline creation for a dest
 *       dimension (the shaders-ON portal view) calls {@code allChanged()} on the main extractor
 *       from inside the dest bracket (observed 2026-10-09: "Creating pipeline for dimension
 *       the_nether" → Voxy "Render shutdown completed" → "WorldRenderer reloaded the_nether").
 *       Voxy's allChanged HEAD has already shut the main system down by then; creating it right
 *       there would read the SWITCHED {@code mc.level} for its Y bounds, so the creation is
 *       deferred to the first {@code voxy$getRenderSystem} call outside any bracket (the next
 *       main-pass hook), where {@code mc.level} is the player's level again.</li>
 * </ul>
 *
 * <p>Both bodies are a few static reads — zero cost off the portal path. Woven only flag-ON +
 * voxy-present ({@code IPCompatMixinPlugin}: the class name carries {@code Voxy} for the
 * substring gate; it must NOT carry {@code Sodium} or {@code Iris}).
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public abstract class MixinVoxyLevelRenderer_PassGuard {

    @Inject(method = "voxy$getRenderSystem", at = @At("HEAD"), cancellable = true, remap = false)
    private void ip_voxyHideRenderSystemInSecondaryContext(CallbackInfoReturnable<?> cir) {
        if (VoxyInterface.isSecondaryContext()) {
            cir.setReturnValue(null);
            return;
        }
        if (VoxyInterface.deferredMainCreate && VoxyInterface.isMainRenderer((LevelRenderer) (Object) this)) {
            // Consume the deferral BEFORE the re-entrant call: its HEAD guard runs again (not a
            // secondary context now) and must not re-arm; a creation that Voxy itself declines
            // ("Not creating renderer due to …") is logged once, not every call.
            VoxyInterface.deferredMainCreate = false;
            VoxyInterface.invoker.createRendererNow((LevelRenderer) (Object) this);
        }
    }

    @Inject(method = "voxy$createRenderer", at = @At("HEAD"), cancellable = true, remap = false)
    private void ip_voxyNoRenderSystemForSecondaryRenderers(CallbackInfo ci) {
        if (!VoxyInterface.isSecondaryContext()) {
            // A creation that goes through (login, respawn, F3+A, the promote hook) supersedes
            // any pending deferral — a second creation on a live system would throw in Voxy.
            VoxyInterface.deferredMainCreate = false;
            return;
        }
        if (VoxyInterface.isMainRenderer((LevelRenderer) (Object) this)) {
            VoxyInterface.deferredMainCreate = true;
        }
        ci.cancel();
    }
}
