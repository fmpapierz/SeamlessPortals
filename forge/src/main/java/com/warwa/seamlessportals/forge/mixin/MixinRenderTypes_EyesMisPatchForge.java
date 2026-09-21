package com.warwa.seamlessportals.forge.mixin;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.ForgeRenderTypes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;

/**
 * 26.3 FORGE — workaround for a MinecraftForge 26.3-66.0.2 mis-patch. ON by default (user decision 2026-09-20: "ship
 * the forge eyes fix on by default"); {@code -Dseamlessportals.disableForgeEyesMispatchFix=true} is the A/B lever that
 * hands the call back to Forge unchanged.
 *
 * <p><b>The Forge bug (javap, forge-26.3-66.0.2.jar vs the vanilla 26.3 jar).</b> Forge's {@code RenderTypes} patch hunk
 * for {@code text(Identifier)} landed on {@code eyes(Identifier)}: Forge's {@code eyes} is
 * {@code aload_0; invokestatic ForgeRenderTypes.getText; areturn}, while its {@code text} is still vanilla's
 * {@code TEXT.apply(..)}. So every {@code EyesLayer} (spider, enderman, phantom …) is drawn with Forge's
 * {@code forge_text} render type. {@code ForgeRenderTypes$Internal} builds ALL of its types without
 * {@code RenderSetup$RenderSetupBuilder.setOitPipelines(..)} (vanilla's {@code eyes}, {@code text},
 * {@code text_grayscale}, {@code text_polygon_offset} and {@code text_grayscale_polygon_offset} all have it), so with
 * Improved Transparency ON the first such mob in view kills the frame in VANILLA's own main pass:
 * {@code IllegalStateException: Render type forge_text does not have OIT pipelines set up}
 * ({@code PreparedRenderType.drawFromBufferOit} ← {@code RenderTypeFeatureRenderer.executeGroup} ←
 * {@code PreparedFrame.executeOit} ← {@code LevelRenderer.executeOit}). Found with a log-only submit probe: the submitter
 * was {@code EyesLayer.submit} → {@code OrderedSubmitNodeCollector.submitModel}. No mod frame is involved — it happens
 * without this mod too; portal views just put more endermen on screen.
 *
 * <p><b>What this does.</b> Redirects that ONE mis-placed call back to vanilla's own {@code EYES}
 * function (still present and initialised in Forge's class: {@code putstatic EYES} in {@code <clinit>}). The
 * {@code @Redirect} targets the {@code ForgeRenderTypes.getText} INVOKE inside {@code eyes} with {@code require = 0}, so
 * it retires itself the day Forge fixes the hunk (no such INVOKE → nothing to redirect).
 *
 * <p><b>What it does NOT do.</b> Forge's replacements for {@code textGrayscale}, {@code textPolygonOffset} (sign text)
 * and {@code textGrayscalePolygonOffset} also lack OIT pipelines, so sign text under Improved Transparency still throws
 * on this Forge build. That is Forge's to fix; undoing Forge's text render types wholesale is not this mod's business.
 */
@Mixin(RenderTypes.class)
public abstract class MixinRenderTypes_EyesMisPatchForge {

    @Unique
    private static final boolean SEAMLESSPORTALS$DISABLED_LEVER =
        Boolean.getBoolean("seamlessportals.disableForgeEyesMispatchFix");

    @Shadow
    @Final
    private static Function<Identifier, RenderType> EYES;

    @Redirect(
        method = "eyes",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraftforge/client/ForgeRenderTypes;getText(Lnet/minecraft/resources/Identifier;)"
                + "Lnet/minecraft/client/renderer/rendertype/RenderType;",
            remap = false
        ),
        require = 0
    )
    private static RenderType seamlessportals$vanillaEyes(Identifier texture) {
        return SEAMLESSPORTALS$DISABLED_LEVER ? ForgeRenderTypes.getText(texture) : EYES.apply(texture);
    }
}
