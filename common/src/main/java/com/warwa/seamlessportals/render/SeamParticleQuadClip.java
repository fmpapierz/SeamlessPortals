package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.passthrough.SeamFractional;
import com.warwa.seamlessportals.passthrough.SeamOccupancy;
import com.warwa.seamlessportals.passthrough.SeamRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * ★ PLANE-EXACT PARTICLE CLIPPING (stitched-space contract item 4, user-confirmed 2026-08-10:
 * "nothing weaker than plane-exact clipping of particle geometry"). Point tests can never seal a
 * plane — a billboard has extent, and the r30/r36 shift/band heuristics all leaked (the whole
 * flicker saga). This is the terrain seam clip's idea applied to particle QUADS, on the CPU at
 * quad-build time, which makes it pass-agnostic (main pass and portal pass alike) and
 * renderer-agnostic (sodium/iris included — no GL state, no shader injection needed).
 *
 * <p>Mechanism, in three stages that share this class as their meeting point:
 * <ol>
 *   <li><b>Extract</b> — the two per-particle extract sites the mod owns (the main-pass wrap in
 *       {@code MixinQuadParticleGroup}, the isolated dest extract in {@code MixinParticleEngine})
 *       compute the particle's seam plane in CAMERA-RELATIVE space ({@link #computePendingPlane})
 *       and park it here; the {@code QuadParticleRenderState.add} hook then records one plane
 *       entry per stored quad, in storage order, on the state's per-layer side-channel
 *       ({@code QuadParticleRenderStateClipMixin}).</li>
 *   <li><b>Build</b> — {@code buildLayer} walks a layer's quads in the same order; the
 *       {@code renderRotatedQuad} HEAD hook pulls this cursor's plane and, when the quad crosses
 *       it, cancels vanilla and emits the exactly-clipped polygon ({@link #clipAndEmit}).</li>
 *   <li><b>Geometry</b> — a corner is {@code center + size·rotate(q,(a,b,0))} with
 *       {@code (a,b) ∈ [-1,1]²} (26.2 bytecode, {@code renderVertex}); the plane function is
 *       AFFINE in {@code (a,b)}, so Sutherland–Hodgman in parameter space is exact, and UVs lerp
 *       linearly ({@code u: u0→u1} along {@code a}, {@code v: v1→v0} along {@code b} — the
 *       decoded vanilla corner mapping).</li>
 * </ol>
 *
 * <p>The kept half is the particle's OWN side of its cell's cut plane — a particle never draws
 * one pixel past the seam, in any pass, from any angle. Render-thread confined; fail-open (a
 * missing/exhausted plane list means "unclipped", never a crash or a dropped particle).
 */
public final class SeamParticleQuadClip {

    private SeamParticleQuadClip() {}

    /** Keep-all sentinel: plane normal zero, constant +1 → f = 1 ≥ 0 everywhere. */
    public static final float KEEP_ALL_NX = 0.0f, KEEP_ALL_NY = 0.0f, KEEP_ALL_NZ = 0.0f,
        KEEP_ALL_D = 1.0f;

    // --------------------------------------------------------------- pending (extract stage)

    private static boolean pendingSet = false;
    private static float pnx, pny, pnz, pd;

    /**
     * Compute and park the camera-relative seam plane for the particle about to extract.
     * Keep-all when the particle's cell carries no mirrorable cut binding.
     */
    public static void computePendingPlane(Level level, Vec3 cameraPos, double x, double y, double z) {
        pendingSet = true;
        pnx = KEEP_ALL_NX;
        pny = KEEP_ALL_NY;
        pnz = KEEP_ALL_NZ;
        pd = KEEP_ALL_D;
        if (level == null || !SeamFractional.active()) {
            return;
        }
        BlockPos cell = BlockPos.containing(x, y, z);
        SeamRegistry.SeamCell seam = SeamRegistry.lookup(level, cell);
        if (seam == null) {
            return;
        }
        for (SeamRegistry.SeamBinding b : seam.bindings()) {
            if (b == null || !b.isMirrorable() || b.cut() == null) {
                continue;
            }
            Direction.Axis axis = b.srcFacing().getAxis();
            double off = b.cut().srcPlaneOffset();
            byte half = SeamOccupancy.halfFromHit(new Vec3(x, y, z), cell, axis, off);
            // Normal points INTO the particle's own half; plane point = cell base + offset.
            float sign = half == SeamOccupancy.HALF_POSITIVE ? 1.0f : -1.0f;
            double w0;
            float nx = 0, ny = 0, nz = 0;
            switch (axis) {
                case X -> { nx = sign; w0 = cell.getX() + off; pd = (float) (sign * (cameraPos.x - w0)); }
                case Y -> { ny = sign; w0 = cell.getY() + off; pd = (float) (sign * (cameraPos.y - w0)); }
                default -> { nz = sign; w0 = cell.getZ() + off; pd = (float) (sign * (cameraPos.z - w0)); }
            }
            pnx = nx;
            pny = ny;
            pnz = nz;
            return;
        }
    }

    /** Extract sites call this after the particle's extract returns. */
    public static void clearPendingPlane() {
        pendingSet = false;
    }

    /** The add-hook reads the parked plane (keep-all when no extract context is active). */
    public static float pendingNx() { return pendingSet ? pnx : KEEP_ALL_NX; }
    public static float pendingNy() { return pendingSet ? pny : KEEP_ALL_NY; }
    public static float pendingNz() { return pendingSet ? pnz : KEEP_ALL_NZ; }
    public static float pendingD()  { return pendingSet ? pd  : KEEP_ALL_D; }

    // ----------------------------------------------------------------- cursor (build stage)

    private static float[] cursorPlanes = null;
    private static int cursorIndex = 0;

    /** buildLayer HEAD: begin walking this layer's plane list (null = no seam data, all vanilla). */
    public static void beginBuild(float[] planes, int count) {
        cursorPlanes = planes;
        cursorIndex = 0;
        cursorCount = count;
    }

    private static int cursorCount = 0;

    /**
     * renderRotatedQuad HEAD: the plane for THIS quad, advancing the cursor. Returns null for
     * keep-all (vanilla proceeds). Fail-open on any misalignment.
     */
    public static float[] nextPlane(float[] out) {
        if (cursorPlanes == null || cursorIndex >= cursorCount) {
            return null;
        }
        int base = cursorIndex * 4;
        cursorIndex++;
        float nx = cursorPlanes[base], ny = cursorPlanes[base + 1], nz = cursorPlanes[base + 2];
        if (nx == 0 && ny == 0 && nz == 0) {
            return null;   // keep-all sentinel
        }
        out[0] = nx;
        out[1] = ny;
        out[2] = nz;
        out[3] = cursorPlanes[base + 3];
        return out;
    }

    // --------------------------------------------------------------- geometry (emit stage)

    /**
     * Clip the quad against {@code plane} and emit the surviving polygon through {@code c} with
     * vanilla-identical vertex attributes. Returns true when it HANDLED the quad (vanilla must be
     * cancelled) — both the clipped-emission case and the fully-culled case; false when the quad
     * is entirely on the kept side (vanilla draws it unchanged, zero cost).
     */
    public static boolean clipAndEmit(
        com.mojang.blaze3d.vertex.VertexConsumer c, float[] plane,
        float x, float y, float z, float qx, float qy, float qz, float qw,
        float size, float u0, float u1, float v0, float v1, int color, int light
    ) {
        org.joml.Quaternionf q = new org.joml.Quaternionf(qx, qy, qz, qw);
        org.joml.Vector3f ax = new org.joml.Vector3f(1, 0, 0).rotate(q);
        org.joml.Vector3f ay = new org.joml.Vector3f(0, 1, 0).rotate(q);
        float f0 = plane[0] * x + plane[1] * y + plane[2] * z + plane[3];
        float ga = size * (plane[0] * ax.x + plane[1] * ax.y + plane[2] * ax.z);
        float gb = size * (plane[0] * ay.x + plane[1] * ay.y + plane[2] * ay.z);
        // Vanilla corner order (bytecode): (+1,-1), (+1,+1), (-1,+1), (-1,-1).
        float[] as = {1, 1, -1, -1};
        float[] bs = {-1, 1, 1, -1};
        float[] fs = new float[4];
        int inside = 0;
        for (int i = 0; i < 4; i++) {
            fs[i] = f0 + as[i] * ga + bs[i] * gb;
            if (fs[i] >= 0) {
                inside++;
            }
        }
        if (inside == 4) {
            return false;   // fully kept — vanilla draws it
        }
        if (SeamParticleProbe.armed()) {
            SeamParticleProbe.onQuadClip(inside == 0);
        }
        if (inside == 0) {
            return true;    // fully past the seam — nothing draws
        }
        // Sutherland–Hodgman in (a,b) parameter space; f is affine, so edge crossings are exact.
        float[] pa = new float[8], pb = new float[8];
        int n = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            boolean inI = fs[i] >= 0, inJ = fs[j] >= 0;
            if (inI) {
                pa[n] = as[i];
                pb[n] = bs[i];
                n++;
            }
            if (inI != inJ) {
                float t = fs[i] / (fs[i] - fs[j]);
                pa[n] = as[i] + t * (as[j] - as[i]);
                pb[n] = bs[i] + t * (bs[j] - bs[i]);
                n++;
            }
        }
        if (n < 3) {
            return true;    // numerically degenerate — treat as culled
        }
        // Emit padded to a multiple of 4 (QUADS topology): n=3 → repeat last; n=5 → 4 + degenerate.
        int quads = (n <= 4) ? 1 : 2;
        for (int qi = 0; qi < quads; qi++) {
            for (int k = 0; k < 4; k++) {
                int idx;
                if (qi == 0) {
                    idx = Math.min(k, n - 1);
                } else {
                    // second quad: v0, v3, v4, v4
                    idx = (k == 0) ? 0 : Math.min(2 + k, n - 1);
                }
                emitVertex(c, pa[idx], pb[idx], x, y, z, ax, ay, size, u0, u1, v0, v1, color, light);
            }
        }
        return true;
    }

    private static void emitVertex(
        com.mojang.blaze3d.vertex.VertexConsumer c, float a, float b,
        float x, float y, float z, org.joml.Vector3f ax, org.joml.Vector3f ay,
        float size, float u0, float u1, float v0, float v1, int color, int light
    ) {
        float px = x + size * (a * ax.x + b * ay.x);
        float py = y + size * (a * ax.y + b * ay.y);
        float pz = z + size * (a * ax.z + b * ay.z);
        // Decoded vanilla UV mapping: u1 at a=+1, u0 at a=-1; v1 at b=-1, v0 at b=+1.
        float u = u0 + (a + 1.0f) * 0.5f * (u1 - u0);
        float v = v1 + (b + 1.0f) * 0.5f * (v0 - v1);
        c.addVertex(px, py, pz).setUv(u, v).setColor(color).setLight(light);
    }
}
