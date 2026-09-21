package com.warwa.seamlessportals.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.warwa.seamlessportals.render.MainPassSplit;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 SUBSTRATE — splits vanilla's single "Main" render pass at the mod's two main-pass timing slots so every
 * handler there runs with NO pass open, as on 26.2. The why, the citations and the Iris precedent are on
 * {@link MainPassSplit}; this class is only the wiring.
 *
 * <p>All four hooks sit in {@code LevelRenderer.executeClassicTransparency(ChunkSectionsToRender, PreparedFrame,
 * RenderPass)} (mc263-ref LevelRenderer.java; javap 26.3: {@code private void executeClassicTransparency(..)}),
 * around its single {@code renderGroup(TRANSLUCENT, renderPass, ..)} INVOKE — the call Fabric API wraps to fire
 * BEFORE/AFTER_TRANSLUCENT_TERRAIN and that NeoForge follows with {@code AfterTranslucentBlocks}:
 * <ol>
 *   <li><b>suspend</b> — {@code @Inject} BEFORE the INVOKE: close the incoming pass. Runs before Fabric's wrapper is
 *       entered (so BEFORE_TRANSLUCENT_TERRAIN fires pass-free) and, thanks to {@code priority = 900}, before the
 *       NeoForge BEFORE-slot mixin's injection at the same point (first-applied runs first).</li>
 *   <li><b>draw</b> — {@code @WrapOperation} on the INVOKE, INNERMOST (priority 900 applies before Fabric API's
 *       default-1000 wrap, and a later-applied wrap encloses an earlier one): the terrain draw gets its own freshly
 *       opened pass, closed again straight after, so AFTER_TRANSLUCENT_TERRAIN is pass-free too.</li>
 *   <li><b>resume</b> — {@code @Inject} AFTER the INVOKE (after every wrapper has returned): reopen and replace the
 *       method's {@code renderPass} argument, so particles, clouds, weather and the world border draw into a live
 *       pass, and NeoForge's {@code AfterTranslucentBlocks} event carries a live pass for OTHER mods' listeners.</li>
 *   <li><b>heal</b> — around {@code executeTranslucentAfterTerrain}: the mod's NeoForge listeners suspend the event's
 *       pass to run the portal driver (and on Forge, which has no such event, {@code
 *       LevelRendererForgeAfterTranslucentSlotMixin} suspends the resumed pass for the same reason); if nothing is
 *       open by then, reopen and replace again. A no-op on Fabric. A {@code @WrapOperation}, NOT an {@code @Inject}:
 *       an INVOKE injection point sits AFTER the call's arguments were pushed (javap, all three jars: {@code aload_2;
 *       aload_3; invokevirtual executeTranslucentAfterTerrain}), so an {@code @Inject} there can only replace the
 *       LOCAL — the call itself still receives the closed pass that is already on the operand stack. Measured on the
 *       first Forge world join that got as far as rendering: {@code IllegalStateException: Can't use a closed render
 *       pass} at {@code FrontendRenderPass.pushDebugGroup} under {@code executeTranslucentAfterTerrain}. The wrap
 *       replaces the ARGUMENT and the local (the later cloud / weather / world-border draws reload it).</li>
 * </ol>
 * RETURN closes the pass this class owns. Vanilla's try-with-resources then closes the pass IT opened, which is
 * already closed and idempotent. The improved-transparency (OIT) path never calls this method and neither loader
 * fires these events on it, so nothing here touches it.
 */
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class LevelRendererMainPassSplitMixin {

    @Unique
    private static final String SEAMLESSPORTALS$RENDER_GROUP =
        "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup("
            + "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
            + "Lcom/mojang/renderpearl/api/commands/RenderPass;"
            + "Lcom/mojang/renderpearl/api/textures/GpuSampler;"
            + "Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V";

    /** The pass THIS class opened for the remainder of the method (closed at RETURN); null when vanilla's is live. */
    @Unique
    private RenderPass seamlessportals$ownedPass;

    @Inject(
        method = "executeClassicTransparency",
        at = @At(value = "INVOKE", target = SEAMLESSPORTALS$RENDER_GROUP),
        require = 1, allow = 1
    )
    private void seamlessportals$suspendBeforeTranslucentTerrain(
        CallbackInfo ci, @Local(argsOnly = true) LocalRef<RenderPass> renderPass
    ) {
        MainPassSplit.suspend(renderPass.get());
    }

    @WrapOperation(
        method = "executeClassicTransparency",
        at = @At(value = "INVOKE", target = SEAMLESSPORTALS$RENDER_GROUP),
        require = 1, allow = 1
    )
    private void seamlessportals$drawTranslucentTerrainInOwnPass(
        ChunkSectionsToRender sections, ChunkSectionLayerGroup group, RenderPass suspended,
        GpuSampler sampler, GpuTextureView atlas, boolean wireframe, Operation<Void> original
    ) {
        try (RenderPass own = MainPassSplit.open()) {
            original.call(sections, group, own, sampler, atlas, wireframe);
        }
    }

    @Inject(
        method = "executeClassicTransparency",
        at = @At(value = "INVOKE", target = SEAMLESSPORTALS$RENDER_GROUP, shift = At.Shift.AFTER),
        require = 1, allow = 1
    )
    private void seamlessportals$resumeAfterTranslucentTerrain(
        CallbackInfo ci, @Local(argsOnly = true) LocalRef<RenderPass> renderPass
    ) {
        this.seamlessportals$ownedPass = MainPassSplit.open();
        renderPass.set(this.seamlessportals$ownedPass);
    }

    // 26.3: a @WrapOperation since the first Forge in-world run (see the class note, item 4) — it was an @Inject at this
    // same INVOKE, which replaced only the local: the closed pass was already on the operand stack as the call's argument.
    @WrapOperation(
        method = "executeClassicTransparency",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
                + "executeTranslucentAfterTerrain(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"
        ),
        require = 1, allow = 1
    )
    private void seamlessportals$healBeforeAfterTerrainFeatures(
        FeatureRenderDispatcher.PreparedFrame featureFrame, RenderPass pass, Operation<Void> original,
        @Local(argsOnly = true) LocalRef<RenderPass> renderPass
    ) {
        if (!MainPassSplit.isPassOpen()) {
            this.seamlessportals$ownedPass = MainPassSplit.open();
            renderPass.set(this.seamlessportals$ownedPass);
            pass = this.seamlessportals$ownedPass;
        }
        original.call(featureFrame, pass);
    }

    @Inject(method = "executeClassicTransparency", at = @At("RETURN"), require = 1)
    private void seamlessportals$closeOwnedPass(CallbackInfo ci) {
        RenderPass owned = this.seamlessportals$ownedPass;
        this.seamlessportals$ownedPass = null;
        MainPassSplit.suspend(owned);
    }
}
