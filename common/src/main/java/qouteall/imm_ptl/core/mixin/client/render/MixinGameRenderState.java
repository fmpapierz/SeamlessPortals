package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * S12-B (render client-mixin half) — <b>R13i</b>: the fabulous/transparency-suppression OVERRIDE
 * re-anchored (API_RISKS.md R13i; CUTOVER_SPEC §6.4; ducks-api-misc.md G10; mixin-client.md §1).
 *
 * <p><b>This is an OVERRIDE to port, not a read — "easy to silently drop from the checklist" (R13i).</b>
 * IP forces the fabulous transparency path OFF while a portal view renders, to avoid corrupting the
 * fabulous render targets. In 1.21.3 that was a HEAD-cancel {@code @Inject} into the STATIC
 * {@code Minecraft.useShaderTransparency()Z} ({@code IP:mixin/client/MixinMinecraft.java:178-183},
 * comment {@code :177} "avoid messing up rendering states in fabulous").
 *
 * <p><b>26.2 re-anchor.</b> {@code Minecraft.useShaderTransparency()} is GONE; the flag moved onto the
 * extracted render state as the INSTANCE method
 * {@code GameRenderState.useShaderTransparency()} ({@code 26.2:state/GameRenderState.java:17-19}:
 * {@code !levelRenderState.cameraRenderState.isPanoramicMode && optionsRenderState.improvedTransparency}),
 * reached by vanilla callers via {@code minecraft.gameRenderer.gameRenderState().useShaderTransparency()}
 * (e.g. {@code LevelRenderer.java:835}, {@code WeatherEffectRenderer.java:129},
 * {@code ItemFeatureRenderer.java:113}). The HEAD-cancel therefore retargets onto this method (and drops
 * IP's {@code static} — the 26.2 method is an instance method). Same force-false-while-portal-rendering
 * semantics; gate unchanged ({@code WorldRenderInfo.isRendering()}).
 *
 * <p><b>Distinct from the multiworld-half {@code MixinMinecraft} port.</b> IP carried this handler inside
 * its monolithic {@code MixinMinecraft}; because the target moved to {@code GameRenderState}, the 26.2
 * re-expression is this standalone render-slice mixin — the multiworld {@code MixinMinecraft} port omits
 * it (it keeps only the tick/lifecycle/duck handlers whose targets stayed on {@code Minecraft}).
 * Held/UNREGISTERED until S13.
 *
 * <p><b>26.3 re-anchor (the flag moved AGAIN — file kept at its 26.2 path, target moved, the same
 * convention as {@code MixinLivingEntity_C}).</b> {@code GameRenderState.useShaderTransparency()} is GONE
 * (javap on the 26.3 merged jar: no such member). Its successor is the instance method
 * {@code GameRenderer.useImprovedTransparency()Z} (mc263-ref GameRenderer.java:868-870:
 * {@code optionsRenderState.improvedTransparency && !levelRenderState.renderWireframeTerrain}); vanilla
 * re-pointed its own callers the same way (mc262-ref LevelRenderer.java:835 -> mc263-ref :1240), and it is
 * what now selects 26.3's order-independent-transparency path (mc263-ref LevelRenderer.java:197,219,269-271).
 * Same HEAD-cancel, same gate, same force-false-while-portal-rendering semantics.
 */
@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public class MixinGameRenderState {
    // avoid messing up rendering states in fabulous
    @Inject(method = "useImprovedTransparency", at = @At("HEAD"), cancellable = true)
    private void onIsFabulousGraphicsOrBetter(CallbackInfoReturnable<Boolean> cir) {
        if (WorldRenderInfo.isRendering()) {
            cir.setReturnValue(false);
        }
    }
}
