package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NF-PARITY W3/B2 (2026-08-25): the NeoForge-shape half of the seamless-transition
 * loading-screen skip — see {@link HandleRespawnLoadScreenShapeVanilla} for the split
 * rationale and behavior.
 *
 * <p>NeoForge's {@code handleRespawn} calls the patched 5-arg
 * {@code startWaitingForNewLevel(LocalPlayer, ClientLevel, Reason, @Nullable
 * ResourceKey<Level> toDimension, @Nullable ResourceKey<Level> fromDimension)}
 * (NF ClientPacketListener.java:1298/:1634); the 3-arg survives only as a delegating stub
 * that {@code handleRespawn} never calls. Cancelling at the 5-arg HEAD also covers any
 * 3-arg caller (the stub delegates here). {@code SeamlessMixinConfigPlugin} applies this
 * variant only ON NeoForge ({@code NEOFORGE_ONLY_MIXINS}).
 */
@Mixin(ClientPacketListener.class)
public abstract class HandleRespawnLoadScreenShapeNeoForge {

    @Shadow private boolean clientLoaded;

    @Inject(
        method = "startWaitingForNewLevel(Lnet/minecraft/client/player/LocalPlayer;"
            + "Lnet/minecraft/client/multiplayer/ClientLevel;"
            + "Lnet/minecraft/client/gui/screens/LevelLoadingScreen$Reason;"
            + "Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/resources/ResourceKey;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void seamlessportals$skipLoadingScreen(LocalPlayer player, ClientLevel level,
            LevelLoadingScreen.Reason reason,
            @Nullable ResourceKey<Level> toDimension, @Nullable ResourceKey<Level> fromDimension,
            CallbackInfo ci) {
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
