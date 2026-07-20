package qouteall.imm_ptl.core.compat.iris_compatibility;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * IS0 — THE PROBE LEVER HOLDER (iris shaders-ON engagement;
 * {@code migration/IRIS_SHADERS_ON_DESIGN.md} §1 IS0 deliverable 3; port-note
 * {@code migration/port-notes/IS-iris-shaders-on.md} §1.3 / §1.5 implementation record).
 *
 * <p>Exists so the anchor mixin's per-frame lever check does NOT class-initialize
 * {@link ShaderpackViewsProbe} (Lens-B IS0 verify correction): {@code Boolean.getBoolean} is a
 * METHOD CALL, not a javac compile-time constant, so a static-final read hosted on the probe
 * class itself would trigger the probe's {@code <clinit>} on the first renderLevel frame in
 * EVERY environment (crossing gametest included). This holder's {@code <clinit>} is the one
 * field below; the probe class loads only when the property is set and the anchor first
 * dispatches into it.
 *
 * <p>Deliberately NOT hosted on the mixin class — Mixin does not merge static initializers,
 * so a non-constant static field initializer on a mixin is silently dropped.
 */
@Environment(EnvType.CLIENT)
public final class ShaderpackViewsProbeLever {

    /** The probe lever, read once at class-init (a JVM property fixed at launch). */
    public static final boolean PROBE_ENABLED =
        Boolean.getBoolean("seamlessportals.shaderpackViewsProbe");

    private ShaderpackViewsProbeLever() {}
}
