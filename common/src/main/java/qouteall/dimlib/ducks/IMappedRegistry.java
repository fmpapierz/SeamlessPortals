// F11 DimLib — S19-D LANDED (migration/port-notes/S19-peripheral-tail.md §6). Minimal port of the
// REAL DimLib IMappedRegistry duck: only the `frozen` accessors are needed for the load-window
// (direct level-stem registration). IP's dimlib_forceRemove is part of the DYNAMIC-remove path
// (R13g-PHASE-2, deferred) and intentionally omitted. Implemented by MixinMappedRegistry.
package qouteall.dimlib.ducks;

public interface IMappedRegistry {

    boolean dimlib_getIsFrozen();

    /**
     * Note: un-freeze is only safe when no place uses its holder.
     */
    void dimlib_setIsFrozen(boolean cond);
}
