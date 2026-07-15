package qouteall.imm_ptl.core.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.warwa.seamlessportals.mixin.client.GameRendererAccessorMixin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.util.profiling.Profiler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.miscellaneous.ClientPerformanceMonitor;
import qouteall.imm_ptl.core.portal.animation.ClientPortalAnimationManagement;
import qouteall.imm_ptl.core.portal.animation.StableClientTimer;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;

/**
 * S12-B port disposition: NEEDS-RETARGET (mixin-client.md §1) — the MULTIWORLD-half {@code MixinMinecraft}:
 * the ClientWorldLoader lifecycle + teleportation tick driver + the IEMinecraftClient duck (render/buffer/
 * screen/thread swap surface).
 *
 * <p><b>Two IP handlers moved OFF this mixin</b> because their 26.2 targets left {@code Minecraft} (the
 * render slice owns them):
 * <ul>
 *   <li>IP ⑥ fabulous {@code useShaderTransparency} HEAD-cancel (R13i) -> re-anchored onto
 *       {@code GameRenderState.useShaderTransparency()} in the render-slice mixin
 *       {@code render/MixinGameRenderState} ({@code useShaderTransparency} is GONE from {@code Minecraft}).</li>
 *   <li>IP ⑦ {@code addInitialScreens} @RETURN -> {@code addInitialScreens} moved to {@code Gui}
 *       (private, {@code 26.2:Gui.java:381}); re-expressed in the sibling multiworld mixin
 *       {@code MixinGui}.</li>
 * </ul>
 *
 * <p><b>Duck retargets (fields moved off {@code Minecraft} onto {@code GameRenderer} in 26.2):</b>
 * {@code mainRenderTarget}/{@code renderBuffers} now live on {@code GameRenderer}
 * ({@code 26.2:GameRenderer.java:104,103}); {@code ip_setFrameBuffer}/{@code ip_setRenderBuffers} route
 * through the mod's {@code GameRendererAccessorMixin} setters (the F12 loader-seam pattern already used by
 * {@code FogRendererContext}). {@code screen} is now {@code this.gui.screen()}
 * ({@code 26.2:Gui.java:218}); {@code levelRenderer} stays on {@code Minecraft} (public final ->
 * {@code @Mutable}); {@code gameThread} unchanged. {@code Minecraft.getProfiler()} is GONE ->
 * {@code Profiler.get()} (static). Held/unregistered until S13.
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft implements IEMinecraftClient {
    @Mutable
    @Shadow
    @Final
    public LevelRenderer levelRenderer;

    @Shadow
    @Final
    public GameRenderer gameRenderer;

    @Shadow
    @Final
    public Gui gui;

    @Shadow
    private static int fps;

    @Shadow
    @Nullable
    public ClientLevel level;

    @Shadow
    @Final
    private static Logger LOGGER;

    @Shadow
    private Thread gameThread;

    @WrapOperation(
        method = "Lnet/minecraft/client/Minecraft;run()V",
        at = @At(
            value = "INVOKE",
            target = "Ljava/lang/Thread;currentThread()Ljava/lang/Thread;"
        )
    )
    private Thread testMixinExtra(Operation<Thread> original) {
        LOGGER.info("[ImmPtl] MixinExtra is working!");
        return original.call();
    }

    /**
     * The whole process involving portal animation and teleportation:
     * - begin ticking
     * <p>
     * - tick entities:
     * - set last tick pos to current pos
     * - do collision calculation for movements, update current pos
     * - portal.animation.lastTickAnimatedState = thisTickAnimatedState, thisTickAnimatedState = null
     * - increase game time
     * - end ticking
     * - partialTick should be 0
     * - update portal animation (set thisTickAnimatedState as 1 tick later)
     * - manage teleportation (right after updating portal animation)
     * - rendering (interpolate between last tick pos and current pos)
     * Note: the camera position is always behind the current tick position
     * - partialTick should be 1
     * - loop
     */
    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V"
        )
    )
    private void onBeforeTickingEntities(CallbackInfo ci) {
//        RenderStates.tickDelta = 1;
//        StableClientTimer.update(level.getGameTime(), RenderStates.tickDelta);
//        ClientPortalAnimationManagement.update();
//        IPCGlobal.clientTeleportationManager.manageTeleportation(true);
    }

    // this happens after ticking client world and entities
    @Inject(
        method = "Lnet/minecraft/client/Minecraft;tick()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;tick(Ljava/util/function/BooleanSupplier;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterClientTick(CallbackInfo ci) {
        // 26.2: Minecraft.getProfiler() is GONE -> static Profiler.get() (matches FogRendererContext).
        Profiler.get().push("imm_ptl_client_tick");

        // including ticking remote worlds
        ClientWorldLoader.tick();

        RenderStates.setPartialTick(0);
        StableClientTimer.tick();
        StableClientTimer.update(level.getGameTime(), RenderStates.getPartialTick());
        ClientPortalAnimationManagement.tick(); // must be after remote world ticking
        ClientTeleportationManager.manageTeleportation(true);

        IPGlobal.POST_CLIENT_TICK_EVENT.invoker().run();

        Profiler.get().pop();
    }

    @Inject(
        method = "Lnet/minecraft/client/Minecraft;runTick(Z)V",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/Minecraft;fps:I",
            shift = At.Shift.AFTER
        )
    )
    private void onSnooperUpdate(boolean tick, CallbackInfo ci) {
        ClientPerformanceMonitor.updateEverySecond(fps);
    }

    // 26.2 translation (pinned "2-arg updateLevelInEngines" corpus trap): IP 1.21.3 had ONE overload;
    // 26.2 split it — the 1-arg updateLevelInEngines(ClientLevel) (:2191) merely delegates to the 2-arg
    // updateLevelInEngines(ClientLevel, boolean stopSound) (:2192,:2195). setLevel (:2061) and
    // clearClientLevel (:2177) route through the 1-arg wrapper, but Minecraft.disconnect/kick/server-stop
    // teardown calls the 2-arg DIRECTLY (this.updateLevelInEngines(null, stopSound), :2146), BYPASSING the
    // 1-arg wrapper. Hooking the 1-arg would silently miss the kick/connection-loss path -> IP's
    // CLIENT_CLEANUP/CLIENT_EXIT/ClientWorldLoader.cleanUp never fire on kick, and the next join runs on
    // stale cached client worlds. The 2-arg is the single funnel for all three paths (the mod's own live
    // substrate MinecraftMixin hooks it for exactly this reason), and each fires it once, so retargeting
    // here covers setLevel + disconnect + clearClientLevel with no double-fire. IP LOGIC UNCHANGED.
    @Inject(
        method = "Lnet/minecraft/client/Minecraft;updateLevelInEngines(Lnet/minecraft/client/multiplayer/ClientLevel;Z)V",
        at = @At("HEAD")
    )
    private void onSetWorld(ClientLevel clientLevel, boolean stopSound, CallbackInfo ci) {
        if (ClientWorldLoader.getIsInitialized()) {
            LOGGER.info("Client cleanup");
            IPCGlobal.CLIENT_CLEANUP_EVENT.invoker().run();

            if (clientLevel == null) {
                LOGGER.info("Client exit world");
                IPCGlobal.CLIENT_EXIT_EVENT.invoker().run();
            }

            ClientWorldLoader.cleanUp();
        }
        else {
            LOGGER.info("Client world updated but not counted as cleanup");
        }
    }

    @Override
    public void ip_setFrameBuffer(RenderTarget buffer) {
        // 26.2: mainRenderTarget moved Minecraft -> GameRenderer; set via the mod's accessor (F12 seam).
        ((GameRendererAccessorMixin) gameRenderer).seamlessportals$setMainRenderTarget(buffer);
    }

    @Override
    public Screen ip_getCurrentScreen() {
        // 26.2: Minecraft.screen field -> this.gui.screen() (Gui.java:218).
        return gui.screen();
    }

    @Override
    public void ip_setWorldRenderer(LevelRenderer r) {
        levelRenderer = r;
    }

    @Override
    public void ip_setRenderBuffers(RenderBuffers arg) {
        // 26.2: renderBuffers moved Minecraft -> GameRenderer; set via the mod's accessor (F12 seam).
        ((GameRendererAccessorMixin) gameRenderer).seamlessportals$setRenderBuffers(arg);
    }

    @Override
    public Thread ip_getRunningThread() {
        return gameThread;
    }
}
