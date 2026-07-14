package qouteall.imm_ptl.core.mixin.common.collision;

import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Projectile.class)
public abstract class MixinProjectile extends MixinEntity {

    // make it recognize the owner in another dimension
    //
    // S10-C forced deviation (F-collision-2): the 1.21.3 redirect targeted the
    // `ServerLevel.getEntity(UUID)` call inside Projectile.getOwner(). In 26.2 getOwner() is
    // `return EntityReference.getEntity(this.owner, this.level());`
    // (Projectile.java:60-62) — there is NO ServerLevel.getEntity(UUID) call left to redirect
    // (ANCHOR-GONE, so a verbatim port would fail to apply). The role the redirect served —
    // resolving the owner across ALL dimensions — is now VANILLA behavior:
    // EntityReference.getEntity(this.owner, this.level()) dispatches to
    // EntityReference.getEntity(Level, Class) (EntityReference.java:78-81), which resolves via
    // `level::getEntityInAnyDimension` (Level.getEntityInAnyDimension(UUID)), i.e. it already
    // searches every loaded level exactly like IP's getAllLevels() loop did. Re-adding the
    // redirect would be dead, redundant code. NEUTRALIZED (behavior-preserving: cross-dimension
    // owner resolution is intact via vanilla). `extends MixinEntity` retained per IP's mixin
    // hierarchy. See migration/api-map/mixin-common.md §5 (MixinProjectile) and
    // migration/api-map/teleportation-collision.md CHANGED row (Projectile.getOwner).
//    @Redirect(
//        method = "getOwner",
//        at = @At(
//            value = "INVOKE",
//            target = "Lnet/minecraft/server/level/ServerLevel;getEntity(Ljava/util/UUID;)Lnet/minecraft/world/entity/Entity;"
//        )
//    )
//    private Entity redirectGetEntityFromUuid(
//        net.minecraft.server.level.ServerLevel serverLevel,
//        java.util.UUID uuid
//    ) {
//        MinecraftServer server = serverLevel.getServer();
//        for (ServerLevel world : server.getAllLevels()) {
//            Entity entity = world.getEntity(uuid);
//            if (entity != null) {
//                return entity;
//            }
//        }
//        return null;
//    }

//    @Shadow
//    public abstract void onHit(HitResult hitResult);
//
//    @Inject(method = "Lnet/minecraft/world/entity/projectile/Projectile;onHit(Lnet/minecraft/world/phys/HitResult;)V", at = @At(value = "HEAD"), cancellable = true)
//    protected void onHit(HitResult hitResult, CallbackInfo ci) {
//        Entity this_ = (Entity) (Object) this;
//        if (hitResult instanceof BlockHitResult) {
//            Block hittingBlock = this_.level().getBlockState(((BlockHitResult) hitResult).getBlockPos()).getBlock();
//            if (hitResult.getType() == HitResult.Type.BLOCK &&
//                hittingBlock == PortalPlaceholderBlock.instance
//            ) {
//                ci.cancel();
//            }
//        }
//    }
//

}
