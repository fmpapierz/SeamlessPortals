package qouteall.imm_ptl.core.mixin.client.render.optimization;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.ducks.IEFrustum;
import qouteall.imm_ptl.core.render.FrustumCuller;

/**
 * S12-B (render client-mixin half) — IP {@code MixinFrustum} (optimization)
 * ({@code IP:mixin/client/render/optimization/MixinFrustum.java}), 26.2-RETARGETED (mixin-client.md §8
 * NEEDS-RETARGET).
 *
 * <p><b>The visibility gate retargets from {@code cubeInFrustum} to {@code isVisible(AABB)}.</b> On 26.2
 * {@code cubeInFrustum(DDDDDD)} became <b>private and its return type changed from {@code boolean} to
 * {@code int}</b> (the raw JOML {@code intersectAab} code, {@code Frustum.java:94-102}); visibility is
 * interpreted by {@code isVisible(AABB)} as {@code result == -2 || result == -1} ({@code :85-88}). IP's
 * HEAD-cancel handler used {@code CallbackInfoReturnable<Boolean>} + {@code setReturnValue(false)}; retyping
 * that CIR to {@code Integer} + an "outside" code is brittle, so per the api-map's sanctioned alternative it
 * retargets onto the boolean {@code isVisible(AABB)} wrapper (extracting the 6 doubles from the AABB) — the
 * FrustumCuller cull result and the "offset mutates camX/Y/Z" hazard are unaffected.
 *
 * <p><b>{@code calculateFrustum} first param retyped {@code Matrix4f} → {@code Matrix4fc}</b>
 * ({@code 26.2:Frustum.java:79}). Copy-ctor / {@code prepare(DDD)} anchors survive unchanged
 * ({@code :26,:73}). Held/UNREGISTERED until S13.
 */
@Mixin(Frustum.class)
public class MixinFrustum implements IEFrustum {
    @Shadow
    private double camX;
    @Shadow
    private double camY;
    @Shadow
    private double camZ;

    @Shadow
    private Vector4f viewVector;

    /**
     * In {@link Frustum#offsetToFullyIncludeCameraCube(int)}
     * the camX, camY, camZ may get changed.
     * So we need to store the original value.
     * normal frustums can be moved back without wrongly culling anything
     * but the portal frustum may be tilted and moving it back may be wrong
     */
    private double portal_camX;
    private double portal_camY;
    private double portal_camZ;

    private FrustumCuller portal_frustumCuller;

    // copy the extra fields when copying frustum
    @Inject(
        method = "<init>(Lnet/minecraft/client/renderer/culling/Frustum;)V",
        at = @At("RETURN")
    )
    private void onFrustumCopy(Frustum other, CallbackInfo ci) {
        if (other instanceof IEFrustum) {
            MixinFrustum otherFrustum = (MixinFrustum) (Object) other;
            portal_camX = otherFrustum.portal_camX;
            portal_camY = otherFrustum.portal_camY;
            portal_camZ = otherFrustum.portal_camZ;
            portal_frustumCuller = otherFrustum.portal_frustumCuller;
        }
    }

    @Inject(
        method = "Lnet/minecraft/client/renderer/culling/Frustum;prepare(DDD)V",
        at = @At("TAIL")
    )
    private void onSetOrigin(double double_1, double double_2, double double_3, CallbackInfo ci) {
        if (IrisInterface.invoker.isRenderingShadowMap()) {
            return;
        }

        if (portal_frustumCuller == null) {
            portal_frustumCuller = new FrustumCuller();
        }
        portal_frustumCuller.update(camX, camY, camZ);

        portal_camX = camX;
        portal_camY = camY;
        portal_camZ = camZ;
    }

    // 26.2: cubeInFrustum became private + (DDDDDD)I; the boolean visibility wrapper is isVisible(AABB)
    // (Frustum.java:85-88). Retargeted here (api-map/mixin-client.md §8). Same portal-cull short-circuit.
    @Inject(
        method = "isVisible",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onIsVisible(AABB aabb, CallbackInfoReturnable<Boolean> cir) {
        if (ip_canDetermineInvisibleWithCamCoord(
            (float) (aabb.minX - portal_camX),
            (float) (aabb.minY - portal_camY),
            (float) (aabb.minZ - portal_camZ),
            (float) (aabb.maxX - portal_camX),
            (float) (aabb.maxY - portal_camY),
            (float) (aabb.maxZ - portal_camZ)
        )) {
            cir.setReturnValue(false);
        }
    }

    // with scaling transformation, the view vector may be not unit-len.
    // 26.2: calculateFrustum(Matrix4fc modelView, Matrix4f projection) (Frustum.java:79).
    @Inject(
        method = "calculateFrustum",
        at = @At("RETURN")
    )
    private void onCalculateFrustumReturn(
        Matrix4fc matrix4f, Matrix4f matrix4f2, CallbackInfo ci
    ) {
        viewVector.normalize();
    }

    @Override
    public boolean ip_canDetermineInvisibleWithCamCoord(
        float minX, float minY, float minZ, float maxX, float maxY, float maxZ
    ) {
        return portal_frustumCuller.canDetermineInvisibleWithCameraCoord(
            minX, minY, minZ, maxX, maxY, maxZ
        );
    }

    @Override
    public Vec3 ip_getViewVec3() {
        return new Vec3(
            viewVector.x(),
            viewVector.y(),
            viewVector.z()
        );
    }
}
