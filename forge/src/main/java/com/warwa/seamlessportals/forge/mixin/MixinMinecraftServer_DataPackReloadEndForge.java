package com.warwa.seamlessportals.forge.mixin;

import com.warwa.seamlessportals.forge.platform.ForgePlatform;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/**
 * 26.3 FORGE — the "a data-pack reload completed" signal behind {@code Platform.onServerDataPackReloadEnd} (consumer:
 * {@code CustomPortalGenManager}, which re-reads the custom-portal-generation entries after {@code /reload}).
 *
 * <p>Fabric has {@code ServerLifecycleEvents.END_DATA_PACK_RELOAD}, NeoForge {@code TagsUpdatedEvent.ServerDataLoad}.
 * MinecraftForge 66 has neither: its one {@code TagsUpdatedEvent} is posted from the CLIENT only
 * ({@code ForgeEventFactory.onTagsUpdated} is referenced by {@code ClientPacketListener} and nothing else). The first
 * Forge binding listened for {@code OnDatapackSyncEvent} with a NULL player — what {@code PlayerList.reloadResources()}
 * happens to post at the end of {@code /reload} — which works but leans on a Forge-internal convention (user decision
 * 2026-09-20: replace it).
 *
 * <p>This is Fabric API's own implementation, shape for shape (javap, fabric-lifecycle-events-v1
 * {@code MinecraftServerMixin.endResourceReload}: {@code @Inject(method = "reloadResources", at = @At("TAIL"))} →
 * {@code cir.getReturnValue().handleAsync(fn, (Executor) this)}): the callback runs ON THE SERVER THREAD once the
 * reload future settles, on success AND on failure, exactly as {@code END_DATA_PACK_RELOAD} does, and never at boot
 * (boot is {@code Platform.onServerStarted}'s job on every loader). Target verified in forge-26.3-66.0.2.jar:
 * {@code public CompletableFuture<Void> MinecraftServer.reloadResources(Collection<String>)}, one return.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer_DataPackReloadEndForge {

    @Inject(method = "reloadResources", at = @At("TAIL"), require = 1, allow = 1)
    private void seamlessportals$endResourceReload(
        Collection<String> selectedIds, CallbackInfoReturnable<CompletableFuture<Void>> cir
    ) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        cir.getReturnValue().handleAsync((value, throwable) -> {
            ForgePlatform.fireServerDataPackReloadEnd(server);
            return value;
        }, server);
    }
}
