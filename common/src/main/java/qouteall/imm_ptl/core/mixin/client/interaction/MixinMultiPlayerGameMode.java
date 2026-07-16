package qouteall.imm_ptl.core.mixin.client.interaction;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;
import qouteall.imm_ptl.core.ducks.IEClientPlayerInteractionManager;
import qouteall.q_misc_util.api.McRemoteProcedureCall;

/**
 * S12-B port disposition: NEEDS-RETARGET (mixin-client.md §4). Routes cross-portal block interaction: the
 * player's {@code level()} reads use the switched {@code mc.level}, the use-on context is built against it,
 * and outgoing action/use packets are re-wrapped into dimension-tagged RPC packets when the world is
 * switched.
 *
 * <p><b>26.2 retargets:</b>
 * <ul>
 *   <li><b>{@code redirectPlayerLevel1} (S13-C RESOLVED):</b> the {@code LocalPlayer.level()} call this
 *       @Redirect targets lives inside a SYNTHETIC LAMBDA of {@code startDestroyBlock} (IP's
 *       {@code method_41930} is a dead 1.21.3 name); {@code startDestroyBlock}'s own body has NO
 *       {@code level()} invoke, so targeting {@code "startDestroyBlock"} matched 0 points and crashed at
 *       weave. Re-derived the lambda's synthetic name from the COMPILED named 26.2 jar (R13c:
 *       {@code lambda$startDestroyBlock$1(BlockState,BlockPos,Direction,int)Packet}) and substituted it
 *       for the {@code method=} selector; the @At INVOKE target string was already correct.</li>
 *   <li><b>{@code redirectPlayerLevel2}:</b> the surviving {@code player.level()} in
 *       {@code continueDestroyBlock} is DIRECT (not in a lambda, {@code :256}); the @Redirect resolves
 *       normally.</li>
 *   <li><b>{@code redirectNewUseOnContext}:</b> {@code performUseItemOn} still builds
 *       {@code new UseOnContext(player, hand, blockHit)} (3-arg public, {@code :370}); the explicit-level
 *       5-arg ctor is now {@code protected}, so the switched-level {@code UseOnContext} is built via the
 *       {@link IEUseOnContext} constructor-{@code @Invoker}.</li>
 *   <li><b>send {@code @ModifyArg}s:</b> {@code startPrediction}/{@code startDestroyBlock}/
 *       {@code stopDestroyBlock} still call {@code ClientPacketListener.send(Packet)}; the packet is
 *       re-wrapped via {@code ip_redirectPacket} using the surviving {@code STREAM_CODEC}s
 *       ({@code ServerboundPlayerActionPacket.STREAM_CODEC} :11, {@code ServerboundUseItemOnPacket.STREAM_CODEC}
 *       :11). {@code BlockManipulationServer}/{@code McRemoteProcedureCall} resolve at S13.</li>
 * </ul>
 * Held/unregistered until S13.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MixinMultiPlayerGameMode implements IEClientPlayerInteractionManager {
    @Shadow
    @Final
    private ClientPacketListener connection;

    @Shadow
    @Final
    private Minecraft minecraft;

    // the player level field is not being switched now
    // S13-C weave fix: on 26.2 the ONLY LocalPlayer.level() invoke reachable from startDestroyBlock lives
    // inside the SYNTHETIC lambda `lambda$startDestroyBlock$1` (offset 67; IP's method_41930 is a dead
    // 1.21.3 name). startDestroyBlock's own body has ZERO level() invokes, so the IP-era selector matched
    // 0 points => fatal InvalidInjectionException at weave (require:1). Re-derived the lambda name from the
    // compiled named dev jar (R13c: `lambda$startDestroyBlock$1(BlockState,BlockPos,Direction,int)Packet`,
    // ordinal $1 compiler-assigned) and substituted it for the method selector; the @At INVOKE target
    // string is unchanged (bytecode owner is literally LocalPlayer.level). IP semantics identical.
    @Redirect(
        method = "lambda$startDestroyBlock$1",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;level()Lnet/minecraft/world/level/Level;"
        )
    )
    private Level redirectPlayerLevel1(LocalPlayer instance) {
        return Minecraft.getInstance().level;
    }

    // the player level field is not being switched now
    @Redirect(
        method = "continueDestroyBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;level()Lnet/minecraft/world/level/Level;"
        )
    )
    private Level redirectPlayerLevel2(LocalPlayer instance) {
        return Minecraft.getInstance().level;
    }

    // use another constructor that does not use player level
    // 26.2: the 5-arg explicit-level ctor is protected -> build via the IEUseOnContext constructor-invoker.
    @Redirect(
        method = "performUseItemOn",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/item/context/UseOnContext;"
        )
    )
    private UseOnContext redirectNewUseOnContext(Player player, InteractionHand interactionHand, BlockHitResult blockHitResult) {
        return IEUseOnContext.ip_create(
            Minecraft.getInstance().level,
            player,
            interactionHand,
            player.getItemInHand(interactionHand),
            blockHitResult
        );
    }

    @ModifyArg(
        method = "startPrediction",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private Packet<?> modifyPacketInStartPrediction(Packet<?> packet) {
        return ip_redirectPacket(packet);
    }

    @ModifyArg(
        method = "startDestroyBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private Packet redirectSendInStartDestroyBlock(Packet packet) {
        return ip_redirectPacket(packet);
    }

    @ModifyArg(
        method = "stopDestroyBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private Packet redirectSendInStopDestroyBlock(Packet packet) {
        return ip_redirectPacket(packet);
    }

    private static Packet<?> ip_redirectPacket(Packet<?> packet) {
        if (ClientWorldLoader.getIsWorldSwitched()) {
            ResourceKey<Level> dimension = Minecraft.getInstance().level.dimension();
            if (packet instanceof ServerboundPlayerActionPacket playerActionPacket) {
                if (BlockManipulationServer.isAttackingAction(playerActionPacket.getAction())) {
                    FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                    ServerboundPlayerActionPacket.STREAM_CODEC.encode(buf, playerActionPacket);

                    return McRemoteProcedureCall.createPacketToSendToServer(
                        "qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer.RemoteCallables.processPlayerActionPacket",
                        dimension,
                        IPMcHelper.bufToBytes(buf)
                    );
                }
            }
            else if (packet instanceof ServerboundUseItemOnPacket useItemOnPacket) {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                ServerboundUseItemOnPacket.STREAM_CODEC.encode(buf, useItemOnPacket);

                return McRemoteProcedureCall.createPacketToSendToServer(
                    "qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer.RemoteCallables.processUseItemOnPacket",
                    dimension,
                    IPMcHelper.bufToBytes(buf)
                );
            }
            // ServerboundUseItemPacket is not redirected
        }

        return packet;
    }

}
