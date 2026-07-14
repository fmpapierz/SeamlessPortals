// S10-A loader-seam compileOnly stub (entity-portal migration; migration/EXECUTION_PLAN.md
// §3 S10; port-note S10A-loader-seam.md). NOT IP source, NOT shipped — a faithful shell of
// Fabric's @Environment side annotation. The @Environment POLICY decision is keep-verbatim:
// every held IP file keeps its @Environment(EnvType) annotation byte-for-byte, resolved here
// on the :common probe and against the REAL fabric-loader annotation at S13. RUNTIME retention
// + TYPE/METHOD/FIELD targets match the real annotation shape. :common compile classpath only.
// Removed at S20.
package net.fabricmc.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
public @interface Environment {
    EnvType value();
}
