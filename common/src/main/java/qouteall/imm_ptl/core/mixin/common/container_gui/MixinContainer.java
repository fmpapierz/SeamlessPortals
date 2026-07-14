package qouteall.imm_ptl.core.mixin.common.container_gui;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;

@SuppressWarnings("ALL")
@Mixin(Container.class)
public interface MixinContainer {
    // S10-C mechanical rename: Player.canInteractWithBlock(BlockPos,double) ->
    // Player.isWithinBlockInteractionRange(BlockPos,double) (Player.java:1993); the invokevirtual
    // owner stays Player (Container.stillValidBlockEntity :100, javap-confirmed owner
    // net/minecraft/world/entity/player/Player). Target method + descriptor unchanged.
    @WrapOperation(
        method = "stillValidBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/player/Player;F)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isWithinBlockInteractionRange(Lnet/minecraft/core/BlockPos;D)Z"
        )
    )
    private static boolean wrapCanInteractWithBlock(
        Player player, BlockPos blockPos, double distance, Operation<Boolean> operation,
        @Local BlockEntity blockEntity
    ) {
        Boolean originalResult = operation.call(player, blockPos, distance);

        if (!originalResult) {
            BlockPos targetPos = blockEntity.getBlockPos();
            Level targetWorld = blockEntity.getLevel();

            return BlockManipulationServer.validateReach(player, targetWorld, targetPos);
        }
        else {
            return true;
        }
    }

}
