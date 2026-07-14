package qouteall.imm_ptl.core.mixin.common;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class MixinLivingEntity {

    @Inject(method = "Lnet/minecraft/world/entity/LivingEntity;tick()V", at = @At("RETURN"))
    private void onTickEnded(CallbackInfo ci) {
        LivingEntity this_ = (LivingEntity) (Object) this;
        if (this_.getLastHurtByMob() != null) {
            if (this_.getLastHurtByMob().level() != this_.level()) {
                this_.setLastHurtByMob(null);
            }
        }
        if (this_.getLastHurtMob() != null) {
            if (this_.getLastHurtMob().level() != this_.level()) {
                // S10-C mechanical translation (api-map/mixin-common.md §1 MixinLivingEntity):
                // the 1-arg LivingEntity.setLastHurtByPlayer(Player) is GONE in 26.2; the overloads
                // are (Player,int) / (UUID,int) (LivingEntity.java:632/:636). The faithful "clear"
                // is (Player) null with timeToRemember 0 — matching vanilla's own clear idiom
                // (lastHurtByPlayer=null; lastHurtByPlayerMemoryTime=0, LivingEntity.java:1370-1371,
                // via the private setter :640-643; EntityReference.of((Player) null)==null). IP's
                // asymmetry (test getLastHurtMob, clear setLastHurtByPlayer) is PRESERVED verbatim.
                this_.setLastHurtByPlayer((Player) null, 0);
            }
        }
    }
}
