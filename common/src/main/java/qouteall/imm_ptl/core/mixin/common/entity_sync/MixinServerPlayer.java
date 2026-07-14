package qouteall.imm_ptl.core.mixin.common.entity_sync;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.ducks.IEServerPlayerEntity;

@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer extends Player implements IEServerPlayerEntity {
    @Shadow
    public ServerGamePacketListenerImpl connection;
    @Shadow
    private Vec3 enteredNetherPosition;

    @Shadow
    private boolean isChangingDimension;

    public MixinServerPlayer(Level level, GameProfile gameProfile) {
        super(level, gameProfile);
    }

    @Shadow protected abstract void triggerDimensionChangeTriggers(ServerLevel origin);

    // 26.2 NEEDS-RETARGET (mixin-common.md §3 MixinServerPlayer; S08-teleportation §7.4): ServerPlayer
    // no longer overrides stopRiding — the dismount packets moved to the removeVehicle() override
    // (ServerPlayer.java:2138-2150, sends ClientboundRemoveMobEffectPacket + ClientboundSetPassengersPacket).
    // IP's super.stopRiding() bypass is now INEFFECTIVE (Entity.stopRiding -> this.removeVehicle() virtual
    // -> ServerPlayer.removeVehicle, the packets it wanted to skip). Retarget to super.removeVehicle():
    // super = Player, and Player.removeVehicle (Player.java:857) = super.removeVehicle() (Entity, the
    // real dismount) + boardingCooldown=0, sending NO packets — so INVOKESPECIAL Player.removeVehicle
    // skips ServerPlayer.removeVehicle's packet override exactly as intended.
    @Override
    public void ip_stopRidingWithoutTeleportRequest() {
        super.removeVehicle();
    }

    // 26.2 NEEDS-RETARGET (mixin-common.md §3; S08-teleportation §7.4): the 2-arg startRiding(Entity, boolean)
    // is GONE; the split form is startRiding(Entity, boolean force, boolean sendEventAndTriggers)
    // (Entity.java:2414). super = Player (no startRiding override) -> Entity.startRiding, bypassing
    // ServerPlayer.startRiding's teleport packet (:2122-2135). sendEventAndTriggers=false = force + no
    // events, matching the S08 vanilla-dim-travel re-seat convention (force + no advancement triggers).
    @Override
    public void ip_startRidingWithoutTeleportRequest(Entity newVehicle) {
        super.startRiding(newVehicle, true, false);
    }

    /**
     * See {@link ServerPlayer#changeDimension(ServerLevel)}
     */
    @Override
    public void portal_worldChanged(ServerLevel fromWorld, Vec3 fromPos) {
        if (fromWorld.dimension() == Level.OVERWORLD && this.level().dimension() == Level.NETHER) {
            enteredNetherPosition = fromPos;
        }
        triggerDimensionChangeTriggers(fromWorld);
    }
}
