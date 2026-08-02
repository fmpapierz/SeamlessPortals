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

    /**
     * S13-M Finding B (view-bob "window head-bob" fix). The finalized MAIN-pass DRAW projection
     * {@code base * bob * spin}, captured POST-spin at {@code GameRenderer.renderLevel}'s final
     * projection upload (26.2 renderLevel:557, {@code levelProjectionMatrixBuffer.getBuffer(projectionMatrix)}
     * — AFTER both the bob multiply (:542) AND the nausea/portal spin skew (:547-554)). This is the
     * TRUE ambient the main pass rasterizes with — the 26.2 re-expression of the value IP's
     * {@code RenderSystem.getProjectionMatrix()} returned during the main pass.
     *
     * <p>S13-M P2 correction: the earlier capture was PRE-spin (at the :542 bob multiply), on the FALSE
     * premise that "IP's dest excluded the spin". IP's dest re-enters the FULL {@code renderLevel}
     * ({@code IP:MyGameRenderer:231}) which applies the SAME nausea/portal spin at frame-identical
     * intensity, so IP's dest is {@code base*bob*spin} exactly like its main pass. Capturing POST-spin
     * matches it (in normal play spin==0, so this equals {@code base*bob} — nothing changes there).
     *
     * <p>{@link #getPortalDrawProjection} derives the portal-view DRAW projection from this (scaling the
     * bob TRANSLATION by the pass's {@link PortalRendering#getExtraModelViewScaling()} so content at dest
     * eye-depth {@code s*z} bobs on-screen in lock with the portal-plane aperture — S13-M P3), and
     * {@code PortalRenderer.getCurrentProjectionMatrix()} returns that for the stencil aperture / depth
     * restore / cull frustum (S13-M P1) so the aperture, the dest content, and the frame all bob together.
     * Written each main frame by {@code MixinGameRenderer} (woven flag-ON only, so flag-OFF is untouched);
     * {@code null} until the first capture, where {@link #getPortalDrawProjection} falls back to the
     * unbobbed extract-time projection.
     */
    public static Matrix4f capturedMainPassBobbedProjection;

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
            else {
                // KEEP SAYING IT WHILE IT IS TRUE. This is the whole fix: the notice used to be
                // shown ONCE, on the false->true transition, and vanilla's overlay message expires
                // after about 3 seconds — while the clamp itself stays on indefinitely. So the
                // player saw a brief flash and then had portal recursion silently pinned to ONE
                // layer with nothing on screen to say why. USER-REPORTED after losing time to
                // exactly that: recursion looked broken, and the cause was this protection firing.
                showLaggyNotice(false);
            }
        }
        else {
            if (lastPortalRenderInfos.size() > 10) {
                if (ClientPerformanceMonitor.getAverageFps() < 8 || ClientPerformanceMonitor.getMinimumFps() < 6) {
                    isLaggy = true;
                    // Animated on the FIRST show only — vanilla's animate flag makes the text pulse,
                    // which is what catches the eye at the moment the clamp engages. The repeats
                    // below are steady, because a permanently pulsing message is harder to read than
                    // a still one and this may now stay up for a long time.
                    showLaggyNotice(true);
                }
            }
        }
    }

    private static long lastLaggyNoticeMs = 0L;

    /**
     * Re-issues the "rendering fewer portals" overlay while the lag clamp is engaged.
     *
     * <p>Repeat interval is under vanilla's ~60-tick overlay lifetime so the message never blinks
     * out mid-clamp; the {@code animate} flag is reserved for the first show. Red + bold because the
     * default styling reads as an incidental status line, and this one is reporting that a feature
     * the player configured has been overridden.
     *
     * <p>26.2: {@code Gui.setOverlayMessage} moved to the split-out {@code Hud} (Hud.java:1225),
     * reached via the public field {@code Gui.hud} (Gui.java:72).
     */
    private static void showLaggyNotice(boolean animate) {
        long now = System.currentTimeMillis();
        if (!animate && now - lastLaggyNoticeMs < 2000L) {
            return;
        }
        lastLaggyNoticeMs = now;
        try {
            MyRenderHelper.client.gui.hud.setOverlayMessage(
                Component.translatable("imm_ptl.laggy")
                    .withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD),
                animate
            );
        }
        catch (Throwable ignored) {
            // A HUD notice must never be able to break the render path it is reporting on.
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

    /**
     * S13-M Finding B (P1/P3) — the portal-view DRAW projection for a pass whose combined portal
     * scaling is {@code extraScaling} (= {@link PortalRendering#getExtraModelViewScaling()} at that
     * layer). The 26.2 re-expression of IP's ambient {@code RenderSystem.getProjectionMatrix()} as seen
     * INSIDE a portal pass: IP re-enters {@code renderLevel} for the dest, and its A2 bob ModifyArg scales
     * the walk-bob translate by {@code viewBobFactor * getExtraModelViewScaling()} (see
     * {@link #getViewBobbingOffsetMultiplier()} + {@link PortalRendering#getExtraModelViewScaling()} — a
     * NON-fuse scaling portal IS in the product), the exact depth-compensation for content at dest
     * eye-depth {@code s*z}: on-screen shift = t/z, so a scale-{@code s} window (content at {@code s*z})
     * needs bob translate {@code s*t} to shift by the same t/z as the portal-plane aperture.
     *
     * <p>We do NOT re-enter {@code renderLevel}; instead we start from the captured POST-spin main-pass
     * projection {@code Pfinal = Pbase * B * SPIN} ({@link #capturedMainPassBobbedProjection}) and scale
     * ONLY the bob TRANSLATION by {@code s}. {@code SPIN} carries no translation, so the 4th (translation)
     * column of {@code B*SPIN} equals {@code B}'s (= {@code R_hurt·t}, IP's ModifyArg target); scaling
     * {@code B}'s translation column by {@code s} is exactly IP's ModifyArg. Column algebra (with the
     * affine bob column's homogeneous {@code w=1}):
     * {@code col3(Pbase * B_scaled * SPIN) = s·col3(Pfinal) + (1-s)·col3(Pbase)}; every other column is
     * unchanged. {@code s==1} (no scaling / a fuse-view portal, whose scale is baked into the model-view
     * per {@code PortalRenderer.shouldApplyScaleToModelView} and so is EXCLUDED from the product) returns
     * {@code Pfinal} bit-unchanged. Always a fresh copy (callers install/mutate it).
     *
     * @param baseProjection the pass's UNBOBBED base projection ({@code Pbase} — the extract-time
     *                       {@code cameraRenderState.projectionMatrix} that {@code Pfinal} was built from)
     * @param extraScaling   {@link PortalRendering#getExtraModelViewScaling()} for the pass
     */
    public static Matrix4f getPortalDrawProjection(Matrix4f baseProjection, double extraScaling) {
        Matrix4f postSpinBobbed = capturedMainPassBobbedProjection;
        if (postSpinBobbed == null) {
            return baseProjection != null ? new Matrix4f(baseProjection) : new Matrix4f();
        }
        Matrix4f result = new Matrix4f(postSpinBobbed);
        if (extraScaling != 1.0 && baseProjection != null) {
            float s = (float) extraScaling;
            float oneMinusS = 1.0f - s;
            result.m30(s * postSpinBobbed.m30() + oneMinusS * baseProjection.m30());
            result.m31(s * postSpinBobbed.m31() + oneMinusS * baseProjection.m31());
            result.m32(s * postSpinBobbed.m32() + oneMinusS * baseProjection.m32());
            result.m33(s * postSpinBobbed.m33() + oneMinusS * baseProjection.m33());
        }
        return result;
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
