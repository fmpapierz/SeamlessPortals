package qouteall.imm_ptl.peripheral.mixin.client.alternate_dimension;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.peripheral.alternate_dimension.AlternateDimensions;

// S19-D re-site of IP's MixinFogRenderer_A_CVB ("avoid alternate dimension dark when seeing
// from overworld"). IP @Redirect'd Camera.getPosition() inside the GONE FogRenderer.setupColor,
// clamping the camera y to max(32, y) for alt dims so the void-darkness fog term never kicks in
// below the floating island.
//
// 26.2-forced re-site (MixinDebugRenderer-re-site precedent style, S18): the 26.2 FogRenderer was
// rewritten. setupColor is gone; the void-darkness computation now lives in the private instance
// method FogRenderer.computeFogColor, where the SOLE camera-y-dependent fog term is
//   `(float) camera.position().y`  (FogRenderer.java:111, the darkness clamp)
//     darkness = clamp((voidDarknessOnsetRange + minY - camera.position().y)/voidDarknessOnsetRange, 0, 1)
// which then multiplies the fog color toward black when > 0. The base fog/sky COLOR is
// attribute-probe driven (AtmosphericFogEnvironment.getBaseColor reads FOG_COLOR/SKY_COLOR
// from camera.attributeProbe() — spatially sampled, so technically y-inclusive, but no
// sub-32 biome sets FOG_COLOR and IP's 1.21.3 redirect clamped the biome-sample position the
// same way — equivalent-or-closer; verify wf_c18735d7-449). Redirecting this one read
// reproduces IP's EFFECT (no black void fog); the darkness term is the only meaningful
// y-dependent path.
//
// Named 26.2 deltas vs IP: getPosition() -> position() (rename); the handler is now an INSTANCE
// method (computeFogColor is an instance method; IP's setupColor was static -> IP's handler was
// static). Gate + body are otherwise IP-1:1 (Minecraft.getInstance().level +
// AlternateDimensions.isAlternateDimension, exactly as IP). Calling camera.position() inside the
// handler is not recursive: @Redirect replaces only the call site in computeFogColor, not the
// Camera method itself.
@Mixin(FogRenderer.class)
public class MixinFogRenderer_A_CVB {
    @Redirect(
        method = "computeFogColor",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;position()Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 redirectCameraGetPos(Camera camera) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world != null && AlternateDimensions.isAlternateDimension(world)) {
            return new Vec3(
                camera.position().x,
                Math.max(32.0, camera.position().y),
                camera.position().z
            );
        }
        else {
            return camera.position();
        }
    }
}
