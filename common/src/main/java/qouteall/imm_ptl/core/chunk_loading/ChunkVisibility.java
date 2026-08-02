package qouteall.imm_ptl.core.chunk_loading;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.q_misc_util.my_util.LimitedLogger;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class ChunkVisibility {
    private static final LimitedLogger limitedLogger = new LimitedLogger(10);
    
    private static final int portalLoadingRange = 48;
    public static final int secondaryPortalLoadingRange = 16;
    
    public static ChunkLoader playerDirectLoader(ServerPlayer player) {
        return new ChunkLoader(
            new DimensionalChunkPos(
                player.level().dimension(),
                player.chunkPosition()
            ),
            McHelper.getPlayerLoadDistance(player)
        );
    }
    
    private static int getDirectLoadingDistance(int renderDistance, double distanceToPortal) {
        if (distanceToPortal < 5) {
            return renderDistance;
        }
        if (distanceToPortal < 15) {
            return (renderDistance * 2) / 3;
        }
        return renderDistance / 3;
    }
    
    private static int getCappedLoadingDistance(
        Portal portal, ServerPlayer player, int targetLoadingDistance
    ) {
        PerformanceLevel performanceLevel =
            ImmPtlChunkTracking.getPlayerInfo(player).performanceLevel;
        int cap1 = PerformanceLevel.getIndirectLoadingRadiusCap(performanceLevel);
        int cap2 = IPGlobal.indirectLoadingRadiusCap;
        int cap3 = PerformanceLevel.getIndirectLoadingRadiusCap(ServerPerformanceMonitor.getLevel());
        
        int cap = Math.min(cap1, cap2);
        
        // load more for scaling portal
        if (portal.getScale() > 2) {
            cap *= 2;
        }
        
        int cappedLoadingDistance = Math.min(targetLoadingDistance, cap);
        
        return cappedLoadingDistance;
    }
    
    public static List<Portal> getNearbyPortals(
        ServerLevel world, Vec3 pos, Predicate<Portal> predicate,
        int radiusChunks, int radiusChunksForGlobalPortals
    ) {
        List<Portal> result = McHelper.findEntitiesRough(
            Portal.class,
            world,
            pos,
            radiusChunks,
            predicate
        );
        
        for (Portal globalPortal : GlobalPortalStorage.getGlobalPortals(world)) {
            double distance = globalPortal.getDistanceToNearestPointInPortal(pos);
            if (distance < radiusChunksForGlobalPortals * 16) {
                result.add(globalPortal);
            }
        }
        
        if (result.size() > 100) {
            limitedLogger.err("too many portal nearby " + world + pos);
            
            Optional<Portal> nearest =
                result.stream().min(Comparator.comparingDouble(p -> p.getDistanceToNearestPointInPortal(pos)));
            
            return List.of(nearest.get());
        }
        
        return result;
    }
    
    private static ChunkLoader getGeneralDirectPortalLoader(
        ServerPlayer player, Portal portal
    ) {
        if (portal.getIsGlobal()) {
            int renderDistance = Math.min(
                IPGlobal.indirectLoadingRadiusCap * 2,
                //load a little more to make dimension stack more complete
                Math.max(
                    2,
                    McHelper.getPlayerLoadDistance(player) -
                        Math.floorDiv((int) portal.getDistanceToNearestPointInPortal(player.position()), 16)
                )
            );
            
            return new ChunkLoader(
                new DimensionalChunkPos(
                    portal.getDestDim(),
                    ChunkPos.containing(BlockPos.containing(
                        portal.transformPoint(player.position())
                    ))
                ),
                renderDistance
            );
        }
        else {
            int loadDistance = McHelper.getPlayerLoadDistance(player);
            double distance = portal.getDistanceToNearestPointInPortal(player.position());
            
            // load more for up scaling portal
            if (portal.getScaling() > 2 && distance < 5) {
                loadDistance = (int) ((portal.getDestAreaRadiusEstimation() * 1.4) / 16);
            }
            
            return new ChunkLoader(
                new DimensionalChunkPos(
                    portal.getDestDim(),
                    ChunkPos.containing(BlockPos.containing(portal.getDestPos()))
                ),
                getCappedLoadingDistance(
                    portal, player,
                    getDirectLoadingDistance(loadDistance, distance)
                )
            );
        }
    }
    
    private static ChunkLoader getGeneralPortalIndirectLoader(
        ServerPlayer player,
        Vec3 transformedPos,
        Portal portal
    ) {
        int loadDistance = McHelper.getPlayerLoadDistance(player);
        
        if (portal.getIsGlobal()) {
            int renderDistance = Math.min(
                IPGlobal.indirectLoadingRadiusCap,
                loadDistance / 3
            );
            return new ChunkLoader(
                new DimensionalChunkPos(
                    portal.getDestDim(),
                    ChunkPos.containing(BlockPos.containing(transformedPos))
                ),
                renderDistance
            );
        }
        else {
            return new ChunkLoader(
                new DimensionalChunkPos(
                    portal.getDestDim(),
                    ChunkPos.containing(BlockPos.containing(portal.getDestPos()))
                ),
                getCappedLoadingDistance(
                    portal, player, loadDistance / 4
                )
            );
        }
    }
    
    //includes:
    //1.player direct loader
    //2.loaders from the portals that are directly visible
    //3.loaders from the portals that are indirectly visible through portals
    public static void foreachBaseChunkLoaders(
        ServerPlayer player, Consumer<ChunkLoader> func
    ) {
        PerformanceLevel perfLevel = ImmPtlChunkTracking.getPlayerInfo(player).performanceLevel;
        int visiblePortalRangeChunks = PerformanceLevel.getVisiblePortalRangeChunks(perfLevel);

        // IS5-WDIST — TIE THE DESTINATION LOADING RANGE TO THE WINDOW RENDER DISTANCE.
        //
        // This is how far from the player a portal gets a destination chunk loader at all. Default
        // 8 chunks = 128 blocks (PerformanceLevel:42). The portal WINDOW's own visibility range is a
        // separate gate (the entity-tracking range in MixinTrackedEntity), and when the two
        // disagree the frame renders with nothing inside it: USER-MEASURED as a window that stayed
        // visible but went BLANK from ~146 blocks and only refilled back inside ~127.
        //
        // So above 0 they are ONE number: the destination loads exactly as far as the window can be
        // seen. At 0 this is inert and the original 8-chunk range stands — which is consistent
        // BECAUSE the vanilla window gate stops at ~88 blocks, comfortably inside it. Neither mode
        // can produce a blank window; only mixing them could, which is what this removes.
        //
        // The perf level still wins when it is WORSE than the configured value: a struggling client
        // drops to 3 or 1 chunk (PerformanceLevel:46-51) and this must not override that protection
        // upward. Hence min() with the configured value rather than a bare assignment — raising the
        // setting asks for more reach on a healthy client, not for the degradation path disabled.
        int configuredWindowChunks = IPGlobal.portalWindowRenderDistance;
        if (configuredWindowChunks > 0 && perfLevel == PerformanceLevel.good) {
            visiblePortalRangeChunks = configuredWindowChunks;
        }
        int indirectVisiblePortalRangeChunks = PerformanceLevel.getIndirectVisiblePortalRangeChunks(perfLevel);
        
        ChunkLoader playerDirectLoader = playerDirectLoader(player);
    
        func.accept(playerDirectLoader);
    
        List<Portal> nearbyPortals = getNearbyPortals(
            ((ServerLevel) player.level()),
            player.position(),
            portal -> portal.broadcastToPlayer(player),
            visiblePortalRangeChunks, 256
        );
        
        // IS5-RLOAD: ONE budget for the whole player tick, shared across every direct portal's
        // chain walk. Declared here rather than inside the walk because the walk is called once per
        // DIRECT portal — a budget local to it would be re-granted per portal and the real ceiling
        // would be (direct portals) x budget.
        int[] chainLoaderBudget = {IPGlobal.portalChainLoaderBudget};

        for (Portal portal : nearbyPortals) {
            Level destinationWorld = portal.getDestinationWorld();
    
            if (destinationWorld == null) {
                continue;
            }
    
            Vec3 transformedPlayerPos = portal.transformPoint(player.position());
            
            func.accept(getGeneralDirectPortalLoader(player, portal));
    
            if (!isShrinkLoading()) {
                // IS5-RLOAD — LOAD AS DEEP AS WE RENDER, instead of a hardcoded two levels.
                //
                // This loop used to be flat: direct portals (layer 1) each got ONE pass over their
                // destination's portals (layer 2), and nothing loaded layer 3+. That was correct
                // while the renderer only ever showed about two layers. It is not correct now that
                // recursion depth is user-settable to 10+ — the renderer faithfully draws layer 3
                // into a world whose chunks were never loaded, so the terrain AND the next portal
                // are both simply absent. USER-REPORTED: "like 2 or 3 levels deep the terrain stops
                // rendering as well as the next portal". Same shape as the window/content mismatch
                // fixed earlier: one bound was raised and the bound that FEEDS it was left behind.
                loadPortalChainRecursively(
                    player, func, (ServerLevel) destinationWorld, transformedPlayerPos,
                    portal, 2, indirectVisiblePortalRangeChunks, chainLoaderBudget
                );
            }
        }
    }

    /**
     * IS5-RLOAD — walk the portal graph outward from a layer-1 destination, adding a chunk loader
     * for every portal destination the renderer could actually reach.
     *
     * <p><b>Depth</b> follows the render bound ({@code maxPortalLayer}, which also caps the
     * shaderpack depth), so loading and rendering stop at the same place by construction rather than
     * by coincidence. Depth 2 reproduces the old behaviour exactly.
     *
     * <p><b>Three bounds, because this walks a GRAPH on the server tick and the naive form is
     * unbounded in two different ways:</b>
     * <ul>
     *   <li><b>Depth</b> — {@code depth > maxDepth} stops the descent.</li>
     *   <li><b>A total loader budget</b> — portals FAN OUT, so a room with 5 portals each seeing 5
     *       more is 25 at depth 3 and 125 at depth 4. Depth alone does not bound the work; this
     *       does. Without it a dense build would hold thousands of chunk regions loaded.</li>
     *   <li><b>A cycle guard</b> — a linked pair A&lt;-&gt;B walks A,B,A,B forever. Skipping the portal
     *       we just came THROUGH is the same rule the renderer uses
     *       ({@code PortalRendering.isInvalidRecursionRendering}), so loading terminates on exactly
     *       the geometry rendering terminates on. This is the one that would hang a server tick, not
     *       merely slow it.</li>
     * </ul>
     *
     * <p>Iterative rather than recursive: the depth is user-settable, and a user-settable recursion
     * depth on a server-tick call path is a stack-overflow waiting to happen.
     */
    private static void loadPortalChainRecursively(
        ServerPlayer player, Consumer<ChunkLoader> func,
        ServerLevel startWorld, Vec3 startPos, Portal arrivedVia,
        int startDepth, int indirectRangeChunks, int[] sharedBudget
    ) {
        int maxDepth = Math.max(2, IPGlobal.maxPortalLayer);
        // THE BUDGET IS SHARED ACROSS THE WHOLE PLAYER TICK, threaded in as a one-element array.
        // The first version declared it as a LOCAL here — but this method is called once per
        // DIRECT portal, so each direct portal got its own fresh 64 and the real ceiling was
        // (direct portals) x 64, up to ~100x what this field's own javadoc promises. Caught by
        // adversarial review, and it is exactly the kind of scoping error that reads as correct in
        // isolation: the bound is right, it is just applied at the wrong level.
        java.util.ArrayDeque<Object[]> queue = new java.util.ArrayDeque<>();
        queue.add(new Object[]{startWorld, startPos, arrivedVia, startDepth});

        while (!queue.isEmpty() && sharedBudget[0] > 0) {
            Object[] frame = queue.poll();
            ServerLevel world = (ServerLevel) frame[0];
            Vec3 pos = (Vec3) frame[1];
            Portal via = (Portal) frame[2];
            int depth = (Integer) frame[3];

            if (depth > maxDepth) {
                continue;
            }

            List<Portal> portals = getNearbyPortals(
                world, pos, p -> p.broadcastToPlayer(player), indirectRangeChunks, 32
            );

            for (Portal inner : portals) {
                if (sharedBudget[0] <= 0) {
                    break;
                }
                // THE CYCLE GUARD. Do not walk back through the portal we just came out of.
                if (via != null && Portal.isReversePortal(inner, via)) {
                    continue;
                }
                // Resolve the destination BEFORE spending budget on a loader. A loader whose
                // dimension no longer exists makes ImmPtlChunkTracking abort the player's ENTIRE
                // tracking update, so a stale portal must not be able to emit one.
                Level innerDest = inner.getDestinationWorld();
                if (innerDest == null) {
                    continue;
                }
                func.accept(getGeneralPortalIndirectLoader(player, pos, inner));
                sharedBudget[0]--;

                if (depth + 1 <= maxDepth) {
                    queue.add(new Object[]{
                        (ServerLevel) innerDest, inner.transformPoint(pos), inner, depth + 1
                    });
                }
            }
        }
    }
    
    public static boolean isShrinkLoading() {
        return ServerPerformanceMonitor.getLevel() != PerformanceLevel.good;
    }
    
}
