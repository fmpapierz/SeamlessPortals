package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Per-frame crossing trace for the "source dim flashes while teleporting" investigation.
 *
 * <p>DESIGN CONSTRAINT (memory: render-thread logging = log4j stall): the render thread only
 * writes into a fixed ring buffer of parallel arrays — NO logging, no formatting, near-zero
 * allocation. A daemon thread dumps the annotated window to the log ~1.5s after each crossing
 * ({@link #armDump}), so gameplay cost is nil and the log gets a complete per-frame record.
 *
 * <p>Each frame records: current dim, camera pos, SIGNED distance from the camera to the nearest
 * linked portal's plane (axis X portal → cam.z − center.z; axis Z → cam.x − center.x — matching
 * PortalTransform's depth axis), player pos, how many portal views rendered the previous frame,
 * and the promote-bridge flag. The dump annotates the two smoking guns:
 * <ul>
 *   <li>{@code <-- PAST-PLANE, SOURCE STILL RENDERING}: the camera's plane-side sign flipped vs
 *       the pre-crossing baseline while {@code mc.level} is still the OLD dim — i.e. the player
 *       physically crossed but the visual swap hasn't happened; those frames render the source
 *       world from beyond the portal (the tick-latency flash hypothesis).</li>
 *   <li>{@code <== LEVEL SWAPPED}: the first frame whose dim differs from the previous frame.</li>
 * </ul>
 */
public final class CrossingTracer {

    private CrossingTracer() {}

    private static final int N = 4096;
    private static final long[] tN = new long[N];
    private static final double[] camX = new double[N], camY = new double[N], camZ = new double[N];
    private static final double[] plX = new double[N], plY = new double[N], plZ = new double[N];
    private static final double[] planeDist = new double[N];
    private static final Object[] dim = new Object[N];        // ResourceKey<Level>
    private static final Object[] portalRef = new Object[N];  // BlockPos origin of nearest portal
    private static final short[] portalsRendered = new short[N];
    private static final byte[] flags = new byte[N];          // bit0: promote bridge active
    private static int written = 0;                            // render-thread only
    private static short portalsThisFrame = 0;

    // Event marks (rare; render/game thread writes, daemon reads).
    private static final java.util.List<Object[]> events =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>()); // {Long nanos, String msg}

    private static volatile long armNanos = 0L;
    private static volatile long dumpDeadlineNanos = 0L;
    private static volatile boolean daemonStarted = false;

    /** Called once per frame from the portal render hook. Never throws, never logs. */
    public static void recordFrame() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || mc.gameRenderer == null) return;
            Camera cam = mc.gameRenderer.mainCamera();
            if (cam == null) return;
            Vec3 cp = cam.position();
            int i = written % N;
            tN[i] = System.nanoTime();
            camX[i] = cp.x; camY[i] = cp.y; camZ[i] = cp.z;
            Vec3 pp = mc.player.position();
            plX[i] = pp.x; plY[i] = pp.y; plZ[i] = pp.z;
            dim[i] = mc.level.dimension();

            double best = Double.NaN;
            Object bestP = null;
            com.warwa.seamlessportals.portal.PortalManager pm =
                com.warwa.seamlessportals.portal.PortalManager.getClientInstance();
            com.warwa.seamlessportals.portal.PortalTracker tr = pm.getTracker(mc.level.dimension());
            if (tr != null) {
                for (com.warwa.seamlessportals.portal.PortalInfo p
                        : tr.getPortalsInRange(mc.player.blockPosition(), 48.0)) {
                    if (pm.getLinkForPortal(p.getPortalId()).isEmpty()) continue;
                    Vec3 c = p.getCenter();
                    double d = (p.getAxis() == Direction.Axis.X) ? (cp.z - c.z) : (cp.x - c.x);
                    if (bestP == null || Math.abs(d) < Math.abs(best)) {
                        best = d;
                        bestP = p.getOrigin();
                    }
                }
            }
            planeDist[i] = best;
            portalRef[i] = bestP;
            portalsRendered[i] = portalsThisFrame;
            portalsThisFrame = 0;
            flags[i] = (byte) (PortalContextSwitch.isPromoteBridgeActive() ? 1 : 0);
            written++;
        } catch (Throwable t) {
            // Tracing must never break the render.
        }
    }

    /** Called from renderOnePortal so the trace shows how many portal views drew each frame. */
    public static void notePortalRendered() {
        portalsThisFrame++;
    }

    /** Timestamped event mark (crossing steps). Cheap; rare. */
    public static void event(String msg) {
        try {
            events.add(new Object[]{ System.nanoTime(), msg });
            if (events.size() > 400) events.remove(0);
        } catch (Throwable ignored) {}
    }

    /** Arm the post-crossing dump (first arm in a burst anchors the window). */
    public static void armDump() {
        long now = System.nanoTime();
        if (armNanos == 0L) armNanos = now;
        dumpDeadlineNanos = now + 1_500_000_000L;
        ensureDaemon();
    }

    private static synchronized void ensureDaemon() {
        if (daemonStarted) return;
        daemonStarted = true;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(200L);
                    long arm = armNanos;
                    if (arm != 0L && System.nanoTime() > dumpDeadlineNanos) {
                        try {
                            dump(arm);
                        } finally {
                            armNanos = 0L;
                            events.clear();
                        }
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable ignored) {}
            }
        }, "seamless-crossing-tracer");
        t.setDaemon(true);
        t.start();
    }

    private static void dump(long arm) {
        long lo = arm - 800_000_000L;
        long hi = arm + 1_400_000_000L;
        StringBuilder sb = new StringBuilder(16384);
        sb.append("[SEAMLESS XTRACE] ===== crossing trace (t=0 is detection) =====");

        sb.append("\n[SEAMLESS XTRACE] EVENTS:");
        synchronized (events) {
            for (Object[] e : events) {
                long en = (Long) e[0];
                if (en < lo || en > hi) continue;
                sb.append(String.format("\n  %+9.1fms  %s", (en - arm) / 1e6, e[1]));
            }
        }

        sb.append("\n[SEAMLESS XTRACE] FRAMES: t | dim | planeDist@portal | cam | player | portalViews(prevFrame) | bridge");
        int end = written;                    // snapshot
        int start = Math.max(0, end - N);
        Object prevDim = null;
        Double baselineSign = null;           // sign of planeDist before detection
        Object baselineDim = null;
        int pastPlaneFrames = 0;
        for (int k = start; k < end; k++) {
            int i = k % N;
            long t = tN[i];
            if (t < lo || t > hi) continue;
            Object d = dim[i];
            double pd = planeDist[i];
            // Baseline: last frame at/before detection with a real plane distance.
            if (t <= arm && !Double.isNaN(pd)) {
                baselineSign = Math.signum(pd);
                baselineDim = d;
            }
            String note = "";
            if (prevDim != null && d != prevDim) {
                note = "  <== LEVEL SWAPPED";
            } else if (t > arm && baselineSign != null && baselineDim == d
                       && !Double.isNaN(pd) && Math.signum(pd) != baselineSign && pd != 0.0) {
                note = "  <-- PAST-PLANE, SOURCE STILL RENDERING";
                pastPlaneFrames++;
            }
            sb.append(String.format(
                "\n  %+9.1fms  %-20s pd=%+7.3f@%-16s cam=(%.2f,%.2f,%.2f) pl=(%.2f,%.2f,%.2f) views=%d %s%s",
                (t - arm) / 1e6,
                d == null ? "?" : ((net.minecraft.resources.ResourceKey<?>) d).identifier().getPath(),
                pd,
                portalRef[i] == null ? "-" : ((net.minecraft.core.BlockPos) portalRef[i]).toShortString(),
                camX[i], camY[i], camZ[i],
                plX[i], plY[i], plZ[i],
                portalsRendered[i],
                (flags[i] & 1) != 0 ? "B" : "-",
                note));
            prevDim = d;
        }
        sb.append("\n[SEAMLESS XTRACE] SUMMARY: past-plane-while-source-rendering frames = ")
          .append(pastPlaneFrames)
          .append(pastPlaneFrames > 0
              ? "  → the flash is the TICK-LATENCY gap (camera crossed, swap not yet run)"
              : "  → no past-plane frames; flash must be post-swap (check frames after LEVEL SWAPPED)");
        SeamlessPortalsConstants.LOGGER.warn(sb.toString());
    }
}
