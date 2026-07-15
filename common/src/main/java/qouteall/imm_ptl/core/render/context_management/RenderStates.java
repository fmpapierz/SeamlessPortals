package qouteall.imm_ptl.core.render.context_management;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.ducks.IEGameRenderer;
import qouteall.imm_ptl.core.miscellaneous.ClientPerformanceMonitor;
import qouteall.imm_ptl.core.mixin.client.particle.IEParticle;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.animation.StableClientTimer;
import qouteall.imm_ptl.core.render.ForceMainThreadRebuild;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.QueryManager;
import qouteall.q_misc_util.Helper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// S11-A port disposition: NEW (IP-add, current-mod-render §5). VERBATIM 1:1 from IP with three
// mechanical 26.2 renames (no logic change):
//   1. GameRenderer.getMainCamera() -> mainCamera() (GameRenderer.java:657, render-core C6) — 2 sites.
//   2. Camera.getPosition() -> position() (Camera.java:359) — onTotalRenderEnd.
//   3. Gui.setOverlayMessage(Component,boolean) moved to the split-out Hud (Hud.java:1225);
//      reached via the public field Gui.hud (Gui.java:72) — updateIsLaggy.
// The per-dim lightmap restore (onTotalRenderEnd) rides DimensionRenderHelper.lightmapTexture, now a
// 26.2 Lightmap (retyped this slice), pushed through IEGameRenderer.ip_setLightmapTextureManager
// (retyped LightTexture->Lightmap this slice, S04 port-note line 181). On 26.2 the actual per-dim
// bind is pull-based via GameRendererLightmapMixin (current-mod-render §3.2 KEEP); this explicit
// push is the IP-faithful restore hook and stays inert until S13 wires it.
// SIGN NOTE (D4.4): RenderStates only HOLDS matrices/positions (basicProjectionMatrix, cameraPosDelta)
// — it performs no depth/clip/winding math, so R5 reversed-Z does not touch this file.
// render-thread-logging discipline (memory render-thread-logging-log4j-stall): the LOGGER field is
// declared but never called per-frame; the only overlay text (setOverlayMessage) is rate-gated by the
// lag-attack heuristic, not per-frame.
// Forward-refs (documented debt, S11(b) ledger): MyRenderHelper (U9 same-stage),
// BlockManipulationClient (U11, S13), mixin.client.particle.IEParticle (S12), ForceMainThreadRebuild /
// QueryManager (U9 same-stage). Held/inert until S13.
public class RenderStates {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static int frameIndex = 0;

    public static ResourceKey<Level> originalPlayerDimension;
    public static Vec3 originalPlayerPos = Vec3.ZERO;
    public static Vec3 originalPlayerLastTickPos = Vec3.ZERO;
    public static GameType originalGameMode;
    public static AABB originalPlayerBoundingBox;

    /**
     * On the IP 1.21.3 baseline this was noted as not always equal to {@code Minecraft#getFrameTime()}
     * (26.2: the frame delta now lives on {@code DeltaTracker}); it will be 0 right after ticking.
     */
    private static float partialTick = 0;

    public static Set<ResourceKey<Level>> renderedDimensions = new HashSet<>();
    public static List<List<WeakReference<Portal>>> lastPortalRenderInfos = new ArrayList<>();
    public static List<List<WeakReference<Portal>>> portalRenderInfos = new ArrayList<>();
    public static int portalsRenderedThisFrame = 0;// mixins to sodium use that

    public static Vec3 lastCameraPos = Vec3.ZERO;
    public static Vec3 cameraPosDelta = Vec3.ZERO;

    public static boolean shouldForceDisableCull = false;

    public static long renderStartNanoTime;

    public static double viewBobFactor;

    public static Matrix4f basicProjectionMatrix;

    public static Camera originalCamera;

    public static String debugText;

    public static boolean isLaggy = false;

    public static boolean isRenderingEntities = false;

    public static boolean renderedScalingPortal = false;

    public static boolean isRenderingPortalWeather = false;

    public static void updatePreRenderInfo(
        float newPartialTick
    ) {
        ClientWorldLoader.initializeIfNeeded();

        // 26.2: Minecraft.cameraEntity FIELD is gone; only getCameraEntity() remains
        // (mc262-ref Minecraft.java:2648). Field->getter translation (R-render category-c).
        Entity cameraEntity = MyRenderHelper.client.getCameraEntity();

        if (cameraEntity == null) {
            return;
        }

        originalPlayerDimension = cameraEntity.level().dimension();
        originalPlayerPos = cameraEntity.position();
        originalPlayerLastTickPos = McHelper.lastTickPosOf(cameraEntity);
        PlayerInfo entry = CHelper.getClientPlayerListEntry();
        originalGameMode = entry != null ? entry.getGameMode() : GameType.CREATIVE;
        partialTick = newPartialTick;

        renderedDimensions.clear();
        lastPortalRenderInfos = portalRenderInfos;
        portalRenderInfos = new ArrayList<>();
        portalsRenderedThisFrame = 0;

        FogRendererContext.update();

        renderStartNanoTime = System.nanoTime();

        updateViewBobbingFactor(cameraEntity);

        basicProjectionMatrix = null;
        // 26.2: GameRenderer.getMainCamera() renamed to mainCamera() (GameRenderer.java:657).
        originalCamera = MyRenderHelper.client.gameRenderer.mainCamera();

        updateIsLaggy();

        ForceMainThreadRebuild.onPreRender();

        debugText = "";
//        debugText = originalCamera.getPos().toString();

        QueryManager.queryStallCounter = 0;

        Vec3 velocity = McHelper.getWorldVelocity(cameraEntity);
        originalPlayerBoundingBox = cameraEntity.getBoundingBox().expandTowards(
            -velocity.x, -velocity.y, -velocity.z
        );
    }

    //protect the player from mirror room lag attack
    private static void updateIsLaggy() {
        if (!IPGlobal.lagAttackProof) {
            isLaggy = false;
            return;
        }
        if (isLaggy) {
            if (ClientPerformanceMonitor.getMinimumFps() > 15) {
                isLaggy = false;
            }
        }
        else {
            if (lastPortalRenderInfos.size() > 10) {
                if (ClientPerformanceMonitor.getAverageFps() < 8 || ClientPerformanceMonitor.getMinimumFps() < 6) {
                    // 26.2: Gui.setOverlayMessage moved to the split-out Hud (Hud.java:1225),
                    // reached via the public field Gui.hud (Gui.java:72).
                    MyRenderHelper.client.gui.hud.setOverlayMessage(
                        Component.translatable("imm_ptl.laggy"),
                        false
                    );
                    isLaggy = true;
                }
            }
        }
    }

    private static void updateViewBobbingFactor(Entity cameraEntity) {
//        if (!IrisInterface.invoker.isIrisPresent()) {
//            if (renderedScalingPortal) {
//                setViewBobFactor(0);
//                renderedScalingPortal = false;
//                return;
//            }
//        }

        Vec3 cameraPosVec = cameraEntity.getEyePosition(getPartialTick());
        double minPortalDistance = CHelper.getClientNearbyPortals(16)
            .map(portal -> portal.getDistanceToNearestPointInPortal(cameraPosVec))
            .min(Double::compareTo).orElse(100.0);
        if (minPortalDistance < 2) {
            if (minPortalDistance < 1) {
                setViewBobFactor(0);
            }
            else {
                setViewBobFactor(minPortalDistance - 1);
            }
        }
        else {
            setViewBobFactor(1);
        }
    }

    public static double getViewBobbingOffsetMultiplier() {
        if (!IPGlobal.viewBobbingReduce) {
            return 1;
        }

        if (!WorldRenderInfo.isViewBobbingEnabled()) {
            return 0;
        }

        double allScaling = PortalRendering.getExtraModelViewScaling();

        return viewBobFactor * allScaling;
    }

    private static void setViewBobFactor(double arg) {
        if (arg < viewBobFactor) {
            viewBobFactor = arg;
        }
        else {
            viewBobFactor = Mth.lerp(0.1, viewBobFactor, arg);
        }
    }

    public static void onTotalRenderEnd() {
        Minecraft client = Minecraft.getInstance();
        IEGameRenderer gameRenderer = (IEGameRenderer) Minecraft.getInstance().gameRenderer;
        gameRenderer.ip_setLightmapTextureManager(ClientWorldLoader
            .getDimensionRenderHelper(client.level.dimension()).lightmapTexture);

        // 26.2: GameRenderer.getMainCamera() -> mainCamera() (GameRenderer.java:657);
        // Camera.getPosition() -> position() (Camera.java:359).
        Vec3 currCameraPos = client.gameRenderer.mainCamera().position();
        cameraPosDelta = currCameraPos.subtract(lastCameraPos);
        if (cameraPosDelta.lengthSqr() > 1) {
            cameraPosDelta = Vec3.ZERO;
        }
        lastCameraPos = currCameraPos;


    }

    public static int getRenderedPortalNum() {
        return portalRenderInfos.size();
    }

    public static boolean isDimensionRendered(ResourceKey<Level> dimensionType) {
        if (dimensionType == originalPlayerDimension) {
            return true;
        }
        return renderedDimensions.contains(dimensionType);
    }

    public static boolean shouldRenderParticle(Particle particle) {
        if (((IEParticle) particle).portal_getWorld() != Minecraft.getInstance().level) {
            return false;
        }
        if (PortalRendering.isRendering()) {
            Portal renderingPortal = PortalRendering.getRenderingPortal();
            Vec3 particlePos = particle.getBoundingBox().getCenter();
            return renderingPortal.isOnDestinationSide(particlePos, 0.5);
        }
        return true;
    }

    public static void setPartialTick(float partialTick_) {
        partialTick = partialTick_;
    }

    /**
     * This does not always equal the {@code DeltaTracker} frame delta.
     * It will be 0 right after ticking.
     */
    public static float getPartialTick() {
        return partialTick;
    }

    public static List<String> collectDebugText() {
        List<String> result = new ArrayList<>();
        result.add("Rendered Portals: " + lastPortalRenderInfos.size());

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            Portal collidingPortal = ((IEEntity) player).ip_getCollidingPortal();
            if (collidingPortal != null) {
                String text = "Colliding " + collidingPortal.toString();
                result.addAll(Helper.splitStringByLen(text, 50));
            }
        }

        result.add("Occlusion Query Stall: " + QueryManager.queryStallCounter);
        result.add("Client Perf %s %d %d".formatted(
            ClientPerformanceMonitor.level,
            ClientPerformanceMonitor.getAverageFps(),
            ClientPerformanceMonitor.getAverageFreeMemoryMB()
        ));

        result.add(StableClientTimer.getDebugString());

        String blockPointingInfo = BlockManipulationClient.getDebugString();
        if (blockPointingInfo != null) {
            result.add(blockPointingInfo);
        }

        if (debugText != null && !debugText.isEmpty()) {
            result.addAll(Helper.splitStringByLen(
                "Debug: " + debugText,
                50
            ));
        }

        return result;
    }
}
