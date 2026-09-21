package com.warwa.seamlessportals.mixin.client;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.warwa.seamlessportals.render.PortalSlicing;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Non-Euclidean slice: apply an oblique near-clip plane to the main
 * world's projection matrix when the eye is within activation range of
 * a portal plane.
 *
 * <p>Vanilla's {@code GameRenderer.renderLevel} builds the final
 * projection matrix in a local variable, then passes it to
 * {@code RenderSystem.setProjectionMatrix} via
 * {@code levelProjectionMatrixBuffer.getBuffer(matrix)}. We
 * {@code @Redirect} that buffer-get call — intercept the fully assembled
 * Matrix4f, apply our oblique clip if
 * {@link PortalSlicing#isActive() slicing is active}, then pass through
 * to the real getBuffer. This is the very last step before GPU upload,
 * so the clip sits on top of bob, portal-effect skew, and every other
 * projection adjustment vanilla applies.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererObliqueClipMixin {

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private GameRenderState gameRenderState;

    // 26.3: renderLevel(DeltaTracker) -> renderLevel() (mc262-ref GameRenderer.java:525 -> mc263-ref :635). With require = 0 the
    // stale selector would not fail — it would silently stop applying (audit: 1 match on 26.2, 0 on 26.3). The INVOKE itself is
    // unchanged and still unique in the method: javap 26.3 renderLevel()V offset 262 `ProjectionMatrixBuffer.getBuffer:
    // (Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;` (the HUD projection's getBuffer(Projection)
    // overload moved out to render3dHud).
    @Redirect(
        method = "renderLevel()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",
            ordinal = 0
        ),
        require = 0
    )
    private GpuBufferSlice seamlessportals$maybeSliceProjection(
            ProjectionMatrixBuffer self, Matrix4f matrix) {
        // Slice disabled — oblique clip on the main projection distorts
        // the full view too heavily when active. True IP-style slicing
        // requires rendering both dimensions in a single frame (swap
        // target FBO + level + camera mid-frame, composite), which is a
        // larger architectural change than a simple projection tweak.
        // Parked until Phase 2f.
        return self.getBuffer(matrix);
    }
}
