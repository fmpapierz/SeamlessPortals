package qouteall.imm_ptl.core.mixin.common.position_sync;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.ducks.IEPlayerMoveC2SPacket;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;
import qouteall.imm_ptl.core.ducks.IEServerPlayNetworkHandler;
import qouteall.imm_ptl.core.mc_utils.ServerTaskList;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.my_util.CountDownInt;

import java.util.Set;

@Mixin(value = ServerGamePacketListenerImpl.class, priority = 900)
public abstract class MixinServerGamePacketListenerImpl implements IEServerPlayNetworkHandler {
    @Shadow
    public ServerPlayer player;
    @Shadow
    private Vec3 awaitingPositionFromClient;
    @Shadow
    private int awaitingTeleport;
    @Shadow
    private int awaitingTeleportTime;
    @Shadow
    private int tickCount;

    @Shadow
    private double vehicleLastGoodX;

    @Shadow
    private double vehicleLastGoodY;

    @Shadow
    private double vehicleLastGoodZ;

    @Shadow
    private double vehicleFirstGoodX;

    @Shadow
    private double vehicleFirstGoodY;

    @Shadow
    private double vehicleFirstGoodZ;

    @Shadow
    private Entity lastVehicle;

    @Shadow
    private boolean clientVehicleIsFloating;

    @Shadow
    @Final
    static Logger LOGGER;

    @Shadow
    public abstract ServerPlayer getPlayer();

    @Shadow
    private boolean clientIsFloating;

    @Unique
    private static final CountDownInt LOG_LIMIT = new CountDownInt(20);

    @Unique
    private int ip_wrongMovePacketCount = 0;

    /**
     * Attach the dimension information to
     * {@link ServerGamePacketListenerImpl#awaitingPositionFromClient}
     *
     * The teleport system: when server wants to teleport a player,
     * the server will send the {@link ClientboundPlayerPositionPacket}, then
     * set {@link ServerGamePacketListenerImpl#awaitingPositionFromClient}
     * and {@link ServerGamePacketListenerImpl#awaitingTeleport} counter.
     *
     * Before the client sending {@link ServerboundAcceptTeleportationPacket},
     * the position packets are ignored, and some of the item using packets are ignored.
     *
     * Attach the dimension information to the position, to avoid messing up coordinates
     * of different dimensions.
     */
    @SuppressWarnings("JavadocReference")
    @Unique
    private @Nullable ResourceKey<Level> ip_dimOfAwaitingPosition;

    //do not process move packet when client dimension and server dimension are not synced
    // isSameThread guard: injected AFTER PacketUtils.ensureRunningOnSameThread (26.2 :1065),
    // so this runs on the game thread (the netty first pass re-queues via the throw).
    @Inject(
        method = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V",
        at = @At(
            value = "INVOKE",
            shift = At.Shift.AFTER,
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"
        ),
        cancellable = true
    )
    private void onProcessMovePacket(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        ResourceKey<Level> packetDimension = ((IEPlayerMoveC2SPacket) packet).ip_getPlayerDimension();

        if (packetDimension == null) {
            // this actually never happens, because the vanilla client will disconnect immediately
            // when receiving the position sync packet that has the extra dimension field
            LOGGER.error("Player move packet is missing dimension info. Maybe the player client doesn't install iPortal");
            ServerTaskList.of(player.server).addTask(() -> {
                player.connection.disconnect(Component.literal(
                    "The client does not have Immersive Portals mod"
                ));
                return true;
            });
            return;
        }

        if (player.level().dimension() != packetDimension) {
            if (LOG_LIMIT.tryDecrement()) {
                LOGGER.info(
                    "[ImmPtl] Ignoring player move packet. Player: {} Packet: {} {} {} {}",
                    player, packetDimension.identifier(),
                    packet.getX(player.getX()),
                    packet.getY(player.getY()),
                    packet.getZ(player.getZ())
                );
            }

            ip_wrongMovePacketCount += 1;

            if (ip_wrongMovePacketCount > 10) {
                LOGGER.info(
                    "[ImmPtl] Force move player {} {} {}",
                    player, player.level().dimension().identifier(), player.position()
                );
                ServerTeleportationManager.of(player.server).forceTeleportPlayer(
                    player, player.level().dimension(), player.position()
                );
                ip_wrongMovePacketCount = 0;
            }

            ci.cancel();
        }
        else {
            ip_wrongMovePacketCount = 0;
        }
    }

    /**
     * @reason make PlayerPositionLookS2CPacket contain dimension data and do some special handling
     * @author qouteall
     *
     * 26.2 RE-DERIVATION (F17): the IP overwrite targeted the 1.21.3
     * teleport(double,double,double,float,float,Set&lt;RelativeMovement&gt;). That signature is GONE;
     * this overwrite is RE-DERIVED LINE-BY-LINE from 26.2's
     * teleport(PositionMoveRotation destination, Set&lt;Relative&gt; relatives)
     * (ServerGamePacketListenerImpl.java:1261-1270). The relative-delta base computation and the
     * hand-built ClientboundPlayerPositionPacket(x-xBase,...) are GONE — 26.2 carries the
     * relative/absolute semantics inside PositionMoveRotation + the relatives Set, applied by
     * player.teleportSetPosition(destination, relatives) and re-applied client-side from the packet.
     * IP's ADDED behavior layered on top: removed-player guard, serverTeleportLogging,
     * ip_dimOfAwaitingPosition stamp, and the R8 dimension stamp on the sent packet.
     */
    @Overwrite
    @IPVanillaCopy
    public void teleport(PositionMoveRotation destination, Set<Relative> relatives) {
        // it may request teleport while this.player is marked removed during respawn

        if (player.getRemovalReason() != null) {
            LOGGER.error(
                "[ImmPtl] Tries to send player pos packet to a removed player {}",
                player, new Throwable()
            );
            return;
        }

        if (IPConfig.getConfig().serverTeleportLogging) {
            LOGGER.info(
                "Teleporting player {} to {} {}",
                player, player.level().dimension().identifier(), destination.position()
            );
        }

        this.awaitingTeleportTime = this.tickCount;
        if (++this.awaitingTeleport == Integer.MAX_VALUE) {
            this.awaitingTeleport = 0;
        }

        this.player.teleportSetPosition(destination, relatives);
        this.awaitingPositionFromClient = this.player.position();
        this.ip_dimOfAwaitingPosition = player.level().dimension();

        ClientboundPlayerPositionPacket lookPacket =
            ClientboundPlayerPositionPacket.of(this.awaitingTeleport, destination, relatives);

        // (Object) intermediate cast: 26.2 ClientboundPlayerPositionPacket is a final record, so the
        // direct interface cast IP used would be an inconvertible-types error.
        ((IEPlayerPositionLookS2CPacket) (Object) lookPacket).ip_setPlayerDimension(player.level().dimension());

        this.player.connection.send(lookPacket);
    }

    // 26.2 NEEDS-RETARGET (mixin-common.md §4; S08-teleportation §7.3): the server anticheat
    // isPlayerCollidingWithAnythingNew was RENAMED + generalized to
    // isEntityCollidingWithAnythingNew(LevelReader, Entity, AABB oldAABB, double, double, double)
    // (ServerGamePacketListenerImpl.java:1243-1255) and is now called from BOTH handleMovePlayer AND
    // handleMoveVehicle. The body re-derived against getPreMoveCollisions (CollisionGetter.java:90)
    // with the bottom-center CollisionContext origin. Behavior-review flag (S08 §7.3): IP's
    // cross-portal exemption is extended to the vehicle path (fidelity default — a cross-portal
    // minecart is the same false-positive class); IP 1.21.3 had no vehicle version to patch.
    @Inject(
        method = "isEntityCollidingWithAnythingNew", at = @At("HEAD"), cancellable = true
    )
    private void onIsEntityCollidingWithAnythingNew(
        LevelReader level, Entity entity, AABB oldAABB, double newX, double newY, double newZ,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!IPGlobal.crossPortalCollision) {
            return;
        }

        // for this to work, the entity's portal collision status must be updated after teleporting

        AABB activeOldBB = ((IEEntity) entity).ip_getActiveCollisionBox(oldAABB);

        if (activeOldBB == null) {
            cir.setReturnValue(false);
            return;
        }

        AABB newBB = entity.getBoundingBox().move(
            newX - entity.getX(), newY - entity.getY(), newZ - entity.getZ()
        );

        AABB activeNewBB = ((IEEntity) entity).ip_getActiveCollisionBox(newBB);

        if (activeNewBB == null) {
            cir.setReturnValue(false);
            return;
        }

        Iterable<VoxelShape> newBBCollisions =
            level.getPreMoveCollisions(entity, activeNewBB.deflate(1.0E-5F), oldAABB.getBottomCenter());

        VoxelShape activeOldBBShape = Shapes.create(activeOldBB.deflate(1.0E-5F));

        for (VoxelShape shape : newBBCollisions) {
            if (!Shapes.joinIsNotEmpty(shape, activeOldBBShape, BooleanOp.AND)) {
                // when a new collision box does not intersect with old bounding box
                // it's considered as colliding with something new
                cir.setReturnValue(true);
                return;
            }
        }

        cir.setReturnValue(false);
    }

    // don't get recognized as floating when standing on blocks on other side of portal
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        if (((IEEntity) player).ip_isRecentlyCollidingWithPortal()) {
            clientIsFloating = false;
        }
    }

    // if the awaiting position is in a different dimension, move the player accordingly
    // avoid messing up position for different dimensions
    // 26.2 NEEDS-RETARGET (mixin-common.md §4): the accept-teleport anchor absMoveTo is GONE — 26.2
    // handleAcceptTeleportPacket calls this.player.absSnapTo(...) (:539-546). Re-anchored to absSnapTo.
    @Inject(
        method = "handleAcceptTeleportPacket",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;absSnapTo(DDDFF)V"
        )
    )
    private void onHandleAcceptTeleportPacket(
        ServerboundAcceptTeleportationPacket packet, CallbackInfo ci
    ) {
        if (ip_dimOfAwaitingPosition == null) {
            LOGGER.error("[ImmPtl] ip_dimOfAwaitingPosition is null {}", player);
            return;
        }

        if (ip_dimOfAwaitingPosition != player.level().dimension()) {
            LOGGER.info(
                "Accepted teleport to another dimension {} {}",
                ip_dimOfAwaitingPosition, awaitingPositionFromClient
            );

            ServerLevel destWorld = player.server.getLevel(ip_dimOfAwaitingPosition);

            if (destWorld == null) {
                LOGGER.error(
                    "[ImmPtl] Cannot find destination world {}",
                    ip_dimOfAwaitingPosition.identifier()
                );
                return;
            }

            ServerTeleportationManager.of(player.server)
                .forceTeleportPlayer(
                    player, ip_dimOfAwaitingPosition,
                    awaitingPositionFromClient, false
                );
            ip_dimOfAwaitingPosition = null;
        }
    }

    @Inject(
        method = "handlePlayerCommand",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;awaitingPositionFromClient:Lnet/minecraft/world/phys/Vec3;",
            opcode = Opcodes.PUTFIELD
        )
    )
    private void onTeleportPlayerCancelSleeping(
        ServerboundPlayerCommandPacket packet, CallbackInfo ci
    ) {
        ip_dimOfAwaitingPosition = player.level().dimension();
    }

    @Override
    public boolean ip_hasAwaitingTeleport() {
        return awaitingPositionFromClient != null;
    }
}
