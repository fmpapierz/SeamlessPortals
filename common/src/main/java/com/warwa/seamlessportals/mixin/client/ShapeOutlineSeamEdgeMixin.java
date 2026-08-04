package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import net.minecraft.client.renderer.feature.ShapeOutlineFeatureRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ★ THE OPEN HALF BOX (user round 19: "as i move to the side of portal, a sudden outline appears
 * at the seam, cutting in half down the full block. this should never happen"). A seam object's
 * side-view outline must stop at the cut plane WITHOUT a closing rectangle — but a VoxelShape
 * cannot express a box with a missing lid: every box shape draws all twelve edges. So the
 * omission happens here, at the one place vanilla turns an outline shape into line segments
 * ({@code buildGroup}'s {@code forAllEdges} call, bytecode-verified as the sole consumer for
 * block outlines): shapes registered in {@link SeamFractional#OUTLINE_CUT_PLANES} by the outline
 * extract get their in-plane edges dropped. Every other shape passes through untouched — one
 * identity-map miss per outline submit.
 */
@Mixin(ShapeOutlineFeatureRenderer.class)
public abstract class ShapeOutlineSeamEdgeMixin {

    @Redirect(
        method = "buildGroup",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/shapes/VoxelShape;forAllEdges("
                + "Lnet/minecraft/world/phys/shapes/Shapes$DoubleLineConsumer;)V"
        )
    )
    private void seamlessportals$dropCutPlaneEdges(
        VoxelShape shape, Shapes.DoubleLineConsumer consumer
    ) {
        SeamFractional.OutlineCutPlane plane = SeamFractional.OUTLINE_CUT_PLANES.get(shape);
        if (plane == null) {
            shape.forAllEdges(consumer);
            return;
        }
        final Direction.Axis axis = plane.axis();
        final double off = plane.offset();
        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            double a1 = axis == Direction.Axis.X ? x1 : axis == Direction.Axis.Y ? y1 : z1;
            double a2 = axis == Direction.Axis.X ? x2 : axis == Direction.Axis.Y ? y2 : z2;
            if (Math.abs(a1 - off) < 1.0e-4 && Math.abs(a2 - off) < 1.0e-4) {
                return;   // this edge lies IN the cut plane — the lid the box must not have
            }
            consumer.consume(x1, y1, z1, x2, y2, z2);
        });
    }
}
