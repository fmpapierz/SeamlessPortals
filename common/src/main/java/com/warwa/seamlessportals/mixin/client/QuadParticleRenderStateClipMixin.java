package com.warwa.seamlessportals.mixin.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.warwa.seamlessportals.render.SeamParticleQuadClip;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * ★ THE PLANE SIDE-CHANNEL (plane-exact particle clipping, contract item 4 — see
 * {@link SeamParticleQuadClip} for the full mechanism). The vanilla state stores each quad as
 * center/rotation/size/UV floats per {@code Layer}; this mixin carries one camera-relative seam
 * plane per stored quad, in the same per-layer storage order, so the build stage can clip each
 * quad against ITS OWN cell's plane — plane-count-free (two portals near each other simply give
 * different entries different planes).
 *
 * <p>Alignment contract: every {@code add} appends exactly one plane entry (the extract sites
 * park the particle's plane before calling extract; un-parked adds get the keep-all sentinel);
 * {@code clear} — the per-frame reset the extractor drives via {@code ParticlesRenderState}
 * (S14.40 shared-accumulator model) — resets the side-channel with it; {@code buildLayer} walks
 * a layer's quads in storage order, and the {@code renderRotatedQuad} HEAD pulls the cursor's
 * plane. Fail-open everywhere: a missing or exhausted list means "draw unclipped", never a crash.
 */
@Mixin(QuadParticleRenderState.class)
public class QuadParticleRenderStateClipMixin {

    @Unique
    private final Map<SingleQuadParticle.Layer, float[]> seamlessportals$planes = new HashMap<>();
    @Unique
    private final Map<SingleQuadParticle.Layer, Integer> seamlessportals$planeCounts = new HashMap<>();

    @Inject(method = "add", at = @At("HEAD"))
    private void seamlessportals$recordPlane(
        SingleQuadParticle.Layer layer,
        float x, float y, float z, float qx, float qy, float qz, float qw,
        float size, float u0, float u1, float v0, float v1, int color, int light,
        CallbackInfo ci
    ) {
        int count = seamlessportals$planeCounts.getOrDefault(layer, 0);
        float[] arr = seamlessportals$planes.get(layer);
        if (arr == null || arr.length < (count + 1) * 4) {
            float[] grown = new float[Math.max(64, (count + 1) * 8)];
            if (arr != null) {
                System.arraycopy(arr, 0, grown, 0, count * 4);
            }
            arr = grown;
            seamlessportals$planes.put(layer, arr);
        }
        int base = count * 4;
        arr[base] = SeamParticleQuadClip.pendingNx();
        arr[base + 1] = SeamParticleQuadClip.pendingNy();
        arr[base + 2] = SeamParticleQuadClip.pendingNz();
        arr[base + 3] = SeamParticleQuadClip.pendingD();
        seamlessportals$planeCounts.put(layer, count + 1);
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void seamlessportals$clearPlanes(CallbackInfo ci) {
        seamlessportals$planeCounts.clear();   // arrays kept for reuse; counts define validity
    }

    @Inject(method = "buildLayer", at = @At("HEAD"))
    private void seamlessportals$beginBuild(
        SingleQuadParticle.Layer layer, VertexConsumer consumer, CallbackInfo ci
    ) {
        SeamParticleQuadClip.beginBuild(
            seamlessportals$planes.get(layer),
            seamlessportals$planeCounts.getOrDefault(layer, 0));
    }

    @Inject(method = "renderRotatedQuad", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$clipQuad(
        VertexConsumer c,
        float x, float y, float z, float qx, float qy, float qz, float qw,
        float size, float u0, float u1, float v0, float v1, int color, int light,
        CallbackInfo ci
    ) {
        float[] plane = SeamParticleQuadClip.nextPlane(seamlessportals$planeScratch);
        if (plane == null) {
            return;   // keep-all: vanilla draws
        }
        if (SeamParticleQuadClip.clipAndEmit(
            c, plane, x, y, z, qx, qy, qz, qw, size, u0, u1, v0, v1, color, light)) {
            ci.cancel();
        }
    }

    @Unique
    private final float[] seamlessportals$planeScratch = new float[4];
}
