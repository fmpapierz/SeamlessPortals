package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.WorldBorderRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.client.renderer.debug.GameTestBlockHighlightRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.client.renderer.state.level.WorldBorderRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.SecondaryWorldRenderCore;

/**
 * S14.40 attribution kit (default OFF, S20-removal-ledgered): per-sub-call skip levers inside
 * {@code LevelExtractor.extract(...)}, active ONLY while the dest-pass extract runs
 * ({@link SecondaryWorldRenderCore#isDestExtracting}) — the MAIN extract is bytecode-behavior
 * identical with every lever off, and identical regardless of levers since the gate is false there.
 *
 * <p><b>Why:</b> the live rung-2 wedge bisection proved the corruptor is the dest
 * {@code extract()} call itself; the confirmed shared-state writer is the mid-frame
 * {@code ParticleEngine.extract} (fixed in {@code MixinParticleEngine}, mechanism documented
 * there). Per the no-guessing rule, every OTHER shared/global write inside {@code extract()} gets
 * its own lever so a residual defect attributes in ONE session instead of a new code round:
 * dispatcher {@code prepare} re-aims (BERD is NOT restored by the shell; ERD is), the shared-
 * instance weather/sky/border extracts, entity/block-entity extraction (mutates
 * {@code Entity.xOld/setViewScale}), and the gizmo family ({@code Minecraft.getPerTickGizmos()}
 * drain into the secondary renderer). Enumeration source: 26.2 {@code LevelExtractor.java:95-219}
 * — extract() touches ZERO RenderSystem/GL state, so these Java-side writes are the complete
 * candidate set.
 */
@Mixin(LevelExtractor.class)
public class MixinLevelExtractor_DestSubLevers {

    @WrapOperation(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;"
                + "prepare(Lnet/minecraft/world/phys/Vec3;)V"
        )
    )
    private void ip_leverBerdPrepare(
        BlockEntityRenderDispatcher instance, Vec3 cameraPos, Operation<Void> original
    ) {
        if (SecondaryWorldRenderCore.isDestExtracting && IPGlobal.debugSkipExtractBerdPrepare) {
            return;
        }
        original.call(instance, cameraPos);
    }

    @WrapOperation(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;"
                + "prepare(Lnet/minecraft/client/Camera;Lnet/minecraft/world/entity/Entity;)V"
        )
    )
    private void ip_leverErdPrepare(
        EntityRenderDispatcher instance, Camera camera, Entity crosshairPickEntity, Operation<Void> original
    ) {
        if (SecondaryWorldRenderCore.isDestExtracting && IPGlobal.debugSkipExtractErdPrepare) {
            return;
        }
        original.call(instance, camera, crosshairPickEntity);
    }

    @WrapOperation(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractVisibleEntities"
                + "(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;"
                + "Lnet/minecraft/client/DeltaTracker;"
                + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"
        )
    )
    private void ip_leverExtractEntities(
        LevelExtractor instance, Camera camera, Frustum frustum, DeltaTracker deltaTracker,
        LevelRenderState output, Operation<Void> original
    ) {
        if (SecondaryWorldRenderCore.isDestExtracting && IPGlobal.debugSkipExtractEntities) {
            return;
        }
        original.call(instance, camera, frustum, deltaTracker, output);
    }

    @WrapOperation(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractVisibleBlockEntities"
                + "(Lnet/minecraft/client/Camera;F"
                + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V"
        )
    )
    private void ip_leverExtractBlockEntities(
        LevelExtractor instance, Camera camera, float deltaPartialTick, LevelRenderState output,
        Operation<Void> original
    ) {
        if (SecondaryWorldRenderCore.isDestExtracting && IPGlobal.debugSkipExtractBlockEntities) {
            return;
        }
        original.call(instance, camera, deltaPartialTick, output);
    }

    @WrapOperation(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/WeatherEffectRenderer;extractRenderState"
                + "(Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/world/phys/Vec3;"
                + "Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V"
        )
    )
    private void ip_leverExtractWeather(
        WeatherEffectRenderer instance, ClientLevel level, float partialTicks, Vec3 cameraPos,
        WeatherRenderState renderState, Operation<Void> original
    ) {
        if (SecondaryWorldRenderCore.isDestExtracting && IPGlobal.debugSkipExtractWeather) {
            return;
        }
        original.call(instance, level, partialTicks, cameraPos, renderState);
    }

    @WrapOperation(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SkyRenderer;extractRenderState"
                + "(Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/client/Camera;"
                + "Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V"
        )
    )
    private void ip_leverExtractSky(
        SkyRenderer instance, ClientLevel level, float partialTicks, Camera camera,
        SkyRenderState state, Operation<Void> original
    ) {
        if (SecondaryWorldRenderCore.isDestExtracting && IPGlobal.debugSkipExtractSky) {
            return;
        }
        original.call(instance, level, partialTicks, camera, state);
    }

    @WrapOperation(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/WorldBorderRenderer;extract"
                + "(Lnet/minecraft/world/level/border/WorldBorder;FLnet/minecraft/world/phys/Vec3;D"
                + "Lnet/minecraft/client/renderer/state/level/WorldBorderRenderState;)V"
        )
    )
    private void ip_leverExtractBorder(
        WorldBorderRenderer instance, WorldBorder border, float deltaPartialTick, Vec3 cameraPos,
        double renderDistance, WorldBorderRenderState state, Operation<Void> original
    ) {
        if (SecondaryWorldRenderCore.isDestExtracting && IPGlobal.debugSkipExtractBorder) {
            return;
        }
        original.call(instance, border, deltaPartialTick, cameraPos, renderDistance, state);
    }

    // ===== S14.41: the gizmo family — SKIP IS THE DEFAULT for the dest-pass extract (the WEDGE
    // FIX, user-attributed live via the S14.40 debug_skip_extract_gizmos lever). Mechanism, every
    // link source-verified (port-note S14C-round6-gizmo-verdict.md): the driver sets a captured
    // frustum on the virtual camera (SecondaryWorldRenderCore:388, the applyFrustum-skip trick) and
    // the shell swaps it into gameRenderer.mainCamera (MyGameRenderer ip_setCamera); the dest
    // extract's DebugRenderer.emitGizmos then hits ChunkCullingDebugRenderer — registered
    // UNCONDITIONALLY, and its capturedFrustum branch has NO debug-screen gate — which emits six
    // alpha-0.25 frustum-plane quads (one color per plane: cyan/red/yellow/blue/green/magenta) +
    // twelve opaque black wireframe lines. Those land in the frame's gizmo collector, drain into
    // the MAIN renderer's renderThreadGizmos, and draw in the main gizmo pass
    // (pipeline/debug_filled_box + lines — the round-3 DrawCallTrace labels): translucent,
    // depth-tested => paint ONLY far-depth (sky/fog) pixels, only while a portal is in view — the
    // user's exact wedge signature (cyan left / washed middle / purple right / dark seams / apex
    // top). Block-era precedent: DebugRendererPortalSkipMixin fixed the same emission for the
    // block driver ("ghostly camera diagnostic") but its gate is block-era-only => inert flag-ON.
    // Skipping extractGizmos() also stops the dest extract DUPLICATING client+integrated-server
    // per-tick gizmos into the portal view (getPerTickGizmos is a non-consuming snapshot getter —
    // the main view never lost them; the copies were wrong-dim diagnostics drawn inside the
    // window). debug_allow_dest_extract_gizmos restores the corrupting vanilla emission live for
    // A/B attribution (S20-removal-ledgered).

    @WrapOperation(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/debug/DebugRenderer;emitGizmos"
                + "(Lnet/minecraft/client/renderer/culling/Frustum;DDDF)V"
        )
    )
    private void ip_leverDebugGizmos(
        DebugRenderer instance, Frustum frustum, double camX, double camY, double camZ,
        float partialTicks, Operation<Void> original
    ) {
        if (SecondaryWorldRenderCore.isDestExtracting && !IPGlobal.debugAllowDestExtractGizmos) {
            return;
        }
        original.call(instance, frustum, camX, camY, camZ, partialTicks);
    }

    @WrapOperation(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/debug/GameTestBlockHighlightRenderer;emitGizmos()V"
        )
    )
    private void ip_leverGameTestGizmos(
        GameTestBlockHighlightRenderer instance, Operation<Void> original
    ) {
        if (SecondaryWorldRenderCore.isDestExtracting && !IPGlobal.debugAllowDestExtractGizmos) {
            return;
        }
        original.call(instance);
    }

    @WrapOperation(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractGizmos()V"
        )
    )
    private void ip_leverExtractGizmos(
        LevelExtractor instance, Operation<Void> original
    ) {
        if (SecondaryWorldRenderCore.isDestExtracting && !IPGlobal.debugAllowDestExtractGizmos) {
            return;
        }
        original.call(instance);
    }
}
