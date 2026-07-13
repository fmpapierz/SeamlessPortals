package com.warwa.seamlessportals.event;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * Array-backed {@link Event} implementation mirroring the semantics of Fabric
 * API's {@code net.fabricmc.fabric.impl.base.event.ArrayBackedEvent} (default
 * phase only — see the F5 note on {@link Event}):
 *
 * <ul>
 *   <li><b>Ordering:</b> listeners dispatch synchronously in registration order.</li>
 *   <li><b>Exceptions:</b> a listener exception propagates immediately; the
 *       remaining listeners are skipped (the invoker factories are plain
 *       for-loops with no try/catch — IP Helper.java:1424-1455).</li>
 *   <li><b>Null:</b> registering a null listener throws NPE.</li>
 *   <li><b>Snapshot:</b> registration replaces the handler array and rebuilds
 *       the invoker under a lock; an invoker obtained before the registration
 *       keeps dispatching to the old array (a listener registered during a
 *       dispatch does NOT run in that dispatch).</li>
 *   <li><b>Duplicates:</b> allowed; dispatched once per registration.</li>
 * </ul>
 */
final class ArrayBackedEvent<T> extends Event<T> {

    private final Function<T[], T> invokerFactory;
    private final Object lock = new Object();
    private T[] handlers;

    @SuppressWarnings("unchecked")
    ArrayBackedEvent(Class<? super T> type, Function<T[], T> invokerFactory) {
        this.invokerFactory = invokerFactory;
        this.handlers = (T[]) Array.newInstance(type, 0);
        update();
    }

    private void update() {
        this.invoker = invokerFactory.apply(handlers);
    }

    @Override
    public void register(T listener) {
        Objects.requireNonNull(listener, "Tried to register a null listener!");
        synchronized (lock) {
            handlers = Arrays.copyOf(handlers, handlers.length + 1);
            handlers[handlers.length - 1] = listener;
            update();
        }
    }
}
