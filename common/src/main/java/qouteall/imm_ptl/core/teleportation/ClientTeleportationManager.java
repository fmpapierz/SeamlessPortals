package qouteall.imm_ptl.core.teleportation;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ScaleUtils;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.collision.CollisionHelper;
import qouteall.imm_ptl.core.collision.PortalCollisionHandler;
import qouteall.imm_ptl.core.compat.GravityChangerInterface;
import qouteall.imm_ptl.core.ducks.IEAbstractClientPlayer;
import qouteall.imm_ptl.core.ducks.IEClientPlayNetworkHandler;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.ducks.IEParticleManager;
import qouteall.imm_ptl.core.network.ImmPtlNetworking;
import qouteall.imm_ptl.core.network.PacketRedirectionClient;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.imm_ptl.core.portal.animation.ClientPortalAnimationManagement;
import qouteall.imm_ptl.core.portal.animation.StableClientTimer;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.imm_ptl.core.render.context_management.FogRendererContext;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.Plane;
import qouteall.q_misc_util.my_util.Vec2d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

@Environment(EnvType.CLIENT)
public class ClientTeleportationManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static final Minecraft client = Minecraft.getInstance();
    
    public static long tickTimeForTeleportation = 0;
    private static long lastTeleportGameTime = 0;
    private static long teleportTickTimeLimit = 0;
    
    private static Vec3 lastPlayerEyePos = null;
    private static long lastRecordStableTickTime = 0;
    private static float lastRecordStablePartialTicks = 0;
    
    // for debug
    public static boolean isTeleportingTick = false;
    public static boolean isTeleportingFrame = false;
    public static boolean isTicking = false;
    
    private static final int teleportLimitPerFrame = 3;
    
    private static long teleportationCounter = 0;
    
    public static void init() {
        IPGlobal.POST_CLIENT_TICK_EVENT.register(
            ClientTeleportationManager::tick
        );
        
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(() -> {
            lastPlayerEyePos = null;
//            disableTeleportFor(2);
        });
    }
    
    private static void tick() {
        tickTimeForTeleportation++;
        changePlayerMotionIfCollidingWithPortal();
        
        isTeleportingTick = false;
    }
    
    public static void acceptSynchronizationDataFromServer(
        ResourceKey<Level> dimension,
        Vec3 pos,
        boolean forceAccept
    ) {
        if (!forceAccept) {
            if (isTeleportingFrequently()) {
                return;
            }
            // newly teleported by vanilla means
            if (client.player.tickCount < 200) {
                return;
            }
        }
        if (client.player.level().dimension() != dimension) {
            forceTeleportPlayer(dimension, pos);
        }
    }
    
    public static void manageTeleportation(boolean isTicking_) {
        if (IPGlobal.disableTeleportation) {
            return;
        }
        
        isTicking = isTicking_;
        
        teleportationCounter++;
        
        isTeleportingFrame = false;
        
        if (client.level == null || client.player == null) {
            lastPlayerEyePos = null;
            return;
        }
        
        // not initialized
        if (client.player.xo == 0 && client.player.yo == 0 && client.player.zo == 0) {
            return;
        }
        
        Profiler.get().push("ip_teleport");
        
        ClientPortalAnimationManagement.foreachCustomAnimatedPortals(
            portal -> {
                // update teleportation-related data
                PortalExtension.forClusterPortals(
                    portal, p -> p.animation.updateClientState(p, teleportationCounter)
                );
            }
        );

//        ClientPortalAnimationManagement.debugCheck();
        
        // the real partial ticks (not from stable timer)
        float realPartialTicks = RenderStates.getPartialTick();
        
        TeleportationUtil.Teleportation lastTeleportation = null;
        ResourceKey<Level> originalDim = client.player.level().dimension();
        
        if (lastPlayerEyePos != null) {
            for (int i = 0; i <= teleportLimitPerFrame; i++) {
                TeleportationUtil.Teleportation teleportation = tryTeleport(realPartialTicks);
                if (teleportation == null) {
                    break;
                }
                else {
                    lastTeleportation = teleportation;
                    if (i != 0) {
                        LOGGER.info("The client player made a combo-teleport");
                        if (i == teleportLimitPerFrame) {
                            // we should reject combo teleportation of too many layers
                            // if not, the player can escape a fractal scale box from the portal that overlaps its two sides
                            LOGGER.info("Combo teleport out of limit. Reject teleportation!");
                            Vec3 oldPos =
                                lastPlayerEyePos.subtract(McHelper.getEyeOffset(client.player));
                            forceTeleportPlayer(
                                originalDim,
                                oldPos.add(teleportation.worldSurfaceNormal().scale(-0.001))
                            );
                            client.player.setDeltaMovement(
                                teleportation.worldSurfaceNormal().scale(-0.1)
                            );
                            lastTeleportation = null;
                            break;
                        }
                    }
                }
            }
        }
        
        if (lastTeleportation != null) {
            if (PortalExtension.get(lastTeleportation.portal()).adjustPositionAfterTeleport) {
                adjustPlayerPosition(client.player);
            }
        }
        
        lastPlayerEyePos = getPlayerEyePos(realPartialTicks);
        lastRecordStableTickTime = StableClientTimer.getStableTickTime();
        lastRecordStablePartialTicks = StableClientTimer.getStablePartialTicks();
        
        Profiler.get().pop();
    }
    
    private static record TeleportationRec(
        Portal portal, Vec2d portalLocalXY, Vec3 collisionPos
    ) {}
    
    // return null if failed
    @Nullable
    private static TeleportationUtil.Teleportation tryTeleport(float partialTicks) {
        LocalPlayer player = client.player;
        assert player != null;
        
        Vec3 thisFrameEyePos = getPlayerEyePos(partialTicks);
        
        if (lastPlayerEyePos.distanceToSqr(thisFrameEyePos) > 1600) {
            // when the player is moving too fast, don't do teleportation
            return null;
        }
        
        assert client.level != null;
        long currentGameTime = client.level.getGameTime();
        
        Vec3 lastTickEyePos = McHelper.getLastTickEyePos(player);
        Vec3 thisTickEyePos = McHelper.getEyePos(player);
        
        ArrayList<TeleportationUtil.Teleportation> teleportationCandidates = new ArrayList<>();
        IPMcHelper.traverseNearbyPortals(
            player.level(),
            thisFrameEyePos,
            IPGlobal.maxNormalPortalRadius + 1,
            portal -> {
                if (!portal.canTeleportEntity(player)) {
                    return;
                }
                
                // Separately handle dynamic teleportation and static teleportation.
                // Although the dynamic teleportation code can handle static teleportation.
                // I want the dynamic teleportation bugs to not affect static teleportation.
                if (portal.animation.clientLastFramePortalStateCounter == teleportationCounter - 1
                    && portal.animation.clientLastFramePortalState != null
                    && portal.animation.lastTickAnimatedState != null
                    && portal.animation.thisTickAnimatedState != null
                ) {
                    // the portal is running a real animation
                    assert portal.animation.clientCurrentFramePortalState != null;
                    
                    TeleportationUtil.Teleportation teleportation =
                        TeleportationUtil.checkDynamicTeleportation(
                            portal,
                            portal.animation.clientLastFramePortalState,
                            portal.animation.clientCurrentFramePortalState,
                            lastPlayerEyePos,
                            thisFrameEyePos,
                            portal.animation.lastTickAnimatedState,
                            portal.animation.thisTickAnimatedState,
                            lastTickEyePos,
                            thisTickEyePos,
                            partialTicks
                        );
                    
                    if (teleportation != null) {
                        teleportationCandidates.add(teleportation);
                    }
                }
                else {
                    // the portal is static
                    TeleportationUtil.Teleportation teleportation =
                        TeleportationUtil.checkStaticTeleportation(
                            portal,
                            lastPlayerEyePos, thisFrameEyePos,
                            lastTickEyePos, thisTickEyePos
                        );
                    if (teleportation != null) {
                        teleportationCandidates.add(teleportation);
                    }
                }
            }
        );
        
        TeleportationUtil.Teleportation teleportation = teleportationCandidates
            .stream()
            .min(Comparator.comparingDouble(
                p -> p.worldCollisionPoint().distanceToSqr(lastPlayerEyePos)
            ))
            .orElse(null);
        
        
        if (teleportation != null) {
            Portal portal = teleportation.portal();
            Vec3 collidingPos = teleportation.worldCollisionPoint();
            
            Profiler.get().push("portal_teleport");
            teleportPlayer(teleportation, partialTicks);
            Profiler.get().pop();
            
            boolean allowOverlappedTeleport = portal.respectParallelOrientedPortal();
            
            // avoid teleporting through parallel portal due to floating point inaccuracy
            double adjustment = allowOverlappedTeleport ? -0.001 : 0.001;
            
            Vec3 newDelta = teleportation.newThisTickEyePos()
                .subtract(teleportation.newLastTickEyePos());
            
            lastPlayerEyePos = teleportation.teleportationCheckpoint()
                .add(newDelta.scale(adjustment));
            
            return teleportation;
        }
        else {
            return null;
        }
    }
    
    public static Vec3 getPlayerEyePos(float partialTick) {
        return client.player.getEyePosition(partialTick);
    }
    
    private static void teleportPlayer(
        TeleportationUtil.Teleportation teleportation, float partialTicks
    ) {
        Portal portal = teleportation.portal();
        
        if (tickTimeForTeleportation <= teleportTickTimeLimit) {
            Helper.log("Client player teleportation rejected");
            return;
        }
        
        lastTeleportGameTime = tickTimeForTeleportation;
        
        LocalPlayer player = client.player;
        Validate.isTrue(player != null);
        
        ResourceKey<Level> toDimension = portal.getDestDim();
        float partialTick = RenderStates.getPartialTick();
        
        Entity vehicle = player.getVehicle();
        Vec3 oldVehiclePos = vehicle != null ? vehicle.position() : null;

        // (e) DEFECT-B instrument, armed HERE rather than only in changePlayerDimension.
        // The 2026-08-01 round proved the first placement wrong: a SAME-DIM crossing never calls
        // changePlayerDimension (see the fromDimension != toDimension gate below), so the probe
        // recorded ZERO lines across 472 real crossings. This is the entry point BOTH topologies
        // share, so arming here covers same-dim and cross-dim alike.
        if (vehicle != null) {
            com.warwa.seamlessportals.passthrough.SeamRideProbe.armForCrossing(
                player.level().dimension().identifier().toString(),
                toDimension.identifier().toString(), vehicle);
        }

        Vec3 thisTickEyePos = McHelper.getEyePos(player);
        Vec3 lastTickEyePos = McHelper.getLastTickEyePos(player);
        
        Vec3 newThisTickEyePos = teleportation.newThisTickEyePos();
        Vec3 newLastTickEyePos = teleportation.newLastTickEyePos();
        
        ClientLevel fromWorld = client.level;
        ResourceKey<Level> fromDimension = fromWorld.dimension();
        
        if (fromDimension != toDimension) {
            ClientLevel toWorld = ClientWorldLoader.getWorld(toDimension);
            
            changePlayerDimension(player, fromWorld, toWorld, newThisTickEyePos);
        }
        
        McHelper.setEyePos(player, newThisTickEyePos, newLastTickEyePos);
        McHelper.updateBoundingBox(player);
        
        Vec3 oldRealVelocity = McHelper.getWorldVelocity(player);
        TransformationManager.managePlayerRotationAndChangeGravity(portal);
        McHelper.setWorldVelocity(player, oldRealVelocity); // reset velocity change
        
        TeleportationUtil.PortalPointVelocity portalPointVelocity = teleportation.portalPointVelocity();
        TeleportationUtil.transformEntityVelocity(portal, player, portalPointVelocity, thisTickEyePos);
        
        if (vehicle != null) {
            TeleportationUtil.transformEntityVelocity(portal, vehicle, portalPointVelocity, oldVehiclePos);
        }
        
        ScaleUtils.onClientPlayerTeleported(portal);
        
        player.connection.send(new ServerboundCustomPayloadPacket(
            new ImmPtlNetworking.TeleportPacket(
                PortalAPI.clientDimKeyToInt(fromDimension),
                thisTickEyePos,
                portal.getUUID()
            )
        ));
        
        PortalCollisionHandler.updateCollidingPortalAfterTeleportation(
            player, newThisTickEyePos, newLastTickEyePos, RenderStates.getPartialTick()
        );

        McHelper.adjustVehicle(player);

        if (vehicle != null) {
            // F5: the carry moved the cart whole — flip its collision bookkeeping WITH it.
            // The player got its refresh just above, but the cart kept the SOURCE portal's
            // stale entry (garbage clip + counterpart for 1-3 rendered frames) and gained
            // the dest-side entry only at the next POST_CLIENT_TICK sweep — the ridden
            // flavour of the live "back half of the cart" pop.
            PortalCollisionHandler.updateCollidingPortalAfterTeleportation(
                vehicle, McHelper.getEyePos(vehicle), McHelper.getEyePos(vehicle),
                RenderStates.getPartialTick()
            );
            qouteall.imm_ptl.core.render.CrossPortalEntityRenderer.onEntityTickClient(vehicle);
        }

        // (e) instrument: the CLIENT's post-crossing ride state, read after adjustVehicle — the
        // last thing the client mutates on this path. On the SAME-DIM path this is the ONLY
        // vehicle handling there is (no dismount, no recreate, no re-mount), so if the link is
        // already broken here the cause is upstream of the crossing entirely.
        com.warwa.seamlessportals.passthrough.SeamRideProbe.event("CLIENT-TELEPORT-DONE",
            "sameDim=" + (fromDimension == toDimension)
                + " vehicle=" + (player.getVehicle() == null
                    ? "NULL(dismounted)" : String.valueOf(player.getVehicle().getId()))
                + " playerPos=" + player.position()
                + " vehiclePos=" + (player.getVehicle() == null
                    ? "-" : player.getVehicle().position().toString()));

        //because the teleportation may happen before rendering
        //but after pre render info being updated
        RenderStates.updatePreRenderInfo(partialTick);
        
        if (teleportation.isDynamic()) {
            LOGGER.info(
                """
                    Client Teleported Dynamically
                    portal: {}
                    tickTime: {}
                    during ticking: {}
                    counter: {}
                    eye pos (by frame): {} -> {}
                    partial ticks: {}
                    new immediate eye pos: {}
                    portal origin/normal: {} {}
                    portal dest/content dir: {} {}""",
                portal, tickTimeForTeleportation, isTicking, teleportationCounter,
                teleportation.lastWorldEyePos(), teleportation.currentWorldEyePos(), partialTicks,
                teleportation.newLastTickEyePos().lerp(teleportation.newThisTickEyePos(), partialTick),
                portal.getOriginPos(), portal.getNormal(),
                portal.getDestPos(), portal.getContentDirection()
            );
        }
        else {
            LOGGER.info(
                """
                    Client Teleported Statically
                    portal: {}
                    eye pos: {} -> {}""",
                portal, teleportation.lastWorldEyePos(), teleportation.currentWorldEyePos()
            );
        }
        
        isTeleportingTick = true;
        isTeleportingFrame = true;
        
        MyGameRenderer.armVanillaTerrainSetupOverride(); // S14.9: + SOG frustum force (same-frame consumption, IP contract)
    }
    
    
    public static boolean isTeleportingFrequently() {
        return (tickTimeForTeleportation - lastTeleportGameTime <= 100) ||
            (tickTimeForTeleportation <= teleportTickTimeLimit);
    }
    
    public static void forceTeleportPlayer(ResourceKey<Level> toDimension, Vec3 destination) {
        LOGGER.info("client player force teleported {} {}", toDimension.identifier(), destination);
        
        ClientLevel fromWorld = client.level;
        assert fromWorld != null;
        ResourceKey<Level> fromDimension = fromWorld.dimension();
        LocalPlayer player = client.player;
        assert player != null;
        if (fromDimension != toDimension) {
            ClientLevel toWorld = ClientWorldLoader.getWorld(toDimension);
            Vec3 eyeOffset = McHelper.getEyeOffset(player);
            changePlayerDimension(player, fromWorld, toWorld, destination.add(eyeOffset));
        }
        
        player.setPos(destination.x, destination.y, destination.z);
        McHelper.adjustVehicle(player);
        
        lastPlayerEyePos = null;
        
        RenderStates.updatePreRenderInfo(RenderStates.getPartialTick());
        MyGameRenderer.armVanillaTerrainSetupOverride(); // S14.9: + SOG frustum force (same-frame consumption, IP contract)
    }
    
    /**
     * {@link ClientPacketListener#handleRespawn(ClientboundRespawnPacket)}
     */
    public static void changePlayerDimension(
        LocalPlayer player, ClientLevel fromWorld, ClientLevel toWorld, Vec3 newEyePos
    ) {
        // (e) DEFECT-B instrument: arm BEFORE the three preconditions below, deliberately. One
        // recon candidate for the user's "white/blank background" is precisely a SECOND
        // changePlayerDimension aborting on one of these Validates mid-cutover (client.level and
        // the renderer already re-pointed, gameRenderer.setLevel not yet) — armed here, the
        // window's own SAMPLE lines record that half-swapped state instead of going silent.
        com.warwa.seamlessportals.passthrough.SeamRideProbe.armForCrossing(
            fromWorld.dimension().identifier().toString(),
            toWorld.dimension().identifier().toString(),
            player.getVehicle());

        Validate.isTrue(!WorldRenderInfo.isRendering());
        Validate.isTrue(!FrontClipping.isClippingEnabled);
        Validate.isTrue(!PacketRedirectionClient.getIsProcessingRedirectedMessage());

        Entity vehicle = player.getVehicle();
        player.unRide();
        com.warwa.seamlessportals.passthrough.SeamRideProbe.event("UNRIDE",
            "heldVehicle=" + (vehicle == null ? "null" : String.valueOf(vehicle.getId()))
                + " playerVehicleNow="
                + (player.getVehicle() == null ? "null" : String.valueOf(player.getVehicle().getId())));
        
        ResourceKey<Level> toDimension = toWorld.dimension();
        ResourceKey<Level> fromDimension = fromWorld.dimension();
        
        ((IEClientPlayNetworkHandler) client.getConnection()).ip_setWorld(toWorld);
        
        fromWorld.removeEntity(player.getId(), Entity.RemovalReason.CHANGED_DIMENSION);
        
        ((IEEntity) player).ip_setWorld(toWorld);
        
        McHelper.setEyePos(player, newEyePos, newEyePos);
        McHelper.updateBoundingBox(player);
        
        ((IEEntity) player).ip_unsetRemoved();
        
        toWorld.addEntity(player);

        // IP 1.21.3 (ClientTeleportationManager:483): re-point the client player's level via
        // ((IEAbstractClientPlayer) player).ip_setClientLevel(toWorld), preserved verbatim in call order.
        // 26.2 two-field->one-field collapse: IP wrote AbstractClientPlayer.clientLevel here, a field DISTINCT
        // from Entity.level (written by ip_setWorld at the top of this method). On 26.2 AbstractClientPlayer
        // has no clientLevel; ip_setClientLevel re-sites onto Entity.level (MixinAbstractClientPlayer routes it
        // through ip_setWorld == `this.level = level;`), so this is now a harmless duplicate write of the same
        // pointer already set above. Kept (not dropped) so the IEAbstractClientPlayer duck stays IP-faithful
        // and non-caller-less, and IP's exact write sequence is preserved.
        ((IEAbstractClientPlayer) player).ip_setClientLevel(toWorld);

        IEGameRenderer gameRenderer = (IEGameRenderer) Minecraft.getInstance().gameRenderer;
        gameRenderer.ip_setLightmapTextureManager(ClientWorldLoader
            .getDimensionRenderHelper(toDimension).lightmapTexture);
        
        client.level = toWorld;
        ((IEMinecraftClient) client).ip_setWorldRenderer(
            ClientWorldLoader.getWorldRenderer(toDimension)
        );

        // S14-A FIX-1 (audit BLOCKER B1): on 26.2 the level+renderer swap above is NOT the
        // complete render cutover — the per-frame extract driver (the single global
        // mc.levelExtractor) and both worlds' extractor identities must flip too, or the main
        // view keeps extracting the SOURCE dim into the wrong renderer (see the helper's javadoc).
        ClientWorldLoader.promoteAndDemoteOnPlayerDimensionChange(fromWorld, toWorld);

        // S14.51 fix P — the PROMOTE-GAP PLUG (trace wf_1e07ce4b-f53 M2, HIGH): vanilla's
        // contract is poll+publish ADJACENT and BEFORE the frame's consume (ClientLevel.update()
        // runs pollLightUpdates then runLightUpdates, and renderFrame calls it BEFORE extract).
        // A render-side crossing (the frame pump) fires AFTER the OLD main's update(), so the
        // promote frame's forced wholesale extract batch-consumed every pending dirty mark of the
        // just-promoted dim against a one-frame-STALE published light store — and the next
        // frame's publish is SILENT for re-sent light corrections (queuedSections → only
        // changedSections; zero onLightUpdate callbacks) → mass permanent dark seams when
        // crossing during streaming. Run the pair the crossing frame skipped, HERE — after the
        // promote (routing is map-first on the just-re-pointed row; client.level already =
        // toWorld) and before the frame-N extract consumes anything. Guarded (verify fold): a
        // throwing light lambda here would otherwise abort changePlayerDimension between the
        // promote and the gameRenderer.setLevel below, stranding a half-cutover client —
        // swallow+log instead (vanilla's own drain is equally unguarded, but its crash doesn't
        // strand a teleport).
        try {
            toWorld.pollLightUpdates();
            toWorld.getChunkSource().getLightEngine().runLightUpdates();
        }
        catch (Throwable t) {
            LOGGER.error("promote-gap light drain failed", t);
        }

        // S14-A FIX-1 tail (audit MAJOR, link teleport): 26.2 re-expression of IP's implicit
        // per-frame camera-level refresh. 1.21.3 Camera.setup received minecraft.level every
        // frame, so IP's client.level write auto-propagated; 26.2 caches the level on the Camera
        // (mainCamera.setLevel) and selects entity cardinal lighting at dim-change time — both
        // live in GameRenderer.setLevel (mc262 GameRenderer.java:705-711, side-effect-light: no
        // mesh/render-state invalidation). Without it the main camera keeps the SOURCE ClientLevel
        // forever: wrong fog/sky/cloud attribute probe, no fluid-submersion fog, third-person
        // camera clipping against the old world, stale cardinal lighting.
        client.gameRenderer.setLevel(toWorld);

        if (client.particleEngine != null) {
            // avoid clearing all particles — IP-verbatim (IP ClientTeleportationManager:494-497
            // uses the ip_setWorld duck precisely because vanilla setLevel() clears all particles;
            // S14-A confirmed the port's withSwitchedWorld already uses the same duck).
            ((IEParticleManager) client.particleEngine).ip_setWorld(toWorld);
        }
        
        if (vehicle != null) {
            Vec3 offset = McHelper.getVehicleOffsetFromPassenger(vehicle, player);
            Vec3 vehiclePos = player.position().add(offset);
            // (e) instrument: dump BOTH offset forms in the same line, so the §0 A/B question
            // ("is the 2026-07-28 two-term attachment fix implicated in the ridden defect?") is
            // answered numerically without spending a separate launch on the lever. The two-term
            // form is what ships; the one-term form is what -PdisableSeamVehicleAttach restores.
            com.warwa.seamlessportals.passthrough.SeamRideProbe.event("CARRY-TERMS",
                "offsetUsed=" + offset
                    + " oneTerm=" + player.getVehicleAttachmentPoint(vehicle)
                    + " vehiclePassengerAttach="
                    + vehicle.getPassengerRidingPosition(player).subtract(vehicle.position())
                    + " playerPos=" + player.position()
                    + " vehiclePosTarget=" + vehiclePos);
            moveClientEntityAcrossDimension(
                vehicle, toWorld,
                vehiclePos
            );
            McHelper.setPosAndLastTickPos(
                vehicle,
                player.position().add(offset),
                McHelper.lastTickPosOf(player).add(offset)
            );
            // The boolean return is IP-discarded; capture it. Entity.startRiding returns false
            // early on !couldAcceptPassenger or !type.canSerialize (javap: 26.2 Entity.startRiding
            // (Entity,ZZ)Z), so a silent false here would produce exactly the reported symptom
            // with nothing in any existing log to show for it.
            boolean remounted = player.startRiding(vehicle, true, false);
            com.warwa.seamlessportals.passthrough.SeamRideProbe.onRemount(vehicle, remounted);
        }
        else {
            com.warwa.seamlessportals.passthrough.SeamRideProbe.event("NO-VEHICLE",
                "crossing carried no vehicle");
        }
        
        Helper.log(String.format(
            "Client Changed Dimension from %s to %s time: %s age: %s",
            fromDimension.identifier(),
            toDimension.identifier(),
            tickTimeForTeleportation,
            player.tickCount
        ));
        
        FogRendererContext.onPlayerTeleport(fromDimension, toDimension);
        
        O_O.onPlayerChangeDimensionClient(fromDimension, toDimension);
    }
    
    private static void changePlayerMotionIfCollidingWithPortal() {
        LocalPlayer player = client.player;
        
        Portal portal = ((IEEntity) player).ip_getCollidingPortal();
        
        if (portal != null) {
            if (PortalExtension.get(portal).motionAffinity > 0) {
                changeMotion(player, portal);
            }
            else if (PortalExtension.get(portal).motionAffinity < 0) {
                if (player.getDeltaMovement().length() > 0.7) {
                    changeMotion(player, portal);
                }
            }
        }
    }
    
    private static void changeMotion(Entity player, Portal portal) {
        Vec3 velocity = player.getDeltaMovement();
        player.setDeltaMovement(velocity.scale(1 + PortalExtension.get(portal).motionAffinity));
    }
    
    //foot pos, not eye pos
    public static void moveClientEntityAcrossDimension(
        Entity entity,
        ClientLevel newWorld,
        Vec3 newPos
    ) {
        ClientLevel oldWorld = (ClientLevel) entity.level();
        oldWorld.removeEntity(entity.getId(), Entity.RemovalReason.CHANGED_DIMENSION);
        ((IEEntity) entity).ip_setWorld(newWorld);
        entity.setPos(newPos.x, newPos.y, newPos.z);

        // (e) DEFECT B — REBASE THE RELATIVE-MOVE CODEC AND DROP THE STALE INTERPOLATION.
        //
        // Measured 2026-08-01, ridden cross-dim crossing, cart id=2396:
        //   t+0  cart placed in the nether at (40000.5, 68.0625, 0.710)      [this method]
        //   t+2  cart at (56.5, 68.0625, 8.910) via ClientPacketListener.handleMoveEntity
        //          < ClientboundMoveEntityPacket$Pos < PacketRedirectionClient.handleRedirectedPacket
        //   t+7  dragged back by handleEntityPositionSync, then lerped to (26586, …) by
        //          InterpolationHandler.interpolate < OldMinecartBehavior.tick — partway along the
        //          40,000-block gap. The player, riding it, is dragged with it: the reported
        //          "spazzing in place", ending stranded and only escapable with /kill.
        //
        // The packet was correctly redirected and the entity correctly resolved — the DECODE was
        // wrong. ClientboundMoveEntityPacket.Pos carries relative deltas, and the absolute position
        // comes from the entity's VecDeltaCodec base (javap: handleMoveEntity reads
        // Entity.getPositionCodec()). setPos does NOT touch that base, and vanilla only ever
        // rebases it from ABSOLUTE packets (add-entity / position-sync / teleport). So an entity
        // moved across dimensions here kept a base from the SOURCE dimension, and the destination
        // tracker's first relative move decoded to source coordinates.
        //
        // Why it bites the ridden path specifically: an entity crossing on its own is RECREATED on
        // the client from an absolute add-entity packet (fresh base), whereas a ridden vehicle is
        // MOVED as the existing client object by this method — and IP's own add-entity guard
        // (MixinClientPacketListener: skip when the existing entity has passengers) deliberately
        // cancels the very packet that would otherwise have rebased it. That is exactly why empty
        // and entity-ridden carts cross cleanly while a player-ridden cart does not.
        if (!com.warwa.seamlessportals.passthrough.AperturePassthroughLever
            .DISABLE_CROSS_DIM_POSITION_CODEC_SYNC) {
            entity.syncPacketPositionCodec(newPos.x, newPos.y, newPos.z);
            // A teleport must not be interpolated from where the entity used to be. Same rule
            // McHelper.adjustVehicle already applies at its own carry site; this method had no
            // equivalent, and the trace above shows the interpolation actively lerping across the
            // gap for several ticks after each bad packet.
            net.minecraft.world.entity.InterpolationHandler interpolation = entity.getInterpolation();
            if (interpolation != null) {
                interpolation.cancel();
            }
        }
        com.warwa.seamlessportals.passthrough.SeamRideProbe.recordCarryBaseDrift(
            entity, newPos.x, newPos.y, newPos.z);

        ((IEEntity) entity).ip_unsetRemoved();
        newWorld.addEntity(entity);
        Validate.isTrue(!entity.isRemoved());
    }
    
    public static void disableTeleportFor(int ticks) {
        teleportTickTimeLimit = tickTimeForTeleportation + ticks;
    }
    
    private static void adjustPlayerPosition(LocalPlayer player) {
        if (player.isSpectator()) {
            return;
        }
        
        if (player.getVehicle() != null) {
            return;
        }
        
        AABB playerBoundingBox = player.getBoundingBox();
        PortalCollisionHandler portalCollisionHandler = ((IEEntity) player).ip_getPortalCollisionHandler();
        List<Portal> collidingPortals = portalCollisionHandler == null ?
            Collections.emptyList() : portalCollisionHandler.getCollidingPortals();
        
        Direction gravityDir = GravityChangerInterface.invoker.getGravityDirection(player);
        Direction levitationDir = gravityDir.getOpposite();
        Vec3 eyeOffset = GravityChangerInterface.invoker.getEyeOffset(player);
        
        AABB bottomHalfBox = playerBoundingBox.contract(eyeOffset.x / 2, eyeOffset.y / 2, eyeOffset.z / 2);
        Function<VoxelShape, VoxelShape> shapeFilter = c -> {
            VoxelShape curr = c;
            for (Portal collidingPortal : collidingPortals) {
                Plane outerClipping = collidingPortal.getPortalShape()
                    .getOuterClipping(collidingPortal.getThisSideState());
                
                if (outerClipping != null) {
                    curr = CollisionHelper.clipVoxelShape(
                        curr, outerClipping.pos(), outerClipping.normal()
                    );
                    if (curr == null) {
                        return null;
                    }
                }
            }
            
            return curr;
        };
        
        AABB collisionUnion = CollisionHelper.getTotalBlockCollisionBox(
            player, bottomHalfBox, shapeFilter
        );
        
        if (collisionUnion == null) {
            return;
        }
        
        Vec3 anchor = player.position();
        AABB collisionUnionLocal = Helper.transformBox(
            collisionUnion, v -> GravityChangerInterface.invoker.transformWorldToPlayer(
                gravityDir, v.subtract(anchor)
            )
        );
        
        AABB playerBoxLocal = Helper.transformBox(
            playerBoundingBox, v -> GravityChangerInterface.invoker.transformWorldToPlayer(
                gravityDir, v.subtract(anchor)
            )
        );
        
        double targetLocalY = collisionUnionLocal.maxY + 0.01;
        double originalLocalY = playerBoxLocal.minY;
        double delta = targetLocalY - originalLocalY;
        
        if (delta <= 0) {
            return;
        }
        
        Vec3 levitationVec = Vec3.atLowerCornerOf(levitationDir.getUnitVec3i());
        
        Vec3 offset = levitationVec.scale(delta);
        
        final int ticks = 5;
        
        Helper.log("Adjusting Client Player Position");
        
        int[] counter = {0};
        IPGlobal.CLIENT_TASK_LIST.addTask(() -> {
            if (player.isRemoved()) {
                return true;
            }
            
            if (GravityChangerInterface.invoker.getGravityDirection(player) != gravityDir) {
                return true;
            }
            
            if (counter[0] >= 5) {
                return true;
            }
            
            counter[0]++;
            
            double len = player.position().subtract(anchor).dot(levitationVec);
            if (len < -1 || len > 2) {
                // stop early
                return true;
            }
            
            double progress = ((double) counter[0]) / ticks;
            progress = TransformationManager.mapProgress(progress);
            
            Vec3 expectedPos = anchor.add(offset.scale(progress));
            
            Vec3 newPos = Helper.putCoordinate(player.position(), levitationDir.getAxis(),
                Helper.getCoordinate(expectedPos, levitationDir.getAxis())
            );
            
            Portal currentCollidingPortal = ((IEEntity) player).ip_getCollidingPortal();
            if (currentCollidingPortal != null) {
                Vec3 eyePos = McHelper.getEyePos(player);
                Vec3 newEyePos = newPos.add(McHelper.getEyeOffset(player));
                if (currentCollidingPortal.rayTrace(eyePos, newEyePos) != null) {
                    return true; // avoid going back into the portal
                }
            }
            
            player.setPosRaw(newPos.x, newPos.y, newPos.z);
            McHelper.updateBoundingBox(player);
            
            return false;
        });
        
    }
    
    public static class RemoteCallables {
        // living entities do position interpolation
        // it may interpolate into unloaded chunks and stuck
        // avoid position interpolation
        public static void updateEntityPos(
            ResourceKey<Level> dim,
            int entityId,
            Vec3 pos,
            ResourceKey<Level> portalDim,
            int portalId
        ) {
            ClientLevel world = ClientWorldLoader.getWorld(dim);

            Entity entity = world.getEntity(entityId);

            if (entity == null) {
                Helper.err("cannot find entity to update position");
                return;
            }

            // F6 — REBASE, not snap, at a seam crossing (portalId != -1 iff the server took the
            // conserved-arrival path). The old snap+cancel froze the cart for a tick and dropped
            // the client's interpolation lag on the floor — the residual hiccup after F5. The
            // rebase maps EVERY piece of stored visual state (position, last-tick position, the
            // pending interpolation target, velocity) through the portal transform, so every
            // frame-to-frame delta is preserved exactly and the on-screen path is continuous
            // through the flip — the render-side analogue of the rs(e) VecDeltaCodec rebase
            // rule. The codec base still takes the server's authoritative position: subsequent
            // move deltas must decode against what the server actually uses.
            boolean rebased = false;
            qouteall.imm_ptl.core.portal.Portal crossingPortal = null;
            if (portalId != -1) {
                ClientLevel portalWorld = ClientWorldLoader.getWorld(portalDim);
                if (portalWorld != null
                    && portalWorld.getEntity(portalId)
                        instanceof qouteall.imm_ptl.core.portal.Portal p) {
                    crossingPortal = p;
                    Vec3 newCur = p.transformPoint(entity.position());
                    Vec3 newLast = p.transformPoint(McHelper.lastTickPosOf(entity));
                    McHelper.setPosAndLastTickPos(entity, newCur, newLast);
                    McHelper.updateBoundingBox(entity);
                    McHelper.setWorldVelocity(
                        entity, p.transformLocalVec(McHelper.getWorldVelocity(entity)));
                    InterpolationHandler interp = entity.getInterpolation();
                    if (interp != null && interp.hasActiveInterpolation()) {
                        interp.interpolateTo(
                            p.transformPoint(interp.position()), interp.yRot(), interp.xRot());
                    }
                    entity.getPositionCodec().setBase(pos);
                    com.warwa.seamlessportals.passthrough.SeamCartProbe.event(entity,
                        "REBASE via portal " + portalId + " visual=" + newCur
                            + " server=" + pos);
                    rebased = true;
                }
            }
            if (!rebased) {
                // both of them are important for Minecart
                entity.getPositionCodec().setBase(pos);
                entity.snapTo(pos, entity.getYRot(), entity.getXRot());
                InterpolationHandler interpolation = entity.getInterpolation();
                if (interpolation != null) {
                    interpolation.cancel();
                }
            }
            // F5/F6 (the live "back half of the cart" pop + the flip blink): the arrived
            // entity's render bracket must exist on its FIRST rendered frame, and it must be
            // the ARRIVAL-FACING face chosen by the CROSSING — the eye-side geometric sweep
            // picks the wrong co-located face (or none) while the rebased visual hasn't
            // emerged yet, which drew the whole object on the wrong side of the portal and
            // blanked it in portal views (2026-08-11 live). The grace registry keeps the
            // entry alive across the eye-side prune until the visual catches up.
            boolean seeded = false;
            if (rebased && crossingPortal != null) {
                qouteall.imm_ptl.core.portal.Portal arrivalFace =
                    qouteall.imm_ptl.core.portal.PortalManipulation
                        .findArrivalFacingPortal(crossingPortal);
                if (arrivalFace != null) {
                    ((qouteall.imm_ptl.core.ducks.IEEntity) entity).ip_clearCollidingPortal();
                    ((qouteall.imm_ptl.core.ducks.IEEntity) entity)
                        .ip_notifyCollidingWithPortal(arrivalFace);
                    // The straddle pin (SeamStraddleBracket, consulted by the prune and the
                    // register gates) now keeps this entry authoritative for exactly as long
                    // as the box straddles the plane — no grace timer needed.
                    seeded = true;
                    com.warwa.seamlessportals.passthrough.SeamCartProbe.event(entity,
                        "SNAP-SEED arrival-face=" + arrivalFace.getId());
                }
            }
            if (!seeded) {
                PortalCollisionHandler.updateCollidingPortalAfterTeleportation(
                    entity, McHelper.getEyePos(entity), McHelper.getEyePos(entity), 1
                );
                com.warwa.seamlessportals.passthrough.SeamCartProbe.event(entity,
                    "SNAP-SEED sweep colliding="
                        + ((qouteall.imm_ptl.core.ducks.IEEntity) entity)
                            .ip_isCollidingWithPortal());
            }
            qouteall.imm_ptl.core.render.CrossPortalEntityRenderer.onEntityTickClient(entity);
        }
    }
}
