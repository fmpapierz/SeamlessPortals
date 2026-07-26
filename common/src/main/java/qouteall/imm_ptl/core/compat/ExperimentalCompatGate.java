package qouteall.imm_ptl.core.compat;

/**
 * The Sodium/Iris compat gate — born as the S19-E honest-gating scaffold, now (since C2-2/C2-4)
 * the LIVE per-mod verdict gate. HISTORY (lens-B corrected, C2-4): the paragraphs that stood
 * here described the S19-E posture (default-false, "unverified internals", warn+force as the
 * norm) — that era ended as C2 landed the verified chains stage by stage (C2-1 swap core,
 * C2-2 clipping + DEFAULT-ON per user decision #1, C2-3 culling, C2-4 iris). The CURRENT truth
 * lives on {@link #ENABLE_SODIUM_IRIS_COMPAT}'s own javadoc: default {@code true}, one gate
 * covering BOTH per-mod verdicts (the activation formula is gate {@code ||} the
 * {@code -Dseamlessportals.experimentalSodiumCompat=true} JVM lever — the lever is an
 * ON-override only), iris shaders-ON routing to the honest pass-through until the C2-5
 * shaders-ON decision.
 *
 * <p>The warn-and-force machinery below survives for the D7 iris-resolve-failure fallback and
 * for gate-false (rollback) builds; the D11 tracker feed installs independently of the gate
 * whenever sodium is present (correctness plumbing). (S20: the note that this was "entirely inert
 * flag-OFF" is history — the {@code entityPortals} flag is deleted, and the detection its
 * client-init branch hosted now always runs on Fabric.) DISPOSITION: the C2-5 close-out decides
 * whether this class survives as the rollback switch or is deleted with the warn machinery — it is
 * NOT S20 collateral, and note that {@link #ENABLE_SODIUM_IRIS_COMPAT}'s VALUE is load-bearing for
 * the IS4 default-ON shaderpack portal views (port-note §E.1 row 2).
 */
public final class ExperimentalCompatGate {
    private ExperimentalCompatGate() {}

    /**
     * The sodium-compat gate. <b>DEFAULT {@code true} SINCE C2-2 — USER DECISION #1
     * (2026-07-19): sodium compat ships DEFAULT-ON</b> once the D3 clip transport landed (the
     * full chain: D1 widened context swap, D5 entity-cull neutralize, #3 per-layer render lists,
     * FlawlessFrames bridge, the C2-1b/c/d live-round fixes + dest drive + endFrame walk, C2-1e
     * dest entities, and the C2-2 sodium shader source patch + GLDrawContext clip upload which
     * retired the D10 interim bracket). Sodium users get portal views out of the box with the
     * GOLD experimental notice retained. Set {@code false} (or ship a build with it false) as the
     * one-boolean ROLLBACK to the warn+force-off posture; the
     * {@code -Dseamlessportals.experimentalSodiumCompat=true} JVM lever remains as an override
     * that can turn compat ON when the gate is false (it cannot turn it off). Non-{@code final}
     * per {@code IPGlobal}'s lever convention.
     *
     * <p><b>SINCE C2-4 this ONE gate activates BOTH verdicts</b> (the name finally true):
     * {@code sodiumActive = sodiumPresent && gate}, {@code irisActive = irisPresent && gate}.
     * Iris-present installs get {@code OnIrisPresent} live (D7 loud pipeline-field resolve at
     * install; resolve failure → warn+force fallback, never silent) with honest per-state
     * routing (D8): shaders OFF = full portal views (sodium chains active underneath — iris
     * requires sodium; the C2-1 {@code !irisPresent} sodium exclusion is retired); shaders ON =
     * {@code rendererDummy} pass-through + one-shot notice, until the shaders-ON re-expression
     * (C2-5 user checkpoint). Mod-ABSENT installs are untouched by this flag entirely
     * (the activation condition is present &amp;&amp; gate — presence short-circuits). See
     * {@code SeamlessPortalsClientFabric.detectAndGateRenderCompat} for the per-mod verdicts.
     */
    public static boolean ENABLE_SODIUM_IRIS_COMPAT = true;

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
