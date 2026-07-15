package qouteall.imm_ptl.core.mixin.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.teleportation.CrossPortalSound;

/**
 * S12-B port disposition: PORTS-CLEAN target, minor {@code @IPVanillaCopy} renames (mixin-client.md §9).
 * Reroutes far/remote-world sounds through {@code CrossPortalSound} (sound played at the portal-transformed
 * position); mutes remote-world sounds no portal transfers.
 *
 * <p>26.2: {@code ClientLevel.playSound(double,double,double,SoundEvent,SoundSource,float,float,boolean,
 * long)} survives exactly ({@code 26.2:ClientLevel.java:710-719}) — the single funnel for positioned sounds
 * (now {@code private}, irrelevant to a HEAD injection). The {@code @IPVanillaCopy portal_playSound}
 * distance-delay helper is re-verified against the 26.2 body: the distance source is
 * {@code gameRenderer.mainCamera().position()} — IP's {@code getMainCamera()}/{@code getPosition()} renamed
 * to {@code mainCamera()} ({@code 26.2:GameRenderer.java:657}) / {@code position()}
 * ({@code 26.2:Camera.java:359}). Held/unregistered until S13.
 */
@Mixin(ClientLevel.class)
public class MixinClientLevel_Sound {

    @Shadow
    @Final
    private Minecraft minecraft;

    @IPVanillaCopy
    @Inject(
        method = "playSound",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onPlaySound(
        double x,
        double y,
        double z,
        SoundEvent soundEvent,
        SoundSource soundSource,
        float volume,
        float pitch,
        boolean distanceDelay,
        long seed,
        CallbackInfo ci
    ) {
        if (!IPGlobal.enableCrossPortalSound) {
            return;
        }

        ClientLevel this_ = (ClientLevel) (Object) this;
        Vec3 soundPos = new Vec3(x, y, z);

        if (!portal_isPosNearPlayer(soundPos)) {
            SimpleSoundInstance crossPortalSound = CrossPortalSound.createCrossPortalSound(
                this_, soundEvent, soundSource, soundPos, volume, pitch, seed
            );
            if (crossPortalSound != null) {
                portal_playSound(crossPortalSound, distanceDelay);
                ci.cancel();
            }
            else if (!CrossPortalSound.isPlayerWorld(this_)) {
                // do not play remote sound when no portal can transfer the sound
                ci.cancel();
            }
        }
    }

    private void portal_playSound(SimpleSoundInstance sound, boolean distanceDelay) {
        // 26.2: GameRenderer.getMainCamera() -> mainCamera(); Camera.getPosition() -> position().
        double d = minecraft.gameRenderer.mainCamera().position().distanceToSqr(sound.getX(), sound.getY(), sound.getZ());
        if (distanceDelay && d > 100.0D) {
            double e = Math.sqrt(d) / 40.0D;
            minecraft.getSoundManager().playDelayed(sound, (int) (e * 20.0D));
        }
        else {
            minecraft.getSoundManager().play(sound);
        }
    }

    private boolean portal_isPosNearPlayer(Vec3 pos) {
        ClientLevel this_ = (ClientLevel) (Object) this;

        LocalPlayer player = minecraft.player;

        if (player == null) {
            return false;
        }

        if (this_ != player.level()) {
            return false;
        }

        return pos.distanceToSqr(player.position()) < 64 * 64;
    }

}
