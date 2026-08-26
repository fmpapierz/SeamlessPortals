package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.client.SeamlessRespawnTransitionAccess;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NF-PARITY W3/B2 (2026-08-25): the vanilla/Fabric-shape half of the seamless-transition
 * loading-screen skip that used to live in {@link HandleRespawnMixin} (see the pointer
 * comment there for the full split rationale).
 *
 * <p>Vanilla 26.2's {@code handleRespawn} calls the 3-arg
 * {@code startWaitingForNewLevel(LocalPlayer, ClientLevel, LevelLoadingScreen.Reason)}
 * (mc262 ClientPacketListener.java:1294/:1624). {@code SeamlessMixinConfigPlugin} applies
 * this variant only OFF NeoForge ({@code NON_NEOFORGE_MIXINS}); the explicit descriptor
 * keeps the selector unambiguous, and the config's {@code defaultRequire = 1} makes a
 * zero-match a hard boot failure.
 *
 * <p>Behavior (verbatim from the original): during a seamless transition we cancel the
 * LevelLoadTracker/LevelLoadingScreen entirely and send the player-loaded packet
 * immediately — the destination chunks are already resident.
 */
@Mixin(ClientPacketListener.class)
public abstract class HandleRespawnLoadScreenShapeVanilla {

    @Shadow private boolean clientLoaded;

    @Inject(
        method = "startWaitingForNewLevel(Lnet/minecraft/client/player/LocalPlayer;"
            + "Lnet/minecraft/client/multiplayer/ClientLevel;"
            + "Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void seamlessportals$skipLoadingScreen(LocalPlayer player, ClientLevel level,
            LevelLoadingScreen.Reason reason, CallbackInfo ci) {
        if (!((SeamlessRespawnTransitionAccess) this).seamlessportals$isSeamlessTransition()) {
            return;
        }

        // Send player-loaded notification to server immediately
        // (vanilla would wait until chunks compile, but we already have them)
        ((ClientPacketListener) (Object) this).send(new ServerboundPlayerLoadedPacket());
        this.clientLoaded = true;

        SeamlessPortalsConstants.rlog(
            "[SEAMLESS] Skipped loading screen for {} — sent player-loaded immediately",
            level.dimension().identifier());

        ci.cancel();
    }
}
