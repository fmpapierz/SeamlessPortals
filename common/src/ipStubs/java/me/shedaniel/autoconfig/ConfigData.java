// F21 Cloth-Config / AutoConfig compileOnly stub (entity-portal migration; EXECUTION_PLAN.md §3 S13
// "F21 EXTENSION: the stub classpath grows an autoconfig shell"; gate census S12B-gate-closure.md §4
// "U11 autoconfig mass"). NOT IP source, NOT shipped — a compileOnly shell in the ipStubs source set
// (wired to both the :common probe and the loader classpath exactly like the sodium/iris/gravity/DimLib
// shells). Cloth Config's AutoConfig has NO MC 26.2 build in this project's dependency set, so a stub is
// the correct resolution: it declares ONLY the FQN/kind + the members IPConfig/IPConfigGUI/IPModMain/
// IPGlobal actually touch (S12B census: 71 probe errors). The load-bearing behaviour (IPConfig
// .onConfigChanged() fan-out into IPGlobal) is pure common code and does not depend on this shell's
// runtime behaviour. Removed at S20.
package me.shedaniel.autoconfig;

/**
 * Marker interface implemented by config classes (IPConfig). Cloth-Config's real ConfigData carries a
 * default {@code validatePostLoad()}; IPConfig overrides nothing, so the stub is a pure marker.
 */
public interface ConfigData {
}
