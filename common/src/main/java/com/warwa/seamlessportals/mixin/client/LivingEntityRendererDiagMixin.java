package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.client.PortalWorldManager;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic-only: log the render-state equipment for mirrored entities
 * right after {@link LivingEntityRenderer#extractRenderState} runs.
 *
 * <p>The Phase 2b equipment-apply readback proves the bow is stored on the
 * mirrored entity, but users report the bow doesn't render through the
 * portal. If the extraction captures the bow into the render state, the
 * fault is downstream (layer submit / item model resolver). If the state
 * has empty hand items, extraction itself isn't reading our slot — which
 * would point to reuse-state caching across frames or a context-specific
 * short-circuit in extractArmedEntityRenderState.
 */
@Mixin(HumanoidMobRenderer.class)
public abstract class LivingEntityRendererDiagMixin {

    private static int seamlessportals$diagCount = 0;

    /**
     * Inject at TAIL of {@code HumanoidMobRenderer.extractRenderState}.
     * That method calls super (LivingEntityRenderer) first, then
     * {@code extractHumanoidRenderState} which sets hand items via
     * {@code extractArmedEntityRenderState}. At TAIL, state.rightHandItemStack
     * is populated.
     */
    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;F)V",
        at = @At("TAIL"),
        require = 0
    )
    private void seamlessportals$logMirrorEquip(
            Mob entity, HumanoidRenderState state, float partialTicks, CallbackInfo ci) {
        if (seamlessportals$diagCount >= 30) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (entity.level() == mc.level) return;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim = entity.level().dimension();
        if (PortalWorldManager.getLevel(dim) != entity.level()) return;

        net.minecraft.world.item.ItemStack mainHand = entity.getMainHandItem();
        String mainHandId = mainHand.isEmpty() ? "EMPTY" : mainHand.getItem().toString();
        String stateHandId = state.rightHandItemStack == null || state.rightHandItemStack.isEmpty()
            ? "EMPTY"
            : state.rightHandItemStack.getItem().toString();
        // The ItemInHandLayer actually gates rendering on
        // rightHandItemState.isEmpty() — the ItemStackRenderState, which is
        // set of render layers. If this reports empty while the stack is
        // present, itemModelResolver.updateForLiving didn't populate any
        // layers (likely because getItemModel returned a no-op or the
        // item's model id lookup failed for the cached-level context).
        boolean handStateEmpty = state.rightHandItemState == null || state.rightHandItemState.isEmpty();
        seamlessportals$diagCount++;
        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS LIVE ENT] renderDiag #{} id={} type={} entity.mainHand={} state.rightHand={} state.rightHandItemState.isEmpty={} in {}",
            seamlessportals$diagCount, entity.getId(),
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
            mainHandId, stateHandId, handStateEmpty, dim.identifier());
    }
}
