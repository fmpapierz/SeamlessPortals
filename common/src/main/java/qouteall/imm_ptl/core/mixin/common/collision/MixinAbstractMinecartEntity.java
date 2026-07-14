package qouteall.imm_ptl.core.mixin.common.collision;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractMinecart.class)
public class MixinAbstractMinecartEntity {
    // for debugging
    //
    // S10-C forced deviation (F-collision-1): the 1.21.3 debug inject targeted
    // AbstractMinecart.lerpTo(double,double,double,float,float,int) — TARGET-GONE in 26.2.
    // The lerp system was replaced by InterpolationHandler
    // (net/minecraft/world/entity/InterpolationHandler.java); the minecart exposes
    // `public InterpolationHandler getInterpolation()`
    // (net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.java:341) whose handler
    // drives interpolateTo(Vec3,float,float). This is a debug-only aid gated behind
    // IPGlobal.allowClientEntityPosInterpolation; it is NEUTRALIZED here (no anchor on
    // AbstractMinecart) rather than re-anchored onto a new @Mixin(InterpolationHandler) target.
    // Re-anchor recipe deferred to entity-traffic bring-up (S15): after
    // InterpolationHandler.interpolateTo, force entity.snapTo(...), or simpler
    // getInterpolation().setInterpolationLength(0) (InterpolationHandler.java:73) so
    // interpolateTo snaps immediately. See migration/api-map/mixin-common.md §5 and
    // migration/api-map/teleportation-collision.md CHANGED row (AbstractMinecart.lerpTo).
//    @Inject(
//        method = "lerpTo",
//        at = @At("RETURN")
//    )
//    private void onUpdateTracketPositionAndAngles(
//        double x, double y, double z, float yaw, float pitch, int steps, CallbackInfo ci
//    ) {
//        AbstractMinecart this_ = (AbstractMinecart) ((Object) this);
//        if (!IPGlobal.allowClientEntityPosInterpolation) {
//            this_.setPos(x, y, z);
//        }
//    }
}
