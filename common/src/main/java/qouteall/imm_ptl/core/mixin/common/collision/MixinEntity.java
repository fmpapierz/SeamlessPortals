package qouteall.imm_ptl.core.mixin.common.collision;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.api.ImmPtlEntityExtension;
import qouteall.imm_ptl.core.collision.PortalCollisionHandler;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.portal.EndPortalEntity;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.CountDownInt;

@Mixin(Entity.class)
public abstract class MixinEntity implements IEEntity, ImmPtlEntityExtension,
    com.warwa.seamlessportals.passthrough.SeamCrossingHolder {

    @Nullable
    @Unique
    private PortalCollisionHandler ip_portalCollisionHandler;

    // ENGINE STAGE 2a: the crossing anchor (SeamCrossingHolder). Plain defaults only —
    // @Unique initializers run in the target ctor; cross-class static calls there deadlock
    // class-init (the standing mixin rule).
    @Nullable
    @Unique
    private Portal seamlessportals$anchorFace;

    @Unique
    private int seamlessportals$anchorEpoch;

    @Override
    public @Nullable Portal seamlessportals$getAnchorFace() {
        return seamlessportals$anchorFace;
    }

    @Override
    public void seamlessportals$setAnchorFace(@Nullable Portal face) {
        seamlessportals$anchorFace = face;
    }

    @Override
    public int seamlessportals$getAnchorEpoch() {
        return seamlessportals$anchorEpoch;
    }

    @Override
    public void seamlessportals$setAnchorEpoch(int epoch) {
        seamlessportals$anchorEpoch = epoch;
    }

    @Shadow
    private Level level;

    @Shadow
    protected abstract Vec3 collide(Vec3 vec3d_1);

    @Shadow
    public abstract Component getName();

    @Shadow
    public abstract double getX();

    @Shadow
    public abstract double getY();

    @Shadow
    public abstract double getZ();


    @Shadow
    public int tickCount;

    @Shadow
    protected abstract void unsetRemoved();

    @Shadow
    private Vec3 position;

    @Shadow
    private BlockPos blockPosition;

    @Shadow
    private ChunkPos chunkPosition;

    @Shadow @Final private static Logger LOGGER;
    @Shadow private @Nullable BlockState inBlockState;

    // S10-C: shadow of the per-segment box factory redirected below (checkInsideBlocks rework).
    // Entity.makeBoundingBox(Vec3) — net/minecraft/world/entity/Entity.java:481.
    @Shadow
    protected abstract AABB makeBoundingBox(Vec3 pos);

    @Unique
    private static final CountDownInt IMM_PTL_LOG_COUNTER = new CountDownInt(20);

    @Redirect(
        method = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 redirectHandleCollisions(Entity entity, Vec3 attemptedMove) {
        if (!IPGlobal.enableServerCollision) {
            if (!entity.level().isClientSide()) {
                if (entity instanceof Player) {
                    return attemptedMove;
                }
                else {
                    return Vec3.ZERO;
                }
            }
        }

        if (attemptedMove.lengthSqr() > 60 * 60) {
            // avoid loading too many chunks in collision calculation and lag the server
            if (IMM_PTL_LOG_COUNTER.tryDecrement()) {
                LOGGER.error(
                    "[ImmPtl] Skipping collision calculation because entity moves too fast {} {} {}",
                    entity, attemptedMove, entity.level().getGameTime(),
                    new Throwable()
                );
            }

            return Vec3.ZERO;
        }

        if (!IPGlobal.crossPortalCollision
            || ip_portalCollisionHandler == null
            || !ip_portalCollisionHandler.hasCollisionEntry()
        ) {
            Vec3 normalCollisionResult = collide(attemptedMove);
            return normalCollisionResult;
        }

        Vec3 result = ip_portalCollisionHandler.handleCollision(
            (Entity) (Object) this, attemptedMove
        );

        if (result.lengthSqr() > 20 * 20) {
            if (IMM_PTL_LOG_COUNTER.tryDecrement()) {
                LOGGER.error(
                    "[ImmPtl] cross portal collision result too large {} {} {}",
                    this, attemptedMove, result
                );
            }
            return Vec3.ZERO;
        }

        return result;
    }

    //don't burn when jumping into end portal
    // TODO make it work for all portals
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;fireImmune()Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onIsFireImmune(CallbackInfoReturnable<Boolean> cir) {
        if (ip_getCollidingPortal() instanceof EndPortalEntity) {
            cir.setReturnValue(true);
            cir.cancel();
        }
    }

    // S10-C checkInsideBlocks rework (F-collision-3): the 1.21.3 pair
    //   @Redirect Entity.getBoundingBox() inside checkInsideBlocks()  -> ip_getActiveCollisionBox(box)
    //   @Inject INVOKE_ASSIGN-after that getBoundingBox(), cancel when box == null
    // has NO anchor in 26.2: checkInsideBlocks() was rearchitected into a movement-path walker.
    // Entry applyEffectsFromBlocks(List<Movement>) (Entity.java:948) -> checkInsideBlocks(
    //   List<Movement>, InsideBlockEffectApplier.StepBasedCollector) (Entity.java:1269) ->
    // per-segment checkInsideBlocks(Vec3 from, Vec3 to, StepBasedCollector, LongSet, int)
    // (Entity.java:1299) which builds its box as `this.makeBoundingBox(to).deflate(1.0E-5F)`
    // (Entity.java:1302) and walks blocks via BlockGetter.forEachBlockIntersectedBetween.
    // Faithful re-derivation: clip the PER-SEGMENT box against the portal (redirect the :1302
    // makeBoundingBox(to)), and when that clipped box is null (segment target entirely in the
    // portal's ghost half-space) apply no block-inside effects for the segment. Fidelity target
    // unchanged: no fire / powder-snow / portal-trigger effects from the ghost part of the box.
    @Inject(
        method = "checkInsideBlocks(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/InsideBlockEffectApplier$StepBasedCollector;Lit/unimi/dsi/fastutil/longs/LongSet;I)I",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onCheckInsideBlocksSegment(
        Vec3 from, Vec3 to,
        InsideBlockEffectApplier.StepBasedCollector effectCollector,
        LongSet visitedBlocks, int maxMovementIterations,
        CallbackInfoReturnable<Integer> cir
    ) {
        if (ip_getActiveCollisionBox(makeBoundingBox(to)) == null) {
            // consume 0 movement iterations; skip block-inside effects for this ghost segment
            cir.setReturnValue(0);
        }
    }

    @Redirect(
        method = "checkInsideBlocks(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/InsideBlockEffectApplier$StepBasedCollector;Lit/unimi/dsi/fastutil/longs/LongSet;I)I",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;"
        )
    )
    private AABB redirectBoundingBoxInCheckingBlockCollision(Entity entity, Vec3 to) {
        // guarded non-null by onCheckInsideBlocksSegment (HEAD cancel when clip == null)
        return ip_getActiveCollisionBox(makeBoundingBox(to));
    }

    // avoid suffocation when colliding with a portal on wall
    @Inject(method = "Lnet/minecraft/world/entity/Entity;isInWall()Z", at = @At("HEAD"), cancellable = true)
    private void onIsInsideWall(CallbackInfoReturnable<Boolean> cir) {
        if (ip_isRecentlyCollidingWithPortal()) {
            cir.setReturnValue(false);
        }
    }

    // for teleportation debug
    @Inject(
        method = "Lnet/minecraft/world/entity/Entity;setPosRaw(DDD)V",
        at = @At("HEAD")
    )
    private void onSetPos(double nx, double ny, double nz, CallbackInfo ci) {
        Entity this_ = (Entity) (Object) this;

        if (this_ instanceof Player) {
            if (IPGlobal.teleportationDebugEnabled) {
                if (Math.abs(getX() - nx) > 10 ||
                    Math.abs(getY() - ny) > 10 ||
                    Math.abs(getZ() - nz) > 10
                ) {
                    Helper.log(String.format(
                        "%s %s teleported from %s %s %s to %s %s %s",
                        getName().getContents(),
                        level.dimension(),
                        (int) getX(), (int) getY(), (int) getZ(),
                        (int) nx, (int) ny, (int) nz
                    ));
                    new Throwable().printStackTrace();
                }
            }
        }
    }

    // fix climbing onto ladder cross portal
    @Inject(method = "getInBlockState", at = @At("HEAD"), cancellable = true)
    private void onGetInBlockState(CallbackInfoReturnable<BlockState> cir) {
        Portal collidingPortal = ((IEEntity) this).ip_getCollidingPortal();
        Entity this_ = (Entity) (Object) this;
        if (collidingPortal != null) {
            if (collidingPortal.getNormal().y > 0) {
                BlockPos remoteLandingPos = BlockPos.containing(
                    collidingPortal.transformPoint(this_.position())
                );

                Level destinationWorld = collidingPortal.getDestinationWorld();

                if (destinationWorld.hasChunkAt(remoteLandingPos)) {
                    BlockState result = destinationWorld.getBlockState(remoteLandingPos);

                    if (!result.isAir()) {
                        cir.setReturnValue(result);
                        cir.cancel();
                    }
                }
            }
        }
    }

//    // IDEA's conditional breakpoint hurts performance
//    @Inject(
//        method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V",
//        at = @At("HEAD")
//    )
//    private void debug_onSetDeltaMovement(Vec3 deltaMovement, CallbackInfo ci) {
//        Entity this_ = (Entity) (Object) this;
//        if (this_ instanceof Player && this_.level().isClientSide()) {
//            int i = 0;
//        }
//    }

    @Override
    public Portal ip_getCollidingPortal() {
        if (ip_portalCollisionHandler == null) {
            return null;
        }
        if (ip_portalCollisionHandler.portalCollisions.isEmpty()) {
            return null;
        }
        return ip_portalCollisionHandler.portalCollisions.get(0).portal;
    }

    // this should be called between the range of updating last tick pos and calculating movement
    // because between these two operations, this tick pos is the same as last tick pos
    // CollisionHelper.getStretchedBoundingBox uses the difference between this tick pos and last tick pos
    @Override
    public void ip_tickCollidingPortal() {
        Entity this_ = (Entity) (Object) this;

        // ENGINE STAGE 2a: anchor maintenance BEFORE the prune — rider inheritance mirrors the
        // unit root's anchor; CLOSE releases it; the prune's mustKeep then reads settled state.
        com.warwa.seamlessportals.passthrough.SeamCrossingRule.tickAnchor(this_);

        if (ip_portalCollisionHandler != null) {
            ip_portalCollisionHandler.update(this_);
        }

        // S10-C mechanical translation: Level.isClientSide is a private final field in 26.2
        // (Level.java:127); read it through the public isClientSide() accessor (Level.java:163).
        if (level.isClientSide()) {
            IPMcHelper.onClientEntityTick(this_);
        }
    }

    @Override
    public void ip_notifyCollidingWithPortal(Entity portal) {
        Entity this_ = (Entity) (Object) this;

        if (ip_portalCollisionHandler == null) {
            ip_portalCollisionHandler = new PortalCollisionHandler();
        }

        // Stage 0 (engine design §1.3): the behind-refusal + twin-refusal register gates live
        // in the module as mayBook (a portal is entered from its FRONT; a booked face's
        // co-located twin may not register mid-crossing; the arrival seed bypasses the
        // behind-refusal — the rebased trail body is legitimately wholly behind the arrival
        // face). Stage 2a replaces the seed ThreadLocal with anchor-authorized booking.
        if (!com.warwa.seamlessportals.passthrough.SeamCrossingRule.mayBook(
            this_, ip_portalCollisionHandler, (Portal) portal)) {
            return;
        }

        ip_portalCollisionHandler.notifyCollidingWithPortal(this_, ((Portal) portal));

        // F5/F6 RIDER REGISTRATION FAN (live round 2026-08-17, log-nailed: "MAIN
        // vanilla-unclipped (not in collidedEntities)" for the cow while its cart was clipped
        // and projecting): registration happens on the Entity.move collision path, which
        // PASSENGERS never run — so a rider had no entry until the arrival seed. Its
        // through-portal image was missing from the whole emergence (the cowless ghost cart
        // sliding out of the seam = the "couple-seconds sighting"; the cow "disappearing from
        // the minecart" every return crossing) and its straddling body drew unclipped (the
        // split-second bleed). The vehicle's registration now fans to its passengers — each
        // through its own duck call, so the per-rider refuses-gate runs and stacked riders fan
        // naturally. Their entries then live and prune through the existing per-tick passenger
        // hooks, exactly like the vehicle's.
        for (Entity rider : this_.getPassengers()) {
            ((IEEntity) rider).ip_notifyCollidingWithPortal(portal);
        }
    }

    @Override
    public boolean ip_isCollidingWithPortal() {
        if (ip_portalCollisionHandler == null) {
            return false;
        }
        return ip_portalCollisionHandler.hasCollisionEntry();
    }

    @Override
    public boolean ip_isRecentlyCollidingWithPortal() {
        if (ip_portalCollisionHandler == null) {
            return false;
        }
        return ip_portalCollisionHandler.isRecentlyCollidingWithPortal((Entity) (Object) this);
    }

    @Override
    public void ip_unsetRemoved() {
        unsetRemoved();
    }

    /**
     * {@link Entity#setPosRaw(double, double, double)}
     */
    @IPVanillaCopy
    @Override
    public void ip_setPositionWithoutTriggeringCallback(Vec3 newPos) {
        double x = newPos.x;
        double y = newPos.y;
        double z = newPos.z;

        if (this.position.x != x || this.position.y != y || this.position.z != z) {
            this.position = new Vec3(x, y, z);
            int bx = Mth.floor(x);
            int by = Mth.floor(y);
            int bz = Mth.floor(z);
            if (bx != this.blockPosition.getX()
                || by != this.blockPosition.getY()
                || bz != this.blockPosition.getZ()
            ) {
                this.blockPosition = new BlockPos(bx, by, bz);
                this.inBlockState = null;
                if (SectionPos.blockToSectionCoord(bx) != this.chunkPosition.x()
                    || SectionPos.blockToSectionCoord(bz) != this.chunkPosition.z()
                ) {
                    this.chunkPosition = ChunkPos.containing(this.blockPosition);
                }
            }
            // NOTE (@IPVanillaCopy of Entity.setPosRaw, Entity.java:3786-3811): the 26.2 body also
            // fires this.levelCallback.onMove() and the ServerLevel WaypointTransmitter/ServerPlayer
            // waypoint updates (Entity.java:3800-3809). Those are the callbacks this copy exists to
            // OMIT (three things now: levelCallback.onMove() + both waypoint branches).
        }
    }

    @Override
    public void ip_clearCollidingPortal() {
        ip_portalCollisionHandler = null;
    }

    @Nullable
    @Override
    public AABB ip_getActiveCollisionBox(AABB originalBox) {
        Entity this_ = (Entity) (Object) this;

        if (ip_portalCollisionHandler == null) {
            return originalBox;
        }

        return ip_portalCollisionHandler.getActiveCollisionBox(this_, originalBox);
    }

    @Nullable
    @Override
    public PortalCollisionHandler ip_getPortalCollisionHandler() {
        return ip_portalCollisionHandler;
    }

    @Override
    public PortalCollisionHandler ip_getOrCreatePortalCollisionHandler() {
        if (ip_portalCollisionHandler == null) {
            ip_portalCollisionHandler = new PortalCollisionHandler();
        }
        return ip_portalCollisionHandler;
    }

    @Override
    public void ip_setPortalCollisionHandler(@Nullable PortalCollisionHandler handler) {
        ip_portalCollisionHandler = handler;
    }

    @Override
    public void ip_setWorld(Level world) {
        this.level = world;
    }

    // don't use game time because client game time may jump due to time synchronization
    private long ip_getStableTiming() {
        return tickCount;
    }
}
