package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * S12-B (render client-mixin half) — <b>R13k</b>: IP {@code MixinCamera}
 * ({@code IP:mixin/client/render/MixinCamera.java}), 26.2-RETARGETED for the pull-model camera
 * (API_RISKS.md R13k; mixin-client.md §7 NEEDS-RETARGET; render-sub C2).
 *
 * <p><b>Camera became pull-model.</b> 1.21.3's {@code Camera.setup(BlockGetter,Entity,ZZF)} is GONE;
 * positioning is now {@code update(DeltaTracker)} ({@code 26.2:Camera.java:93-112}), which calls
 * {@code alignWithEntity(float)} ({@code :249}) and THEN snapshots {@code this.position} into the cull
 * frustum via {@code prepareCullFrustum} ({@code :106}). IP's {@code adjustCameraPos} therefore injects at
 * {@code update} <b>after the {@code alignWithEntity} INVOKE and before {@code prepareCullFrustum}</b>
 * (moving the camera after the frustum snapshot would render the portal view with a stale frustum).
 *
 * <p><b>R13k initialized-flag gate (documented, handled by the mod's existing seam).</b> A mod-built /
 * mod-repositioned Camera must have the private {@code initialized} flag set or
 * {@code getFluidInCamera()} silently returns {@code NONE} ({@code 26.2:Camera.java:437-440}). The mod's
 * live {@code CameraInvokerMixin} already exposes/sets that flag; the S13 driver wiring flips it when it
 * repositions the portal camera. This mixin does not duplicate it.
 *
 * <p><b>R13k view-rotation caveat (handled in {@link MixinGameRenderer}, NOT here).</b> The portal
 * view-rotation post-processing must be applied to {@code cameraState.viewRotationMatrix} AFTER extract —
 * NEVER by wrapping the dirty-flag-cached {@code Camera.getViewRotationMatrix} ({@code Camera.java:385-389}).
 * See {@code MixinGameRenderer}'s TransformationManager re-anchor.
 *
 * <p><b>Field retype.</b> IP shadowed {@code BlockGetter level}; 26.2 {@code Camera.level} is
 * {@code net.minecraft.world.level.Level} ({@code Camera.java:50}) — retyped here ({@code ClientLevel}
 * assigned into it in {@code ip_resetState} is still a widening to {@code Level}). Vanilla also exposes
 * {@code setLevel(ClientLevel)} ({@code :491}); IP's verbatim field-assign is kept for fidelity.
 * Held/UNREGISTERED until S13.
 */
@Mixin(Camera.class)
public abstract class MixinCamera implements IECamera {

    @Shadow
    private Vec3 position;
    @Shadow
    private Level level;
    @Shadow
    private Entity entity;
    @Shadow
    private float eyeHeight;
    @Shadow
    private float eyeHeightOld;

    @Shadow
    protected abstract void setPosition(Vec3 vec3d_1);

    // S13-C weave fix: dropped the orphaned `@Shadow public abstract Entity getEntity();`. 26.2 renamed
    // the getter to `Camera.entity()` (Camera.java:407); `getEntity()` no longer exists on Camera (nor is
    // it inherited — Camera extends Object), so the abstract @Shadow failed apply-time validation
    // ("@Shadow method getEntity ... NOT located in ... Camera"). The member was DEAD — nothing in the mod
    // consumes it and IECamera never declared it (the focused-entity field is reached via the @Shadow
    // `entity` field + portal_setFocusedEntity), so it is removed rather than re-anchored.

    // IP injected at setup(...) RETURN; 26.2 re-anchors onto update(DeltaTracker) right AFTER
    // alignWithEntity and BEFORE prepareCullFrustum snapshots this.position (mixin-client.md §7 ①).
    @Inject(
        method = "update",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V",
            shift = At.Shift.AFTER
        )
    )
    private void onUpdateFinished(DeltaTracker deltaTracker, CallbackInfo ci) {
        Camera this_ = (Camera) (Object) this;
        WorldRenderInfo.adjustCameraPos(this_);
    }

    @Inject(
        method = "Lnet/minecraft/client/Camera;getFluidInCamera()Lnet/minecraft/world/level/material/FogType;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void getSubmergedFluidState(CallbackInfoReturnable<FogType> cir) {
        if (PortalRendering.isRendering()) {
            cir.setReturnValue(FogType.NONE);
            cir.cancel();
        }
    }

    // to let the player be rendered when rendering portal
    @Inject(method = "Lnet/minecraft/client/Camera;isDetached()Z", at = @At("HEAD"), cancellable = true)
    private void onIsThirdPerson(CallbackInfoReturnable<Boolean> cir) {
        if (CrossPortalEntityRenderer.shouldRenderPlayerDefault()) {
            cir.setReturnValue(true);
        }
    }

    @Override
    public void ip_resetState(Vec3 pos, ClientLevel currWorld) {
        setPosition(pos);
        level = currWorld;
    }

    @Override
    public float ip_getCameraY() {
        return eyeHeight;
    }

    @Override
    public float ip_getLastCameraY() {
        return eyeHeightOld;
    }

    @Override
    public void ip_setCameraY(float cameraY_, float lastCameraY_) {
        eyeHeight = cameraY_;
        eyeHeightOld = lastCameraY_;
    }

    @Override
    public void portal_setPos(Vec3 pos) {
        setPosition(pos);
    }

    @Override
    public void portal_setFocusedEntity(Entity arg) {
        entity = arg;
    }
}
