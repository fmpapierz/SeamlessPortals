package qouteall.imm_ptl.core.mixin.common;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.ducks.IEServerWorld;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevel implements IEServerWorld {

    @Shadow
    public abstract SavedDataStorage getDataStorage();

    @Shadow
    public abstract ServerChunkCache getChunkSource();

    @Shadow
    @Final
    private ServerLevelData serverLevelData;

    @Shadow
    @Final
    private PersistentEntitySectionManager<Entity> entityManager;

    //in vanilla if a dimension has no player and no forced chunks then it will not tick
    //
    // S10-C retarget (api-map/mixin-common.md §1 MixinServerLevel): the 1.21.3 redirect wrapped the
    // `List.isEmpty()` call inside ServerLevel.tick(BooleanSupplier). That call is GONE — the
    // empty-dimension gate is now `boolean isActive = this.chunkSource.hasActiveTickets();`
    // (ServerLevel.java:408; drives resetEmptyTime :410 / emptyTime++ :414 / emptyTime<300 :417).
    // Retargeted onto ServerChunkCache.hasActiveTickets(): forcing it to return true keeps the
    // dimension "active" (resetEmptyTime → emptyTime stays < 300 → keep ticking), exactly the
    // 1.21.3 semantics where the redirect returned false from isEmpty() to force keep-ticking.
    @Redirect(
        method = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;hasActiveTickets()Z"
        )
    )
    private boolean redirectHasActiveTickets(ServerChunkCache instance) {
        final ServerLevel this_ = (ServerLevel) (Object) this;
        if (ImmPtlChunkTracking.shouldLoadDimension(this_.dimension())) {
            return true;
        }
        return instance.hasActiveTickets();
    }

    // for debug
    @Inject(method = "Lnet/minecraft/server/level/ServerLevel;toString()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void onToString(CallbackInfoReturnable<String> cir) {
        final ServerLevel this_ = (ServerLevel) (Object) this;
        cir.setReturnValue("ServerWorld " + this_.dimension().identifier() +
            " " + serverLevelData.getLevelName());
    }

    @Inject(
        method = "tickNonPassenger",
        at = @At("HEAD")
    )
    private void onTickNonPassenger(Entity entity, CallbackInfo ci) {
        // this should be done right before setting last tick pos to this tick pos
        ((IEEntity) entity).ip_tickCollidingPortal();
    }

    // F5 rider fix, part 3: passengers tick through this private method, never
    // tickNonPassenger — without this hook a rider's portal-collision entries had no
    // per-tick pruning on the server either (stale cross-portal collision). Mirror of
    // the client hook in MixinClientLevel.
    @Inject(
        method = "tickPassenger",
        at = @At("HEAD"),
        require = 1
    )
    private void onTickPassenger(Entity vehicle, Entity passenger, CallbackInfo ci) {
        ((IEEntity) passenger).ip_tickCollidingPortal();
    }

    @Override
    public PersistentEntitySectionManager<Entity> ip_getEntityManager() {
        return entityManager;
    }
}
