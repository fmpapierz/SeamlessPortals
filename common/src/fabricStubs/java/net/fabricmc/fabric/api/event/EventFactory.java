// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md +
// S13A-peripheral.md). NOT IP source, NOT shipped. Faithful shell of Fabric's EventFactory as far
// as the HELD tree consumes it: createArrayBacked(Class<? super T>, Function<T[], T>) -> Event<T>
// (first held consumer: DimensionStackAPI's two public dim-stack events, S13). The returned Event is
// the never-firing sibling shell in this package (register/invoker are no-ops). The loader-neutral
// public-event redesign (do not expose Fabric Event in common code — api-map platform-compat-
// peripheral §4) is C1/S19. :common compile classpath only; the REAL fabric EventFactory resolves at
// S13-B on :fabric. Removed at S20.
package net.fabricmc.fabric.api.event;

import java.util.function.Function;

public final class EventFactory {
    public static <T> Event<T> createArrayBacked(Class<? super T> type, Function<T[], T> invokerFactory) {
        return new Event<>();
    }

    private EventFactory() {
    }
}
