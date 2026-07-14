package qouteall.imm_ptl.core.mixin.common.container_gui;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;

@Mixin(AbstractContainerMenu.class)
public class MixinAbstractContainerMenu {
    // S10-C retargets:
    //  (1) method: the 1.21.3 intermediary lambda name "method_17696" does not exist in
    //      Mojang-mapped 26.2. The reach check now lives in the stillValid(...) lambda, compiled
    //      (javap of vanilla-26.2 AbstractContainerMenu) as
    //      `private static Boolean lambda$stillValid$0(Block, Player, Level, BlockPos)`.
    //  (2) op rename: Player.canInteractWithBlock(BlockPos,double) ->
    //      Player.isWithinBlockInteractionRange(BlockPos,double) (Player.java:1993); javap-confirmed
    //      invokevirtual owner net/minecraft/world/entity/player/Player inside lambda$stillValid$0.
    //  The @Local(argsOnly = true) Level captures the lambda's Level parameter (arg index 2).
    @WrapOperation(
        method = "lambda$stillValid$0",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isWithinBlockInteractionRange(Lnet/minecraft/core/BlockPos;D)Z"
        )
    )
    private static boolean wrapDistanceToSqr(
        Player player, BlockPos blockPos, double distance,
        Operation<Boolean> operation,
        @Local(argsOnly = true) Level world
    ) {
        boolean canInteract = operation.call(player, blockPos, distance);
        if (canInteract) {
            return true;
        }

        return BlockManipulationServer.validateReach(player, world, blockPos);
    }
}
