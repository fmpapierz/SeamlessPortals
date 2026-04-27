package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Mirror block-state changes to portal watchers — but hook
 * {@link LevelChunk#setBlockState} TAIL instead of
 * {@code ServerLevel.sendBlockUpdated} TAIL.
 *
 * <p>Why this exists (and why the old {@code ServerLevelBlockUpdateMixin}
 * mirror path was insufficient):
 *
 * <p>Vanilla {@code Level.setBlock} → {@code chunk.setBlockState} →
 * {@code markAndNotifyBlock} → {@code sendBlockUpdated}. The chain has
 * MULTIPLE filters that can drop the call before reaching
 * {@code sendBlockUpdated}:
 * <ul>
 *   <li>{@code chunk.setBlockState} returns null when the new state
 *       equals the old (no-op write) — {@code Level.setBlock} then
 *       returns false WITHOUT calling {@code markAndNotifyBlock}.</li>
 *   <li>{@code markAndNotifyBlock} (line ~260 of {@code Level.java} in
 *       26.1.2) wraps the {@code sendBlockUpdated} call in
 *       {@code if (newState == blockState)} — if the actual stored
 *       state diverges from the requested state for any reason, the
 *       update isn't broadcast.</li>
 *   <li>NeoForge's {@code captureBlockSnapshots} mechanism, when
 *       active, suppresses {@code markAndNotifyBlock} entirely.</li>
 * </ul>
 *
 * <p>Empirically (FLUID-DIAG SETBLOCK / FLUID-DIAG SERVER counts in
 * test 19:55–19:57): the server fires {@code Level.setBlock} for fluid
 * spread (Air → Water) tens of thousands of times, but our
 * {@code sendBlockUpdated} TAIL hook only saw 109 fluid events total.
 * Roughly 99 % of fluid spread setBlock calls do not fire
 * {@code sendBlockUpdated}. That is the freeze the user sees in the
 * portal view: water spreads server-side, but no mirror packet is
 * issued, so the cached client {@link net.minecraft.client.multiplayer.ClientLevel}
 * for the destination dim never receives the updated states.
 *
 * <p>This mixin hooks {@link LevelChunk#setBlockState} TAIL, where the
 * call site is upstream of every filter listed above. It fires for
 * every successful state change (when the returned old state is
 * non-null and distinct from the new state) — exactly the events the
 * mirror needs to forward to portal watchers.
 *
 * <p>Thread-safety: skipped if the call isn't on the server main
 * thread. World generation runs on worker threads via {@code ProtoChunk}
 * (a different class), so it never reaches this mixin.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkSetBlockStateMixin {

    @Shadow @Final
    Level level;

    @Inject(
        method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN"),
        require = 0
    )
    private void seamlessportals$mirrorChunkBlockStateChange(
            BlockPos pos, BlockState newState, int flags,
            CallbackInfoReturnable<BlockState> cir) {
        BlockState oldState = cir.getReturnValue();
        // Filter 1: setBlockState returns null on no-op write. Nothing changed.
        if (oldState == null) return;
        // Filter 2: same exact instance (rare, but caught by vanilla too).
        if (oldState == newState) return;
        // Filter 3: must be a server-side level — client chunks also use
        // LevelChunk and would otherwise spuriously fire this mixin.
        if (!(this.level instanceof ServerLevel sl)) return;
        // Filter 4: must be on server main thread. Off-thread setBlockState
        // (e.g., chunk loading from disk) is not a runtime player-visible
        // change and shouldn't trigger mirror packets.
        MinecraftServer server = sl.getServer();
        if (server == null || !server.isSameThread()) return;

        // Mirror to all players in OTHER dims whose portal links into THIS dim
        // and whose distance to the portal-destination center covers `pos`.
        ResourceKey<Level> thisDim = sl.dimension();
        PortalManager manager = PortalManager.getServerInstance();
        int renderDistChunks = SeamlessPortalsConfig.get().getPortalRenderDistance();
        double rangeBlocks = renderDistChunks * 16.0;
        double rangeSq = rangeBlocks * rangeBlocks;

        int stateId = Block.getId(newState);
        long packedPos = pos.asLong();
        String thisDimId = thisDim.identifier().toString();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ResourceKey<Level> playerDim = player.level().dimension();
            // Same dim → vanilla notifies via ClientboundBlockUpdatePacket
            // through the chunk-tracker → ChunkHolder path. Don't double-send.
            if (playerDim.equals(thisDim)) continue;

            List<PortalLink> links = manager.getLinksInRange(
                playerDim, player.blockPosition(), rangeBlocks);
            if (links.isEmpty()) continue;

            for (PortalLink link : links) {
                PortalInfo destPortal = link.getDestination();
                if (!destPortal.getDimension().equals(thisDim)) continue;

                Vec3 destCenter = destPortal.getCenter();
                double dx = pos.getX() + 0.5 - destCenter.x;
                double dz = pos.getZ() + 0.5 - destCenter.z;
                if (dx * dx + dz * dz > rangeSq) continue;

                PlatformHelper.getInstance().sendToClient(player,
                    new ModPayloads.RemoteBlockUpdatePayload(
                        thisDimId, packedPos, stateId));
                break; // one matching link is enough — don't double-send
            }
        }
    }
}
