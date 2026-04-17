package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.minecraft.client.renderer.RenderBuffers;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Lazy-allocating pool of {@link RenderBuffers} for portal-view sub-renders.
 *
 * <p>Mirrors IP's {@code acquireRenderBuffersObject / returnRenderBuffersObject}
 * pattern (cap 2 concurrent). Two slots are enough for the current single-depth
 * portal render plus headroom for future portal-in-portal recursion (which is
 * still gated off via {@link PortalContextSwitch#isRenderingPortal} but the
 * pool is sized to allow it without a redesign).
 *
 * <p>Why pool instead of per-renderer buffers:
 * <ul>
 *   <li>The dormant vanilla {@code mc.levelRenderer} (now a map entry per
 *       Subphase 1) shares its {@code renderBuffers} with {@code mc.renderBuffers}.
 *       Using it as a portal-view secondary while the main render is in flight
 *       would conflict on those buffers.</li>
 *   <li>Per-secondary {@code new RenderBuffers(4)} (current PortalWorldManager
 *       behaviour) avoids that conflict for fresh secondaries but wastes memory
 *       on idle dims and doesn't help with the vanilla case.</li>
 *   <li>Pooling once-per-frame (acquire on render entry, release on exit)
 *       gives every portal render a guaranteed-fresh buffer set without
 *       per-renderer permanent allocations.</li>
 * </ul>
 *
 * <p>If the pool is exhausted (more than {@link #CAP} concurrent acquisitions
 * — only possible if portal recursion is enabled in the future), {@link #acquire}
 * returns {@code null} and the caller falls back to the renderer's own buffers.
 * No crash; the only risk is the pre-pool buffer-conflict potential, which the
 * current single-depth render avoids anyway.
 *
 * <p>Single-threaded: portal sub-renders all run on the render thread and the
 * recursion guard (in 26.1.2) means {@code inUse} is at most 1 at any time.
 * No synchronisation needed.
 */
public final class PortalRenderBuffersPool {

    /** Maximum concurrent acquisitions. IP uses 2 (depth-2 portal-in-portal). */
    private static final int CAP = 2;

    /** Section-buffer-builder budget passed to {@code new RenderBuffers(int)}.
     *  Matches the value used by {@link com.warwa.seamlessportals.client.PortalWorldManager}
     *  when constructing per-secondary buffers. */
    private static final int BUFFER_BUDGET = 4;

    private static final Deque<RenderBuffers> available = new ArrayDeque<>(CAP);
    private static int inUse = 0;
    private static int peakAllocated = 0;

    private PortalRenderBuffersPool() {}

    /**
     * Acquire a {@link RenderBuffers} for the duration of one portal-view
     * sub-render. Pair with {@link #release(RenderBuffers)} in a
     * {@code finally}.
     *
     * @return a pooled or freshly-allocated {@code RenderBuffers}, or
     *         {@code null} if the pool is exhausted (cap reached). The caller
     *         must handle {@code null} by skipping the buffer swap.
     */
    public static RenderBuffers acquire() {
        if (!available.isEmpty()) {
            inUse++;
            return available.pop();
        }
        if (inUse < CAP) {
            inUse++;
            peakAllocated = Math.max(peakAllocated, inUse);
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS POOL] Allocating RenderBuffers (inUse={}, peak={})",
                inUse, peakAllocated);
            return new RenderBuffers(BUFFER_BUDGET);
        }
        return null;
    }

    /**
     * Return a previously-acquired {@link RenderBuffers} to the pool.
     * No-op if {@code buf} is {@code null} (matches the {@link #acquire}
     * exhausted return path).
     */
    public static void release(RenderBuffers buf) {
        if (buf == null) return;
        inUse--;
        available.push(buf);
    }
}
