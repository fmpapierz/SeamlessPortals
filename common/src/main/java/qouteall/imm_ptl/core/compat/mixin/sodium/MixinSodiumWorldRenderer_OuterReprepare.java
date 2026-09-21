package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IPSodiumOuterReprepare;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * 26.3 / sodium 0.9.2 — the per-layer record behind the OUTER-VIEW RE-PREPARE (defect, measurement and port:
 * {@code SodiumInterface.OnSodiumPresent#ip_onPortalLayerPopped}).
 *
 * <p>javap, sodium-mc26.3-0.9.2-fabric.jar: {@code public void prepareChunkRendering(ChunkRenderMatrices, double, double,
 * double)} is NEW in 0.9.2 and is the ONLY entry to {@code RenderSectionManager.prepareChunkRendering(.., boolean)} -&gt;
 * {@code ChunkRenderer.prepare(getRenderLists(), new CameraTransform(x, y, z), useTranslucencySorting)}. It has exactly two
 * callers at runtime: sodium's own {@code LevelRendererMixin.getRenderState} wrap (main view, and any dest view that runs a
 * full {@code LevelRenderer.render}), and this mod's {@code ip_armDestChunkRenders} (decomposed dest passes). Both funnel
 * through the HEAD inject below, so "which layer prepared this renderer last" is complete by construction.
 *
 * <p>Recorded per portal layer ({@code 0} = main view): the arguments of that layer's own latest prepare. A prepare made
 * while iris renders its shadow map still MARKS the renderer as prepared-by-that-layer (it rewrites {@code shouldDraw[]}
 * like any other) but does not overwrite the layer's recorded arguments — those must stay the camera view's.
 *
 * <p>State lives ON the renderer it describes (one per dimension), never in a static: a cross-dim dest pass prepares a
 * different renderer and must not make the main view's look foreign.
 *
 * <p>The class name carries {@code Sodium} for the compat plugin's substring gate.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class MixinSodiumWorldRenderer_OuterReprepare implements IPSodiumOuterReprepare {

    /** Deeper layers than this are not recorded (and therefore never re-prepared) — far above any reachable recursion. */
    @Unique
    private static final int IP_MAX_LAYERS = 16;

    @Shadow
    public abstract void prepareChunkRendering(ChunkRenderMatrices matrices, double x, double y, double z);

    @Unique
    private int ip_lastPrepareLayer = -1;
    @Unique
    private int ip_lastPrepareFrame = Integer.MIN_VALUE;

    @Unique
    private final ChunkRenderMatrices[] ip_layerMatrices = new ChunkRenderMatrices[IP_MAX_LAYERS];
    @Unique
    private final double[] ip_layerX = new double[IP_MAX_LAYERS];
    @Unique
    private final double[] ip_layerY = new double[IP_MAX_LAYERS];
    @Unique
    private final double[] ip_layerZ = new double[IP_MAX_LAYERS];
    @Unique
    private final int[] ip_layerFrame = new int[IP_MAX_LAYERS];

    @Inject(method = "prepareChunkRendering", at = @At("HEAD"), remap = false)
    private void ip_recordPrepare(ChunkRenderMatrices matrices, double x, double y, double z, CallbackInfo ci) {
        int layer = PortalRendering.getPortalLayer();
        int frame = RenderStates.frameIndex;
        this.ip_lastPrepareLayer = layer;
        this.ip_lastPrepareFrame = frame;
        if (layer < 0 || layer >= IP_MAX_LAYERS) {
            return;
        }
        if (IrisInterface.invoker.isRenderingShadowMap()) {
            return;
        }
        this.ip_layerMatrices[layer] = matrices;
        this.ip_layerX[layer] = x;
        this.ip_layerY[layer] = y;
        this.ip_layerZ[layer] = z;
        this.ip_layerFrame[layer] = frame;
    }

    @Override
    public void ip_reprepareIfPreparedByAnotherLayer(int layer) {
        if (layer < 0 || layer >= IP_MAX_LAYERS) {
            return;
        }
        int frame = RenderStates.frameIndex;
        // Nothing prepared on THIS renderer this frame, or the last prepare was this layer's own: its state is intact.
        if (this.ip_lastPrepareFrame != frame || this.ip_lastPrepareLayer == layer) {
            return;
        }
        // This layer has not prepared on this renderer this frame (a cross-dim outer view, or a frame-replacing
        // cross-portal view with no outer draw at all): there is nothing of its to put back.
        ChunkRenderMatrices matrices = this.ip_layerMatrices[layer];
        if (matrices == null || this.ip_layerFrame[layer] != frame) {
            return;
        }
        IPGlobal.sodiumOuterReprepareCount++;
        // Re-enters the HEAD inject above with the outer layer current: the renderer is marked prepared-by-`layer` again.
        this.prepareChunkRendering(matrices, this.ip_layerX[layer], this.ip_layerY[layer], this.ip_layerZ[layer]);
    }
}
