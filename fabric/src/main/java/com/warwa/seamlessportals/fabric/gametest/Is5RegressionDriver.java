package com.warwa.seamlessportals.fabric.gametest;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * IS5-PRE REGRESSION DRIVER ({@code migration/IS5_PRE_REGRESSION_HANDOFF.md} §2) — a scripted
 * shaders-ON leg that stages the ring scene and walks the regression matrix with screenshots as
 * classifiable evidence. The whole point is A/B discipline: the SAME code stages the SAME scene
 * and drives the SAME motion on both paths, so a pair of runs differs in exactly one variable —
 * the {@code -PstageConsistentComposite} lever.
 *
 * <p>Legs (full mode): static window → MB strafe burst (the ring's reproduction motion — player
 * TRANSLATION, not panning; the ghost-double lesson) → XWIN third-person-back through the
 * aperture → crossing BOTH directions through a bi-way portal pair (hand + seam + burst frames)
 * → nested recursion corridor (part4's A/B surface) → windowed resize both directions (the
 * FBO no-cache regression) → shaders OFF/ON toggle (stencil family + pipeline-recreate rebuild).
 *
 * <p>C2 mode ({@code -Pis5C2HoldSeconds=N}): the {@code C2_JIT_PROTECTION.md} §6 recipe instead —
 * a DENSE portal stack immediately on world load (the call site must get hot BEFORE C2 decides
 * the inline), continuous motion, periodic cross-dim teleports back to the same viewpoint, no
 * shader toggling (a bimorphic {@code runnable.run()} site prevents the weld from forming — a
 * false negative). The gate is UNIT SIZE in {@code c2_inlining.xml}, never a clean run.
 *
 * <p>Evidence contract: screenshots land in {@code runs/gametest-is5/screenshots/} prefixed
 * {@code new-}/{@code old-} (read from {@link IPGlobal#STAGE_CONSISTENT_COMPOSITE}, the derived
 * truth — not the raw property, which the default flip will invert). Functional legs hard-throw;
 * visual legs are fail-soft (a capture failure must not void the functional record). A run with
 * {@code -Dseamlessportals.is5.expectIris=true} (baked by the run block when {@code -PirisRuntime}
 * is present) hard-fails if the shaderpack did not actually load — a leg without its landing
 * proof is VOID, not a pass.
 */
public class Is5RegressionDriver implements FabricClientGameTest {

    private static final String LOG = "[IS5-REGRESSION] ";

    /** Portal plane distance north (−Z) of the player, the CrossingSmoke convention. */
    private static final int PORTAL_OFFSET_Z = 6;

    @Override
    public void runTest(ClientGameTestContext context) {
        String only = System.getProperty("seamlessportals.gametest.only", "");
        if (!only.isEmpty() && !only.equals("is5regression")) {
            SeamlessPortalsConstants.LOGGER.info(LOG + "skipped (selected test: {})", only);
            return;
        }

        final String pathTag = IPGlobal.STAGE_CONSISTENT_COMPOSITE ? "new" : "old";
        final boolean expectIris = Boolean.getBoolean("seamlessportals.is5.expectIris");
        final int c2HoldSeconds = Integer.getInteger("seamlessportals.is5.c2holdSeconds", 0);
        SeamlessPortalsConstants.LOGGER.info(
            LOG + "starting — path={} expectIris={} c2HoldSeconds={}",
            pathTag, expectIris, c2HoldSeconds);

        context.runOnClient(mc -> {
            mc.options.renderDistance().set(8);
            // 60 fps CAP — measured 2026-08-05 leg-A defect: at the harness's uncapped fps the
            // per-FRAME camera delta from a 10 m/s strafe is ~1-2px of smear, which put the ring
            // discriminator at the instrument's resolution limit (edge widths 1-2px static vs
            // strafe — NON-DISCRIMINATING, and a resolution limit is a bound, not a finding).
            // 60 fps at 14 m/s gives a ~15-20px smear: the user's whip regime.
            mc.options.framerateLimit().set(60);
        });

        try (TestSingleplayerContext sp = context.worldBuilder()
                .setUseConsistentSettings(true)
                .adjustSettings(s -> {
                    s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    s.setName("is5-regression-" + pathTag);
                })
                .create()) {

            context.waitTicks(80);

            // Landing proof FIRST: with -PirisRuntime the pack must actually be in use, or every
            // shaders-ON verdict this run produces is a measurement of the wrong renderer.
            if (expectIris && !isIrisPackActive(context)) {
                throw new AssertionError(LOG + "expectIris=true but no iris shaderpack is in use"
                    + " — the pack/iris.properties staging failed; this leg is VOID");
            }

            BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
            int px = feet.getX(), py = feet.getY(), pz = feet.getZ();
            double planeZ = pz - PORTAL_OFFSET_Z + 0.5;
            SeamlessPortalsConstants.LOGGER.info(LOG + "staging around ({},{},{})", px, py, pz);

            stageScene(context, px, py, pz, planeZ);

            if (c2HoldSeconds > 0) {
                runC2DenseHold(context, px, py, pz, planeZ, pathTag, c2HoldSeconds);
                SeamlessPortalsConstants.LOGGER.info(LOG + "C2 HOLD COMPLETE — gate on the unit"
                    + " sizes in c2_inlining.xml, never on this clean exit");
                return;
            }

            // ---- leg 1: static window ------------------------------------------------------
            // Enlarge the harness window first: 854x480 leaves the classification starved of
            // pixels. The later resize leg still exercises both directions from this base.
            context.runOnClient(mc -> mc.getWindow().setWindowed(1600, 900));
            context.waitTicks(20);
            pinPlayer(context, px + 0.5, py, pz + 1.5, 180f, 2f);
            context.waitTicks(60); // let the dest view fill
            shot(context, pathTag + "-static-window");

            // ---- leg 2: the MB strafe burst (the ring's reproduction motion) ---------------
            // Player TRANSLATION with MB=1 str=2.00 on the staged sidecar. The white occluder
            // pillar overlaps the window's left half; the ring — if present — is SOURCE-world
            // pixels (obsidian/sky) in the smear gap around the pillar silhouette against the
            // orange/glowstone dest wall. Pre-registered check: these frames must show visible
            // motion streaking, or the motion mechanism failed and the ring verdict is VOID.
            context.runOnClient(mc -> {
                mc.player.getAbilities().mayfly = true;
                mc.player.getAbilities().flying = true;
            });
            for (int cycle = 0; cycle < 6; cycle++) {
                double vx = (cycle % 2 == 0) ? 0.7 : -0.7;
                for (int t = 0; t < 8; t++) {
                    context.runOnClient(mc ->
                        mc.player.setDeltaMovement(vx, 0, 0));
                    context.waitTicks(1);
                    if (t == 4 && cycle >= 1) {
                        shot(context, pathTag + "-ring-strafe-" + cycle);
                    }
                }
            }
            context.runOnClient(mc -> mc.player.setDeltaMovement(Vec3.ZERO));

            // ---- leg 2b: the 20 fps strafe (the MB-liveness discriminator) -----------------
            // Measured 2026-08-05: at 60 fps every captured strafe frame was 1px-sharp on BOTH
            // paths — consistent with the capture landing on settled frames whose per-frame
            // camera delta is zero (frames between tick advances). At 20 fps there is ONE frame
            // per tick, so EVERY frame carries the full 0.7-block tick delta: if the pack's MB
            // reaches harness frames at all, these shots MUST streak; if they stay sharp, MB is
            // structurally dead in-harness and the ring row routes to the live client.
            context.runOnClient(mc -> mc.options.framerateLimit().set(20));
            pinPlayer(context, px + 0.5, py, pz + 1.5, 180f, 2f);
            for (int cycle = 0; cycle < 4; cycle++) {
                double vx = (cycle % 2 == 0) ? 0.7 : -0.7;
                for (int t = 0; t < 8; t++) {
                    context.runOnClient(mc ->
                        mc.player.setDeltaMovement(vx, 0, 0));
                    context.waitTicks(1);
                    if (t == 5 && cycle >= 1) {
                        shot(context, pathTag + "-ring-strafe20-" + cycle);
                    }
                }
            }
            context.runOnClient(mc -> {
                mc.player.setDeltaMovement(Vec3.ZERO);
                mc.options.framerateLimit().set(60);
            });

            // ---- leg 2c: the blur-BURST ring frames (the PROVEN in-harness MB carrier) -----
            // Ordinary strafe frames measured blur-free in-harness at 60 AND 20 fps, while the
            // crossing-ARRIVAL frame blurs heavily (0008, both paths) — the one MB carrier this
            // harness demonstrably delivers is a large same-frame camera jump. Reproduce it
            // deterministically: a client-side 10-block position jump toward the scene, shot on
            // the very next frame. The occluder pillar overlaps the window in that frame — the
            // ring, when the mechanism regresses, is SOURCE pixels ringing the pillar there.
            for (int i = 1; i <= 3; i++) {
                pinPlayer(context, px + 0.5, py, pz + 11.5, 180f, 2f);
                context.waitTicks(10);
                context.runOnClient(mc ->
                    // xo/yo/zo deliberately NOT pinned — the camera must see a genuine jump
                    mc.player.setPos(px + 0.5, py, pz + 1.5));
                // THREE consecutive captures, no waits: the burst lives on the 1-3 frames right
                // after the jump (the settled crossing shot is already sharp 10 ticks later),
                // and a single same-task capture lands on the PRE-jump frame (measured: all six
                // single-shot reps showed the far view, unblurred). Shots b/c land 1-2 frames
                // later — inside the burst window.
                shot(context, pathTag + "-burst-ring-" + i + "a");
                shot(context, pathTag + "-burst-ring-" + i + "b");
                shot(context, pathTag + "-burst-ring-" + i + "c");
                context.waitTicks(5);
            }
            pinPlayer(context, px + 0.5, py, pz + 1.5, 180f, 2f);

            // ---- leg 3: XWIN — third-person-back camera through the aperture ---------------
            // Cross-view frames keep the OLD compositing on both paths (design §2, disclosed:
            // the ring persists there). The gate here is only "the reverse window still
            // renders" — the XWIN pass must not have been broken by the fork.
            try {
                pinPlayer(context, px + 0.5, py, planeZ + 1.2, 0f, 0f);
                context.runOnClient(mc ->
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                context.waitTicks(40);
                shot(context, pathTag + "-xwin-third-person");
            } finally {
                context.runOnClient(mc ->
                    mc.options.setCameraType(CameraType.FIRST_PERSON));
            }
            // XWIN cross-dim variant — the HISTORICAL defect class (third person through a
            // cross-dim portal): back camera through portal B into the nether; the reverse
            // window must show the player's overworld body from the nether side.
            try {
                pinPlayer(context, px + 5.5, py, planeZ + 1.2, 0f, 0f);
                context.runOnClient(mc ->
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                context.waitTicks(40);
                shot(context, pathTag + "-xwin-crossdim-third-person");
            } finally {
                context.runOnClient(mc ->
                    mc.options.setCameraType(CameraType.FIRST_PERSON));
            }

            // ---- leg 4: crossing BOTH directions (hand + seam + burst frames) --------------
            pinPlayer(context, px + 0.5, py + 1.0, pz + 1.5, 180f, 0f);
            context.waitTicks(10);
            shot(context, pathTag + "-pre-crossing");
            flyUntil(context, 0, -0.8, mc -> mc.player.getX() > px + 50, 120,
                "crossing north through portal A");
            shot(context, pathTag + "-post-crossing-arrival");
            context.waitTicks(10);
            shot(context, pathTag + "-post-crossing-settled");
            assertClientCoherent(context, "crossing A->dest");

            // Return: the player is north of the reverse portal R's plane; face south, fly +Z.
            context.getInput().lookAt(0f, 0f);
            context.waitTicks(5);
            flyUntil(context, 0, 0.8, mc -> mc.player.getX() < px + 50, 120,
                "crossing south back through portal R");
            shot(context, pathTag + "-return-crossing-arrival");
            assertClientCoherent(context, "crossing R->origin");
            SeamlessPortalsConstants.LOGGER.info(LOG + "crossing legs PASS both directions");

            // ---- leg 5: nested recursion corridor (part4's A/B surface) --------------------
            // Portal N faces itself 9 blocks back: through N you see N again — layer 1 renders
            // under the default irisMaxPortalLayer=2. Pre-part4 the NEW path defers nested
            // layers (announced once in the log — the inner window stays flat); post-part4 the
            // inner window carries content. The OLD path recurses today (IS5-REC).
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                // Dest offset 3 blocks WEST of the origin: a zero-offset corridor is visually
                // SEAMLESS (each recursion level shows the identical scene — measured 2026-08-05:
                // deferred and recursing paths were indistinguishable), so the corridor steps
                // west per level instead: the layer-1 window shows a shifted scene containing N
                // again off-axis, and the recursion cut is where the series stops.
                spawnPortal(ow, new Vec3(px - 6.5, py + 2.5, planeZ),
                    new Vec3(1, 0, 0), 4, 4,
                    Level.OVERWORLD, new Vec3(px - 9.5, py + 2.5, planeZ + 9));
            });
            pinPlayer(context, px - 6.5, py, pz + 1.5, 180f, 2f);
            context.waitTicks(60);
            shot(context, pathTag + "-nested-corridor-early");
            context.waitTicks(100);
            shot(context, pathTag + "-nested-corridor-settled");

            // ---- leg 6: windowed resize, both directions (the FBO no-cache regression) -----
            // Two latch classes were paid for here; the fix is NO caching at all. The gate:
            // capture-geometry lines track each size and the window survives — census green,
            // no breakMechanism, window visible in both shots.
            try {
                int[] orig = context.computeOnClient(mc -> new int[]{
                    mc.getWindow().getWidth(), mc.getWindow().getHeight()});
                pinPlayer(context, px + 0.5, py, pz + 1.5, 180f, 2f);
                context.runOnClient(mc -> mc.getWindow().setWindowed(1024, 640));
                context.waitTicks(30);
                shot(context, pathTag + "-resized-small");
                context.runOnClient(mc -> mc.getWindow().setWindowed(1600, 900));
                context.waitTicks(30);
                shot(context, pathTag + "-resized-restored");
                SeamlessPortalsConstants.LOGGER.info(LOG + "resize leg done (base was {}x{})",
                    orig[0], orig[1]);
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "resize leg FAILED (non-fatal)", t);
            }

            // ---- leg 7: shaders OFF -> ON (stencil family + the pipeline-recreate rebuild) --
            // Runs LAST deliberately: a shader toggle makes the query call site bimorphic and
            // would poison any C2 observation, and the recreate is the harshest rebuild the
            // no-cache stamp FBOs face. Shaders-OFF must show the stencil-family window
            // (untouched by this arc); restored shaders-ON must show the window again.
            if (expectIris) {
                setShadersEnabled(context, false);
                context.waitTicks(60);
                shot(context, pathTag + "-shaders-off-stencil");
                setShadersEnabled(context, true);
                context.waitTicks(100);
                shot(context, pathTag + "-shaders-restored");
                // Post-rebuild strafe: the window must still composite under motion.
                for (int t = 0; t < 10; t++) {
                    context.runOnClient(mc -> mc.player.setDeltaMovement(0.5, 0, 0));
                    context.waitTicks(1);
                }
                shot(context, pathTag + "-post-toggle-strafe");
                context.runOnClient(mc -> mc.player.setDeltaMovement(Vec3.ZERO));
            }

            SeamlessPortalsConstants.LOGGER.info(LOG + "ALL DRIVER LEGS COMPLETE — path={}", pathTag);
        }
    }

    // =============================================================================================
    // The scene — identical on every run: platform, dest wall (orange + glowstone bloom stripe),
    // portal pair A/R (bi-way, 4x4), cross-dim portal B, the white occluder pillar.
    // =============================================================================================

    private void stageScene(ClientGameTestContext context, int px, int py, int pz, double planeZ) {
        runCommands(context, List.of(
            "gamerule doDaylightCycle false",
            "gamerule doWeatherCycle false",
            "gamerule doMobSpawning false",
            "gamerule doFireTick false",
            "time set 1000",
            "weather clear",
            // Source platform + cleared box (portals live at planeZ = pz-5.5).
            "fill " + (px - 10) + " " + (py - 1) + " " + (pz - 12) + " "
                + (px + 10) + " " + (py - 1) + " " + (pz + 6) + " minecraft:obsidian",
            "fill " + (px - 10) + " " + py + " " + (pz - 12) + " "
                + (px + 10) + " " + (py + 8) + " " + (pz + 6) + " minecraft:air",
            // Same-dim dest area (+100 x): floor pad, cleared corridor, and the view wall the
            // window rays land on (the §2.7 lesson: a mid-air dest is sky-only and
            // NON-DISCRIMINATING). Orange concrete = unmistakable window content against the
            // obsidian/sky source; the glowstone stripe is the bloom source for the BLOOMMB row.
            "fill " + (px + 90) + " " + (py - 1) + " " + (pz - 12) + " "
                + (px + 110) + " " + (py - 1) + " " + (pz + 6) + " minecraft:obsidian",
            "fill " + (px + 90) + " " + py + " " + (pz - 12) + " "
                + (px + 110) + " " + (py + 13) + " " + (pz + 6) + " minecraft:air",
            "fill " + (px + 92) + " " + py + " " + (pz - 12) + " "
                + (px + 109) + " " + (py + 13) + " " + (pz - 11) + " minecraft:orange_concrete",
            "fill " + (px + 92) + " " + (py + 4) + " " + (pz - 12) + " "
                + (px + 109) + " " + (py + 6) + " " + (pz - 11) + " minecraft:glowstone",
            // Same-dim + nether dest chunks stay loaded.
            "forceload add " + (px + 88) + " " + (pz - 14) + " " + (px + 112) + " " + (pz + 8),
            "execute in minecraft:the_nether run forceload add -16 -16 16 16",
            // THE OCCLUDER: a white pillar between the stand point and portal A's window,
            // offset west so window content stays visible around it. The ring — when the
            // mechanism regresses — is the SOURCE world ringing THIS silhouette under MB.
            "fill " + (px - 1) + " " + py + " " + (pz - 4) + " "
                + (px - 1) + " " + (py + 3) + " " + (pz - 4) + " minecraft:white_concrete"
        ));
        context.waitTicks(20);
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            // Portal A: same-dim 4x4 facing the player (normal +Z), dest +100 x.
            spawnPortal(ow, new Vec3(px + 0.5, py + 2.5, planeZ), new Vec3(1, 0, 0), 4, 4,
                Level.OVERWORLD, new Vec3(px + 100.5, py + 2.5, planeZ));
            // Portal R: the reverse half of the bi-way pair at the dest, facing north
            // (normal −Z), back to A's origin — the return crossing's surface.
            spawnPortal(ow, new Vec3(px + 100.5, py + 2.5, planeZ), new Vec3(-1, 0, 0), 4, 4,
                Level.OVERWORLD, new Vec3(px + 0.5, py + 2.5, planeZ));
            // Portal B: cross-dim to the flat nether roof (the CrossingSmoke destination),
            // PLUS its nether-side twin B_R — a one-way portal has no dest-side entity, so
            // without the twin a cross-view camera in the nether correctly sees NO window
            // (measured 2026-08-05: the empty cross-dim XWIN shot was one-way semantics, not a
            // defect; the user's live scenes are complete_bi_way portals).
            spawnPortal(ow, new Vec3(px + 5.5, py + 2.5, planeZ), new Vec3(1, 0, 0), 3, 3,
                Level.NETHER, new Vec3(0.5, 129.5, 0.5));
            ServerLevel nether = server.getLevel(Level.NETHER);
            spawnPortal(nether, new Vec3(0.5, 129.5, 0.5), new Vec3(-1, 0, 0), 3, 3,
                Level.OVERWORLD, new Vec3(px + 5.5, py + 2.5, planeZ));
            SeamlessPortalsConstants.LOGGER.info(LOG + "scene portals spawned (A/R bi-way pair,"
                + " cross-dim B)");
        });
        context.waitTicks(20);
    }

    // =============================================================================================
    // C2 mode — the §6 recipe: dense stack immediately, stay hot, cross-dim teleports, NO toggles.
    // =============================================================================================

    private void runC2DenseHold(
        ClientGameTestContext context, int px, int py, int pz, double planeZ,
        String pathTag, int holdSeconds
    ) {
        // Densify: five more portals stacked on A's plane (all rendered every frame — the
        // occlusion-query path runs per portal per frame) + the recursion corridor N.
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            for (int i = 0; i < 5; i++) {
                spawnPortal(ow, new Vec3(px + 0.5, py + 2.5, planeZ - 0.05 * (i + 1)),
                    new Vec3(1, 0, 0), 4, 4,
                    Level.OVERWORLD, new Vec3(px + 100.5, py + 2.5, planeZ));
            }
            spawnPortal(ow, new Vec3(px - 6.5, py + 2.5, planeZ), new Vec3(1, 0, 0), 4, 4,
                Level.OVERWORLD, new Vec3(px - 6.5, py + 2.5, planeZ + 9));
            SeamlessPortalsConstants.LOGGER.info(LOG + "C2 dense stack spawned (5 stacked + N)");
        });
        context.runOnClient(mc -> {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = true;
        });
        pinPlayer(context, px + 0.5, py, pz + 1.5, 180f, 2f);
        context.waitTicks(40);
        shot(context, pathTag + "-c2-dense-scene");

        long deadline = System.currentTimeMillis() + holdSeconds * 1000L;
        int cycle = 0;
        while (System.currentTimeMillis() < deadline) {
            cycle++;
            // ~15s of strafe motion facing the stack (keeps every query call site hot).
            for (int c = 0; c < 18 && System.currentTimeMillis() < deadline; c++) {
                double vx = (c % 2 == 0) ? 0.45 : -0.45;
                for (int t = 0; t < 8; t++) {
                    context.runOnClient(mc -> mc.player.setDeltaMovement(vx, 0, 0));
                    context.waitTicks(1);
                }
            }
            context.runOnClient(mc -> mc.player.setDeltaMovement(Vec3.ZERO));
            if (System.currentTimeMillis() >= deadline) break;
            // Cross-dim round trip back to the SAME viewpoint (each dimension load forces
            // recompiles; every recompile is another chance — the §6 recipe verbatim).
            runCommands(context, List.of(
                "execute in minecraft:the_nether run tp @p 0.5 129.0 0.5"));
            context.waitTicks(60);
            runCommands(context, List.of(
                "execute in minecraft:overworld run tp @p "
                    + (px + 0.5) + " " + py + " " + (pz + 1.5) + " 180 2"));
            pinPlayer(context, px + 0.5, py, pz + 1.5, 180f, 2f);
            context.waitTicks(40);
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "C2 hold cycle {} done ({}s remaining)", cycle,
                Math.max(0, (deadline - System.currentTimeMillis()) / 1000));
        }
        shot(context, pathTag + "-c2-hold-end");
    }

    // =============================================================================================
    // Helpers (the CrossingSmoke idioms, trimmed)
    // =============================================================================================

    /** An axis-aligned portal: {@code axisW}×(0,1,0), normal = axisW × +Y. */
    private static void spawnPortal(
        ServerLevel world, Vec3 origin, Vec3 axisW, double w, double h,
        net.minecraft.resources.ResourceKey<Level> destDim, Vec3 dest
    ) {
        Portal portal = Portal.ENTITY_TYPE.create(world, EntitySpawnReason.COMMAND);
        if (portal == null) throw new AssertionError(LOG + "Portal.ENTITY_TYPE.create returned null");
        portal.setOriginPos(origin);
        portal.setDestinationDimension(destDim);
        portal.setDestination(dest);
        portal.setOrientationAndSize(axisW, new Vec3(0, 1, 0), w, h);
        McHelper.spawnServerEntity(portal);
    }

    /** Pin the player client+server (the CrossingSmoke EM-O-B idiom — no rubber-band). */
    private static void pinPlayer(
        ClientGameTestContext context, double x, double y, double z, float yaw, float pitch
    ) {
        runOnServer(context, server -> {
            CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(src,
                "tp @p " + x + " " + y + " " + z + " " + yaw + " " + pitch);
        });
        context.runOnClient(mc -> {
            mc.player.setPos(x, y, z);
            mc.player.xo = x;
            mc.player.yo = y;
            mc.player.zo = z;
            mc.player.setDeltaMovement(Vec3.ZERO);
        });
        context.getInput().lookAt(yaw, pitch);
        context.waitTicks(3);
    }

    /** Per-tick velocity until the predicate holds (bounded), else throw — a crossing that
     *  never lands is a FAILURE, not a skipped leg. */
    private static void flyUntil(
        ClientGameTestContext context, double vx, double vz,
        java.util.function.Predicate<net.minecraft.client.Minecraft> arrived,
        int maxTicks, String what
    ) {
        context.runOnClient(mc -> {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = true;
        });
        for (int t = 0; t < maxTicks; t++) {
            context.runOnClient(mc -> mc.player.setDeltaMovement(vx, 0, vz));
            context.waitTicks(1);
            if (context.computeOnClient(arrived::test)) {
                context.runOnClient(mc -> mc.player.setDeltaMovement(Vec3.ZERO));
                return;
            }
        }
        throw new AssertionError(LOG + what + " FAILED — predicate never held in "
            + maxTicks + " ticks");
    }

    /** The crossing rows' functional gate: a coherent client player/level pair. */
    private static void assertClientCoherent(ClientGameTestContext context, String what) {
        String state = context.computeOnClient(mc -> {
            if (mc.player == null || mc.level == null) return "player/level null";
            if (mc.player.level() != mc.level) return "player level != client level";
            return "OK";
        });
        if (!state.equals("OK")) {
            throw new AssertionError(LOG + what + " FAILED client-side: " + state);
        }
    }

    /** Always-on screenshot, fail-soft (visual evidence must not void functional legs). */
    private static void shot(ClientGameTestContext context, String name) {
        try {
            java.nio.file.Path p = context.takeScreenshot(name);
            SeamlessPortalsConstants.LOGGER.info(LOG + "screenshot saved: {}", p);
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(LOG + "screenshot '" + name + "' FAILED"
                + " (non-fatal)", t);
        }
    }

    private static boolean isIrisPackActive(ClientGameTestContext context) {
        return context.computeOnClient(mc -> {
            try {
                Class<?> apiC = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object api = apiC.getMethod("getInstance").invoke(null);
                return (Boolean) apiC.getMethod("isShaderPackInUse").invoke(api);
            } catch (Throwable t) {
                return false;
            }
        });
    }

    private static void setShadersEnabled(ClientGameTestContext context, boolean enabled) {
        context.runOnClient(mc -> {
            try {
                Class<?> apiC = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object api = apiC.getMethod("getInstance").invoke(null);
                Object cfg = apiC.getMethod("getConfig").invoke(api);
                Class<?> cfgC = Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig");
                cfgC.getMethod("setShadersEnabledAndApply", boolean.class).invoke(cfg, enabled);
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(
                    LOG + "iris setShadersEnabled(" + enabled + ") failed", t);
            }
        });
    }

    private static void runOnServer(
        ClientGameTestContext context, java.util.function.Consumer<MinecraftServer> action
    ) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        context.runOnClient(mc -> {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server == null) {
                error.set(new AssertionError(LOG + "no singleplayer server"));
                done.set(true);
                return;
            }
            server.execute(() -> {
                try {
                    action.accept(server);
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    done.set(true);
                }
            });
        });
        context.waitFor(mc -> done.get(), 100);
        Throwable t = error.get();
        if (t instanceof AssertionError ae) throw ae;
        if (t != null) throw new AssertionError(LOG + "server-side action failed", t);
    }

    private static void runCommands(ClientGameTestContext context, List<String> commands) {
        runOnServer(context, server -> {
            CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
            for (String c : commands) {
                try {
                    server.getCommands().performPrefixedCommand(src, c);
                } catch (Exception e) {
                    SeamlessPortalsConstants.LOGGER.warn(LOG + "command failed: {} ({})", c, e.toString());
                }
            }
        });
    }
}
