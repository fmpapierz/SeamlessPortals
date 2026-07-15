// F21 Cloth-Config / AutoConfig — SHIPPED functional no-op (entity-portal migration; S13-B P-1 fix).
// PROMOTED from the compileOnly ipStubs shell to shipped in-tree source, mirroring the F11 DimLib
// promotion (qouteall/dimlib), because IP's init sequence calls AutoConfig.register(IPConfig.class, …)
// at RUNTIME behind the entityPortals flag (IPModMain.loadConfig, IPModMain.java:146) and Cloth
// Config's AutoConfig has NO MC 26.2 build in this project's dependency set. A compileOnly stub
// returns null at that call → NPE at configHolder.registerSaveListener → flag-ON boot dies.
//
// This package is a functional, Gson-backed reimplementation of exactly the Cloth-Config surface IP
// touches (register / ConfigHolder get/set/save/registerSaveListener / getConfigScreen): it loads the
// config JSON on register and persists it on save, so flag-ON config behaviour is faithful. IPConfig's
// own onConfigChanged() clamping (the load-bearing fan-out into IPGlobal) is pure common code and
// does not depend on Cloth's GUI/annotation processing.
//
// NOT loaded at all when entityPortals is OFF (nothing references it until IP init is wired flag-ON),
// so shipping it is inert for the block-era baseline. Deleted with the migration scaffolding at S20,
// when the real Cloth-Config dependency (if any) replaces it.
package me.shedaniel.autoconfig;

/**
 * Marker interface implemented by config classes (IPConfig). Cloth-Config's real ConfigData carries a
 * default {@code validatePostLoad()}; IPConfig overrides nothing, so this stays a pure marker.
 */
public interface ConfigData {
}
