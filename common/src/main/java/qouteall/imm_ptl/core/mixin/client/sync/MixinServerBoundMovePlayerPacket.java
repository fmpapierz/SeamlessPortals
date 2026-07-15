package qouteall.imm_ptl.core.mixin.client.sync;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEPlayerMoveC2SPacket;

/**
 * S12-B port disposition: NEEDS-RETARGET (mixin-client.md §9). Client-side base-ctor STAMP: records the
 * player's current dimension into the {@link IEPlayerMoveC2SPacket} duck at packet construction, so the
 * per-subclass {@code write()} mixins can append it to the wire (client encode) and the S7 common
 * {@code position_sync.MixinServerboundMovePlayerPacket*} {@code read()} mixins can consume it (server
 * decode). The duck field is held by the S7 common {@code MixinServerboundMovePlayerPacket_S}.
 *
 * <p><b>26.2 retarget:</b> the protected base ctor gained two trailing flags — the descriptor is now
 * {@code (double,double,double,float,float,boolean onGround, boolean horizontalCollision, boolean hasPos,
 * boolean hasRot)} ({@code 26.2:ServerboundMovePlayerPacket.java:43-51}), i.e. {@code (DDDFFZZZZ)V} vs IP
 * 1.21.3's {@code (DDDFFZZZ)V}. The {@code @Inject @RETURN} handler adds the {@code hasPos}/{@code hasRot}
 * params. Held/unregistered until S13.
 */
@Mixin(ServerboundMovePlayerPacket.class)
public class MixinServerBoundMovePlayerPacket {
    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void onConstruct(
        double x, double y, double z, float yaw, float pitch, boolean onGround,
        boolean horizontalCollision, boolean hasPos, boolean hasRot, CallbackInfo ci
    ) {
        ResourceKey<Level> dimension = Minecraft.getInstance().player.level().dimension();
        ((IEPlayerMoveC2SPacket) this).ip_setPlayerDimension(dimension);
    }
}
