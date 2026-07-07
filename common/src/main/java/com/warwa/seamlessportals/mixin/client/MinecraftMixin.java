package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.StencilPortalRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "close", at = @At("HEAD"))
    private void seamlessportals$onClose(CallbackInfo ci) {
        StencilPortalRenderer.cleanup();
    }

    /**
     * Session teardown for the paths that NEVER call {@code ClientLevel.disconnect}:
     * kick / connection loss / server stop ({@code Minecraft.disconnect(Screen,boolean)}
     * :2146) and server transfer/reconfiguration ({@code clearClientLevel} :2177).
     * Vanilla only calls {@code level.disconnect()} on CLIENT-initiated quits
     * (disconnectFromWorld / exitWorldAndClose / abortResourcePackRecovery), so the
     * ClientLevelMixin disconnect hook alone leaves a kicked client's next join
     * running on the previous session's dead-connection cached levels — the exact
     * stale-session bug the cleanup() wiring fixes. Both vanilla teardown paths
     * funnel through the two-arg {@code updateLevelInEngines} with {@code null};
     * every call below is idempotent, so double-firing after a normal quit
     * (level.disconnect() already ran the same chain) is harmless.
     */
    @Inject(
        method = "updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V",
        at = @At("HEAD")
    )
    private void seamlessportals$onLevelTeardown(
            net.minecraft.client.multiplayer.ClientLevel level, boolean stopSound, CallbackInfo ci) {
        if (level != null) return;
        com.warwa.seamlessportals.chunk.RedirectedPacketApplier.clearPending();
        com.warwa.seamlessportals.chunk.RemoteChunkManager.clearAll();
        com.warwa.seamlessportals.portal.PortalManager.resetClient();
        com.warwa.seamlessportals.client.PortalWorldManager.cleanup();
        com.warwa.seamlessportals.client.SeamlessClientTeleport.onDisconnect();
    }
}
