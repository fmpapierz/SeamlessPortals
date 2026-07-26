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
    private static final byte[] detStates = new byte[N];      // frame-detector state (see below)
    // Hand-motion channels (the "hand glitches on teleport" investigation): every state that
    // moves the first-person hand, sampled per frame so a dump pinpoints WHICH channel spikes
    // at a glitch. bobAmp = walk-bob amplitude (velocity-driven, ClientAvatarState.bob; feeds
    // bobView on the hand pose). walkD = walk distance (bob PHASE; sin/cos(walkDist*pi)).
    // velH = deltaMovement.horizontalDistance (the bob amplitude INPUT; collapses if velocity
    // is overwritten). swayY = yRot - yBob (hand sway offset; chases any un-carried rotation).
    private static final float[] bobAmp = new float[N];
    private static final float[] walkD = new float[N];
    private static final float[] velH = new float[N];
    private static final float[] swayY = new float[N];
    private static int written = 0;                            // render-thread only
    private static short portalsThisFrame = 0;

    /**
     * Why the per-frame camera-crossing detector did/didn't fire THIS frame, set by
     * {@code SeamlessClientTeleport.checkCameraCrossingPerFrame} (runs earlier in the same
     * frame at renderLevel HEAD): 0=not-run, 1=COOLDOWN-suppressed (tracked, not fired),
     * 2=priming (no previous segment / jump reset), 3=checked-no-cross, 4=FIRED.
     */
    public static volatile byte frameDetState = 0;
    private static final String[] DET_NAMES = { "off ", "cool", "prim", "chk ", "FIRE" };

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
            planeDist[i] = best;
            portalRef[i] = bestP;
            portalsRendered[i] = portalsThisFrame;
            portalsThisFrame = 0;
            flags[i] = 0; // S20: promote-bridge bit retired with PortalContextSwitch
            detStates[i] = frameDetState;
            frameDetState = 0;
            bobAmp[i] = mc.player.avatarState().getInterpolatedBob(1.0f);
            walkD[i] = mc.player.avatarState().getInterpolatedWalkDistance(1.0f);
            velH[i] = (float) mc.player.getDeltaMovement().horizontalDistance();
            swayY[i] = mc.player.getYRot() - mc.player.yBob;
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

        sb.append("\n[SEAMLESS XTRACE] FRAMES: t | dim | det | planeDist@portal | cam | player | hand[bob wd vH swayY] | views | bridge");
        sb.append("\n[SEAMLESS XTRACE]   det: off=check not run  cool=cooldown-suppressed  prim=priming  chk=no-cross  FIRE=fired");
        sb.append("\n[SEAMLESS XTRACE]   hand: bob=walk-bob amplitude  wd=walkDist (bob phase)  vH=horizontal velocity (bob input)  swayY=yRot-yBob (hand sway offset)");
        int end = written;                    // snapshot
        int start = Math.max(0, end - N);
        Object prevDim = null;
        Double prevSign = null;               // previous frame's plane side (same dim)
        int pastPlaneFrames = 0;
        int bobDips = 0;
        float prevBob = Float.NaN;
        for (int k = start; k < end; k++) {
            int i = k % N;
            long t = tN[i];
            if (t < lo || t > hi) continue;
            Object d = dim[i];
            double pd = planeDist[i];
            String note = "";
            if (prevDim != null && d != prevDim) {
                note = "  <== LEVEL SWAPPED";
                prevSign = null; // new dim, new plane baseline
            } else if (prevSign != null && !Double.isNaN(pd)
                       && Math.signum(pd) != prevSign && pd != 0.0) {
                // The camera changed sides of the portal plane WITHOUT a level swap —
                // this frame renders the same dim from the far side = a flash frame.
                note = "  <-- CAMERA CROSSED PLANE, NO SWAP (flash frame)";
                pastPlaneFrames++;
            }
            // Hand-glitch annotator: the walk-bob amplitude lerps 0.4/tick toward the
            // horizontal speed — at steady walk it is ~constant frame-to-frame. A drop
            // >0.015 in one frame means the amplitude INPUT collapsed (velocity was
            // overwritten) or a tick anomaly — the hand visibly sinks toward rest.
            if (!Float.isNaN(prevBob) && prevBob - bobAmp[i] > 0.015f) {
                note += "  <-- BOB DIP (hand sink)";
                bobDips++;
            }
            prevBob = bobAmp[i];
            if (!Double.isNaN(pd) && pd != 0.0) prevSign = Math.signum(pd);
            byte ds = detStates[i];
            sb.append(String.format(
                "\n  %+9.1fms  %-12s %s pd=%+7.3f@%-14s cam=(%.2f,%.2f,%.2f) pl=(%.2f,%.2f,%.2f) hand[%.3f %7.2f %.3f %+6.2f] views=%d %s%s",
                (t - arm) / 1e6,
                d == null ? "?" : ((net.minecraft.resources.ResourceKey<?>) d).identifier().getPath(),
                DET_NAMES[Math.max(0, Math.min(4, ds))],
                pd,
                portalRef[i] == null ? "-" : ((net.minecraft.core.BlockPos) portalRef[i]).toShortString(),
                camX[i], camY[i], camZ[i],
                plX[i], plY[i], plZ[i],
                bobAmp[i], walkD[i], velH[i], swayY[i],
                portalsRendered[i],
                (flags[i] & 1) != 0 ? "B" : "-",
                note));
            prevDim = d;
        }
        sb.append("\n[SEAMLESS XTRACE] SUMMARY: flash frames (camera crossed plane, no swap that frame) = ")
          .append(pastPlaneFrames)
          .append(", bob dips (hand-sink frames) = ").append(bobDips)
          .append(". For flash frames, the det column says WHY the frame detector didn't fire (cool/prim/chk).");
        SeamlessPortalsConstants.LOGGER.warn(sb.toString());
    }
}
