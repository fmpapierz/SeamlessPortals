// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. IPGlobal only references ClientTickEvents#END_CLIENT_TICK in a
// javadoc {@link} (the import must still resolve). :common compile classpath only. Removed at S20.
package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;

public final class ClientTickEvents {
    public static final Event<StartTick> START_CLIENT_TICK = null;
    public static final Event<EndTick> END_CLIENT_TICK = null;

    @FunctionalInterface
    public interface StartTick {
        void onStartTick(Minecraft client);
    }

    @FunctionalInterface
    public interface EndTick {
        void onEndTick(Minecraft client);
    }

    private ClientTickEvents() {
    }
}
