package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamMirror;
import com.warwa.seamlessportals.passthrough.SeamRegistry;
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

    /**
     * RS PASSTHROUGH (a) step 6 — THE MIRROR DRIVER.
     *
     * <p>Shares this site with the block-era hook below, and for the same reason already documented
     * at {@code :36-72}: {@code LevelChunk.setBlockState} sits UPSTREAM of every filter in
     * {@code Level.markAndNotifyBlock}, so it observes changes that never reach
     * {@code sendBlockUpdated} — which is exactly how cross-dimension fluid flow was fixed. A seam
     * must mirror every write, including the ~95% vanilla filters out.
     *
     * <p>Unlike the hook below this one is FLAG-ON: the seam registry only exists under entity portals.
     */
    @Inject(
        method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN"),
        require = 0
    )
    private void seamlessportals$driveSeamMirror(
            BlockPos pos, BlockState newState, int flags,
            CallbackInfoReturnable<BlockState> cir) {
        if (!SeamlessPortalsConfig.isEntityPortals()) return;
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_MIRROR) return;
        if (!(this.level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
        // Fast path first: one field read plus a contains() on a usually-empty set. This runs for
        // EVERY block change in the game, so anything heavier here is a global tax.
        BlockState oldState = cir.getReturnValue();
        if (oldState == null || oldState == newState) return;
        net.minecraft.server.MinecraftServer server = serverLevel.getServer();
        if (server == null || !server.isSameThread()) return;

        // APERTURE mirroring — gated on the section index, which is the hot-path fold.
        //
        // MIRROR THE LIVE STATE, NOT THE newState PARAMETER. LevelChunk.setBlockState calls
        // state.onPlace(...) INSIDE its own body (REF LevelChunk.java:326-327), and for a rail that
        // runs BaseRailBlock.onPlace -> updateState -> updateDir -> RailState.place, which at
        // REF RailState.java:333 issues a NESTED level.setBlock(pos, resolvedShape, 3) to the SAME
        // position. So the inner invocation's inject fires with the RESOLVED shape and mirrors it
        // correctly, then the stack unwinds and THIS inject fires with its own parameter — the
        // PRE-RESOLUTION shape — and overwrites the far side with it. Last write wins, and it is
        // wrong. Reading the live state instead makes both invocations agree on the final state, so
        // the redundant outer write is harmless.
        //
        // Found by the (b) design panel as a PRE-EXISTING (a) defect. It hid because the mirror gate
        // asserted is(Blocks.RAIL) — the BLOCK — and never the SHAPE.
        if (SeamRegistry.sectionHasSeam(serverLevel, pos)) {
            SeamMirror.onSeamCellChanged(serverLevel, pos, serverLevel.getBlockState(pos));
        }

        // FRAME mirroring — deliberately NOT behind sectionHasSeam. That index is derived from LIVE
        // portals, and the case frame mirroring exists for is exactly the one where no portal is
        // alive: both were torn down when the frame broke and the player is now repairing it. Gated
        // instead on the persisted frame-link store, which is empty in any world that has never had
        // a portal and is checked with one map read.
        if (com.warwa.seamlessportals.passthrough.SeamFrameLink.hasAny(serverLevel)) {
            SeamMirror.onFrameCellChanged(serverLevel, pos, newState);
        }
    }

    @Inject(
        method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN"),
        require = 0
    )
    private void seamlessportals$mirrorChunkBlockStateChange(
            BlockPos pos, BlockState newState, int flags,
            CallbackInfoReturnable<BlockState> cir) {
        // D3 EXCLUSIVITY GATE (A3 — block-update observation feeding RemoteBlockUpdater's send path).
        // Flag ON → IP tracking's native block sync replaces it. Flag OFF (default) → unchanged.
        if (SeamlessPortalsConfig.isEntityPortals()) return;
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

        // Speculative pre-warm: an OBSIDIAN block landing (placement or lava+water) may have
        // just completed a valid UNLIT portal frame — probe for one and pre-warm its expected
        // destination. Rare event (obsidian placements), cheap probe, config-gated inside.
        if (newState.is(net.minecraft.world.level.block.Blocks.OBSIDIAN)) {
            com.warwa.seamlessportals.chunk.SpeculativePrewarm.onObsidianPlaced(sl, pos.immutable());
        }

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

                // Coalesce per-tick instead of sending one packet per block: buffer the
                // update (deduped by position) and flush ONE batch per player per dim at
                // server tick end (vanilla-style). Kills the lava/fluid packet + rebuild flood.
                com.warwa.seamlessportals.chunk.BlockUpdateMirrorBuffer.add(
                    player, thisDim, packedPos, stateId);
                break; // one matching link is enough — don't double-send
            }
        }
    }
}
