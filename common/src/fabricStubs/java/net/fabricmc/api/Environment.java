// S10-A loader-seam compileOnly stub (entity-portal migration; migration/EXECUTION_PLAN.md
// §3 S10; port-note S10A-loader-seam.md). NOT IP source, NOT shipped — a faithful shell of
// Fabric's @Environment side annotation. The @Environment POLICY decision is keep-verbatim:
// every held IP file keeps its @Environment(EnvType) annotation byte-for-byte, resolved here
// on the :common probe and against the REAL fabric-loader annotation at S13.
// NF-PARITY W1 (2026-08-25): retention corrected RUNTIME -> CLASS and targets extended to the
// real annotation's {TYPE, METHOD, FIELD, CONSTRUCTOR, PACKAGE} — measured by `javap -v` on
// fabric-loader-0.19.3.jar's net/fabricmc/api/Environment.class (RetentionPolicy.CLASS). The
// old RUNTIME line made NeoForge-compiled classes carry RuntimeVisibleAnnotations for an
// annotation type absent from the NeoForge runtime classpath; CLASS matches the Fabric-built
// bytecode exactly (RuntimeInvisibleAnnotations) and removes the only reflective exposure.
package net.fabricmc.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.PACKAGE})
public @interface Environment {
    EnvType value();
}
