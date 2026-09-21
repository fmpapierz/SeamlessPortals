package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.OitPathPortalSlot;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 SUBSTRATE — drives the mod's main-pass handlers on vanilla's improved-transparency (OIT) path, where neither
 * loader fires the translucent-terrain events they hang off on the classic path. The why, the measurement and the
 * citations are on {@link OitPathPortalSlot}; this class is only the wiring.
 *
 * <p>Target: {@code private void executeOit(ChunkSectionsToRender, FeatureRenderDispatcher$PreparedFrame)} — javap
 * 26.3: the same name and descriptor in the Fabric merged jar, the NeoForge-patched jar and the Forge jar, exactly
 * one such method. HEAD = after the "Solid" pass has closed ({@code lambda$addMainPass$0}: {@code RenderPass.close}
 * @197 precedes the {@code executeOit} invoke @242), before the first OIT stage. Always woven (both flag states): the
 * flag selects the driver family inside the slot, as it does in the loaders' client initialisers.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererOitPortalSlotMixin {

    @Inject(method = "executeOit", at = @At("HEAD"), require = 1, allow = 1)
    private void seamlessportals$portalSlotOnOitPath(CallbackInfo ci) {
        OitPathPortalSlot.onExecuteOitHead();
    }
}
