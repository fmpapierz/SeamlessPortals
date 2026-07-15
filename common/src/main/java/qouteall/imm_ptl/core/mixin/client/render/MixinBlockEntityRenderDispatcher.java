package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

/**
 * S12-B (render client-mixin half) — VERBATIM IP {@code MixinBlockEntityRenderDispatcher}
 * ({@code IP:mixin/client/render/MixinBlockEntityRenderDispatcher.java}). PORTS-CLEAN (mixin-client.md §7):
 * IP ships this as an empty body (its only handler is commented out upstream). The 26.2 BE-render path is
 * submit-based ({@code prepare(Vec3)} / {@code submit(...)} / {@code tryExtractRenderState}); if the
 * shouldRenderInside gate is ever re-implemented it re-sites there. Held/UNREGISTERED until S13.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public class MixinBlockEntityRenderDispatcher {
//    @Inject(
//        method = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
//        at = @At("HEAD"),
//        cancellable = true
//    )
//    private <E extends BlockEntity> void onRenderBlockEntity(
//        E blockEntity,
//        float tickDelta,
//        PoseStack matrix,
//        MultiBufferSource vertexConsumerProvider,
//        CallbackInfo ci
//    ) {
//        if (IrisInterface.invoker.isRenderingShadowMap()) {
//            return;
//        }
//        if (PortalRendering.isRendering()) {
//            PortalLike renderingPortal = PortalRendering.getRenderingPortal();
//            if (renderingPortal instanceof Portal portal) {
//                boolean canRender = portal.getPortalShape()
//                    .shouldRenderInside(portal, new AABB(blockEntity.getBlockPos()));
//                if (!canRender) {
//                    ci.cancel();
//                }
//            }
//        }
//    }
}
