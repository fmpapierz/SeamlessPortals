package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the breakable-portal block overlay (IP {@code render/OverlayRendering}). TRAILING
 * periphery (CUTOVER_SPEC §6.5) — its consumers ({@link BreakablePortalEntity},
 * {@link BlockPortalShape}) are the S13 (U12) closure slice, so the class carries documented S13
 * forward-refs; its runtime is verified at S18.
 *
 * <p>26.2 re-expressions (all api-map-mandated; no IP logic deviated):
 * <ul>
 *   <li>{@code MultiBufferSource vertexConsumerProvider} → {@link SubmitNodeCollector} (the
 *       submit→prepare→execute surface; the 1.21.3 immediate {@code getBuffer(RenderType)} model
 *       is GONE, render-core headline 1).</li>
 *   <li>{@code Sheets.translucentCullBlockSheet()} (GONE, G33) → {@link RenderTypes#translucentMovingBlock()}
 *       — the block-atlas translucent+cull type built on {@code RenderPipelines.TRANSLUCENT_BLOCK};
 *       the falling/moving-block lineage matches IP's own {@code FallingBlockRenderer} template.</li>
 *   <li>{@code BlockRenderDispatcher.getBlockModel} + {@code BakedModel.getQuads(state,dir,random)}
 *       (both GONE, G34) → {@code getModelManager().getBlockStateModelSet().get(state)} →
 *       {@link BlockStateModel#collectParts} → {@link BlockStateModelPart#getQuads(Direction)}.</li>
 *   <li>{@code VertexConsumer.putBulkData(pose, quad, brightness[], r,g,b,a, light[], overlay,
 *       readAlpha)} (GONE, G34) → {@link com.mojang.blaze3d.vertex.VertexConsumer#putBakedQuad}
 *       with a {@link QuadInstance}. IP's {@code brightness = {1,1,1,1}} + {@code (r,g,b)=(1,1,1)}
 *       + {@code a = opacity} is exactly {@code ARGB.white(opacity)} (flat, unshaded); the per-vertex
 *       light {@code 14680304} and {@code OverlayTexture.NO_OVERLAY} carry verbatim.</li>
 *   <li>{@code Direction.getNearest(double,double,double)} → {@code Direction.getApproximateNearest(
 *       double,double,double)} (rename in 26.2, verified in {@code mc262-ref}).</li>
 * </ul>
 * All non-{@code BreakablePortalEntity}/{@code BlockPortalShape} dependencies are held:
 * {@code IrisInterface}/{@code SodiumInterface} (S4 compat bases), {@code CHelper}/{@code RenderStates},
 * {@code Portal}.
 */
@Environment(EnvType.CLIENT)
public class OverlayRendering {
    private static final RandomSource random = RandomSource.create();


    public static boolean shouldRenderOverlay(Portal portal) {
        if (portal instanceof BreakablePortalEntity breakablePortalEntity) {
            if (breakablePortalEntity.getActualOverlay() != null) {
                return breakablePortalEntity.isInFrontOfPortal(CHelper.getCurrentCameraPos());
            }
        }
        return false;
    }

    private static boolean shaderOverlayWarned = false;

    public static void onRenderPortalEntity(
        Portal portal,
        PoseStack matrixStack,
        SubmitNodeCollector submitNodeCollector
    ) {
        if (IrisInterface.invoker.isShaders()) {
            if (!shaderOverlayWarned) {
                shaderOverlayWarned = true;
                CHelper.printChat("[Immersive Portals] Portal overlay cannot be rendered with shaders");
            }

            return;
        }

        if (portal instanceof BreakablePortalEntity) {
            renderBreakablePortalOverlay(
                ((BreakablePortalEntity) portal),
                RenderStates.getPartialTick(),
                matrixStack,
                submitNodeCollector
            );
        }
    }

    // 26.2 re-expression of IP's getQuads(BakedModel, BlockState, Vec3): the (state,dir,random)
    // BakedModel path is GONE (G34). collectParts consumes `random` once for the whole model, then
    // each BlockStateModelPart yields the direction-keyed BakedQuads. The `blockState` param is no
    // longer needed (it was baked into the model resolved by getBlockStateModelSet().get(state)).
    public static List<BakedQuad> getQuads(BlockStateModel model, Vec3 portalNormal) {
        Direction facing = Direction.getApproximateNearest(portalNormal.x, portalNormal.y, portalNormal.z);

        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(random, parts);

        List<BakedQuad> result = new ArrayList<>();

        for (BlockStateModelPart part : parts) {
            result.addAll(part.getQuads(facing));
        }

        for (BlockStateModelPart part : parts) {
            result.addAll(part.getQuads(null));
        }

        if (result.isEmpty()) {
            for (BlockStateModelPart part : parts) {
                for (Direction direction : Direction.values()) {
                    result.addAll(part.getQuads(direction));
                }
            }
        }

        return result;
    }

    /**
     * {@link net.minecraft.client.renderer.LevelRenderer#submitBlockDestroyAnimation} is the 26.2
     * {@code @IPVanillaCopy} template (it replaced {@code FallingBlockRenderer}, G34): resolve the
     * {@link BlockStateModel} via the model set, {@code collectParts}, then submit. Here the per-quad
     * translucent-opacity emit is preserved through {@code submitCustomGeometry} + {@code putBakedQuad}
     * (rather than {@code submitBlockModel}, which cannot express IP's per-quad opacity/alpha).
     */
    private static void renderBreakablePortalOverlay(
        BreakablePortalEntity portal,
        float partialTick,
        PoseStack matrixStack,
        SubmitNodeCollector submitNodeCollector
    ) {
        BreakablePortalEntity.OverlayInfo overlay = portal.getActualOverlay();

        if (overlay == null) {
            return;
        }

        BlockState blockState = overlay.blockState();

        Vec3 cameraPos = CHelper.getCurrentCameraPos();

        if (blockState == null) {
            return;
        }

        BlockPortalShape blockPortalShape = portal.blockPortalShape;
        if (blockPortalShape == null) {
            return;
        }

        matrixStack.pushPose();

        Vec3 offset = portal.getNormal().scale(overlay.offset());

        Vec3 pos = portal.position();

        matrixStack.translate(offset.x, offset.y, offset.z);

        BlockStateModel model = Minecraft.getInstance().getModelManager()
            .getBlockStateModelSet().get(blockState);
        RenderType renderLayer = RenderTypes.translucentMovingBlock();

        List<BakedQuad> quads = getQuads(model, portal.getNormal());

        random.setSeed(0);

        // IP's per-quad putBulkData args, resolved once (opacity is constant across the area):
        // brightness {1,1,1,1} + (r,g,b)=(1,1,1) + a=opacity == white-with-opacity (flat/unshaded);
        // per-vertex light 14680304; OverlayTexture.NO_OVERLAY.
        QuadInstance quadInstance = new QuadInstance();
        quadInstance.setColor(ARGB.white((float) overlay.opacity()));
        quadInstance.setLightCoords(14680304);
        quadInstance.setOverlayCoords(OverlayTexture.NO_OVERLAY);

        for (BlockPos blockPos : blockPortalShape.area) {
            matrixStack.pushPose();
            matrixStack.translate(
                blockPos.getX() - pos.x, blockPos.getY() - pos.y, blockPos.getZ() - pos.z
            );

            if (overlay.rotation() != null) {
                matrixStack.mulPose(overlay.rotation().toMcQuaternion());
            }

            // One submitCustomGeometry per area block: submitCustomGeometry snapshots ONE pose
            // (poseStack.last().copy()), so the per-block translate+rotate must be baked into the
            // pose at submit time — hence a call per block, each closure emitting all quads for
            // that block (mirroring IP's inner per-quad putBulkData loop).
            submitNodeCollector.submitCustomGeometry(
                matrixStack,
                renderLayer,
                (pose, buffer) -> {
                    for (BakedQuad quad : quads) {
                        SodiumInterface.invoker.markSpriteActive(quad.materialInfo().sprite());
                        buffer.putBakedQuad(pose, quad, quadInstance);
                    }
                }
            );

            matrixStack.popPose();
        }

        matrixStack.popPose();

    }
}
