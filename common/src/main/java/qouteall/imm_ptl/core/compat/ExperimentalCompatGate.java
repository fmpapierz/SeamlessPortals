package qouteall.imm_ptl.core.compat;

/**
 * S19-E increment 3 — NAMED DEVIATION scaffold (removed / flipped at C2 completion).
 *
 * <p>The real Sodium 0.9.1 / Iris 1.11.2 compat classes
 * ({@link qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface.OnSodiumPresent},
 * {@link qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface.OnIrisPresent},
 * {@link qouteall.imm_ptl.core.compat.iris_compatibility.ExperimentalIrisPortalRenderer}) were
 * re-expressed onto the 26.2 render surface but are NOT yet retargeted to / verified against the
 * mod's live render substrate — that is the C2 work (see migration/C2_IP_COMPAT_DEPTH.md).
 * Swapping {@code SodiumInterface.invoker} / {@code IrisInterface.invoker} to the {@code On*Present}
 * subclasses today would activate IP render paths ({@code PortalRenderer.switchToCorrectRenderer},
 * the {@code MyGameRenderer} Sodium-context / Iris-pipeline calls) against unverified internals.
 *
 * <p>So the mod-presence DETECTION ({@code FabricLoader.isModLoaded}, cheap + side-effect-free)
 * ALWAYS runs flag-ON, but the invoker swap + {@code ExperimentalIrisPortalRenderer.init()} are
 * gated behind {@link #ENABLE_SODIUM_IRIS_COMPAT} (default {@code false}). While the gate is off
 * and Sodium/Iris IS present flag-ON, the mod warns loudly (log + one-shot world-join chat) and
 * force-disables portal VIEWS for the session ({@code IPGlobal.renderMode = none}, NOT persisted)
 * rather than rendering garbage. Teleportation and portal creation are unaffected.
 *
 * <p>C2 removes this whole class: flip / delete the gate, delete the warn-and-force branch in
 * {@link com.warwa.seamlessportals.fabric.SeamlessPortalsClientFabric} and the force-persistence
 * guard in {@link qouteall.imm_ptl.core.platform_specific.IPConfig#onConfigChanged()}.
 *
 * <p>Flag-OFF this is entirely inert: the detection lives inside the {@code entityPortals}
 * client-init branch, and the block-era compat handling
 * ({@link com.warwa.seamlessportals.compat.SodiumCompat} /
 * {@link com.warwa.seamlessportals.mixin.SeamlessMixinConfigPlugin}) owns the shipping baseline.
 */
public final class ExperimentalCompatGate {
    private ExperimentalCompatGate() {}

    /**
     * The C2-held gate lever. Default {@code false} = the ported Sodium/Iris compat layer stays
     * dormant (the invoker is never swapped away from the no-op base {@code Invoker}). Flipped to
     * {@code true} (or this whole class deleted) at C2 completion, once the compat layer is
     * retargeted to the 26.2 render substrate. Non-{@code final} to match {@code IPGlobal}'s lever
     * convention and to keep the gate-on branch a genuine runtime branch (so its lazy-classload
     * discipline is preserved rather than being compile-time dead-code).
     */
    public static boolean ENABLE_SODIUM_IRIS_COMPAT = false;

    /**
     * Session-scoped force flag. Set {@code true} by the fabric client detection when Sodium/Iris
     * is present flag-ON while {@link #ENABLE_SODIUM_IRIS_COMPAT} is off. Consulted by
     * {@link qouteall.imm_ptl.core.platform_specific.IPConfig#onConfigChanged()} to re-force
     * {@code IPGlobal.renderMode = none} across any runtime config reload (e.g. an in-game config
     * save) — it is NEVER written to the config file, so the user's persisted {@code renderMode}
     * preference is untouched. Only ever set on the client; stays {@code false} on dedicated
     * servers and in the flag-OFF baseline.
     */
    public static volatile boolean forcePortalRenderingOffThisSession = false;
}
