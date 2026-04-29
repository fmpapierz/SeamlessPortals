package com.warwa.seamlessportals.chunk;

/**
 * Per-player chunk-loading performance tier. Mirrors IP 1.19's
 * {@code PerformanceLevel}: scales the cross-dim chunk-loading radii
 * and the per-tick delivery budget so a slow client doesn't get
 * flooded with cross-dim chunks they can't process fast enough.
 *
 * <p>Defaults to {@link #good} server-side. Future client work can
 * report observed FPS and downgrade to {@code medium} / {@code bad}
 * via a serverbound payload — for now, the scaffolding is plumbed
 * but always returns the {@code good}-level scaling.
 */
public enum PerformanceLevel {
    /**
     * Everything healthy. Full RD when close to portal, full
     * chunk-delivery rate. Use this for desktop clients with stable
     * 60+ FPS and good network.
     */
    good,

    /**
     * Moderate constraints. Caps cross-dim radius at 2/3 of full;
     * delivery rate halved.
     */
    medium,

    /**
     * Severe constraints. Cross-dim chunk-loading radius capped at
     * 1/3 of full; delivery rate at minimum.
     */
    bad;

    /**
     * Maximum cross-dim chunk-loading radius cap (chunks). Used to
     * shrink the player→portal-distance-driven adaptive radius for
     * lower-tier clients.
     */
    public int maxIndirectRadius() {
        return switch (this) {
            case good -> 12;
            case medium -> 8;
            case bad -> 4;
        };
    }

    /**
     * Per-tick delivery budget for cross-dim (indirect) records.
     * Tuned to match the integrated server's typical chunk-gen rate.
     * Going higher creates a deferred-delivery backlog that grows
     * faster than chunk-gen can drain it (SP integrated server caps
     * around 5-10 fresh chunks/sec).
     */
    public int indirectDeliveryBudgetPerTick() {
        return switch (this) {
            case good -> 12;
            case medium -> 6;
            case bad -> 2;
        };
    }

    /**
     * Per-tick delivery budget for own-dim (direct) records — drives
     * the post-teleport fill-in rate. Lowered from {25, 10, 4} after
     * empirical SP testing: even at radius 6, 25 direct sends/tick
     * caused tick time to climb to 58 ms during teleport bursts.
     * 10/tick at "good" is plenty for steady-state movement and
     * lets the server breathe during portal-approach bursts.
     */
    public int directDeliveryBudgetPerTick() {
        return switch (this) {
            case good -> 10;
            case medium -> 5;
            case bad -> 2;
        };
    }

    /**
     * Initial per-player burst budget (in player ticks) for the first
     * {@code 100} ticks after join / dim-change. Tuned to avoid
     * overrunning the integrated server's chunk-gen rate during the
     * burst window — empirical: 200 caused 17-20 second freezes
     * during heavy back-and-forth teleport play.
     */
    public int initialBurstBudget() {
        return switch (this) {
            case good -> 50;
            case medium -> 25;
            case bad -> 10;
        };
    }

    /**
     * Map server-side average tick time (ms) to a performance level.
     * Direct port of IP 1.19's
     * {@code PerformanceLevel.getServerPerformanceLevel}.
     *
     * <p>{@link SeamlessServerPerformanceMonitor} samples this every
     * 20 seconds and feeds the result back into the graph's adaptive
     * radius + delivery budgets so an overloaded server scales down
     * automatically.
     */
    public static PerformanceLevel getServerPerformanceLevel(float tickTimeMs) {
        if (tickTimeMs < 40.0f) return good;
        if (tickTimeMs < 80.0f) return medium;
        return bad;
    }
}
