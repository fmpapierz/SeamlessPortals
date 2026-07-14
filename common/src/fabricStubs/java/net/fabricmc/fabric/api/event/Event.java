// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. Faithful shell of Fabric's Event<T> as far as the HELD tree
// consumes THIS type directly: register(T)/invoker() (the tick + chunk lifecycle event fields
// typed as Event<T>) and the static DEFAULT_PHASE constant (DimensionIntId passes it to
// DimensionAPI.addPhaseOrdering). NOTE: IP's OWN event objects (IPGlobal.*_EVENT,
// Portal.*_SIGNAL, ClientPortalAnimationManagement.CLIENT_PORTAL_DEFAULT_ANIMATION_FINISH) use
// the mod-owned com.warwa.seamlessportals.event.Event seam (Helper factories return it) — those
// are NOT this type (F12/B1). :common compile classpath only; the REAL fabric Event resolves at
// S13. Removed at S20.
package net.fabricmc.fabric.api.event;

import net.minecraft.resources.Identifier;

public class Event<T> {
    public static final Identifier DEFAULT_PHASE = null;

    public T invoker() {
        return null;
    }

    public void register(T listener) {
    }
}
