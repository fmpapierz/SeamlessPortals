// Ported from IP imm_ptl_fabric.mixins.json (platform_specific.mixin.common.MixinPlayerManager_MA),
// S13-B P-2 fix. The S00 "absorbed by the Part B seams (A.9)" disposition for the 4 platform_specific
// fabric mixins was never executed, leaving ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntity-
// Trackers with ZERO callers → the cross-dim ChunkMap detach never ran on relog/death-respawn flag-ON.
// The two _MA COMMON mixins are ported verbatim/retargeted and registered (Fabric-only) via
// seamlessportals-ip-fabric.mixins.json, gated flag-ON by SeamlessMixinConfigPlugin.
//
// 26.2: SAME injection points — api-map/platform-compat-peripheral.md:72 ("both injection points
// intact — the chunk/entity-tracker detach mixin ports as-is"): PlayerList.respawn(ServerPlayer,
// boolean, Entity.RemovalReason) (mc262 PlayerList.java:389) and PlayerList.remove(ServerPlayer)
// (:303). No signature change.
package qouteall.imm_ptl.core.platform_specific.mixin.common;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;

@Mixin(PlayerList.class)
public class MixinPlayerManager_MA {
    @Inject(
        method = "respawn",
        at = @At("HEAD")
    )
    private void onPlayerRespawn(
        ServerPlayer oldPlayer, boolean bl, Entity.RemovalReason removalReason,
        CallbackInfoReturnable<ServerPlayer> cir
    ) {
        ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(oldPlayer);
    }

    @Inject(
        method = "remove",
        at = @At("HEAD")
    )
    private void onPlayerDisconnect(ServerPlayer player, CallbackInfo ci) {
        ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(player);
    }
}
