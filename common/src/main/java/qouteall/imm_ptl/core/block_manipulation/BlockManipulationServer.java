package qouteall.imm_ptl.core.block_manipulation;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.event.Event; // NF-PARITY W9
import com.warwa.seamlessportals.event.EventFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.ScaleUtils;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalUtils;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.util.List;
import java.util.function.Predicate;

@SuppressWarnings("resource")
public class BlockManipulationServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static record Context(
        ServerLevel world,
        @Nullable BlockHitResult blockHitResult
    ) {
    
    }
    
    public static final ThreadLocal<Context> REDIRECT_CONTEXT =
        ThreadLocal.withInitial(() -> null);

    /**
     * ★ CROSS-PORTAL ENTITY HIT (2026-09-10): the portal a cross-portal attack came THROUGH,
     * armed only around the acceptor's single synchronous {@code player.attack} call on the
     * server thread. {@code MixinPlayer_CrossPortalKnockback} reads it to move the knockback
     * frame (damage-source position + yaw-derived directions) into the destination frame — the
     * round-1 live verdict was knockback pulling targets TOWARD the attacker, because a
     * facing-linked portal pair flips the attacker's raw frame 180°.
     */
    public static final ThreadLocal<Portal> CROSS_PORTAL_ATTACK_CONTEXT =
        ThreadLocal.withInitial(() -> null);
    
    /**
     * Use this event to conditionally disable cross portal block interaction.
     * The result will be ANDed.
     */
    public static final Event<Predicate<Player>> canDoCrossPortalInteractionEvent =
        EventFactory.createArrayBacked(Predicate.class,
            handlers -> player -> {
                for (Predicate<Player> handler : handlers) {
                    if (!handler.test(player)) {
                        return false;
                    }
                }
                return true;
            });
    
    private static boolean canPlayerReach(
        ResourceKey<Level> dimension,
        ServerPlayer player,
        BlockPos requestPos
    ) {
        return canPlayerReachPos(dimension, player, Vec3.atCenterOf(requestPos));
    }

    // ★ CROSS-PORTAL ENTITY HIT (2026-09-10): the reach core, extracted UNCHANGED from
    // canPlayerReach so the entity acceptor can gate on a Vec3 (an entity's AABB center) with
    // the exact constants and portal-transform shape the block path has always used — one
    // reach policy for every cross-portal interaction.
    private static boolean canPlayerReachPos(
        ResourceKey<Level> dimension,
        ServerPlayer player,
        Vec3 pos
    ) {
        if (!canDoCrossPortalInteractionEvent.invoker().test(player)) {
            return false;
        }

        double playerScale = ScaleUtils.computeBlockReachScale(player);

        Vec3 playerPos = player.position();
        double distanceSquare = 6 * 6 * 4 * 4 * playerScale * playerScale;
        if (player.level().dimension() == dimension) {
            if (playerPos.distanceToSqr(pos) < distanceSquare) {
                return true;
            }
        }
        return IPMcHelper.getNearbyPortals(
            player,
            IPGlobal.maxNormalPortalRadius
        ).anyMatch(portal ->
            portal.getDestDim() == dimension &&
                portal.isInteractableBy(player) &&
                portal.transformPoint(playerPos).distanceToSqr(pos) <
                    distanceSquare * portal.getScale() * portal.getScale()
        );
    }
    
    // 26.2: net.minecraft.util.Tuple is GONE (removed from vanilla; no Tuple.java in mc262-ref).
    // Replaced with com.mojang.datafixers.util.Pair (getA()/getB() → getFirst()/getSecond();
    // new Tuple<>(a,b) → Pair.of(a,b)). Faithful 1:1 pair carrier; the caller
    // BlockManipulationClient.withSwitchedContext is retyped to match.
    public static Pair<BlockHitResult, ResourceKey<Level>> getHitResultForPlacing(
        Level world,
        BlockHitResult blockHitResult
    ) {
        Direction side = blockHitResult.getDirection();
        // 26.2: Direction.getNormal() → getUnitVec3i() (Direction.java:375; returns Vec3i).
        Vec3 sideVec = Vec3.atLowerCornerOf(side.getUnitVec3i());
        BlockPos hitPos = blockHitResult.getBlockPos();
        Vec3 hitCenter = Vec3.atCenterOf(hitPos);
        
        List<Portal> globalPortals = GlobalPortalStorage.getGlobalPortals(world);
        
        Portal portal = globalPortals.stream().filter(p ->
            p.getNormal().dot(sideVec) < -0.9
                && p.getPortalShape().isBoxInPortalProjection(
                p.getThisSideState(),
                new AABB(hitPos)
            ) && p.getDistanceToPlane(hitCenter) < 0.6
        ).findFirst().orElse(null);
        
        if (portal == null) {
            return Pair.of(blockHitResult, world.dimension());
        }
        
        Vec3 newCenter = portal.transformPoint(hitCenter.add(sideVec.scale(0.501)));
        BlockPos placingBlockPos = BlockPos.containing(newCenter);
        
        BlockHitResult newHitResult = new BlockHitResult(
            Vec3.ZERO,
            side.getOpposite(),
            placingBlockPos,
            blockHitResult.isInside()
        );
        
        return Pair.of(newHitResult, portal.getDestDim());
    }
    
    public static class RemoteCallables {
        /**
         * {@link qouteall.imm_ptl.core.mixin.client.interaction.MixinMultiPlayerGameMode#ip_redirectPacket}
         */
        @SuppressWarnings("JavadocReference")
        public static void processPlayerActionPacket(
            ServerPlayer player,
            ResourceKey<Level> dimension,
            byte[] packetBytes
        ) {
            FriendlyByteBuf buf = IPMcHelper.bytesToBuf(packetBytes);
            ServerboundPlayerActionPacket packet = ServerboundPlayerActionPacket.STREAM_CODEC.decode(buf);
            
            ServerLevel world = player.server.getLevel(dimension);
            Validate.notNull(world, "missing %s", dimension.identifier());

            withRedirect(
                new Context(world, null),
                () -> {
                    doProcessPlayerAction(world, player, packet);
                }
            );
        }
        
        /**
         * ★ CROSS-DIM CREATIVE PICK (user order 2026-08-03; 26.2 divergence from IP). On 1.19 the
         * pick was resolved CLIENT-side, so IP's level/hitResult swap covered it for free. 26.2
         * moved resolution into {@code ServerGamePacketListenerImpl.handlePickItemFromBlock}, which
         * reads {@code player.level()} — the ported client swap sent the remote pos into the WRONG
         * dimension, silently picking nothing. This callable is an
         * {@code @IPVanillaCopy} of that handler (+ its private {@code tryPickItem}), with two
         * substitutions: the level comes from the DIMENSION ARGUMENT, and the reach gate is the
         * portal-aware {@code canPlayerReach} instead of the Euclidean same-dim check.
         *
         * <p>The {@code hasInfiniteMaterials() && includeData} gate is preserved verbatim — dropping
         * it would let survival players request NBT-laden clones.
         */
        public static void processPickItemFromBlock(
            ServerPlayer player,
            ResourceKey<Level> dimension,
            BlockPos pos,
            boolean includeDataRequested
        ) {
            ServerLevel world = player.server.getLevel(dimension);
            Validate.notNull(world, "missing %s", dimension.identifier());
            if (!canPlayerReach(dimension, player, pos)) {
                return;
            }
            if (!world.isLoaded(pos)) {
                return;
            }
            net.minecraft.world.level.block.state.BlockState blockState = world.getBlockState(pos);
            boolean includeData = player.hasInfiniteMaterials() && includeDataRequested;
            net.minecraft.world.item.ItemStack itemStack =
                blockState.getCloneItemStack(world, pos, includeData);
            if (itemStack.isEmpty()) {
                return;
            }
            // BE data intentionally NOT copied cross-dim: seam secondaries refuse BEs anyway, and
            // vanilla's addBlockDataToItem is private with a reporter context — the plain clone
            // covers every legitimate cross-window pick. (IPVanillaCopy divergence, recorded.)
            // tryPickItem vanilla-copy (SGPLI is private):
            if (itemStack.isItemEnabled(world.enabledFeatures())) {
                net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
                int slot = inventory.findSlotMatchingItem(itemStack);
                if (slot != -1) {
                    if (net.minecraft.world.entity.player.Inventory.isHotbarSlot(slot)) {
                        inventory.setSelectedSlot(slot);
                    } else {
                        inventory.pickSlot(slot);
                    }
                } else if (player.hasInfiniteMaterials()) {
                    inventory.addAndPickItem(itemStack);
                }
                player.connection.send(
                    new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(
                        inventory.getSelectedSlot()));
                player.inventoryMenu.broadcastChanges();
            }
        }

        /**
         * {@link qouteall.imm_ptl.core.mixin.client.interaction.MixinMultiPlayerGameMode#ip_redirectPacket}
         */
        @SuppressWarnings("JavadocReference")
        public static void processUseItemOnPacket(
            ServerPlayer player,
            ResourceKey<Level> dimension,
            byte[] packetBytes
        ) {
            FriendlyByteBuf buf = IPMcHelper.bytesToBuf(packetBytes);
            ServerboundUseItemOnPacket packet = ServerboundUseItemOnPacket.STREAM_CODEC.decode(buf);

            ServerLevel world = player.server.getLevel(dimension);
            Validate.notNull(world, "missing %s", dimension.identifier());

            withRedirect(
                new Context(world, packet.getHitResult()),
                () -> {
                    doProcessUseItemOn(world, player, packet);
                }
            );
        }

        /**
         * ★ CROSS-PORTAL ENTITY HIT (2026-09-10; deviation from upstream IP — block-only there).
         * {@link qouteall.imm_ptl.core.mixin.client.interaction.MixinMultiPlayerGameMode#ip_redirectPacket}
         */
        @SuppressWarnings("JavadocReference")
        public static void processAttackEntityPacket(
            ServerPlayer player,
            ResourceKey<Level> dimension,
            byte[] packetBytes,
            java.util.UUID portalId
        ) {
            FriendlyByteBuf buf = IPMcHelper.bytesToBuf(packetBytes);
            net.minecraft.network.protocol.game.ServerboundAttackPacket packet =
                net.minecraft.network.protocol.game.ServerboundAttackPacket.STREAM_CODEC.decode(buf);

            ServerLevel world = player.server.getLevel(dimension);
            Validate.notNull(world, "missing %s", dimension.identifier());

            withRedirect(
                new Context(world, null),
                () -> {
                    doProcessAttackEntity(world, player, packet, portalId);
                }
            );
        }
    }
    
    public static void init() {
    
    }
    
    private static void withRedirect(
        Context context,
        Runnable runnable
    ) {
        Context original = REDIRECT_CONTEXT.get();
        REDIRECT_CONTEXT.set(context);
        try {
            PacketRedirection.withForceRedirect(
                context.world(), runnable
            );
        }
        finally {
            REDIRECT_CONTEXT.set(original);
        }
    }
    
    /**
     * {@link ServerGamePacketListenerImpl#handlePlayerAction(ServerboundPlayerActionPacket)}
     */
    @IPVanillaCopy
    private static void doProcessPlayerAction(ServerLevel world, ServerPlayer player, ServerboundPlayerActionPacket packet) {
        player.resetLastActionTime();
        BlockPos blockPos = packet.getPos();
        ServerboundPlayerActionPacket.Action action = packet.getAction();
        
        if (!canPlayerReach(world.dimension(), player, blockPos)) {
            LOGGER.error("Reject cross-portal action {} {} {}", player, world, blockPos);
            return;
        }
        
        if (isAttackingAction(action)) {
            // 26.2: ServerPlayerGameMode.handleBlockBreakAction's maxY arg is now the INCLUSIVE getMaxY()
            // (ducks-api-misc.md S41/C24; vanilla SGPLI passes this.player.level().getMaxY() at :1323).
            player.gameMode.handleBlockBreakAction(
                blockPos, action, packet.getDirection(),
                world.getMaxY(), packet.getSequence()
            );
            player.connection.ackBlockChangesUpTo(packet.getSequence());
        }
    }
    
    public static boolean isAttackingAction(ServerboundPlayerActionPacket.Action action) {
        return action == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK ||
            action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK ||
            action == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK;
    }
    
    /**
     * {@link ServerGamePacketListenerImpl#handleUseItemOn(ServerboundUseItemOnPacket)}
     */
    @IPVanillaCopy
    private static void doProcessUseItemOn(
        ServerLevel world, ServerPlayer player, ServerboundUseItemOnPacket packet
    ) {
        player.connection.ackBlockChangesUpTo(packet.getSequence());
        InteractionHand hand = packet.getHand();
        BlockHitResult blockHitResult = packet.getHitResult();
        ResourceKey<Level> dimension = world.dimension();
        
        ItemStack itemStack = player.getItemInHand(hand);
        
        if (!itemStack.isItemEnabled(world.enabledFeatures())) {
            return;
        }
        
        BlockPos blockPos = blockHitResult.getBlockPos();
        Direction direction = blockHitResult.getDirection();
        player.resetLastActionTime();
        if (world.mayInteract(player, blockPos)) {
            if (!canPlayerReach(dimension, player, blockPos)) {
                LOGGER.error("Reject cross-portal action {} {} {}", player, world, blockPos);
                return;
            }
            
            InteractionResult actionResult = player.gameMode.useItemOn(
                player,
                world,
                itemStack,
                hand,
                blockHitResult
            );
            // 26.2: InteractionResult.shouldSwing() is GONE (ducks-api-misc.md G7; InteractionResult is now a
            // sealed interface). The server-side swing condition is the vanilla pattern
            // (ServerGamePacketListenerImpl.java:1381-1383): a Success result whose swingSource is SERVER.
            if (actionResult instanceof InteractionResult.Success success
                && success.swingSource() == InteractionResult.SwingSource.SERVER) {
                player.swing(hand, true);
            }
        }
        
        PacketRedirection.sendRedirectedMessage(
            player,
            dimension,
            new ClientboundBlockUpdatePacket(world, blockPos)
        );
        
        BlockPos offseted = blockPos.relative(direction);
        // 26.2: getMinBuildHeight()/getMaxBuildHeight() → getMinY()/getMaxY() (ducks-api-misc.md global
        // renames). SEMANTIC TRAP: getMaxY() is INCLUSIVE (= old getMaxBuildHeight() - 1), so the old
        // exclusive `< getMaxBuildHeight()` becomes inclusive `<= getMaxY()`; the MIN side has no off-by-one.
        if (offseted.getY() >= world.getMinY() && offseted.getY() <= world.getMaxY()) {
            PacketRedirection.sendRedirectedMessage(
                player,
                dimension,
                new ClientboundBlockUpdatePacket(world, offseted)
            );
        }
    }
    
    /**
     * ★ CROSS-PORTAL ENTITY HIT acceptor — {@code @IPVanillaCopy} of
     * {@code ServerGamePacketListenerImpl.handleAttack} (mc262-ref
     * ServerGamePacketListenerImpl.java:1812-1838) with four substitutions:
     * <ul>
     *   <li>the level comes from the DIMENSION ARGUMENT, not {@code player.level()} — the whole
     *       point;</li>
     *   <li>the reach gate is the portal-aware {@link #canPlayerReachPos} against the target's
     *       AABB center instead of {@code isWithinAttackRange} (which measures from the player's
     *       raw position and can never pass through a portal, same substitution the block path
     *       makes for {@code canInteractWithBlock});</li>
     *   <li>the invalid-target branch LOGS AND RETURNS instead of disconnecting — vanilla may
     *       assume a vanilla client never sends these, but this RPC races real state changes (the
     *       picked entity can become an item drop between client frame and server tick), so a kick
     *       would punish latency;</li>
     *   <li>{@code Portal} entities are rejected — the pick's CAN_BE_PICKED already excludes them
     *       (portals are not pickable), so a portal id here is a forged or garbage packet.</li>
     * </ul>
     * The PIERCING_WEAPON refusal, {@code isItemEnabled}, {@code cannotAttackWithItem(item, 5)}
     * and the final {@code player.attack(target)} are verbatim. Cross-level {@code attack} is
     * sound: damage flows through {@code Entity.hurtOrSimulate}, which resolves the TARGET's own
     * level (mc262-ref Entity.java:1919-1921), so deaths and drops land in the target's dimension;
     * the attacker-side sound/exhaustion run in the attacker's level as vanilla intends.
     */
    @IPVanillaCopy
    private static void doProcessAttackEntity(
        ServerLevel world, ServerPlayer player,
        net.minecraft.network.protocol.game.ServerboundAttackPacket packet,
        java.util.UUID portalId
    ) {
        if (player.isSpectator()) {
            return;
        }
        net.minecraft.world.entity.Entity target = world.getEntityOrPart(packet.entityId());
        player.resetLastActionTime();
        if (target == null || !world.getWorldBorder().isWithinBounds(target.blockPosition())) {
            return;
        }
        ItemStack mainHandItem = player.getMainHandItem();
        if (!canPlayerReachPos(
            world.dimension(), player, target.getBoundingBox().getCenter())
        ) {
            LOGGER.error("Reject cross-portal attack {} {} {}", player, world, target);
            return;
        }
        // ★ KNOCKBACK FRAME: resolve the portal the CLIENT says the hit went through (it lives
        // in the attacker's own level) and validate it independently of the coarse reach gate
        // above — dest must match and the portal must be interactable. An invalid id is a forged
        // or stale packet: reject rather than attack in the wrong frame.
        if (!(player.level().getEntity(portalId) instanceof Portal viaPortal)
            || viaPortal.getDestDim() != world.dimension()
            || !viaPortal.isInteractableBy(player)
        ) {
            LOGGER.error("Reject cross-portal attack via invalid portal {} {} {}",
                player, world, portalId);
            return;
        }
        if (mainHandItem.has(net.minecraft.core.component.DataComponents.PIERCING_WEAPON)) {
            return;
        }
        if (target instanceof net.minecraft.world.entity.item.ItemEntity
            || target instanceof net.minecraft.world.entity.ExperienceOrb
            || target == player
            || target instanceof Portal
            || target instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow
                && !arrow.isAttackable()
        ) {
            LOGGER.warn("Player {} tried to attack an invalid entity cross-portal", player.getPlainTextName());
            return;
        }
        if (mainHandItem.isItemEnabled(world.enabledFeatures())) {
            if (!player.cannotAttackWithItem(mainHandItem, 5)) {
                CROSS_PORTAL_ATTACK_CONTEXT.set(viaPortal);
                try {
                    player.attack(target);
                }
                finally {
                    CROSS_PORTAL_ATTACK_CONTEXT.remove();
                }
            }
        }
    }

    public static boolean validateReach(Player player, Level targetWorld, BlockPos targetPos) {
        PortalUtils.PortalAwareRaytraceResult result = PortalUtils.portalAwareRayTrace(
            player.level(),
            player.getEyePosition(),
            player.getViewVector(1),
            32,
            player,
            ClipContext.Block.COLLIDER
        );
        
        return result != null
            && result.world() == targetWorld
            && result.hitResult().getBlockPos().distManhattan(targetPos) < 8;
    }
    
}
