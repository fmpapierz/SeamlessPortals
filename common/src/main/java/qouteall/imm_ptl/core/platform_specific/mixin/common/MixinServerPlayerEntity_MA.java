// Ported from IP imm_ptl_fabric.mixins.json (platform_specific.mixin.common.MixinServerPlayerEntity_MA),
// S13-B P-2 fix (see MixinPlayerManager_MA header for the census-gap context). Registered Fabric-only via
// seamlessportals-ip-fabric.mixins.json, gated flag-ON by SeamlessMixinConfigPlugin. Wires the
// custom-portal-gen conventional-dimension-change hooks (onBefore/onAfterConventionalDimensionChange —
// previously ZERO callers) so vanilla dimension travel triggers datapack/convention portal generation
// again, plus the cross-dim tracker detach.
//
// 26.2 RETARGETS (zero IP-logic change):
//  - changeDimension(DimensionTransition) HEAD  →  teleport(TeleportTransition) HEAD
//    (api-map/portal-generation.md:46 row 17; mc262 ServerPlayer.java:1093 returns @Nullable
//    ServerPlayer). 1.21.3's changeDimension was inherently a dimension change; 26.2's unified
//    teleport(TeleportTransition) also serves same-dim teleports, so the cross-dim guard the api-map
//    mandates ("transition's target level vs current") is added to preserve IP's fire-on-dim-change
//    semantics. CallbackInfoReturnable<Entity> → <ServerPlayer> (covariant override; the full
//    descriptor targets the real method, not the synthetic Entity-returning bridge).
//  - teleportTo(ServerLevel, x,y,z, yaw, pitch) HEAD  →  teleportTo(ServerLevel, x,y,z,
//    Set<Relative>, newYRot, newXRot, resetCamera) HEAD (api-map/platform-compat-peripheral.md:42;
//    mc262 ServerPlayer.java:1689 returns boolean). Add the Set<Relative>+resetCamera params;
//    CallbackInfo → CallbackInfoReturnable<Boolean>. The this.level() != targetWorld cross-dim guard
//    is unchanged.
//  - onBeforeDimensionTravel reads player.server (26.2 reduces it to private; access-widened
//    accessible in seamlessportals.accesswidener:30, same widen CustomPortalGenManager relies on).
package qouteall.imm_ptl.core.platform_specific.mixin.common;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPPerServerInfo;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.mc_utils.ServerTaskList;
import qouteall.imm_ptl.core.portal.custom_portal_gen.CustomPortalGenManager;

import java.util.Set;

@Mixin(ServerPlayer.class)
public class MixinServerPlayerEntity_MA {
    @Inject(
        method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
        at = @At("HEAD")
    )
    private void onChangeDimensionByVanilla(
        TeleportTransition transition, CallbackInfoReturnable<ServerPlayer> cir
    ) {
        ServerPlayer this_ = (ServerPlayer) (Object) this;
        if (transition.newLevel() != this_.level()) {
            onBeforeDimensionTravel(this_);
        }
    }

    // update chunk visibility data
    @Inject(
        method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z",
        at = @At("HEAD")
    )
    private void onTeleported(
        ServerLevel targetWorld,
        double x,
        double y,
        double z,
        Set<Relative> relatives,
        float newYRot,
        float newXRot,
        boolean resetCamera,
        CallbackInfoReturnable<Boolean> cir
    ) {
        ServerPlayer this_ = (ServerPlayer) (Object) this;

        if (this_.level() != targetWorld) {
            onBeforeDimensionTravel(this_);
        }
    }

    private static void onBeforeDimensionTravel(ServerPlayer player) {
        CustomPortalGenManager customPortalGenManager =
            IPPerServerInfo.of(player.server).customPortalGenManager;

        if (customPortalGenManager != null) {
            customPortalGenManager.onBeforeConventionalDimensionChange(player);
            ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(player);

            ServerTaskList.of(player.server).addTask(() -> {
                customPortalGenManager.onAfterConventionalDimensionChange(player);
                return true;
            });
        }
    }
}
