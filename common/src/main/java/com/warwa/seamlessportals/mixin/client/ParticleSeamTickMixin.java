package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ★ PARTICLES RESPECT THE CUT — LIFETIME SIDE (round 30: "some smoke particles bleed to the
 * other side ... it looks like only the particles that stick past the seam into dest side b
 * bleed"). Spawn filtering cannot govern drift: torch smoke wanders, and any particle whose path
 * crosses the plane surfaced in the empty half for the rest of its life. A particle entering the
 * empty half of a cut cell dies that tick. Cost: one containing-cell occupancy probe per particle
 * per tick, short-circuited by the model lever and an O(1) map miss for the overwhelmingly
 * common non-seam cell.
 */
@Mixin(Particle.class)
public abstract class ParticleSeamTickMixin {

    @Shadow
    @Final
    protected ClientLevel level;

    @Shadow
    protected double x;

    @Shadow
    protected double y;

    @Shadow
    protected double z;

    @Shadow
    public abstract boolean isAlive();

    @Shadow
    public abstract void remove();

    @Inject(method = "tick", at = @At("RETURN"))
    private void seamlessportals$dieInTheEmptyHalf(CallbackInfo ci) {
        if (isAlive() && SeamFractional.positionInEmptyHalf(level, x, y, z)) {
            remove();
        }
    }
}
