package qouteall.imm_ptl.core.mixin.client.interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;

/**
 * S12-B port disposition: NEEDS-RETARGET (mixin-client.md §4). Runs attack/use/pick keybind actions in a
 * switched-world context when pointing through a portal.
 *
 * <p><b>26.2 retarget.</b> {@code Minecraft.handleKeybinds()} survives ({@code 26.2:Minecraft.java:1865})
 * with its {@code startAttack()}/{@code continueAttack(Z)}/{@code startUseItem()} INVOKE landmarks; IP's
 * {@code pickBlock()} was RENAMED {@code pickBlockOrEntity()} ({@code 26.2:Minecraft.java:2353}, invoked in
 * {@code handleKeybinds} at {@code :1948}) — the {@code @Shadow} and the pick {@code @WrapOperation} retarget
 * to {@code pickBlockOrEntity}. <b>S13 note:</b> {@code startUseItem()} is invoked at TWO sites in 26.2
 * {@code handleKeybinds} ({@code :1944,:1959}); the ordinal-less {@code @WrapOperation} wraps both (each runs
 * in switched context when pointing to a portal — faithful). {@code BlockManipulationClient} is the S13
 * block_manipulation closure set. Held/unregistered until S13.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft_B {
    @Shadow
    protected abstract void pickBlockOrEntity(); // 26.2: pickBlock() -> pickBlockOrEntity()

    @Shadow
    public ClientLevel level;

    @Shadow
    public HitResult hitResult;

    @Shadow
    protected int missTime;

    @Shadow
    protected abstract boolean startAttack();

    @Shadow
    protected abstract void continueAttack(boolean leftClick);

    @Shadow
    protected abstract void startUseItem();

    @WrapOperation(
        method = "handleKeybinds",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;startAttack()Z"
        )
    )
    private boolean wrapStartAttack(Minecraft instance, Operation<Boolean> original) {
        ClientLevel remoteWorld = BlockManipulationClient.getRemotePointedWorld();
        if (BlockManipulationClient.isPointingToPortal()) {
            BlockManipulationClient.withSwitchedContext(
                () -> original.call(instance),
                false
            );
            return false;
        }
        else {
            return original.call(instance);
        }
    }

    @WrapOperation(
        method = "handleKeybinds",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;continueAttack(Z)V"
        )
    )
    private void wrapContinueAttack(Minecraft instance, boolean leftClick, Operation<Void> original) {
        if (BlockManipulationClient.isPointingToPortal()) {
            BlockManipulationClient.withSwitchedContext(
                () -> {
                    original.call(instance, leftClick);
                    return null;
                },
                false
            );
        }
        else {
            original.call(instance, leftClick);
        }
    }

    @WrapOperation(
        method = "handleKeybinds",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;startUseItem()V"
        )
    )
    private void wrapStartUseItem(Minecraft instance, Operation<Void> original) {
        if (BlockManipulationClient.isPointingToPortal()) {
            BlockManipulationClient.withSwitchedContext(
                () -> {
                    original.call(instance);
                    return null;
                },
                true
            );

        }
        else {
            original.call(instance);
        }
    }

    @WrapOperation(
        method = "handleKeybinds",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;pickBlockOrEntity()V" // 26.2: pickBlock() -> pickBlockOrEntity()
        )
    )
    private void wrapPickBlock(Minecraft instance, Operation<Void> original) {
        if (BlockManipulationClient.isPointingToPortal()) {
            // ★ 26.2 INVERSION (user order 2026-08-03): pick resolution moved SERVER-side —
            // ServerboundPickItemFromBlockPacket is handled against player.level(), so the old
            // level/hitResult field swap here sent the remote pos into the WRONG dimension and the
            // pick silently failed. Skip vanilla entirely and RPC the dimension-tagged pick to
            // BlockManipulationServer, which resolves it in the DEST level under the portal-aware
            // reach gate. (The old swap-based path is unreachable dead weight on 26.2 — removed
            // rather than kept as a trap. Ctrl reads the same way pickBlockOrEntity does.)
            if (BlockManipulationClient.remoteHitResult
                    instanceof net.minecraft.world.phys.BlockHitResult bhr
                && bhr.getType() != HitResult.Type.MISS) {
                qouteall.q_misc_util.api.McRemoteProcedureCall.tellServerToInvoke(
                    "qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer.RemoteCallables.processPickItemFromBlock",
                    BlockManipulationClient.remotePointedDim,
                    bhr.getBlockPos(),
                    ((Minecraft) (Object) this).hasControlDown()
                );
            }
        }
        else {
            original.call(instance);
        }
    }
}
