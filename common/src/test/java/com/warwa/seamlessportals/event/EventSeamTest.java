package com.warwa.seamlessportals.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that the S0 event seam ({@link Event}/{@link EventFactory}) mirrors
 * the Fabric array-backed event semantics IP's {@code Helper} factories rely on
 * (IP q_misc_util/Helper.java:1424-1455; EXECUTION_PLAN §3 S0(a)):
 * registration-order dispatch, exception propagation (remaining listeners
 * skipped), null rejection, duplicate registration, and snapshot behavior for
 * an invoker captured before a registration. The invoker factories used here
 * are the exact for-loop shapes the ported Helper delegates at S2.
 */
class EventSeamTest {

    private static Event<Runnable> newRunnableEvent() {
        // The exact shape of IP Helper.createRunnableEvent (Helper.java:1424-1432).
        return EventFactory.createArrayBacked(
            Runnable.class,
            (listeners) -> () -> {
                for (Runnable listener : listeners) {
                    listener.run();
                }
            }
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Event<Consumer<T>> newConsumerEvent() {
        // The exact shape of IP Helper.createConsumerEvent (Helper.java:1435-1444).
        return EventFactory.createArrayBacked(
            Consumer.class,
            (listeners) -> (t) -> {
                for (Consumer<T> listener : listeners) {
                    listener.accept(t);
                }
            }
        );
    }

    @Test
    void listenersRunInRegistrationOrder() {
        Event<Consumer<List<String>>> event = newConsumerEvent();
        event.register(log -> log.add("first"));
        event.register(log -> log.add("second"));
        event.register(log -> log.add("third"));

        List<String> log = new ArrayList<>();
        event.invoker().accept(log);
        assertEquals(List.of("first", "second", "third"), log);
    }

    @Test
    void emptyEventInvokerIsNoOp() {
        Event<Runnable> event = newRunnableEvent();
        event.invoker().run(); // must not throw
    }

    @Test
    void listenerExceptionPropagatesAndSkipsRemaining() {
        Event<Consumer<List<String>>> event = newConsumerEvent();
        event.register(log -> log.add("ran"));
        event.register(log -> {
            throw new IllegalStateException("listener failure");
        });
        event.register(log -> log.add("never"));

        List<String> log = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> event.invoker().accept(log));
        assertEquals(List.of("ran"), log, "listeners after the throwing one must be skipped");
    }

    @Test
    void nullListenerRejected() {
        Event<Runnable> event = newRunnableEvent();
        assertThrows(NullPointerException.class, () -> event.register(null));
    }

    @Test
    void duplicateListenerDispatchesTwice() {
        Event<Consumer<List<String>>> event = newConsumerEvent();
        Consumer<List<String>> listener = log -> log.add("x");
        event.register(listener);
        event.register(listener);

        List<String> log = new ArrayList<>();
        event.invoker().accept(log);
        assertEquals(List.of("x", "x"), log);
    }

    @Test
    void registrationDuringDispatchDoesNotRunInFlight() {
        Event<Runnable> event = newRunnableEvent();
        List<String> log = new ArrayList<>();
        event.register(() -> {
            log.add("outer");
            event.register(() -> log.add("inner"));
        });

        event.invoker().run();
        assertEquals(List.of("outer"), log, "in-flight dispatch uses the pre-registration snapshot");

        event.invoker().run();
        assertEquals(List.of("outer", "outer", "inner"), log, "later dispatches include the new listener");
    }
}
