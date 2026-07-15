package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.LimitedLogger;

/**
 * S12-B (render client-mixin half) — IP {@code MixinFrustum_FixDeadLoop}
 * ({@code IP:mixin/client/render/MixinFrustum_FixDeadLoop.java}), 26.2 @Overwrite RE-COPIED
 * (mixin-client.md §7 NEEDS-RETARGET; R12/@IPVanillaCopy re-derive line-by-line from 26.2).
 *
 * <p><b>@Overwrite body re-derived from the 26.2 method.</b> {@code offsetToFullyIncludeCameraCube}
 * survives ({@code 26.2:Frustum.java:46-71}) but the loop was restructured (the {@code camZ} decrement
 * moved into the {@code for} header; body uses {@code viewVector.x()/.y()/.z()}). The 26.2 logic — floor/ceil
 * cube bounds, {@code intersection.intersectAab(...) != -2}, step back by {@code viewVector * 4}, return
 * {@code this} — is preserved verbatim; IP's two additions are re-applied on top: the isometric-view
 * early-out and the 10-iteration {@code countLimit} cap (so a broken/tilted projection matrix cannot
 * dead-loop). Held/UNREGISTERED until S13.
 */
@Mixin(Frustum.class)
public abstract class MixinFrustum_FixDeadLoop {
    @Shadow
    private double camX;

    @Shadow
    private double camY;

    @Shadow
    private double camZ;

    @Shadow
    private Vector4f viewVector;

    @Shadow @Final private FrustumIntersection intersection;
    private static LimitedLogger limitedLogger = new LimitedLogger(10);

    /**
     * Make it to not deadloop when using isometric view.
     * Also make it to not deadloop even if the projection matrix is broken. (In normal cases the projection should not be broken.)
     *
     * @author qouteall
     * @reason Hard to do by injection or redirection
     */
    @Overwrite
    @IPVanillaCopy
    public Frustum offsetToFullyIncludeCameraCube(int cubeSize) {
        if (TransformationManager.isIsometricView) {
            return (Frustum) (Object) this;
        }

        double minX = Math.floor(this.camX / (double) cubeSize) * (double) cubeSize;
        double minY = Math.floor(this.camY / (double) cubeSize) * (double) cubeSize;
        double minZ = Math.floor(this.camZ / (double) cubeSize) * (double) cubeSize;
        double maxX = Math.ceil(this.camX / (double) cubeSize) * (double) cubeSize;
        double maxY = Math.ceil(this.camY / (double) cubeSize) * (double) cubeSize;
        double maxZ = Math.ceil(this.camZ / (double) cubeSize) * (double) cubeSize;

        int countLimit = 10; // limit the loop count

        while (this.intersection.intersectAab((float) (minX - this.camX), (float) (minY - this.camY), (float) (minZ - this.camZ), (float) (maxX - this.camX), (float) (maxY - this.camY), (float) (maxZ - this.camZ)) != -2) {
            this.camX -= (double) (this.viewVector.x() * 4.0F);
            this.camY -= (double) (this.viewVector.y() * 4.0F);
            this.camZ -= (double) (this.viewVector.z() * 4.0F);
            countLimit--;
            if (countLimit <= 0) {
                limitedLogger.invoke(() -> {
                    Helper.err("the projection matrix and the frustum are abnormal");
                    new Throwable().printStackTrace();
                });
                break;
            }
        }

        return (Frustum) (Object) this;
    }
}
