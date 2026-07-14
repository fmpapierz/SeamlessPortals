// S10-A loader-seam compileOnly stub (entity-portal migration; migration/EXECUTION_PLAN.md
// §3 S10; port-note S10A-loader-seam.md). NOT IP source, NOT shipped — a faithful shell of
// Fabric's physical-side enum so the HELD IP tree's @Environment(EnvType.CLIENT/SERVER)
// annotations + EnvType.SERVER value-uses compile on the loader-neutral :common probe. On the
// fabric loader (S13) the REAL fabric-loader provides this enum. :common compile classpath
// only (never the loader classpath). Removed at S20.
package net.fabricmc.api;

public enum EnvType {
    CLIENT,
    SERVER
}
