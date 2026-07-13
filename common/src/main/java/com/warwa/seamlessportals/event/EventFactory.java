package com.warwa.seamlessportals.event;

import java.util.function.Function;

/**
 * Loader-neutral analog of Fabric API's
 * {@code net.fabricmc.fabric.api.event.EventFactory} — exactly the
 * {@code createArrayBacked} surface IP's {@code Helper} event factories consume
 * (IP q_misc_util/Helper.java:1424-1455). Part of the S0 loader seams
 * (migration/EXECUTION_PLAN.md §3 S0(a); F12/R13h; seam inventory:
 * migration/port-notes/S00-seam-inventory.md).
 *
 * <p><b>S2 delegation contract:</b> the ported {@code qouteall.q_misc_util.Helper}
 * keeps its {@code createRunnableEvent}/{@code createConsumerEvent}/
 * {@code createBiConsumerEvent} bodies shape-verbatim (the for-loop invoker
 * factories), swapping only the Fabric {@code Event}/{@code EventFactory}
 * imports for this package. No other caller should need this class directly;
 * IP code always goes through Helper's factories.
 */
public final class EventFactory {

    /**
     * Creates an array-backed event. The invoker factory is applied to the
     * (initially empty) listener array and re-applied after every
     * registration; see {@link ArrayBackedEvent} for the exact mirrored
     * semantics (ordering, exception propagation, snapshot behavior).
     *
     * @param type           the listener class (used to create the typed array,
     *                       exactly like Fabric's signature)
     * @param invokerFactory combines the listener array into one invoker
     */
    public static <T> Event<T> createArrayBacked(Class<? super T> type, Function<T[], T> invokerFactory) {
        return new ArrayBackedEvent<>(type, invokerFactory);
    }

    private EventFactory() {
    }
}
