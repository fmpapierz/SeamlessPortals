package qouteall.imm_ptl.peripheral.mixin.client.portal_wand;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.peripheral.wand.PortalWandItem;

/**
 * S19-A 1:1 port of IP:peripheral/mixin/client/portal_wand/MixinMinecraft_PortalWand.java —
 * the wand's LEFT-CLICK driver. The 26.2 target survives with the same call-site shape:
 * {@code startAttack} is still {@code private boolean} and its first hand read is still
 * {@code this.player.getItemInHand(InteractionHand.MAIN_HAND)} (26.2 Minecraft.java:1614,
 * 1640 — after the missTime/null-hit/handsBusy/spectator early-outs, before the attack
 * dispatch, exactly IP's injection point). Returning {@code false} cancels the attack the
 * same way at the 26.2 caller ({@code instantAttack |= this.startAttack()}, :1940).
 *
 * <p>Named fidelity delta (verify wf_7e348eaa-89b, bytecode-verified): 26.2 added a
 * spectator early-return BEFORE this injection point (:1631-1638), so spectators bypass the
 * wand hook on 26.2 where 1.21.3 fired it — favors vanilla (spectators cannot use the wand;
 * the server-side checkPermission gates regardless); no fix warranted.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft_PortalWand {
    @Shadow
    @Nullable
    public LocalPlayer player;

    @Inject(
        method = "startAttack",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"
        ),
        cancellable = true
    )
    private void onStartingAttack(CallbackInfoReturnable<Boolean> cir) {
        assert player != null;
        ItemStack itemStack = player.getMainHandItem();
        if (itemStack.getItem() instanceof PortalWandItem) {
            PortalWandItem.onClientLeftClick(player, itemStack);
            cir.setReturnValue(false);
        }
    }
}
