package com.warwa.seamlessportals.event;

/**
 * Loader-neutral event object — the mod-owned analog of Fabric API's
 * {@code net.fabricmc.fabric.api.event.Event<T>}, created for the entity-portal
 * migration's S0 loader seams (migration/EXECUTION_PLAN.md §3 S0(a) "Loader-neutral
 * seams", forced-deviation F12; API_RISKS R13h: never Fabric types in common code).
 *
 * <p>IP builds ALL of its own event objects ({@code IPGlobal.*_EVENT},
 * {@code Portal.*_SIGNAL}, {@code ClientWorldLoader} events, ...) through
 * {@code Helper.createRunnableEvent()/createConsumerEvent()/createBiConsumerEvent()},
 * which wrap Fabric's {@code EventFactory.createArrayBacked}
 * (IP q_misc_util/Helper.java:1424-1455; api-map/q-misc-util.md F4;
 * api-map/world-loader-root.md "EventFactory.createArrayBacked" row).
 * <b>At S2 the ported {@code qouteall.q_misc_util.Helper} DELEGATES its event
 * factories here</b>: its factory bodies stay shape-verbatim, with only the
 * {@code Event}/{@code EventFactory} imports translated to this package (an
 * S0-decided loader-seam substitution under the D4.3 diff gate).
 *
 * <p>Surface and semantics mirror Fabric's {@code Event} exactly as far as IP
 * consumes it: {@link #register(Object)} + {@link #invoker()}. Fabric's phase
 * system ({@code addPhaseOrdering}/{@code DEFAULT_PHASE}) is deliberately NOT
 * implemented: IP only uses phases against DimLib's external event
 * (api-map/q-misc-util.md F5/F6 — DimensionIntId's {@code iportal:early_phase});
 * with DimLib out of scope, the ordering invariant is preserved by init order at
 * the owning stage, not by phases. If a stage ever needs phases, widen here.
 *
 * @param <T> the listener/invoker functional type
 */
public abstract class Event<T> {

    /**
     * The current combined invoker; rebuilt on every {@link #register(Object)}.
     * Mirrors Fabric's {@code Event.invoker} field contract: an in-flight
     * invocation obtained before a concurrent registration keeps dispatching to
     * the listener snapshot it was built from.
     */
    protected volatile T invoker;

    /**
     * Returns the invoker that dispatches to every registered listener,
     * synchronously, in registration order. A listener exception propagates to
     * the caller immediately and skips the remaining listeners (Fabric
     * array-backed semantics — no catching, no isolation).
     */
    public final T invoker() {
        return invoker;
    }

    /**
     * Registers a listener. Appended to the end of the dispatch order
     * (registration order preserved; duplicate registrations dispatch twice —
     * array-backed, no dedup, exactly like Fabric).
     *
     * @throws NullPointerException if {@code listener} is null
     */
    public abstract void register(T listener);
}
