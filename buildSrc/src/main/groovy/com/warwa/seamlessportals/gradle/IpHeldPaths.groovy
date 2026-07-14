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
            // everything outside q_misc_util stays held wholesale
            'qouteall/imm_ptl/**',
            // q_misc_util root files (Helper, MiscGlobals, + later-stage landings)
            'qouteall/q_misc_util/*.java',
            // q_misc_util subtrees other than my_util
            'qouteall/q_misc_util/api/**',
            'qouteall/q_misc_util/dimension/**',
            'qouteall/q_misc_util/ducks/**',
            'qouteall/q_misc_util/mixin/**',
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
