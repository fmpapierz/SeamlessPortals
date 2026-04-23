package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import com.warwa.seamlessportals.portal.PortalDetector;
import com.warwa.seamlessportals.portal.PortalInfo;
import com.warwa.seamlessportals.portal.PortalLink;
import com.warwa.seamlessportals.portal.PortalManager;
import com.warwa.seamlessportals.portal.PortalType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {

    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$onEntityInside(BlockState state, Level level, BlockPos pos,
                                                 Entity entity, InsideBlockEffectApplier effectApplier,
                                                 boolean isPrecise, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel) {
            PortalDetector.onNetherPortalFormed(level, pos, serverLevel.getServer());
        }
        // Also register on client side for rendering
        if (level.isClientSide()) {
            PortalDetector.onNetherPortalDetectedClient(level, pos);
        }

        // Suppress vanilla portal behavior (purple overlay, 4-second timer,
        // loading-screen teleport) when seamless teleportation is enabled.
        // Our EntityMixin.move() hook handles instant teleportation instead.
        if (SeamlessPortalsConfig.shouldSeamlessTeleport(PortalType.NETHER)) {
            ci.cancel();
        }
    }

    /**
     * When vanilla's {@code updateShape} decides this nether_portal block
     * should become air (because a surrounding obsidian got broken and the
     * frame no longer forms a valid portal), tell the {@link PortalManager}
     * and every connected client to forget the portal + its linked
     * destination. Hooking directly here is more reliable than catching
     * the subsequent block change at {@code ServerLevel.sendBlockUpdated}
     * — which proved to not always fire for this cascading path.
     */
    @Inject(
        method = "updateShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/world/level/ScheduledTickAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN"),
        require = 0
    )
    private void seamlessportals$onPortalDestroyed(
            BlockState state, LevelReader level, ScheduledTickAccess ticks,
            BlockPos pos, Direction dir, BlockPos neighbourPos, BlockState neighbourState,
            RandomSource random, CallbackInfoReturnable<BlockState> cir) {
        BlockState returned = cir.getReturnValue();
        if (returned == null || !returned.is(Blocks.AIR)) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        MinecraftServer server = serverLevel.getServer();
        if (server == null) return;
        ResourceKey<Level> thisDim = serverLevel.dimension();

        PortalManager manager = PortalManager.getServerInstance();
        PortalInfo portal = manager.getTracker(thisDim).getPortalAt(pos).orElse(null);
        if (portal == null) {
            for (PortalInfo p : manager.getTracker(thisDim).getAllPortals()) {
                if (p.containsPoint(net.minecraft.world.phys.Vec3.atCenterOf(pos))) {
                    portal = p;
                    break;
                }
            }
        }
        if (portal == null) return;

        PortalInfo linkedDest = null;
        java.util.Optional<PortalLink> linkOpt =
            manager.getLinkAt(thisDim, portal.getOrigin());
        if (linkOpt.isPresent()) {
            linkedDest = linkOpt.get().getDestination();
        }

        BlockPos originToDrop = portal.getOrigin();
        ResourceKey<Level> dropDim = portal.getDimension();
        manager.unregisterPortal(portal);
        String dropDimId = dropDim.identifier().toString();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PlatformHelper.getInstance().sendToClient(p,
                new ModPayloads.PortalUnregisterPayload(dropDimId, originToDrop));
        }

        if (linkedDest != null && linkedDest.getDimension() != null) {
            BlockPos linkedOrigin = linkedDest.getOrigin();
            ResourceKey<Level> linkedDim = linkedDest.getDimension();
            manager.unregisterPortal(linkedDest);
            String linkedDimId = linkedDim.identifier().toString();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                PlatformHelper.getInstance().sendToClient(p,
                    new ModPayloads.PortalUnregisterPayload(linkedDimId, linkedOrigin));
            }
        }

        SeamlessPortalsConstants.LOGGER.info(
            "[SEAMLESS] Portal destroyed (via updateShape→AIR) at {} in {} (linked dest {})",
            pos.toShortString(), thisDim.identifier(),
            linkedDest == null ? "none" : linkedDest.getOrigin().toShortString());
    }

    /**
     * Suppress the ambient purple particle emitter on nether_portal blocks.
     * Vanilla's {@code animateTick} spawns {@code PortalParticle} around
     * each portal block every client tick; users reported the particles
     * cluttering the portal view. Full no-op — purely cosmetic and
     * visually redundant with our rendered portal surface.
     */
    @Inject(
        method = "animateTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void seamlessportals$cancelAmbientParticles(
            BlockState state, Level level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        ci.cancel();
    }

    /**
     * Make nether_portal blocks unbreakable by the player. Vanilla lets
     * you punch out the purple swirl blocks in survival, which then
     * cascades through our {@code updateShape → AIR} destroy hook and
     * tears down the whole portal — user wants only actual obsidian
     * breakage to destroy the portal. Returning 0 from getDestroyProgress
     * means the break bar never progresses, so the block cannot be
     * destroyed via normal left-click-hold.
     */
    @Inject(
        method = "getDestroyProgress(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void seamlessportals$makeUnbreakable(
            BlockState state, Player player, BlockGetter level, BlockPos pos,
            CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(0.0F);
    }
}
