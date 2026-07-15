package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * S12-B port disposition: NEEDS-RETARGET (mixin-client.md §1). Kills entity position interpolation when
 * an entity crosses a portal into the SAME dimension (so it snaps rather than lerps across the seam).
 *
 * <p><b>26.2 retarget (file kept at IP's verbatim held path {@code MixinLivingEntity_C}, target moved).</b>
 * IP 1.21.3 hooked {@code LivingEntity.lerpTo(...)} @RETURN and read the interpolation target from the
 * {@code lerpX/lerpY/lerpZ} shadow fields. On 26.2 {@code lerpTo} and those fields are GONE — all entity
 * interpolation is unified in {@code net.minecraft.world.entity.InterpolationHandler}
 * ({@code LivingEntity.interpolation} {@code :252}, {@code getInterpolation()} {@code :3279}). The lerp
 * analog is {@code InterpolationHandler.interpolateTo(Vec3 position, float yRot, float xRot)}
 * ({@code 26.2:InterpolationHandler.java:49}), whose {@code position} PARAM is exactly the interpolation
 * target the old {@code lerpX/Y/Z} carried. So the mixin re-sites onto {@code InterpolationHandler}
 * ({@code entity} = the handler's {@code @Shadow @Final} entity), reading the target from {@code position}
 * (api-map: "re-sites onto InterpolationHandler.interpolateTo ... read InterpolationData instead of
 * lerpX/Y/Z").
 *
 * <p><b>Behavior note (documented, S13 live-verify):</b> {@code InterpolationHandler} is used by ALL
 * interpolating entities on 26.2, not only {@code LivingEntity} as in 1.21.3, so this handler fires for any
 * entity — but it is guarded by {@code ip_getCollidingPortal() != null}, which is only non-null for
 * entities on a portal, so the broadening is benign (and arguably more correct for cross-portal entity
 * motion). Logic is otherwise VERBATIM IP. Held/unregistered until S13.
 */
@Mixin(InterpolationHandler.class)
public class MixinLivingEntity_C {
    @Shadow
    @Final
    private Entity entity;

    // avoid entity position interpolate when crossing portal to the same dimension
    @Inject(
        method = "interpolateTo",
        at = @At("RETURN")
    )
    private void onUpdateTrackedPositionAndAngles(
        Vec3 position,
        float yaw,
        float pitch,
        CallbackInfo ci
    ) {
        Entity this_ = entity;
        if (!IPGlobal.allowClientEntityPosInterpolation) {
            this_.setPos(position.x, position.y, position.z);
            return;
        }

        Portal collidingPortal = ((IEEntity) this_).ip_getCollidingPortal();
        if (collidingPortal != null) {

            // 26.2: the interpolation target is the `position` param (was lerpX/lerpY/lerpZ on 1.21.3).
            double dx = this_.getX() - position.x;
            double dy = this_.getY() - position.y;
            double dz = this_.getZ() - position.z;
            if (dx * dx + dy * dy + dz * dz > 4) {
                Vec3 currPos = new Vec3(position.x, position.y, position.z);
                McHelper.setPosAndLastTickPos(
                    this_,
                    currPos,
                    currPos.subtract(McHelper.getWorldVelocity(this_))
                );
                McHelper.updateBoundingBox(this_);
            }
        }
    }
}
