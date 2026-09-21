package com.warwa.seamlessportals.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.warwa.seamlessportals.render.MainPassSplit;
import com.warwa.seamlessportals.render.OitPathPortalSlot;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 FORGE-ONLY ({@code FORGE_ONLY_MIXINS}) — the AFTER_TRANSLUCENT_TERRAIN slot on MinecraftForge, which has no
 * event there.
 *
 * <p>Fabric fires {@code LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN} and NeoForge posts
 * {@code RenderLevelStageEvent.AfterTranslucentBlocks} immediately after the
 * {@code renderGroup(TRANSLUCENT, renderPass, ..)} call in {@code LevelRenderer.executeClassicTransparency}; the mod's
 * portal driver, band painter and block-era driver are listeners on them. MinecraftForge 26.3-66.0.2 posts NOTHING
 * inside that method — javap -c on the Forge-patched LevelRenderer: {@code executeTranslucent} @66,
 * {@code renderGroup} @119, {@code executeTranslucentAfterTerrain} @131, clouds/weather/border, and not one
 * {@code net/minecraftforge} reference; the jar has no {@code RenderLevelStageEvent} class. So the slot is driven from
 * here, at the exact instruction the two other loaders use.
 *
 * <p>It runs the way the NeoForge listeners run (the event there fires after the same INVOKE, inside the pass that
 * {@link LevelRendererMainPassSplitMixin} has just REOPENED — priority 900, applied first, so its resume callback
 * precedes this one): suspend the live pass, run the handlers pass-free as on 26.2, and leave it to that mixin's
 * heal step to reopen before {@code executeTranslucentAfterTerrain}. {@code @Local(argsOnly = true)} reads the
 * argument slot the resume callback has just rewritten, i.e. the pass that is actually open.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererForgeAfterTranslucentSlotMixin {

    @Inject(
        method = "executeClassicTransparency",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup("
                + "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                + "Lcom/mojang/renderpearl/api/commands/RenderPass;"
                + "Lcom/mojang/renderpearl/api/textures/GpuSampler;"
                + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V",
            shift = At.Shift.AFTER
        ),
        require = 1, allow = 1
    )
    private void seamlessportals$afterTranslucentTerrainOnForge(
        CallbackInfo ci, @Local(argsOnly = true) RenderPass renderPass
    ) {
        MainPassSplit.suspend(renderPass);
        OitPathPortalSlot.onAfterTranslucentTerrainWithoutLoaderEvent();
    }
}
