package com.warwa.seamlessportals.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile

/**
 * D1 holding machinery for the entity-portal migration
 * (migration/EXECUTION_PLAN.md §1 D1; stage spec §3 S0; deleted at S20).
 *
 * Ported IP source lands at its FINAL path (common/src/main/java/qouteall/**, package
 * policy D2) from its first commit, but the SCC cannot compile until S13. Held paths are
 * therefore excluded from the COMPILE TASKS — never from the source set: common's source
 * DIRECTORY is published to the loaders via
 * {@code artifacts { commonJava sourceSets.main.java.sourceDirectories.singleFile }}
 * (common/build.gradle:35), so adding a second srcDir would break {@code singleFile} and
 * the whole loader consumption chain. One directory + a task-level filter is the only
 * mechanism that keeps the shipping gate green as written (D1).
 *
 * Wiring (all three sites mandated by D1 / "Immediately-next actions" item 1):
 *   1. :common:compileJava            — MAIN list (multiloader-common.gradle)
 *   2. loader compileJava             — MAIN list (multiloader-loader.gradle, immediately
 *      after source(configurations.commonJava): the loaders recompile the RAW common
 *      source directory, so a filter on common's task does NOT propagate there)
 *   3. :common:compileTestJava        — its OWN TEST list (D1.3): the carried IP tests
 *      (Mesh2DTest/HelperTest) live INSIDE the future my_util main carve-in path yet stay
 *      un-compilable until S13, so no single list can serve both main and test.
 *
 * Compile probe (the machine-checked forward-ref debt ledger, EXPECTED red until S13):
 *   .\gradlew.bat :common:compileJava -Pip_scc_closed=true --console=plain --no-daemon
 * (:common alone is enough for triage — the loaders recompile the same sources.)
 *
 * F21 note (S4): the held compat files reference third-party sodium/iris/gravity_changer
 * types absent on MC 26.2. Empty compileOnly shells live in the ipStubs source set
 * (common/build.gradle) and are wired onto :common:compileJava (there) AND the loader
 * compileJava tasks (multiloader-loader.gradle, ipStubsClasspath configuration) — the
 * probe compiles those held files on BOTH. compileOnly only; never shipped. Removed at S20.
 */
final class IpHeldPaths {

    /**
     * MAIN held-paths list, shared by the two MAIN compile paths (:common:compileJava and
     * the loader compileJava tasks — they see the same relative paths). Carve-ins are
     * MONOTONE: patterns only ever get narrower, each narrowing reviewed in its stage
     * commit, filtered by the stage-time import-grep (same-package references count).
     *
     * S2 narrowing (EXECUTION_PLAN.md S2(a)): the original blanket 'qouteall/**' is
     * re-expressed as the union below, which holds everything qouteall EXCEPT the
     * Helper-free my_util carve-in. The enumerated subtrees cover IP's COMPLETE package
     * universe (imm_ptl/** + the five q_misc_util subtrees), so every later stage's
     * landings arrive default-held. my_util is fully landed at S2 (all 37 IP files), so
     * its held remainder is the exact 9-file list: the 8 Helper-importers (Mesh2D.java:24,
     * IntBox, IntMatrix3, MyTaskList, Signal, SignalArged, SignalBiArged, LimitedLogger —
     * import-grep) + AARotation (zero qouteall imports, but same-package IntMatrix3 field
     * at AARotation.java:58 — same-package references count).
     */
    static final List<String> MAIN_HELD_PATHS = [
            // === imm_ptl (S4 narrowing) ============================================
            // S2 held imm_ptl wholesale ('qouteall/imm_ptl/**'). S4 pokes exactly two
            // holes: the vanilla-only-COMPILABLE ducks and the @IPVanillaCopy annotation.
            // Every other imm_ptl subpackage is enumerated held so future-stage landings
            // still arrive default-held (IP source is frozen at 1.21.3, so this covers the
            // complete package universe). If any enumerated subtree were missed, its held
            // files would be compiled by the SHIPPING build and turn it red — the shipping
            // gate is thus self-checking on this enumeration's completeness.
            'qouteall/imm_ptl/core/*.java',
            'qouteall/imm_ptl/core/api/**',
            'qouteall/imm_ptl/core/block_manipulation/**',
            'qouteall/imm_ptl/core/chunk_loading/**',
            'qouteall/imm_ptl/core/collision/**',
            'qouteall/imm_ptl/core/commands/**',
            'qouteall/imm_ptl/core/compat/**',
            'qouteall/imm_ptl/core/debug/**',
            'qouteall/imm_ptl/core/mc_utils/**',
            'qouteall/imm_ptl/core/mixin/**',
            'qouteall/imm_ptl/core/network/**',
            'qouteall/imm_ptl/core/platform_specific/**',
            'qouteall/imm_ptl/core/portal/**',
            'qouteall/imm_ptl/core/redstone/**',
            'qouteall/imm_ptl/core/render/**',
            'qouteall/imm_ptl/core/teleportation/**',
            'qouteall/imm_ptl/peripheral/**',
            // core/miscellaneous: hold every file EXCEPT the carved-in IPVanillaCopy.java.
            // The dir is NOT complete at S4 (the other 4 land later), so they are held by
            // name (no wildcard, which would re-hold IPVanillaCopy).
            'qouteall/imm_ptl/core/miscellaneous/ClientPerformanceMonitor.java',
            'qouteall/imm_ptl/core/miscellaneous/DubiousThings.java',
            'qouteall/imm_ptl/core/miscellaneous/GcMonitor.java',
            'qouteall/imm_ptl/core/miscellaneous/IPortalInitialScreen.java',
            // core/ducks (S4 carve-in = 29 of 36; the other 7 are HELD, two reasons):
            //  (1) 3 ducks import HELD qouteall classes (IEClientWorld->Portal U4,
            //      IEEntity->PortalCollisionHandler+Portal U4/U6,
            //      IEMinecraftServer->IPPerServerInfo held U2).
            //  (2) 4 ducks reference GONE/INCOMPATIBLE 26.2 vanilla types that NO access
            //      widener can fix (renamed/removed classes, generic-arity change) and do NOT
            //      compile against vanilla (empirically confirmed by :common:compileJava —
            //      the D1 "the compiler is the authority" rule; redesign is a later
            //      render/mixin/command slice, not a mechanical S4 translation):
            //        IEWorldRenderer     -> MultiBufferSource GONE (S11/S12)
            //        IEShader            -> com.mojang.blaze3d.shaders.Uniform GONE (S11/S12)
            //        IEGameRenderer      -> LightTexture GONE (S11/S12)
            //        IEDistanceManager   -> Ticket is non-generic in 26.2 (S13/commands)
            // IEChunkMap is NOT held: 26.2 makes ChunkMap.TrackedEntity a private nested class
            // (the S04-ducks.md fragment verified the type EXISTS but not that it is
            // ACCESSIBLE — the compiler caught it), but IP's own accesswidener widens exactly
            // that type. The faithful fix is to grow the mod's AW+AT (done: seamlessportals
            // .accesswidener + accesstransformer.cfg), after which IEChunkMap compiles and is
            // carved in — "grow AW/AT lists as ducks demand" (plan S4(a)).
            'qouteall/imm_ptl/core/ducks/IEClientWorld.java',
            'qouteall/imm_ptl/core/ducks/IEEntity.java',
            'qouteall/imm_ptl/core/ducks/IEMinecraftServer.java',
            'qouteall/imm_ptl/core/ducks/IEWorldRenderer.java',
            'qouteall/imm_ptl/core/ducks/IEShader.java',
            'qouteall/imm_ptl/core/ducks/IEGameRenderer.java',
            'qouteall/imm_ptl/core/ducks/IEDistanceManager.java',
            // === q_misc_util =======================================================
            // q_misc_util root files (Helper, MiscGlobals, + later-stage landings)
            'qouteall/q_misc_util/*.java',
            // q_misc_util subtrees other than my_util + the two S4 carve-ins:
            //   ducks/IEMinecraftServer_Misc (vanilla-only) is carved in -> hold nothing
            //     in q_misc_util/ducks (it is the dir's only file, complete at S4).
            //   mixin/IELevelStorageAccess_Misc (vanilla-only @Accessor) is carved in ->
            //     hold the future S7 mixin files by name/subtree so they stay default-held.
            'qouteall/q_misc_util/api/**',
            'qouteall/q_misc_util/dimension/**',
            'qouteall/q_misc_util/mixin/MixinMinecraftServer_Misc.java',
            'qouteall/q_misc_util/mixin/client/**',
            'qouteall/q_misc_util/mixin/dimension/**',
            // the 9 held my_util files (Helper chain; see javadoc above)
            'qouteall/q_misc_util/my_util/AARotation.java',
            'qouteall/q_misc_util/my_util/IntBox.java',
            'qouteall/q_misc_util/my_util/IntMatrix3.java',
            'qouteall/q_misc_util/my_util/LimitedLogger.java',
            'qouteall/q_misc_util/my_util/Mesh2D.java',
            'qouteall/q_misc_util/my_util/MyTaskList.java',
            'qouteall/q_misc_util/my_util/Signal.java',
            'qouteall/q_misc_util/my_util/SignalArged.java',
            'qouteall/q_misc_util/my_util/SignalBiArged.java',
    ]

    /**
     * TEST held-paths list for :common:compileTestJava — a SEPARATE list, NOT the main
     * list (D1.3). It diverges at S2: my_util/** is carved in for tests while explicit
     * exclusions for Mesh2DTest.java + HelperTest.java remain until S13 (both carried IP
     * tests need the held Helper -> McHelper -> SCC chain to compile: HelperTest imports
     * Helper directly; Mesh2DTest imports Mesh2D, which imports Helper at Mesh2D.java:24).
     * The non-my_util qouteall subtrees stay held with the same shape as the MAIN list
     * (no such test files exist today — IP's whole test tree is the two files above).
     */
    static final List<String> TEST_HELD_PATHS = [
            'qouteall/imm_ptl/**',
            'qouteall/q_misc_util/*.java',
            'qouteall/q_misc_util/api/**',
            'qouteall/q_misc_util/dimension/**',
            'qouteall/q_misc_util/ducks/**',
            'qouteall/q_misc_util/mixin/**',
            // my_util/** carved in for tests at S2, EXCEPT the two carried IP tests
            '**/Mesh2DTest.java',
            '**/HelperTest.java',
    ]

    /**
     * Applies the given held-paths list as {@code exclude} patterns on the given
     * JavaCompile task, unless the gradle property {@code ip_scc_closed} is the string
     * 'true' (default 'false' in gradle.properties; overridable per invocation with
     * -Pip_scc_closed=true — the compile probe).
     */
    static void applyHolding(Project project, JavaCompile task, List<String> heldPaths) {
        if (String.valueOf(project.findProperty('ip_scc_closed')) != 'true') {
            task.exclude(heldPaths)
        } else {
            // Compile probe: generous error limit so the whole forward-ref cascade is
            // visible for triage against the stage ledger (D1: "Use generous
            // options.compilerArgs error limits so cascades are fully visible").
            task.options.compilerArgs.addAll(['-Xmaxerrs', '10000'])
        }
    }

    private IpHeldPaths() {
    }
}
