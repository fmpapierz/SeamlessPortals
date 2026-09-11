package qouteall.imm_ptl.core.block_manipulation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import qouteall.imm_ptl.core.portal.PortalUtils;

import java.util.function.Supplier;

public class BlockManipulationClient {
    private static final Minecraft client = Minecraft.getInstance();
    
    public static ResourceKey<Level> remotePointedDim;
    public static HitResult remoteHitResult;
    /**
     * ★ CROSS-PORTAL ENTITY HIT (2026-09-10): the portal the current through-portal targeting
     * went THROUGH — set exactly where {@code remotePointedDim} is set, cleared where it is
     * cleared. The attack RPC sends its UUID so the server can knock the target back in the
     * portal-transformed frame instead of the attacker's raw one (the round-1 wrong-way
     * knockback verdict).
     */
    @Nullable
    public static Portal remotePointedPortal;
    
    public static boolean isPointingToPortal() {
        return remotePointedDim != null;
    }
    
    @Nullable
    public static ClientLevel getRemotePointedWorld() {
        if (remotePointedDim == null) {
            return null;
        }
        return ClientWorldLoader.getWorld(BlockManipulationClient.remotePointedDim);
    }
    
    private static BlockHitResult createMissedHitResult(Vec3 from, Vec3 to) {
        Vec3 dir = to.subtract(from).normalize();

        // 26.2: Direction.getNearest(double,double,double) is GONE; the double-vector variant is now
        // getApproximateNearest(double,double,double) (Direction.java:303). The int/Vec3i getNearest
        // overloads survive but take a fallback arg — not the shape used here.
        return BlockHitResult.miss(to, Direction.getApproximateNearest(dir.x, dir.y, dir.z), BlockPos.containing(to));
    }
    
    private static boolean hitResultIsMissedOrNull(HitResult bhr) {
        return bhr == null || bhr.getType() == HitResult.Type.MISS;
    }
    
    public static void updatePointedBlock(float partialTick) {
        if (client.gameMode == null || client.level == null || client.player == null) {
            return;
        }
        
        remotePointedDim = null;
        remoteHitResult = null;
        remotePointedPortal = null;

        if (!BlockManipulationServer.canDoCrossPortalInteractionEvent.invoker().test(client.player)) {
            return;
        }
        
        // 26.2: GameRenderer.getMainCamera() → mainCamera() (GameRenderer.java:657); Camera.getPosition()
        // → position() (ducks-api-misc.md C6; Camera.java:359).
        Vec3 cameraPos = client.gameRenderer.mainCamera().position();

        double reachDistance = client.player.blockInteractionRange();
        
        PortalUtils.raytracePortalFromEntityView(client.player, partialTick, reachDistance, true, portal1 -> portal1.isInteractableBy(client.player)).ifPresent(pair -> {
            Portal portal = pair.getFirst();
            Vec3 hitPos = pair.getSecond().hitPos();
            double distanceToPortalPointing = hitPos.distanceTo(cameraPos);
            // RS PASSTHROUGH (a) step 3 — INSTRUMENTATION ONLY, no behaviour change.
            // This comparison is the targeting bet: the design panel's two adversarial verifiers
            // reached OPPOSITE conclusions about what it does once real blocks sit in an aperture,
            // and neither observed it. Until (a), the aperture only ever held PortalPlaceholderBlock,
            // for which getCurrentTargetDistance() returns the 23333 sentinel (:104-109) so the
            // portal always won. With real blocks there the local hit has a real distance and the
            // outcome becomes a genuine race. Armed via -Dseamlessportals.seamAimProbe=true.
            double localTargetDistance = getCurrentTargetDistance();
            // RECORDED IP DEVIATION — RS PASSTHROUGH (a); revert with
            // -Dseamlessportals.disableSeamTargeting=true. THE TARGETING FIX.
            //
            // Measured, not reasoned: with seamAimProbe armed, aiming at a real block sitting in an
            // aperture produced localDist=2.331 portalDist=2.419 margin=0.112 -> THROUGH-PORTAL. The
            // local hit was genuinely CLOSER than the portal and still lost, purely to the hardcoded
            // +0.2 above. The player's block then lands in the OTHER DIMENSION, silently, on the most
            // common gesture the whole feature exists to support. (User-confirmed live: "aiming at
            // the far half of a cell's top face still sends the block to the other dimension".)
            //
            // The fix must be NARROW. The same comparison has a second, CORRECT mode: for an EMPTY
            // aperture cell getCurrentTargetDistance() returns the 23333 placeholder sentinel
            // (:120-125) so the portal always wins — and that is exactly what lets a player reach
            // THROUGH an open portal to interact with the far world, a real shipped feature. A blanket
            // "seam cells win" would fix the rail and break cross-portal interaction in one stroke.
            //
            // So: the local hit wins ONLY when it is a seam cell holding a REAL, non-placeholder
            // block. Aim at an empty aperture and you still reach through, unchanged.
            boolean seamBlockWinsTargeting =
                !com.warwa.seamlessportals.passthrough.AperturePassthroughLever.DISABLE_SEAM_TARGETING
                    && localTargetDistance < 20000.0   // a real local hit, not the sentinel
                    && client.hitResult instanceof BlockHitResult localHit
                    && com.warwa.seamlessportals.passthrough.SeamRegistry.isSeamCell(
                        client.level, localHit.getBlockPos());

            boolean reroute = !seamBlockWinsTargeting
                && distanceToPortalPointing < localTargetDistance + 0.2;

            // Probe AFTER the decision is final. It previously ran BEFORE seamBlockWinsTargeting was
            // computed and reported the OLD expression's result, so it logged "SEAM CELL LOST" for
            // hits the fix was already keeping local — an instrument describing a code path that no
            // longer runs. It now reports what actually happened, and whether the seam override is
            // what caused it.
            com.warwa.seamlessportals.passthrough.SeamAimProbe.aimDecision(
                client.level,
                client.hitResult instanceof BlockHitResult bhr ? bhr.getBlockPos() : null,
                distanceToPortalPointing,
                localTargetDistance,
                reroute,
                seamBlockWinsTargeting
            );

            if (reroute) {
                client.hitResult = createMissedHitResult(cameraPos, hitPos);
                
                updateTargetedBlockThroughPortal(
                    cameraPos,
                    client.player.getViewVector(partialTick),
                    client.player.level().dimension(),
                    distanceToPortalPointing,
                    reachDistance,
                    portal
                );
            }
        });
        // ★ SEAM WHOLE-OBJECT SELECTION (user order 2026-08-03): from the frame's FINAL targeting,
        // derive the targeted seam object's OTHER half so both halves outline as one block —
        // near→far via the portal shells, far→near via the main extract. Separate store on
        // purpose: remotePointedDim doubles as the interaction router and must not be synthesized.
        com.warwa.seamlessportals.render.SeamCounterpartOutline.update(client);
    }
    
    private static double getCurrentTargetDistance() {
        // 26.2: getMainCamera().getPosition() → mainCamera().position() (see updatePointedBlock).
        Vec3 cameraPos = client.gameRenderer.mainCamera().position();
        
        if (hitResultIsMissedOrNull(client.hitResult)) {
            return 23333;
        }
        
        if (client.hitResult instanceof BlockHitResult) {
            BlockPos hitPos = ((BlockHitResult) client.hitResult).getBlockPos();
            if (client.level.getBlockState(hitPos).getBlock() == PortalPlaceholderBlock.instance) {
                return 23333;
            }
        }
        
        return cameraPos.distanceTo(client.hitResult.getLocation());
    }
    
    private static void updateTargetedBlockThroughPortal(
        Vec3 cameraPos,
        Vec3 viewVector,
        ResourceKey<Level> playerDimension,
        double beginDistance,
        double endDistance,
        Portal portal
    ) {
        
        Vec3 from = portal.transformPoint(
            cameraPos.add(viewVector.scale(beginDistance))
        );
        Vec3 to = portal.transformPoint(
            cameraPos.add(viewVector.scale(endDistance))
        );
        
        ClipContext context = new ClipContext(
            from,
            to,
            ClipContext.Block.OUTLINE,
            ClipContext.Fluid.NONE,
            client.player
        );
        
        ClientLevel world = ClientWorldLoader.getWorld(portal.getDestDim());
        
        remoteHitResult = BlockGetter.traverseBlocks(
            from, to,
            context,
            (rayTraceContext, blockPos) -> {
                BlockState blockState = world.getBlockState(blockPos);
                
                if (blockState.getBlock() == PortalPlaceholderBlock.instance) {
                    return null;
                }
                if (blockState.getBlock() == Blocks.BARRIER) {
                    return null;
                }
                
                FluidState fluidState = world.getFluidState(blockPos);
                Vec3 start = rayTraceContext.getFrom();
                Vec3 end = rayTraceContext.getTo();
                
                //correct the start pos to avoid being considered inside block
                Vec3 correctedStart = start.subtract(end.subtract(start).scale(0.0015));
//                Vec3d correctedStart = start;
                VoxelShape solidShape = rayTraceContext.getBlockShape(blockState, world, blockPos);
                BlockHitResult blockHitResult = world.clipWithInteractionOverride(
                    correctedStart, end, blockPos, solidShape, blockState
                );
                VoxelShape fluidShape = rayTraceContext.getFluidShape(fluidState, world, blockPos);
                BlockHitResult fluidHitResult = fluidShape.clip(start, end, blockPos);
                double d = blockHitResult == null ? Double.MAX_VALUE :
                    rayTraceContext.getFrom().distanceToSqr(blockHitResult.getLocation());
                double e = fluidHitResult == null ? Double.MAX_VALUE :
                    rayTraceContext.getFrom().distanceToSqr(fluidHitResult.getLocation());
                return d <= e ? blockHitResult : fluidHitResult;
            },
            (rayTraceContext) -> {
                Vec3 vec3d = rayTraceContext.getFrom().subtract(rayTraceContext.getTo());
                return BlockHitResult.miss(
                    rayTraceContext.getTo(),
                    Direction.getApproximateNearest(vec3d.x, vec3d.y, vec3d.z),
                    BlockPos.containing(rayTraceContext.getTo())
                );
            }
        );
        
        // 26.2: Level.getMinBuildHeight() → getMinY() (LevelHeightAccessor.java:9; ducks-api-misc.md
        // global renames). getMinY equals the old min build height — no off-by-one on the MIN side.
        if (remoteHitResult.getLocation().y < world.getMinY() + 0.1) {
            remoteHitResult = new BlockHitResult(
                remoteHitResult.getLocation(),
                Direction.DOWN,
                ((BlockHitResult) remoteHitResult).getBlockPos(),
                ((BlockHitResult) remoteHitResult).isInside()
            );
        }

        // ★ CROSS-PORTAL ENTITY HIT (2026-09-10; RECORDED DEVIATION from upstream IP, whose
        // cross-portal interaction is BLOCK-only — revert with
        // -Dseamlessportals.disableCrossPortalEntityHit=true). The entity leg of the through-portal
        // pick, mirroring vanilla's crosshair pick (mc262-ref LocalPlayer.java:1265-1284): the ray
        // runs in the DEST world over the same transformed segment as the block leg, budgeted by the
        // ENTITY interaction range along the camera ray (segment construction carries the
        // through-portal reach exactly like the block leg above, portal scale included), and is
        // OCCLUDED by the remote block hit (an entity behind the wall you see is not a target).
        // An entity hit engages pointing-to-portal even over open air — the block-only tail below
        // requires a non-air block hit, which a mob standing in a field never produces. startAttack
        // under the switched context then sees the EntityHitResult and attacks; the outgoing
        // ServerboundAttackPacket is re-wrapped into the dimension-tagged RPC by
        // MixinMultiPlayerGameMode.ip_redirectPacket, and BlockManipulationServer's acceptor
        // validates portal-aware reach server-side.
        if (!com.warwa.seamlessportals.passthrough.AperturePassthroughLever
            .DISABLE_CROSS_PORTAL_ENTITY_HIT
        ) {
            double entityReach = client.player.entityInteractionRange();
            if (entityReach > beginDistance) {
                Vec3 entityTo = portal.transformPoint(
                    cameraPos.add(viewVector.scale(entityReach)));
                double blockDistSq = remoteHitResult.getType() != HitResult.Type.MISS
                    ? remoteHitResult.getLocation().distanceToSqr(from)
                    : Double.MAX_VALUE;
                double maxDistSq = Math.min(blockDistSq, from.distanceToSqr(entityTo));
                net.minecraft.world.phys.EntityHitResult entityHit = pickEntityInWorld(
                    world, client.player, from, entityTo,
                    new net.minecraft.world.phys.AABB(from, entityTo).inflate(1.0),
                    net.minecraft.world.entity.EntitySelector.CAN_BE_PICKED, maxDistSq);
                if (entityHit != null) {
                    remoteHitResult = entityHit;
                    client.hitResult = createMissedHitResult(from, to);
                    remotePointedDim = portal.getDestDim();
                    remotePointedPortal = portal;
                    return;
                }
            }
        }

        if (remoteHitResult != null) {
            if (!world.getBlockState(((BlockHitResult) remoteHitResult).getBlockPos()).isAir()) {
                client.hitResult = createMissedHitResult(from, to);
                remotePointedDim = portal.getDestDim();
                remotePointedPortal = portal;
            }
        }

    }

    /**
     * Vanilla-copy of {@code ProjectileUtil.getEntityHitResult(Entity, Vec3, Vec3, AABB,
     * Predicate, double)} (mc262-ref ProjectileUtil.java:97-131) with ONE substitution: the
     * level is an explicit argument instead of {@code except.level()} — the dest world of a
     * cross-dim portal is not the excluder's level. The excluder is still passed for identity
     * exclusion ({@code getEntities} handles an entity absent from the level vacuously) and for
     * the root-vehicle tie-break, which cross-dim can never match — both faithful.
     */
    @qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy
    @Nullable
    private static net.minecraft.world.phys.EntityHitResult pickEntityInWorld(
        Level level,
        net.minecraft.world.entity.Entity except,
        Vec3 from, Vec3 to,
        net.minecraft.world.phys.AABB box,
        java.util.function.Predicate<net.minecraft.world.entity.Entity> matching,
        double maxValue
    ) {
        double nearest = maxValue;
        net.minecraft.world.entity.Entity hovered = null;
        Vec3 hoveredPos = null;

        for (net.minecraft.world.entity.Entity entity : level.getEntities(except, box, matching)) {
            net.minecraft.world.phys.AABB bb =
                entity.getBoundingBox().inflate(entity.getPickRadius());
            java.util.Optional<Vec3> clipPoint = bb.clip(from, to);
            if (bb.contains(from)) {
                if (nearest >= 0.0 && entity.canBePickedFromInside()) {
                    hovered = entity;
                    hoveredPos = clipPoint.orElse(from);
                    nearest = 0.0;
                }
            } else if (clipPoint.isPresent()) {
                Vec3 location = clipPoint.get();
                double dd = from.distanceToSqr(location);
                if (dd < nearest || nearest == 0.0) {
                    if (entity.getRootVehicle() == except.getRootVehicle()) {
                        if (nearest == 0.0) {
                            hovered = entity;
                            hoveredPos = location;
                        }
                    } else {
                        hovered = entity;
                        hoveredPos = location;
                        nearest = dd;
                    }
                }
            }
        }

        return hovered == null ? null
            : new net.minecraft.world.phys.EntityHitResult(hovered, hoveredPos);
    }
    
    /**
     * It will not switch the dimension of client player
     */
    public static <T> T withSwitchedContext(
        Supplier<T> func, boolean transformHitResult
    ) {
        Validate.notNull(remoteHitResult);
        
        ClientLevel remoteWorld = getRemotePointedWorld();
        Validate.notNull(remoteWorld);
        
        HitResult effectiveHitResult;
        
        if (transformHitResult && (remoteHitResult instanceof BlockHitResult blockHitResult)) {
            // 26.2: net.minecraft.util.Tuple is GONE → com.mojang.datafixers.util.Pair
            // (getA()/getB() → getFirst()/getSecond()); matches BlockManipulationServer.getHitResultForPlacing.
            Pair<BlockHitResult, ResourceKey<Level>> r =
                BlockManipulationServer.getHitResultForPlacing(remoteWorld, blockHitResult);
            effectiveHitResult = r.getFirst();
            remoteWorld = ClientWorldLoader.getWorld(r.getSecond());
            Validate.notNull(remoteWorld);
            Validate.notNull(effectiveHitResult);
        }
        else {
            effectiveHitResult = remoteHitResult;
        }
        
        return ClientWorldLoader.withSwitchedWorld(
            remoteWorld, () -> {
                HitResult originalHitResult = client.hitResult;
                client.hitResult = effectiveHitResult;
                try {
                    return func.get();
                }
                finally {
                    client.hitResult = originalHitResult;
                }
            }
        );
    }

    @Nullable
    public static String getDebugString() {
        if (remotePointedDim == null) {
            return null;
        }
        if (remoteHitResult instanceof BlockHitResult blockHitResult) {
            return "Point:%s %d %d %d".formatted(
                remotePointedDim.identifier(),
                blockHitResult.getBlockPos().getX(),
                blockHitResult.getBlockPos().getY(),
                blockHitResult.getBlockPos().getZ()
            );
        }
        else {
            return null;
        }
    }
}
