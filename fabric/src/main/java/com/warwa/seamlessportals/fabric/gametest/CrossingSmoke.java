package com.warwa.seamlessportals.fabric.gametest;

import com.warwa.seamlessportals.EntityPortalsFlag;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.mixin.LivingEntityHurtAccessor;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.commands.CommandSourceStack;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * S15 (D4.6) — automated server-side entity-crossing smoke on the UNIFIED path
 * ({@code Portal.SERVER_PORTAL_TICK_SIGNAL} → {@code getEntitiesToTeleport} →
 * {@code teleportRegularEntity}), EXECUTION_PLAN §S15(a): spawn portals + entities,
 * assert arrival position/dimension. Runs flag-ON only (the {@code crossingGametest}
 * Gradle run config seeds {@code entityPortals=true} into the run dir's config before
 * launch); aborts loudly if the flag did not resolve ON.
 *
 * <p>Three legs, all server-asserted (the client is only the harness):
 * <ol>
 *   <li><b>Same-dim item</b>: thrown item through an overworld→overworld portal —
 *       arrives at the transformed position, same entity object (no recreate),
 *       same dimension.</li>
 *   <li><b>Cross-dim item</b>: thrown item through an overworld→nether portal —
 *       the recreate path ({@code changeEntityDimension}, {@code restoreFrom} +
 *       same-id); arrives in the nether at the transformed position; the source
 *       entity is gone from the overworld.</li>
 *   <li><b>F3 hurt-state carry (the register's reproduction vehicle)</b>: a cow is
 *       {@code hurtServer}-damaged (setting the TRANSIENT {@code lastDamageSource} —
 *       the exact field {@code PanicGoal.shouldPanic} reads), then shoved through the
 *       cross-dim portal. The recreated cow must still report a non-null
 *       {@code getLastDamageSource()}. BEFORE the F3 patch this leg FAILS — that red
 *       run is the dated "reproduced on the ported path" register evidence
 *       (EXECUTION_PLAN §Deviations F3; current-mod-core §11.4); AFTER the patch it
 *       passes. The raw-field accessor is asserted too so the 40-tick getter window
 *       can never mask the verdict.</li>
 * </ol>
 *
 * <p>Determinism notes: the stage is an obsidian platform built around the spawn
 * point with cleared air; the nether dest is ABOVE THE BEDROCK ROOF (y≈129, roof top
 * y=127 is solid bedrock everywhere) so arrival terrain is flat, lava-free, and
 * fall-damage-free; dest chunks are forceloaded (entity-ticking) before any throw;
 * the cow gets a straight-line velocity impulse the tick after being hurt, crossing
 * within ~2 ticks — before panic pathfinding can steer it — through a 3×3 portal.
 * Assertions run at arrival time so post-arrival physics never races them.
 *
 * <p>Selection: runs only when {@code -Dseamlessportals.gametest.only=crossing}
 * (set by the {@code crossingGametest} run config); no-ops under the title-card
 * config so the two tests never interleave.
 */
public class CrossingSmoke implements FabricClientGameTest {

    private static final String LOG = "[CROSSING SMOKE] ";

    /** Portal plane distance north (−Z) of the player. */
    private static final int PORTAL_OFFSET_Z = 6;

    @Override
    public void runTest(ClientGameTestContext context) {
        String only = System.getProperty("seamlessportals.gametest.only", "");
        if (!only.isEmpty() && !only.equals("crossing")) {
            SeamlessPortalsConstants.LOGGER.info(LOG + "skipped (selected test: {})", only);
            return;
        }

        SeamlessPortalsConstants.LOGGER.info(LOG + "starting; entityPortals flag = {}",
            EntityPortalsFlag.isOn());
        if (!EntityPortalsFlag.isOn()) {
            throw new AssertionError(LOG + "entityPortals flag is OFF — the crossingGametest "
                + "run config must seed entityPortals=true into <runDir>/config/"
                + "seamlessportals.properties BEFORE launch (see fabric/build.gradle). "
                + "The unified entity path does not exist flag-OFF; aborting.");
        }

        context.runOnClient(mc -> mc.options.renderDistance().set(6));

        net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave worldSave;
        try (TestSingleplayerContext sp = context.worldBuilder()
                .setUseConsistentSettings(true)
                .adjustSettings(s -> {
                    s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    s.setName("seamless-crossing-smoke");
                })
                .create()) {
            worldSave = sp.getWorldSave();

            context.waitTicks(80);

            BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
            int px = feet.getX(), py = feet.getY(), pz = feet.getZ();
            SeamlessPortalsConstants.LOGGER.info(LOG + "staging around ({},{},{})", px, py, pz);

            // ---- Stage: rules, platform, cleared air, forceloaded dests ----
            runCommands(context, List.of(
                "gamerule doDaylightCycle false",
                "gamerule doWeatherCycle false",
                "gamerule doMobSpawning false",
                "gamerule doFireTick false",
                "time set 1000",
                "weather clear",
                // platform + cleared box (portals live at z in [pz-8, pz-4])
                "fill " + (px - 8) + " " + (py - 1) + " " + (pz - 10) + " "
                    + (px + 8) + " " + (py - 1) + " " + (pz + 4) + " minecraft:obsidian",
                "fill " + (px - 8) + " " + py + " " + (pz - 10) + " "
                    + (px + 8) + " " + (py + 6) + " " + (pz + 4) + " minecraft:air",
                // same-dim dest chunks (item falls mid-air there; caught at arrival)
                "forceload add " + (px + 92) + " " + (pz - 14) + " " + (px + 108) + " " + (pz + 2),
                // nether above-roof dest chunks
                "execute in minecraft:the_nether run forceload add -16 -16 16 16"
            ));
            context.waitTicks(40);

            // IS2 EM-G PRE-PORTAL CHECKPOINT (defect G evidence; port-note IS-iris-shaders-on
            // §3.1/§3.2): the creative-browse BEFORE any portal entity exists — the
            // discriminator for whether the inventory mangle needs portal machinery to have
            // RUN or is static-init-only (the IrisCompatPaste reflective-register suspect).
            // Lever-gated + fail-soft; inert in the default suite.
            maybeCreativeBrowse(context, "em-g-pre");

            // ---- Portal geometry (identity transform: axisW=+X, axisH=+Y → normal +Z,
            // facing the player standing south of the plane) ----
            double planeZ = pz - PORTAL_OFFSET_Z + 0.5;
            Vec3 originA = new Vec3(px + 0.5, py + 1.5, planeZ);
            Vec3 destA = new Vec3(px + 100.5, 250.0, planeZ);
            Vec3 originB = new Vec3(px + 4.5, py + 1.5, planeZ);
            Vec3 destB = new Vec3(0.5, 129.5, 0.5);

            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                spawnTestPortal(ow, originA, Level.OVERWORLD, destA);
                spawnTestPortal(ow, originB, Level.NETHER, destB);
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "portals spawned: A {} -> overworld {}; B {} -> nether {}",
                    originA, destA, originB, destB);
            });
            context.waitTicks(20);

            // RS-ONLY MODE (-ProsOnly): the crossing/teleport legs are skipped — the recorded
            // 2026-07-26 proposal, because the RS gates are a small fraction of a slow run. Portal
            // staging, legs 6a/6b (the seam gate's involution coverage needs their bi-way pairs)
            // and every RS gate still run. A full run stays the default and precedes every commit.
            if (!AperturePassthroughLever.RS_ONLY) {
                // ---- Leg 1: same-dim item ----
                UUID itemA = spawnThrownItem(context, originA);
                waitForArrival(context, "leg 1 (same-dim item)", Level.OVERWORLD, itemA, destA, 200);
                SeamlessPortalsConstants.LOGGER.info(LOG + "leg 1 PASS — same-dim item arrived at {}", destA);

                // ---- Leg 2: cross-dim item (recreate path) ----
                UUID itemB = spawnThrownItem(context, originB);
                waitForArrival(context, "leg 2 (cross-dim item)", Level.NETHER, itemB, destB, 300);
                assertGoneFrom(context, "leg 2 (cross-dim item)", Level.OVERWORLD, itemB);
                SeamlessPortalsConstants.LOGGER.info(LOG + "leg 2 PASS — cross-dim item recreated in nether at {}", destB);
            }

            // ---- RS-DELIVERY-TEST: the headless reproduction of the same-dim mirror bug ----
            // Placed HERE and nowhere else: portals A (same-dim) and B (cross-dim) both exist and
            // have been ticking long enough to bind, and the player is still standing at the staging
            // area in the overworld. Leg 4 moves the player to the nether, which would change what
            // the client holds and make the two arms incomparable.
            rsDeliveryTest(context, px, py, pz, planeZ);

            if (!AperturePassthroughLever.RS_ONLY) {
            // ---- Leg 3: F3 hurt-state carry (cow, cross-dim) ----
            AtomicReference<UUID> cowId = new AtomicReference<>();
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                Cow cow = EntityTypes.COW.create(ow, EntitySpawnReason.COMMAND);
                if (cow == null) throw new AssertionError(LOG + "cow create returned null");
                cow.snapTo(originB.x, py, originB.z + 2.0, 180f, 0f);
                ow.addFreshEntity(cow);
                cowId.set(cow.getUUID());
            });
            context.waitTicks(10); // let it tick (xo/yo/zo set — the fresh-entity guard)

            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                Entity e = ow.getEntity(cowId.get());
                if (!(e instanceof Cow cow)) {
                    throw new AssertionError(LOG + "leg 3: staged cow missing pre-crossing (got " + e + ")");
                }
                // playerAttack (not generic): minecraft:generic is NOT in the
                // panic_causes damage-type tag, so a generic-hurt cow never panics even
                // with the state carried — player_attack makes the staged hazard
                // end-to-end faithful (BOTH conjuncts of PanicGoal.shouldPanic).
                net.minecraft.server.level.ServerPlayer attacker =
                    server.getPlayerList().getPlayers().get(0);
                boolean hurt = cow.hurtServer(ow, ow.damageSources().playerAttack(attacker), 2.0f);
                if (!hurt || cow.getLastDamageSource() == null) {
                    throw new AssertionError(LOG + "leg 3 precondition failed: hurtServer=" + hurt
                        + " lastDamageSource=" + cow.getLastDamageSource()
                        + " — cannot stage the transient-hurt-state hazard");
                }
                // straight shove north through the 3×3 plane, ~2 blocks away —
                // crosses before panic pathfinding steers (motion guard: 1.2² << 20)
                cow.setDeltaMovement(0, 0, -1.2);
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "leg 3: cow hurt (lastDamageSource={}) + shoved at {}",
                    cow.getLastDamageSource().getMsgId(), cow.position());
            });

            waitForArrival(context, "leg 3 (hurt cow)", Level.NETHER, cowId.get(), destB, 100);

            // The F3 verdict — read IMMEDIATELY at arrival (inside the 40-tick
            // getter window; the raw field is asserted too so the window can
            // never mask a real carry).
            AtomicReference<String> verdict = new AtomicReference<>();
            runOnServer(context, server -> {
                ServerLevel nether = server.getLevel(Level.NETHER);
                Entity e = nether.getEntity(cowId.get());
                if (!(e instanceof LivingEntity cow)) {
                    verdict.set("recreated cow not found/living in nether: " + e);
                    return;
                }
                Object rawField = ((LivingEntityHurtAccessor) cow).seamlessportals$getLastDamageSource();
                Object getter = cow.getLastDamageSource();
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "leg 3: recreated cow at {} in {}; lastDamageSource raw={} getter={}",
                    e.position(), nether.dimension().identifier(), rawField, getter);
                if (rawField == null || getter == null) {
                    verdict.set("TRANSIENT HURT STATE DROPPED by the recreate "
                        + "(lastDamageSource raw=" + rawField + ", getter=" + getter + ") — "
                        + "the F3 hazard (current-mod-core §11.4): PanicGoal.shouldPanic reads "
                        + "getLastDamageSource() != null, so this cow stops panicking at the "
                        + "crossing. Apply preserveTransientHurtState on the recreate branch.");
                }
            });
            context.waitTicks(2);
            if (verdict.get() != null) {
                throw new AssertionError(LOG + "leg 3 (F3) FAILED: " + verdict.get());
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "leg 3 PASS — transient hurt state carried across the recreate");
            }   // end !RS_ONLY (leg 3)

            // ---- Leg 4: ender pearl through the cross-dim portal (the S15 round-1 FREEZE
            // regression). The pearl crosses via the unified path, lands on the bedrock roof,
            // and vanilla's onHit owner-teleport branch fires — which, pre-fix, took the
            // VANILLA respawn-packet path and killed the session (26.2 setScreenAndShow
            // renders a frame mid-packet; the pre-render pump asserted on the transient
            // player/level mismatch → "Packet handling error" disconnect;
            // disconnect-2026-07-18_04.37.31/04.55.38). Post-fix the R13a redirect routes it
            // through forceTeleportPlayer (seamless, no respawn packet). Asserts: player
            // arrives in the nether near the dest AND the client survives with a COHERENT
            // player/level pair (the disconnect would fail both). Runs LAST — it moves the
            // player. ----
            // ---- Leg 7 (S17 sign-off residual, automated): the R4 >71-chunk same-dim
            // dest check. The retired latent bug: two same-dim portal DESTS >71 chunks
            // apart collided in the old bounded client store (memory walking-limbo);
            // the C3-approved unbounded ImmPtlViewArea rebuild retires it. Net: portal C
            // (dest ~1300 blocks / ~81 chunks from leg-1's destA), BOTH portals held in
            // the rendered view for 150 ticks — the collision class corrupts/crashes the
            // client store; survival + coherence is the crash-class regression net (the
            // visual half stays eyeball-only, S17 round PASSED it live). ----
            if (!AperturePassthroughLever.RS_ONLY) {
            runCommands(context, List.of(
                "forceload add 1384 1384 1416 1416"
            ));
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                spawnTestPortal(ow, new Vec3(px - 3.5, py + 1.5, planeZ),
                    Level.OVERWORLD, new Vec3(1400.5, 250.0, 1400.5));
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "leg 7: far-dest same-dim portal C spawned (dest 1400,250,1400 — "
                        + "~81 chunks from destA)");
            });
            // §2.7 THE SAME-DIM SODIUM-SUPPLY DISCRIMINATOR (lever-gated; port-note
            // IS-iris-shaders-on §2.7). The two pre-existing same-dim windows are
            // NON-DISCRIMINATING for the sodium terrain chain: destA and portal C's dest
            // both hover MID-AIR at y=250 with client renderDistance 6, and sodium's
            // terrain collection/draw envelope is min(fog cullDistance, renderDistance·16
            // ≈ 96 blocks) — the ground sits ≥180 blocks below, so ZERO sections are in
            // range and the CORRECT converged dest view is sky+fog on EVERY row,
            // pixel-identical to the plain row's by-design no-extract floor (a working
            // Step-9' drive and a broken one produce the same image there). Portal D
            // fixes the evidence: a same-dim window whose dest camera has COMMAND-BUILT
            // terrain inside the envelope (deterministic across seeds — natural surface
            // height at the dest is not).
            //   PLAIN row expectation: D stays sky-only (same-dim runs no extract, by
            //   design — the pre-registered floor).
            //   SODIUM row expectation: D shows the obsidian pad = the Step-9'
            //   ip_driveDestTerrainSetup drive delivering renderLists (pass 1 may be the
            //   renderOutOfGraph frustum-flood or briefly blank while dest chunks mesh;
            //   occlusion-tree lists within ~2-3 passes; the 150-tick hold = converged).
            // Lever-gated so the default suite stays byte-identical; D sits ABOVE portal
            // A (spans y py+3..py+6, inside the cleared box, no plane overlap with A) —
            // clear of every leg path (item/pearl fly at y≈py+1 through x px+0.5/px+4.5)
            // and outside the cross-dim window B's screen region.
            if (screenshotsLeverOn()) {
                // VERIFY-LENS GEOMETRY FIX (the round-1 staging was itself
                // non-discriminating: D's window sits ABOVE the eye, so all window rays
                // point UP — a floor pad below the dest camera is never hit, and the
                // ZERO-vertical-offset dest keeps the through-portal camera at eye height
                // in cleared air instead of embedded in the pad). The terrain the rays DO
                // hit: a south-facing obsidian WALL across the transformed window frustum
                // (bottom/top rays from eye ~py+1.62 through D's py+3..py+6 span land on
                // z=pz-12 at ~py+4.6..~py+11.2), with the ray corridor air-cleared.
                runCommands(context, List.of(
                    "fill " + (px + 94) + " " + (py + 3) + " " + (pz - 12) + " "
                        + (px + 106) + " " + (py + 13) + " " + (pz - 11) + " minecraft:obsidian",
                    "fill " + (px + 94) + " " + py + " " + (pz - 10) + " "
                        + (px + 106) + " " + (py + 13) + " " + (pz + 2) + " minecraft:air"
                ));
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    spawnTestPortal(ow, new Vec3(px + 0.5, py + 4.5, planeZ),
                        Level.OVERWORLD, new Vec3(px + 100.5, py + 4.5, planeZ));
                    SeamlessPortalsConstants.LOGGER.info(
                        LOG + "portal D spawned (lever-gated §2.7 discriminator): same-dim"
                            + " zero-offset dest ({},{},{}) — the obsidian WALL at z={}"
                            + " sits in every window ray, ~12 blocks into the ~96-block"
                            + " sodium envelope",
                        px + 100.5, py + 4.5, planeZ, pz - 12);
                });
            }
            context.runOnClient(mc -> {
                mc.player.setYRot(180f); // face the portal row (north of the platform)
                mc.player.setXRot(0f);
            });
            // IS1 visual pre-screen (self-run rounds, port-note IS-iris-shaders-on §2.7):
            // lever-gated screenshots at the same-dim portal-view hold — the stamp-shape /
            // frame-edge-halo / same-dim-observable evidence the log legs cannot judge.
            // Inert without -Dseamlessportals.gametest.screenshots (suite-neutral).
            context.waitTicks(75);
            maybeScreenshot(context, "is1-leg7-samedim-portals");
            context.waitTicks(75);
            // §2.7 shot 2 — the converged frame (150 ticks): portal D's window is the
            // decisive pixel pair (sodium row = the obsidian WALL; plain row = sky).
            maybeScreenshot(context, "is1-leg7-samedim-ground-dest-converged");
            String leg7State = context.computeOnClient(mc -> {
                if (mc.player == null || mc.level == null) return "player/level null";
                if (mc.player.level() != mc.level) return "player/level incoherent";
                return "OK";
            });
            if (!leg7State.equals("OK")) {
                throw new AssertionError(LOG + "leg 7 (>71-chunk same-dim dests) FAILED: "
                    + leg7State + " — the unbounded-store retirement regressed");
            }
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "leg 7 PASS — both far-dest same-dim portals rendered 150 ticks, client coherent");
            }   // end !RS_ONLY (leg 7)

            // IS2 EM EVIDENCE LEGS (defects O + G; port-note IS-iris-shaders-on §3.1/§3.2):
            // outline-target shots + the post-portal creative-browse checkpoint. All
            // lever-gated + fail-soft (the maybeScreenshot never-throw discipline) — these
            // legs CAPTURE state as evidence, they assert nothing.
            // RS PASSTHROUGH (a) SEAM EVIDENCE (migration/REDSTONE_RECON.md §0.5 / §6): settles the
            // one INFERRED claim the whole seam model rests on — does the stencil actually clip a
            // block sitting in the aperture at the portal plane? Lever-gated + fail-soft.
            rsSeamEvidenceLegs(context, px, py, pz, planeZ);
            rsTeardownTest(context, py);
            emOutlineEvidenceLegs(context, px, py, pz, planeZ);
            maybeCreativeBrowse(context, "em-g-post");
            // EM-G-R3 the untested trigger: my 3 static-shaders harness configs never
            // reproduced the creative-inventory mangle, so the trigger is the ONE thing they
            // omit — a shader TOGGLE mid-session (iris pipeline destroy+recreate; the leading
            // icon-atlas-poison suspect + the clip-cache stale-id event). Toggle off/on twice
            // with frames rendered between, then re-browse creative. iris-only, fail-soft.
            maybeShaderToggleThenBrowse(context, "em-g-toggle");

            // The vanilla branch preserves the owner's ROTATION+DELTA as RELATIVES
            // (Relative.union(ROTATION, DELTA)); the first fix cut dropped them (verify
            // wf_30866195-b9f BLOCKER: empty relatives → zeroed momentum + snapped
            // rotation). Regression net: pin a distinctive client yaw before the throw and
            // assert it SURVIVES the teleport — a relatives-dropping regression snaps the
            // client to the transition's yaw 0.0. (Rotation and velocity ride the same
            // relatives Set; yaw is the deterministic, physics-free assert of the pair.)
            if (!AperturePassthroughLever.RS_ONLY) {
            final float pinnedYaw = 137.5f;
            context.runOnClient(mc -> {
                mc.player.setYRot(pinnedYaw);
                mc.player.setXRot(4.0f);
            });
            context.waitTicks(5); // let the client rotation reach the server
            AtomicReference<UUID> playerId = new AtomicReference<>();
            runOnServer(context, server -> {
                net.minecraft.server.level.ServerPlayer player =
                    server.getPlayerList().getPlayers().get(0);
                playerId.set(player.getUUID());
                net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl =
                    new net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl(
                        server.getLevel(Level.OVERWORLD), player,
                        new ItemStack(net.minecraft.world.item.Items.ENDER_PEARL));
                pearl.snapTo(originB.x, originB.y - 0.3, originB.z + 2.0, 0f, 0f);
                pearl.setDeltaMovement(0, 0.02, -0.7);
                server.getLevel(Level.OVERWORLD).addFreshEntity(pearl);
                SeamlessPortalsConstants.LOGGER.info(LOG + "leg 4: pearl thrown at {} (owner {})",
                    pearl.position(), player.getGameProfile().name());
            });
            try {
                context.waitFor(mc -> {
                    MinecraftServer server = mc.getSingleplayerServer();
                    if (server == null) return false;
                    net.minecraft.server.level.ServerPlayer p =
                        server.getPlayerList().getPlayer(playerId.get());
                    return p != null && p.level().dimension().equals(Level.NETHER)
                        && p.position().distanceTo(destB) < 16;
                }, 300);
            } catch (Throwable t) {
                throw new AssertionError(LOG + "leg 4 (pearl) FAILED: owner never arrived in the"
                    + " nether near " + destB + " — the seamless native-teleport route"
                    + " (MixinThrownEnderPearl redirect -> forceTeleportPlayer) did not deliver", t);
            }
            // Client-side coherence: the seamless swap must leave mc.player/mc.level paired in
            // the nether with the connection alive (pre-fix the session was already dead here).
            context.waitTicks(40);
            String clientState = context.computeOnClient(mc -> {
                if (mc.player == null || mc.level == null) return "player/level null";
                if (mc.player.level() != mc.level) return "player level != client level";
                if (!mc.level.dimension().equals(Level.NETHER)) {
                    return "client dim = " + mc.level.dimension().identifier();
                }
                return "OK";
            });
            if (!clientState.equals("OK")) {
                throw new AssertionError(LOG + "leg 4 (pearl) FAILED client-side: " + clientState);
            }
            // IS1 visual pre-screen shot 2: post-crossing nether (the dest-preset-residue
            // watch-row-7 window — a blank main terrain here is the residue signature).
            maybeScreenshot(context, "is1-leg4-post-crossing-nether");
            float postYaw = context.computeOnClient(mc -> mc.player.getYRot());
            if (Math.abs(postYaw - pinnedYaw) > 1.0f) {
                throw new AssertionError(LOG + "leg 4 (pearl) FAILED relatives-preservation: "
                    + "client yaw " + postYaw + " != pinned " + pinnedYaw
                    + " — the teleport dropped the ROTATION/DELTA relatives (vanilla preserves "
                    + "both; momentum is wiped the same way when this trips)");
            }
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "leg 4 PASS — pearl owner teleported seamlessly, client coherent in the"
                    + " nether, yaw {} preserved (relatives intact)", postYaw);
            }   // end !RS_ONLY (leg 4)

            // ---- Legs 6a/6b (S16.4): PORTAL GENERATION exactness — automated cover for
            // regression item 10 (negative-coordinate linking) and the nether-side
            // ignition path (the round-1 live session logged ZERO nether-side attempts —
            // ambiguity removed by asserting it here forever). Both call the EXACT
            // ignition entry the fire/flint mixins use (IntrinsicPortalGeneration
            // .onFireLitOnObsidian) against command-built obsidian frames, then assert a
            // NetherPortalEntity spawns whose DESTINATION obeys the 8:1 map with the
            // right SIGN and magnitude (the floored-scaling/sign-flip defect class lands
            // hundreds of blocks off or positive-mirrored). Window calibration (verify
            // wf_09f21c54-e1a): the FABRICATE-path placement freedom is tens of blocks,
            // but MATCH-existing-frame searches netherPortalFindingRadius=128 (~±152
            // blocks) — the tight windows rely on the consistent-seed test world holding
            // no matchable obsidian frame in that box (proven by the live PASS). If this
            // ever false-fails: check the reported dest FIRST — a nearby matchable frame
            // means recalibrate the window, NOT a 8:1-map defect. ----
            // Leg 6a: OW frame at NEGATIVE coords (-200,-200) -> nether dest ~(-25,-25).
            runCommands(context, List.of(
                "forceload add -216 -216 -184 -184",
                "execute in minecraft:the_nether run forceload add -57 -57 7 7",
                fill(-201, py, -200, -198, py, -200),          // base (obsidian)
                fill(-201, py + 4, -200, -198, py + 4, -200),  // lintel
                fill(-201, py + 1, -200, -201, py + 3, -200),  // left column
                fill(-198, py + 1, -200, -198, py + 3, -200),  // right column
                "fill " + (-200) + " " + (py + 1) + " " + (-200) + " "
                    + (-199) + " " + (py + 3) + " " + (-200) + " minecraft:air"
            ));
            context.waitTicks(10);
            runOnServer(context, server -> {
                boolean fired = qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration
                    .onFireLitOnObsidian(server.getLevel(Level.OVERWORLD),
                        new BlockPos(-200, py + 1, -200), null);
                SeamlessPortalsConstants.LOGGER.info(LOG + "leg 6a: OW negative-coords ignition fired={}", fired);
                if (!fired) throw new AssertionError(LOG + "leg 6a: onFireLitOnObsidian returned false"
                    + " — the generation entry rejected a valid negative-coords frame");
            });
            assertGeneratedPortal(context, "leg 6a (negative-coords OW->nether)",
                Level.OVERWORLD, new Vec3(-199.5, py + 2, -200), Level.NETHER,
                -45, -5, -45, -5, 1200);

            // Leg 6b: NETHER frame on the roof at (-40,-40) -> OW dest ~(-320,-320).
            runCommands(context, List.of(
                "execute in minecraft:the_nether run forceload add -56 -56 -24 -24",
                "execute in minecraft:overworld run forceload add -336 -336 -304 -304",
                inDim("minecraft:the_nether", fill(-41, 128, -40, -38, 128, -40)),
                inDim("minecraft:the_nether", fill(-41, 132, -40, -38, 132, -40)),
                inDim("minecraft:the_nether", fill(-41, 129, -40, -41, 131, -40)),
                inDim("minecraft:the_nether", fill(-38, 129, -40, -38, 131, -40)),
                inDim("minecraft:the_nether", "fill -40 129 -40 -39 131 -40 minecraft:air")
            ));
            context.waitTicks(10);
            runOnServer(context, server -> {
                boolean fired = qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration
                    .onFireLitOnObsidian(server.getLevel(Level.NETHER),
                        new BlockPos(-40, 129, -40), null);
                SeamlessPortalsConstants.LOGGER.info(LOG + "leg 6b: nether-side ignition fired={}", fired);
                if (!fired) throw new AssertionError(LOG + "leg 6b: onFireLitOnObsidian returned false"
                    + " — the nether-side ignition path rejected a valid frame");
            });
            assertGeneratedPortal(context, "leg 6b (nether-side ignition ->OW, 8:1)",
                Level.NETHER, new Vec3(-39.5, 130, -40), Level.OVERWORLD,
                -480, -160, -480, -160, 1200);

            // RS (a) STEP-1 GATE (REDSTONE_A_SPEC.md §7 step 1). Asserts; runs unconditionally.
            // DELIBERATELY PLACED HERE, after legs 6a/6b, because those are the only legs that
            // create BI-WAY generated portal pairs — and the involution check, which is the whole
            // point of the gate, needs a reverse portal to check against. Run earlier it examined
            // only the one-way spawned test portals, skipped the involution entirely, and still
            // reported PASS. The gate now also FAILS when its coverage is zero.
            rsSeamMapGate(context);

            // ★ FRACTIONAL FRONT 1 — the fragment decomposition, gated as PURE ARITHMETIC. Runs
            // UNCONDITIONALLY (no portal, no world, no lever): the live discriminating fixture is
            // blocked four independent ways by the exact-only policy the model must delete, so the
            // geometry would otherwise stay unfalsifiable until that whole front lands. Pins the
            // user's worked example exactly and sweeps ~78k offset pairs for conservation, the
            // <=2 proof, orientation symmetry and contiguity. Cheap; asserts nothing about
            // mirroring, placement or rendering — those are separate fronts with separate falsifiers.
            rsFragmentArithmeticGate(context);
            // And the same geometry against REAL portals — the arithmetic sweep would stay green
            // with the plane offset derived at the wrong SIGN, because it never asks a portal
            // anything. Obsidian frames are mid-block by construction, so a COINCIDENT binding must
            // compute ~0.5; that is this leg's load-bearing assertion.
            rsFragmentBindingGate(context);

            // RS (b) RAIL LEGS — rails CONNECTING across the seam (REDSTONE_B_SPEC.md §9, adapted).
            // After the seam-map gate on purpose: these consume the primitive it just proved, so a
            // failure here is a consumer bug, not seam arithmetic. Both are lever-aware: with (b) on
            // they prove the connection; under -PdisableSeamShadow they must prove the INVERSION.
            rsRailLegTopologyB(context);
            rsRailLegTopologyA(context, py);

            // RS (c) SIGNAL LEGS — redstone signal CROSSING the seam (REDSTONE_C_SPEC.md §5).
            // After the rail legs on purpose: they consume the (b) shapes those legs just proved,
            // so a failure here is a signal bug, not a rail-connection one. Lever-aware: master
            // inversion in both arms; the DISPATCH inversion lives in arm B (no mirror there to
            // notify the far side) and in arm A's lamp (power originating beyond the seam).
            rsSignalLegDisjoint(context);
            rsSignalLegCoincident(context, py);

            // DIAGNOSTIC REPRO (probe-gated, asserts the DESIGN expectation): the user's live
            // 2026-07-27 report — same-dim COINCIDENT (make_portal-style mid-block planes) with a
            // Y-offset; signal stops at the seam / sticks on. Neither shipped arm covers this
            // combination (A is cross-dim coincident, B is same-dim disjoint).
            if (AperturePassthroughLever.SEAM_SIGNAL_PROBE) {
                rsSignalSameDimCoincidentRepro(context);
                rsSignalTwoSeamLineRepro(context, py);
                rsSignalCommandPairRepro(context, 5600, 100, 5600, 1, 2, "1x2");
                // The user's REAL aperture size (their live pair binds cells=15 → 5×3) — wide
                // apertures were uncovered by every earlier repro; the edge-column variant
                // exercises the lateral cell mapping for off-center columns.
                rsSignalCommandPairRepro(context, 6400, 100, 6400, 5, 3, "5x3");
                rsSignalCommandPairRepro(context, 7200, 100, 7200, 5, 3, -2, "5x3-edge");
            }

            // RS (d) CART-CROSSING GATES (always run; extra SAMPLE/EVT logging under
            // -PseamCartProbe). Arm A (COINCIDENT) is regression coverage — the 2026-07-28
            // instrument round measured it CLEAN stock, so it asserts identically in every lever
            // direction. Arm B (DISJOINT) is the (d) fix's proof and inverts under
            // -PdisableSeamCartRail (and (b)'s -PdisableSeamShadow, which the bridge consumes).
            rsCartLegCoincident(context, py);
            rsCartLegDisjoint(context);
            rsCartLegPhantomRail(context, py);
            // RIDDEN measurement (probe-gated, report-only): the ridden crossing does NOT use the
            // regular-entity pipeline whose <=1-tick bound the one-cell bridge is sized for.
            // Instrument-first, exactly as arms A/B were decided; it moves the real player, so it
            // runs last among the cart legs and restores them in its finally.
            if (AperturePassthroughLever.SEAM_CART_PROBE) {
                rsCartLegRiddenProbe(context, py);
                rsCartLegRiddenSameDimProbe(context);
            }
            // RS-CART-F — DEFECT A: entities in a FAR same-dim portal window. Probe-gated because
            // its assertions read CartWindowProbe's counters, which only accumulate when the probe
            // computes both lookups.
            if (AperturePassthroughLever.CART_WINDOW_PROBE) {
                rsCartWindowEntityGate(context);
            }

            // RS SEAM-CLIP GATE (renderer) — the suite's first PIXEL gate. Since the 2026-07-27
            // user decision the clip is DEFAULT OFF (fractional model chosen instead): the
            // default run asserts the whole-cube branch; -PenableSeamClip asserts the cut.
            // After the rail legs: it moves the player.
            rsSeamClipGate(context, px, py, pz);

            // RS SEAM-CLIP ARC EVIDENCE (screenshots lever only, asserts nothing) — 2026-07-27
            // user live report: "the block doesn't load on the other side at first; it reappears
            // as soon as I cross where the seam sits" while walking AROUND the portal. Eight
            // camera positions along that walk, screenshotted, so the flip/pop can be read
            // frame-by-frame instead of theorised about.
            rsSeamClipArcEvidence(context, px, py, pz);

            if (AperturePassthroughLever.RS_ONLY) {
                SeamlessPortalsConstants.LOGGER.info(LOG + "RS-ONLY MODE — crossing/teleport legs"
                    + " (1, 2, 3, 4, 7) and the leg-5 datapack reopen were SKIPPED"
                    + " (-Dseamlessportals.rsOnly). Not a full-suite verdict; run the full matrix"
                    + " before a commit.");
                SeamlessPortalsConstants.LOGGER.info(LOG + "ALL LEGS PASS");
            }

            if (!AperturePassthroughLever.RS_ONLY) {
            // ---- Leg 5 setup (S16 commit 3): write a DEV-ONLY datapack into THIS
            // throwaway world's save dir (never shipped resources). FIRST-RUN LESSON
            // (log-proven): dynamic-registry entries load at WORLD OPEN only — /reload
            // re-buckets the unchanged registry but cannot add entries (vanilla
            // semantics; the reload ran, entries stayed 0). So the files are written
            // here and the ASSERT happens after close + TestWorldSave.open() below. ----
            runOnServer(context, server -> {
                java.nio.file.Path dpDir = server.getWorldPath(
                        net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR)
                    .resolve("s16test");
                try {
                    java.nio.file.Path genDir = dpDir.resolve(
                        "data/s16test/immersive_portals/custom_portal_generation");
                    java.nio.file.Files.createDirectories(genDir);
                    // 26.2 mcmeta shape: formats >81 REQUIRE min_format/max_format (the legacy
                    // single pack_format fails metadata parsing and the pack is never
                    // discovered — first leg-5 run's exact failure; vanilla example:
                    // data/minecraft/datapacks/minecart_improvements/pack.mcmeta).
                    java.nio.file.Files.writeString(dpDir.resolve("pack.mcmeta"),
                        "{\"pack\": {\"min_format\": 107, \"max_format\": 107,"
                            + " \"description\": \"S16 dev test\"}}");
                    java.nio.file.Files.writeString(genDir.resolve("test_gen.json"), """
                        {
                          "schema_version": "imm_ptl:v1",
                          "from": ["minecraft:overworld"],
                          "to": "minecraft:the_nether",
                          "form": {
                            "type": "imm_ptl:classical",
                            "from_frame_block": "minecraft:gold_block",
                            "area_block": "minecraft:air",
                            "to_frame_block": "minecraft:gold_block",
                            "generate_frame_if_not_found": true
                          },
                          "trigger": {
                            "type": "imm_ptl:use_item",
                            "item": "minecraft:golden_apple"
                          }
                        }
                        """);
                } catch (java.io.IOException e) {
                    throw new AssertionError(LOG + "leg 5: failed writing the dev datapack", e);
                }
                SeamlessPortalsConstants.LOGGER.info(LOG + "leg 5: dev datapack written; will"
                    + " assert after world reopen (dynamic registries load at open only)");
            });
            // THE FAR-PAIR RELOG STAGE — the user's DECODED live topology (store dump from
            // "New World (8)": cells at (-264,88,-457) and (40,118,7000000) — a same-dim pair
            // whose partner sits SEVEN MILLION blocks out, with NO forceload). The 9200 relog
            // fixture deliberately keeps a forceload alive across the close; the user's world
            // has none — after reopen the far side exists only if IP's own chunk tickets revive
            // it. Staged LAST so the player stays parked at the near portal through the close,
            // exactly where the live user logs out.
            rsRelogFarStage(context);
            }   // end !RS_ONLY (leg 5 setup)
        }

        if (AperturePassthroughLever.RS_ONLY) {
            return;   // ALL LEGS PASS already printed inside the world block
        }

        // ---- Leg 5 assert: reopen the SAME save — the datapacks folder now contains the
        // dev pack at world open, so the dynamic registry decodes it and the manager
        // buckets it at SERVER_STARTED. (World-folder datapacks are auto-enabled on
        // discovery, the standard drop-in-folder player workflow.) ----
        try (TestSingleplayerContext sp2 = worldSave.open()) {
            try {
                context.waitFor(mc -> {
                    MinecraftServer server = mc.getSingleplayerServer();
                    if (server == null) return false;
                    var registry = server.registryAccess().lookupOrThrow(
                        qouteall.imm_ptl.core.portal.custom_portal_gen.CustomPortalGeneration.REGISTRY_KEY);
                    Object manager = qouteall.imm_ptl.core.IPPerServerInfo.of(server).customPortalGenManager;
                    return registry.size() >= 1 && manager != null;
                }, 600);
            } catch (Throwable t) {
                throw new AssertionError(LOG + "leg 5 (datapack) FAILED: after reopening the save"
                    + " with the dev pack in datapacks/, the"
                    + " immersive_portals:custom_portal_generation dynamic registry has no entries"
                    + " (or the manager never built)", t);
            }
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "leg 5 PASS — datapack custom generation decoded at world open + manager built");

            // RELOG PERSISTENCE — the fractional model's SavedData + rebind + behaviour, asserted
            // against the fixture rsRelogStage left in this very save before the close.
            rsRelogAssert(context);
            // AND THE FAR PAIR — no forceload, partner 7M out, player parked at the near portal:
            // the user's live relog, reproduced variable-for-variable.
            rsRelogFarAssert(context);

            SeamlessPortalsConstants.LOGGER.info(LOG + "ALL LEGS PASS");
        }
    }

    /**
     * A 3×3 identity-oriented portal (axisW=+X, axisH=+Y, normal +Z) at {@code origin}
     * targeting {@code destDim}/{@code dest} — the PortalCommand
     * {@code make_portal} recipe, minus the player-look derivation.
     */
    private static void spawnTestPortal(
        ServerLevel world, Vec3 origin, net.minecraft.resources.ResourceKey<Level> destDim, Vec3 dest
    ) {
        Portal portal = Portal.ENTITY_TYPE.create(world, EntitySpawnReason.COMMAND);
        if (portal == null) throw new AssertionError(LOG + "Portal.ENTITY_TYPE.create returned null");
        portal.setOriginPos(origin);
        portal.setDestinationDimension(destDim);
        portal.setDestination(dest);
        portal.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 3, 3);
        McHelper.spawnServerEntity(portal);
    }

    /**
     * IS1+ visual pre-screen: capture a screenshot iff
     * {@code -Dseamlessportals.gametest.screenshots} is set (the self-run rounds' lever;
     * port-note IS-iris-shaders-on §2.7). Never throws — a capture failure must not fail
     * a functional leg; it logs and moves on. Inert without the property (suite-neutral).
     */
    private static void maybeScreenshot(ClientGameTestContext context, String name) {
        if (!screenshotsLeverOn()) {
            return;
        }
        try {
            java.nio.file.Path shot = context.takeScreenshot(name);
            SeamlessPortalsConstants.LOGGER.info(LOG + "pre-screen screenshot saved: {}", shot);
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(LOG + "pre-screen screenshot '" + name
                + "' FAILED (non-fatal)", t);
        }
    }

    /**
     * The self-run visual rounds' lever ({@code -Dseamlessportals.gametest.screenshots}) —
     * gates the screenshots AND the §2.7 discriminator staging (portal D + its terrain pad)
     * so the default 8-leg suite stays byte-identical.
     */
    private static boolean screenshotsLeverOn() {
        return Boolean.getBoolean("seamlessportals.gametest.screenshots");
    }

    // =============================================================================================
    // IS2 EM EVIDENCE LEGS (iris shaders-ON engagement; port-note IS-iris-shaders-on §3.1
    // defects O + G, §3.2). All under the screenshots lever; all fail-soft — a failed aim or
    // screen interaction LOGS and continues, never fails a functional leg. These legs implement
    // NO fix: they capture the outline/creative-screen state as screenshot evidence rows.
    // =============================================================================================

    /**
     * EM-O-A + EM-O-B (defect O evidence): targeted-block-outline shots.
     * <ul>
     *   <li><b>EM-O-A</b>: aim (player rotation via {@code TestInput.lookAt}) at an ORDINARY
     *       obsidian platform block with NO portal on the pick ray — the target sits SOUTH
     *       (+Z, away from the portal row at {@code planeZ}) and one block down, ~2.9 blocks
     *       from the eye (creative pick range 5.0).</li>
     *   <li><b>EM-O-B</b>: reposition ~3 blocks south of the cross-dim portal B and aim INTO
     *       its window at the window-bottom point, so the transformed ray lands on the solid
     *       nether bedrock roof (y&le;127) just past the plane — total ray ~3.3 blocks, inside
     *       pick range even measured through the portal.</li>
     * </ul>
     */
    /**
     * RS-SEAM (redstone/rail/minecart passthrough, sub-feature (a) — the DECISIVE visual round).
     *
     * <p>The pinned seam model ({@code migration/REDSTONE_RECON.md} §0.5) assumes that a block
     * sitting in a portal's aperture is already clipped at the plane by the stencil, so its near
     * half draws in the source world and its far half is replaced by the portal view. That claim is
     * INFERRED from the stencil mechanics, never observed. Everything downstream — rail continuity,
     * the "two visible halves read as one block" behaviour, the 0.5-phase-offset case — depends on
     * it. This leg settles it.
     *
     * <p><b>The discriminator.</b> Portal B is 3×3, centred on {@code (px+4.5, py+1.5, planeZ)},
     * normal +Z, and {@code planeZ = pz - 5.5} — i.e. the plane bisects the block layer at
     * {@code z = pz-6}, exactly as an obsidian frame's plane does. Three white_concrete blocks go
     * into ONE column at {@code (px+4, py+1)}, differing only in z:
     * <ul>
     *   <li>{@code z = pz-5} — spans [pz-5, pz-4], entirely SOUTH of the plane → must render WHOLE.</li>
     *   <li>{@code z = pz-6} — spans [pz-6, pz-5], STRADDLES the plane → the decisive cell. Half =
     *       model is clipped at the plane (seam model HOLDS). Whole = no clipping, the block draws
     *       over the portal view (seam model FAILS). Absent = the portal view draws over it entirely
     *       (seam model FAILS the other way).</li>
     *   <li>{@code z = pz-7} — spans [pz-7, pz-6], entirely NORTH of the plane and inside the window
     *       → must be FULLY HIDDEN by the portal view.</li>
     * </ul>
     * The top and bottom blocks are controls: if they do not behave as stated, the shot is
     * mis-framed and the middle block proves nothing. All three in one frame means a single
     * screenshot answers the question with its own calibration built in.
     *
     * <p>A rail line is laid along the same column at foot level ({@code y = py}, on the obsidian
     * platform) running {@code z = pz-3 … pz-7}, straight through the aperture — the actual feature
     * geometry, so the round also shows what a real track through a portal looks like today.
     *
     * <p>Three shots: head-on, close-up head-on, and oblique. The oblique separates model clipping
     * from stencil overdraw — a model-clipped block stays half at every angle, whereas an overdrawn
     * one changes with viewing angle.
     *
     * <p>Asserts nothing (evidence only), fail-soft throughout, and gated behind the screenshots
     * lever so the default 8-leg suite stays byte-identical.
     */
    private static void rsSeamEvidenceLegs(
        ClientGameTestContext context, int px, int py, int pz, double planeZ
    ) {
        if (!screenshotsLeverOn()) {
            return;
        }
        try {
            final int colX = px + 4;          // portal B's centre column
            final int apertureZ = pz - PORTAL_OFFSET_Z;   // the block layer the plane bisects
            final int southZ = apertureZ + 1; // fully in front of the plane
            final int northZ = apertureZ - 1; // fully behind the plane, inside the window

            SeamlessPortalsConstants.LOGGER.info(
                LOG + "[RS-SEAM] plane z={} bisects block layer z={} (spans [{}, {}]);"
                    + " discriminator column x={} y={}: SOUTH(whole)={} STRADDLE(decisive)={}"
                    + " NORTH(hidden)={}; rail line y={} along z={}..{}",
                planeZ, apertureZ, apertureZ, apertureZ + 1, colX, py + 1,
                southZ, apertureZ, northZ, py, pz - 3, pz - 7);

            runCommands(context, List.of(
                // the three-block discriminator column
                "setblock " + colX + " " + (py + 1) + " " + southZ + " minecraft:white_concrete",
                "setblock " + colX + " " + (py + 1) + " " + apertureZ + " minecraft:white_concrete",
                "setblock " + colX + " " + (py + 1) + " " + northZ + " minecraft:white_concrete",
                // a real rail line running straight through the aperture at foot level
                "fill " + colX + " " + py + " " + (pz - 7) + " "
                    + colX + " " + py + " " + (pz - 3) + " minecraft:rail"
            ));
            context.waitTicks(20);

            // Report what actually landed — a rail that failed canSurvive, or a concrete block that
            // was rejected, would otherwise be invisible in the screenshot for the wrong reason.
            String placed = context.computeOnClient(mc -> {
                if (mc.level == null) return "level null";
                return "south=" + mc.level.getBlockState(new BlockPos(colX, py + 1, southZ)).getBlock()
                    + " straddle=" + mc.level.getBlockState(new BlockPos(colX, py + 1, apertureZ)).getBlock()
                    + " north=" + mc.level.getBlockState(new BlockPos(colX, py + 1, northZ)).getBlock()
                    + " railInAperture=" + mc.level.getBlockState(new BlockPos(colX, py, apertureZ)).getBlock();
            });
            SeamlessPortalsConstants.LOGGER.info(LOG + "[RS-SEAM] placement result: {}", placed);

            // ---- Shot 1: head-on, 3.5 blocks south of the plane, eye on the straddling block ----
            seamStand(context, px + 4.5, py, pz - 2.0);
            context.getInput().lookAt(new BlockPos(colX, py + 1, apertureZ));
            context.waitTicks(10);
            maybeScreenshot(context, "rs-seam-1-headon");

            // ---- Shot 2: close-up head-on (1.5 blocks out) — maximises the visible half ----
            seamStand(context, px + 4.5, py, pz - 4.0);
            context.getInput().lookAt(new BlockPos(colX, py + 1, apertureZ));
            context.waitTicks(10);
            maybeScreenshot(context, "rs-seam-2-closeup");

            // ---- Shot 3: oblique — separates model clipping from stencil overdraw ----
            seamStand(context, px + 8.0, py, pz - 3.0);
            context.getInput().lookAt(new BlockPos(colX, py + 1, apertureZ));
            context.waitTicks(10);
            maybeScreenshot(context, "rs-seam-3-oblique");

            SeamlessPortalsConstants.LOGGER.info(
                LOG + "[RS-SEAM] evidence captured — read the three shots against the column:"
                    + " SOUTH block whole + NORTH block hidden = shot is correctly framed;"
                    + " then the STRADDLE block half = seam model HOLDS, whole or absent = FAILS");
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(LOG + "[RS-SEAM] FAILED (non-fatal, evidence only)", t);
        } finally {
            // MANDATORY CLEANUP — this leg stages blocks INSIDE portal B's window, and leg 4 throws
            // an ender pearl straight through that window at (originB.x, originB.y-0.3) heading -Z.
            // The straddling block sits exactly on the pearl's path, so leaving it in place fails
            // leg 4 (observed: "owner never arrived in the nether"). An evidence leg must never
            // perturb a functional leg — restore the staging box before handing control back.
            try {
                final int colX = px + 4;
                final int apertureZ = pz - PORTAL_OFFSET_Z;
                runCommands(context, List.of(
                    "fill " + colX + " " + (py + 1) + " " + (pz - 7) + " "
                        + colX + " " + (py + 1) + " " + (pz - 5) + " minecraft:air",
                    "fill " + colX + " " + py + " " + (pz - 7) + " "
                        + colX + " " + py + " " + (pz - 3) + " minecraft:air"
                ));
                context.waitTicks(10);
                String after = context.computeOnClient(mc -> {
                    if (mc.level == null) return "level null";
                    return "straddle=" + mc.level.getBlockState(
                        new BlockPos(colX, py + 1, apertureZ)).getBlock()
                        + " railInAperture=" + mc.level.getBlockState(
                            new BlockPos(colX, py, apertureZ)).getBlock();
                });
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "[RS-SEAM] staging cleared (must both be air before leg 4): {}", after);
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(
                    LOG + "[RS-SEAM] CLEANUP FAILED — later legs may see a blocked window", t);
            }
        }
    }

    /**
     * RS-A-STEP1 — THE SEAM MAP GATE ({@code migration/REDSTONE_A_SPEC.md} §7 step 1).
     *
     * <p>{@code SeamMap} is the primitive that (b) rail connection, (c) redstone bridging and (d)
     * minecart traversal all consume, so an error in it is an error in all four sub-features at once.
     * The spec names its two most likely failures, and this leg is the falsifier for both — it runs
     * BEFORE anything is built on top.
     *
     * <p><b>It asserts against each portal's own geometry, never against hardcoded coordinates</b>,
     * so it cannot pass by coincidence and does not need rewriting when the staging moves:
     *
     * <ol>
     *   <li><b>Column count</b> — a grid-aligned w×h portal must bind exactly {@code w*h} columns.
     *       Too few means the overlap threshold is rejecting real cells; too many means slivers are
     *       being bound (the F7 defect the overlap rule exists to prevent).</li>
     *   <li><b>Distinctness</b> — no two columns may map to the same source cell.</li>
     *   <li><b>Containment</b> — every {@code seamCell} must lie in the block layer the plane passes
     *       through, i.e. the cell the plane bisects. This is what makes "the aperture cell"
     *       well-defined at all.</li>
     *   <li><b>THE INVOLUTION</b> (the real prize) — for a bi-way generated pair, stepping across via
     *       {@code mirrorCell} and back must return the original cell. This is the property that makes
     *       "break one half breaks the other" and "refuse on conflict" decidable, and it is the one
     *       both adversarial verifiers re-derived by hand without observing. Round-tripping through
     *       real portal transforms tests rotation, translation and phase simultaneously.</li>
     *   <li><b>Rotation</b> — {@code blockRotationOf} must be non-null for any mirrorable portal,
     *       since a null there means block states cannot be carried and mirroring must refuse. The
     *       spec calls this its single most likely arithmetic error.</li>
     * </ol>
     */
    private static void rsSeamMapGate(ClientGameTestContext context) {
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<String> report = new AtomicReference<>("(no portals examined)");
        AtomicReference<Integer> involutions = new AtomicReference<>(0);

        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow == null) { failure.set("no overworld"); return; }

            // Wide box: the spawned test portals sit near spawn, the leg-6a/6b generated pairs at
            // (-200,-200) and ~(-320,-320). A box that covers only spawn silently drops every bi-way
            // pair and with it the involution check.
            List<qouteall.imm_ptl.core.portal.Portal> portals = new java.util.ArrayList<>(
                ow.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                    new net.minecraft.world.phys.AABB(-1000, -128, -1000, 1000, 320, 1000), p -> true));
            if (portals.isEmpty()) { failure.set("no Portal entities to examine"); return; }

            StringBuilder sb = new StringBuilder();
            int examined = 0;
            for (qouteall.imm_ptl.core.portal.Portal p : portals) {
                if (!com.warwa.seamlessportals.passthrough.SeamMap.isMirrorable(p)) {
                    sb.append("\n  portal ").append(p.getId()).append(" NOT mirrorable (skipped)");
                    continue;
                }
                examined++;
                List<Vec3> cols = com.warwa.seamlessportals.passthrough.SeamMap.enumerateColumns(p);

                int expected = (int) Math.round(p.getWidth() * p.getHeight());
                if (cols.size() != expected) {
                    failure.set("portal " + p.getId() + " bound " + cols.size()
                        + " columns, expected " + expected + " for a "
                        + p.getWidth() + "x" + p.getHeight() + " grid-aligned portal");
                    return;
                }

                java.util.Set<BlockPos> seen = new java.util.HashSet<>();
                for (Vec3 col : cols) {
                    BlockPos src = com.warwa.seamlessportals.passthrough.SeamMap.seamCell(p, col);
                    if (!seen.add(src)) {
                        failure.set("portal " + p.getId() + " mapped two columns to the same cell " + src);
                        return;
                    }
                }

                // Containment: the plane must bisect every source cell it binds. Checked on the axis
                // the normal runs along, derived from the portal rather than assumed to be Z.
                Vec3 n = p.getNormal();
                for (BlockPos src : seen) {
                    double planeCoord = component(p.getOriginPos(), n);
                    double cellLo = component(Vec3.atLowerCornerOf(src), n);
                    double d = planeCoord - cellLo;
                    if (d < -1.0e-6 || d > 1.0 + 1.0e-6) {
                        failure.set("portal " + p.getId() + " seamCell " + src
                            + " is not bisected by its own plane (plane=" + planeCoord
                            + ", cell spans " + cellLo + ".." + (cellLo + 1) + ")");
                        return;
                    }
                }

                if (com.warwa.seamlessportals.passthrough.SeamMap.blockRotationOf(p) == null) {
                    failure.set("portal " + p.getId() + " is mirrorable but blockRotationOf returned"
                        + " null — block states could not be carried across it");
                    return;
                }

                sb.append("\n  portal ").append(p.getId())
                    .append(" ").append(p.getWidth()).append("x").append(p.getHeight())
                    .append(" cols=").append(cols.size())
                    .append(" rot=").append(com.warwa.seamlessportals.passthrough.SeamMap.blockRotationOf(p))
                    .append(" firstSeamCell=")
                    .append(com.warwa.seamlessportals.passthrough.SeamMap.seamCell(p, cols.get(0)))
                    .append(" firstMirrorCell=")
                    .append(com.warwa.seamlessportals.passthrough.SeamMap.mirrorCell(p, cols.get(0)));

                // THE INVOLUTION, on bi-way generated pairs only (spawned test portals are one-way).
                if (p instanceof qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity bp) {
                    var revs = qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity
                        .findReversePortals(bp);
                    if (revs.size() == 1) {
                        var q = revs.get(0);
                        for (Vec3 col : cols) {
                            BlockPos viaMirror =
                                com.warwa.seamlessportals.passthrough.SeamMap.mirrorCell(p, col);
                            Vec3 acrossOnPlane = com.warwa.seamlessportals.passthrough.SeamMap
                                .onPlane(q, p.transformPoint(col));
                            BlockPos viaSeamOfQ = com.warwa.seamlessportals.passthrough.SeamMap
                                .seamCell(q, acrossOnPlane);
                            if (!viaMirror.equals(viaSeamOfQ)) {
                                failure.set("INVOLUTION BROKEN on portal " + p.getId() + "/" + q.getId()
                                    + ": mirrorCell_P=" + viaMirror + " but seamCell_Q(T_P(x))="
                                    + viaSeamOfQ + " — 'break one half breaks the other' and"
                                    + " 'refuse on conflict' are undecidable if these disagree");
                                return;
                            }
                        }
                        involutions.set(involutions.get() + 1);
                        sb.append(" [involution OK vs reverse ").append(q.getId()).append("]");
                    }
                }
            }
            if (examined == 0) { failure.set("no mirrorable portals examined"); return; }

            // ---- STEP-2 GATE: the registry must agree with the arithmetic ----
            // SeamMap says which cells pair up; SeamRegistry indexes them by position. If the two
            // disagree, every later stage reads a lie. Checked against the SAME portals just
            // verified above, so a registry that indexed nothing cannot pass by being empty.
            //
            // LEVER-AWARE. Everything above this point is pure SeamMap arithmetic and is
            // lever-independent, so it always runs. The registry, by contrast, is only SEEDED when
            // the feature is on — AperturePassthroughInit returns early under the master lever — so
            // under -PdisableAperturePassthrough=true the correct assertion INVERTS: the registry
            // must be EMPTY. Demanding a populated registry there was asserting feature behaviour in
            // the configuration where the feature is off, and it failed the A/B attribution run.
            if (com.warwa.seamlessportals.passthrough.AperturePassthroughLever.DISABLED) {
                for (qouteall.imm_ptl.core.portal.Portal p : portals) {
                    if (com.warwa.seamlessportals.passthrough.SeamRegistry
                            .boundCellCount(p.level()) != 0) {
                        failure.set("passthrough is DISABLED but the registry holds "
                            + com.warwa.seamlessportals.passthrough.SeamRegistry
                                .boundCellCount(p.level())
                            + " bound cell(s) in " + p.level().dimension().identifier()
                            + " — the master lever is not fully disabling the feature");
                        return;
                    }
                }
                report.set("examined " + examined + " mirrorable portal(s), "
                    + involutions.get() + " involution check(s); registry cross-checks SKIPPED and"
                    + " emptiness asserted instead (passthrough DISABLED)" + sb);
                return;
            }

            int registryChecks = 0;
            int phaseChecks = 0;
            int queryOnlyChecks = 0;
            int strictChecks = 0;
            for (qouteall.imm_ptl.core.portal.Portal p : portals) {
                if (!com.warwa.seamlessportals.passthrough.SeamMap.isMirrorable(p)) continue;
                for (Vec3 col : com.warwa.seamlessportals.passthrough.SeamMap.enumerateColumns(p)) {
                    BlockPos src = com.warwa.seamlessportals.passthrough.SeamMap.seamCell(p, col);
                    BlockPos expectedDst =
                        com.warwa.seamlessportals.passthrough.SeamMap.mirrorCell(p, col);

                    if (!com.warwa.seamlessportals.passthrough.SeamRegistry
                            .sectionHasSeam(p.level(), src)) {
                        failure.set("registry hot-path gate missed section for bound cell " + src
                            + " (portal " + p.getId() + ") — setBlockState would skip it entirely");
                        return;
                    }
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry
                        .lookup(p.level(), src);
                    if (cell == null) {
                        failure.set("registry has no binding at " + src + " (portal " + p.getId()
                            + ") though SeamMap binds that column");
                        return;
                    }
                    // ★ POLICY-DECLINED SEAMS BIND QUERY-ONLY, AND THE GATE MUST KNOW THE POLICY.
                    // Since the 2026-07-26 exact-only decision, a portal whose translation is not
                    // integral (portal A's dest hangs a half-block off in Y) is classified OFFSET
                    // and bound with destPos == null. This check predates that policy and demanded
                    // a destination from every arithmetically-mirrorable portal — a LATENT red
                    // that never fired only because such portals happened to sit in chunks the
                    // full suite had unloaded by gate time (the player is in the nether after leg
                    // 4); RS-only mode keeps them loaded and exposed it. The gate now asserts the
                    // policy BOTH ways: a declined seam must be query-only, an admitted seam must
                    // carry the SeamMap destination — so under -PdisableSeamExactOnly the strict
                    // path applies to offset seams again, symmetrically.
                    boolean policyDeclined = !com.warwa.seamlessportals.passthrough.SeamMirrorPolicy
                        .mirrors(com.warwa.seamlessportals.passthrough.SeamMap.alignmentOf(p, src));
                    var ownBinding = cell.bindings().stream()
                        .filter(b -> b.portalUuid().equals(p.getUUID()))
                        .findFirst().orElse(null);
                    if (ownBinding == null) {
                        failure.set("registry cell at " + src + " carries no binding for portal "
                            + p.getId() + "; bindings=" + cell.bindings());
                        return;
                    }
                    if (policyDeclined) {
                        if (ownBinding.isMirrorable()) {
                            failure.set("alignment policy DECLINES portal " + p.getId() + " at "
                                + src + " but its binding still carries destination "
                                + ownBinding.destPos() + " — bind() is not applying the policy");
                            return;
                        }
                        queryOnlyChecks++;
                        registryChecks++;
                        continue;   // no destination => the destPos/cross-side/phase checks below
                                    // have no subject; the decline itself was the assertion
                    }
                    boolean found = cell.bindings().stream()
                        .anyMatch(b -> b.portalUuid().equals(p.getUUID())
                            && expectedDst.equals(b.destPos()));
                    if (!found) {
                        failure.set("registry binding at " + src + " for portal " + p.getId()
                            + " does not carry a destination consistent with SeamMap ("
                            + expectedDst + "); bindings=" + cell.bindings());
                        return;
                    }
                    strictChecks++;

                    // THE CROSS-SIDE AGREEMENT CHECK — the assertion whose absence let a real bug
                    // through. The mirror gate only ever verified the binding against ITSELF, which
                    // is self-consistent by construction and therefore proves nothing. What matters
                    // is that the cell this side WRITES TO is the same cell the far portal CLAIMS.
                    // Observed live before the fix: source mirrored to (-495,75,-500) while the
                    // destination portal's own seamCell was (-495,75,-501) — provenance was recorded
                    // against a cell no portal owned, so the frame-break rule found nothing to clear
                    // and the mirrored half survived as a duplicate.
                    var bindingForP = cell.bindings().stream()
                        .filter(b -> b.portalUuid().equals(p.getUUID()) && b.isMirrorable())
                        .findFirst().orElse(null);
                    if (bindingForP != null) {
                        ServerLevel destLevel = server.getLevel(bindingForP.destDim());
                        if (destLevel != null) {
                            Vec3 dpos = p.getDestPos();
                            var farPortals = destLevel.getEntitiesOfClass(
                                qouteall.imm_ptl.core.portal.Portal.class,
                                new net.minecraft.world.phys.AABB(
                                    dpos.subtract(2, 2, 2), dpos.add(2, 2, 2)),
                                q -> q != p && q.getOriginPos().distanceToSqr(dpos) < 0.25);
                            if (!farPortals.isEmpty()) {
                                var far = farPortals.get(0);
                                BlockPos farClaims = com.warwa.seamlessportals.passthrough.SeamMap
                                    .seamCell(far, com.warwa.seamlessportals.passthrough.SeamMap
                                        .onPlane(far, p.transformPoint(col)));
                                if (!farClaims.equals(bindingForP.destPos())) {
                                    failure.set("CROSS-SIDE DISAGREEMENT at " + src + ": portal "
                                        + p.getId() + " mirrors to " + bindingForP.destPos()
                                        + " but the destination portal " + far.getId()
                                        + " claims " + farClaims + " as its own aperture cell."
                                        + " Provenance would be recorded against a cell no portal"
                                        + " owns, and the frame-break rule would leave a duplicate.");
                                    return;
                                }
                            }
                        }
                    }
                    // ---- (b) PRIMITIVE: phase classification and continuationCell ----
                    // The two topologies differ by exactly ONE cell and neither error throws:
                    // in COINCIDENT the destination aperture cell IS this cell, so continuing into
                    // it would connect a rail to itself; in DISJOINT it is a genuinely distinct
                    // neighbour. Asserted here because every later (b)/(c)/(d) consumer inherits it.
                    var bForPhase = cell.bindings().stream()
                        .filter(x -> x.portalUuid().equals(p.getUUID()) && x.isMirrorable())
                        .findFirst().orElse(null);
                    if (bForPhase != null) {
                        double dPlane = Math.abs(p.getDistanceToPlane(Vec3.atCenterOf(src)));
                        var expectedPhase = dPlane < 0.25
                            ? com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT
                            : com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.DISJOINT;
                        if (bForPhase.phase() != expectedPhase) {
                            failure.set("PHASE MISCLASSIFIED at " + src + ": binding says "
                                + bForPhase.phase() + " but the plane is " + dPlane
                                + " from the cell centre, i.e. " + expectedPhase);
                            return;
                        }
                        BlockPos cont = bForPhase.continuationCell();
                        if (cont == null) {
                            failure.set("continuationCell null for a mirrorable binding at " + src);
                            return;
                        }
                        boolean sameAsDest = cont.equals(bForPhase.destPos());
                        // COINCIDENT must step PAST the shared slot; DISJOINT must land ON it.
                        if (expectedPhase == com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT
                            && sameAsDest) {
                            failure.set("COINCIDENT seam at " + src + " returned the destination"
                                + " aperture cell " + cont + " as its continuation — but that cell IS"
                                + " this cell (they are one mirrored slot), so a rail would connect to"
                                + " itself. It must step BEYOND it.");
                            return;
                        }
                        if (expectedPhase == com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.DISJOINT
                            && !sameAsDest) {
                            failure.set("DISJOINT seam at " + src + " skipped the destination aperture"
                                + " cell " + bForPhase.destPos() + " and returned " + cont
                                + " — in this topology the two cells are distinct neighbours and the"
                                + " destination cell IS the next cell.");
                            return;
                        }
                        // ★ DIRECTION PINNED AGAINST THE PORTAL'S OWN TRANSFORM. "Steps past the
                        // shared slot" alone cannot tell the CROSSING from the co-located FALLBACK —
                        // both differ from destPos — and the first build of continuationCell()
                        // returned the fallback (one step BEHIND the far plane) while this gate
                        // passed. Self-consistent tests prove nothing; the portal's content
                        // direction is the independent authority: the crossing continues one step
                        // from destPos along it.
                        if (expectedPhase == com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT) {
                            Vec3 cd = p.getContentDirection();
                            BlockPos viaContent = bForPhase.destPos().relative(
                                net.minecraft.core.Direction.getApproximateNearest(cd.x, cd.y, cd.z));
                            if (!cont.equals(viaContent)) {
                                failure.set("COINCIDENT continuation DIRECTION WRONG at " + src
                                    + ": continuationCell()=" + cont + " but the portal's own content"
                                    + " direction puts the crossing continuation at " + viaContent
                                    + " — the binding points at the co-located fallback cell behind"
                                    + " the far plane, not at the crossing");
                                return;
                            }
                        }
                        phaseChecks++;
                    }

                    registryChecks++;
                }
            }
            if (registryChecks == 0) {
                failure.set("zero registry cross-checks ran — the step-2 assertion never executed");
                return;
            }
            // Policy declines legitimately satisfy registryChecks, so they must not be allowed to
            // satisfy the anti-vacuity guard on their own: the STRICT destination-consistency and
            // phase batteries each need their own coverage floor, or a future policy tightening
            // could silently turn the whole step-2 section into query-only passes. (Panel
            // hardening, 2026-07-27.)
            if (strictChecks == 0) {
                failure.set("zero STRICT destination-consistency checks ran — every examined"
                    + " binding was policy-declined query-only; the step-2 assertion never"
                    + " executed on a real destination");
                return;
            }
            if (phaseChecks == 0) {
                failure.set("zero phase/continuation checks ran — no mirrorable binding with a"
                    + " destination was examined");
                return;
            }

            report.set("examined " + examined + " mirrorable portal(s), "
                + involutions.get() + " involution check(s), "
                + registryChecks + " registry cross-check(s) (" + queryOnlyChecks
                + " policy-declined query-only), "
                + phaseChecks + " phase/continuation check(s)" + sb);
        });

        String f = failure.get();
        if (f != null) {
            throw new AssertionError(LOG + "RS-A step-1 SEAM MAP GATE FAILED: " + f);
        }
        // ---- TOPOLOGY B COVERAGE: a BOUNDARY-PHASE fixture ----
        // Every portal the suite builds is an obsidian frame, whose plane is always mid-block, so
        // without this the DISJOINT branch of continuationCell() is written, asserted and NEVER
        // REACHED — an assertion nothing reaches, which is the trap that produced five false
        // instrument readings this engagement. A plain spawned Portal takes an arbitrary origin, so
        // putting its plane on an INTEGER coordinate rather than a .5 gives the boundary phase the
        // user requires: cells distinct, face-to-face, unmirrored.
        rsDisjointPhaseGate(context);

        // COVERAGE ASSERTION. The involution is the gate's whole reason to exist — it is what makes
        // "break one half breaks the other" and "refuse on conflict" decidable. A run in which no
        // bi-way pair was examined proves nothing about it, so passing silently would be a lie. This
        // is the same failure mode as the teardown probe that only ever logged intact=true.
        // Deliberately NOT lever-gated: the arithmetic is lever-independent and must hold in both
        // configurations, so this coverage requirement applies to the disabled run too.
        if (involutions.get() == 0) {
            throw new AssertionError(LOG + "RS-A step-1 SEAM MAP GATE FAILED: zero involution checks"
                + " ran — no bi-way portal pair was in range, so the gate's central assertion was"
                + " never exercised. A gate that can pass without testing its main property is not a"
                + " gate. Check leg 6a/6b ran before this and that the search box covers them.");
        }
        SeamlessPortalsConstants.LOGGER.info(LOG + "RS-A step-1 SEAM MAP GATE PASS — {}", report.get());
    }

    /**
     * TOPOLOGY B GATE — the boundary-phase case, which no obsidian portal can produce.
     *
     * <p>An obsidian frame's plane is always mid-block, so the whole suite exercises only
     * COINCIDENT seams. This spawns a plain {@code Portal} whose plane sits on an INTEGER coordinate,
     * giving the geometry the user actually asked for: source and destination cells DISTINCT,
     * face-to-face across the plane, and NOT mirrored. Asserts the phase is classified DISJOINT and
     * that {@code continuationCell()} lands ON the destination cell rather than stepping past it.
     *
     * <p>Fail-soft on setup, hard on the assertion: if the fixture cannot be built the gate says so
     * rather than passing quietly, because a silently-skipped topology-B check is worth nothing.
     */
    private static void rsDisjointPhaseGate(ClientGameTestContext context) {
        if (AperturePassthroughLever.DISABLED) {
            return;
        }
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<String> detail = new AtomicReference<>("(not run)");
        final int bx = 2000, by = 100, bz = 2000;   // far from every other fixture

        // FORCE-LOAD FIRST. A portal binds only while it TICKS (documented on SeamRegistry), and a
        // fixture 2000 blocks from any player does not tick, so it never binds and the gate has
        // nothing to examine. getChunk alone loads blocks, not entity ticking. This gate's own
        // coverage assertion caught that on the first run.
        runCommands(context, List.of(
            "forceload add " + (bx - 16) + " " + (bz - 16) + " " + (bx + 16) + " " + (bz + 16)));
        context.waitTicks(20);

        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow == null) { failure.set("no overworld"); return; }
            ow.getChunk(bx >> 4, bz >> 4);
            // Plane on an INTEGER z — the boundary phase. (An obsidian frame would give z+0.5.)
            qouteall.imm_ptl.core.portal.Portal portal =
                qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE.create(
                    ow, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if (portal == null) { failure.set("portal create returned null"); return; }
            portal.setOriginPos(new Vec3(bx + 0.5, by + 0.5, bz));
            portal.setDestinationDimension(Level.OVERWORLD);
            portal.setDestination(new Vec3(bx + 60.5, by + 0.5, bz));
            portal.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 1, 1);
            qouteall.imm_ptl.core.McHelper.spawnServerEntity(portal);
            detail.set("spawned boundary-phase portal id=" + portal.getId() + " at z=" + bz);
        });
        if (failure.get() != null) {
            throw new AssertionError(LOG + "RS-B DISJOINT GATE SETUP FAILED: " + failure.get());
        }
        // Long enough for the portal to tick and bind (bind runs off SERVER_PORTAL_TICK_SIGNAL).
        context.waitTicks(60);

        AtomicReference<Integer> checks = new AtomicReference<>(0);
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            for (var p : ow.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                new net.minecraft.world.phys.AABB(bx - 4, by - 4, bz - 4, bx + 4, by + 4, bz + 4),
                q -> true)) {
                if (!com.warwa.seamlessportals.passthrough.SeamMap.isMirrorable(p)) continue;
                for (Vec3 col : com.warwa.seamlessportals.passthrough.SeamMap.enumerateColumns(p)) {
                    BlockPos src = com.warwa.seamlessportals.passthrough.SeamMap.seamCell(p, col);
                    double dPlane = Math.abs(p.getDistanceToPlane(Vec3.atCenterOf(src)));
                    var phase = com.warwa.seamlessportals.passthrough.SeamMap.phaseOf(p, src);
                    if (phase != com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.DISJOINT) {
                        failure.set("FIXTURE IS NOT BOUNDARY-PHASE — cell " + src + " is " + dPlane
                            + " from the plane and classified " + phase
                            + "; the topology-B branch is still unreached and this gate proves nothing");
                        return;
                    }
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, src);
                    if (cell == null) { failure.set("no binding at boundary-phase cell " + src); return; }
                    for (var b : cell.bindings()) {
                        if (!b.isMirrorable()) continue;
                        if (!b.continuationCell().equals(b.destPos())) {
                            failure.set("DISJOINT continuation WRONG at " + src + ": returned "
                                + b.continuationCell() + " but in this topology the destination cell "
                                + b.destPos() + " IS the neighbour — stepping past it skips a real cell");
                            return;
                        }
                        checks.set(checks.get() + 1);
                    }
                }
            }
        });

        String f = failure.get();
        if (f != null) {
            throw new AssertionError(LOG + "RS-B DISJOINT GATE FAILED: " + f);
        }
        if (checks.get() == 0) {
            throw new AssertionError(LOG + "RS-B DISJOINT GATE FAILED: zero checks ran — the"
                + " boundary-phase fixture produced no mirrorable binding, so the topology-B branch"
                + " is STILL unreached. A gate that cannot reach its subject proves nothing.");
        }
        SeamlessPortalsConstants.LOGGER.info(
            LOG + "RS-B DISJOINT GATE PASS — {} boundary-phase check(s); destination cell IS the"
                + " continuation, cells distinct and unmirrored. {}", checks.get(), detail.get());
    }

    /**
     * RS (b) RAIL LEG, TOPOLOGY B — the decisive boundary-phase case: two distinct worlds
     * face-to-face at the plane, nothing mirrored, and a rail laid at the near cell must CONNECT to
     * the far side's own track.
     *
     * <p>Fixture: a same-dim bi-way pair whose plane sits on integer X, so the through axis is
     * EAST_WEST — deliberately NOT the placement default (NORTH_SOUTH), so the connected shape can
     * only come from the far rail. A Z-plane fixture would false-pass: its through shape IS the
     * default. Every verdict logs its working (actual shapes), and the leg is LEVER-AWARE: under
     * {@code -PdisableSeamShadow} the same fixture must produce the vanilla shapes, and a connected
     * shape there is reported as a REGRESSION.
     */
    private static void rsRailLegTopologyB(ClientGameTestContext context) {
        if (AperturePassthroughLever.DISABLED) {
            return;
        }
        final boolean shadowOn = !AperturePassthroughLever.DISABLE_SEAM_SHADOW;
        final boolean phaseGateOn = !AperturePassthroughLever.DISABLE_SEAM_PHASE_GATE;
        final int bx = 3200, by = 100, bz = 3200;   // clear of every other fixture's 128-block radius
        final BlockPos cellS = new BlockPos(bx - 1, by, bz);       // west of the plane at x=bx
        final BlockPos cellD = new BlockPos(bx + 60, by, bz);      // east of the dest plane at x=bx+60
        final BlockPos cellDE = new BlockPos(bx + 61, by, bz);     // far track continuing east
        final BlockPos cellLat = new BlockPos(bx - 1, by, bz - 1); // S's NORTH lateral
        AtomicReference<String> failure = new AtomicReference<>(null);

        try {
            runCommands(context, List.of(
                "forceload add " + (bx - 16) + " " + (bz - 16) + " " + (bx + 76) + " " + (bz + 16),
                "fill " + (bx - 6) + " " + (by - 1) + " " + (bz - 3) + " "
                    + (bx + 66) + " " + (by - 1) + " " + (bz + 3) + " minecraft:stone",
                "fill " + (bx - 6) + " " + by + " " + (bz - 3) + " "
                    + (bx + 66) + " " + (by + 3) + " " + (bz + 3) + " minecraft:air"
            ));
            context.waitTicks(20);

            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ow.getChunk(bx >> 4, bz >> 4);
                ow.getChunk((bx + 60) >> 4, bz >> 4);
                // Plane at integer x=bx, normal WEST (axisW=+Z, axisH=+Y): S is the cell WEST of
                // the plane, the crossing direction is EAST, and the through axis is X.
                qouteall.imm_ptl.core.portal.Portal p =
                    qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE.create(
                        ow, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                if (p == null) {
                    failure.set("portal create returned null");
                    return;
                }
                p.setOriginPos(new Vec3(bx, by + 0.5, bz + 0.5));
                p.setDestinationDimension(Level.OVERWORLD);
                p.setDestination(new Vec3(bx + 60, by + 0.5, bz + 0.5));
                p.setOrientationAndSize(new Vec3(0, 0, 1), new Vec3(0, 1, 0), 1, 1);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(p);
                qouteall.imm_ptl.core.portal.Portal q =
                    qouteall.imm_ptl.core.portal.PortalManipulation.createReversePortal(
                        p, qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(q);
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-RAIL-B SETUP FAILED: " + failure.get());
            }

            // Bind runs off the portal tick signal — poll the precondition, never a tick count.
            AtomicReference<Boolean> bound = new AtomicReference<>(false);
            for (int attempt = 0; attempt < 20 && !bound.get(); attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    bound.set(com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS) != null
                        && com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellD) != null);
                });
                if (!bound.get()) {
                    context.waitTicks(10);
                }
            }
            if (!bound.get()) {
                throw new AssertionError(LOG + "RS-RAIL-B FAILED: seam cells " + cellS + " / " + cellD
                    + " never bound — the boundary-phase pair did not register, every assertion"
                    + " below would be vacuous");
            }

            // ---- B0 COVERAGE PREAMBLE — aborts the leg if the fixture is not what it claims ----
            AtomicReference<String> inversionNote = new AtomicReference<>(null);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                var b = cell.bindings().stream()
                    .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                    .findFirst().orElse(null);
                if (b == null) {
                    failure.set("no mirrorable binding at S=" + cellS);
                    return;
                }
                if (b.phase() != com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.DISJOINT) {
                    failure.set("TOPOLOGY B NOT CONSTRUCTED — phase " + b.phase()
                        + " at S; every assertion below would be testing topology A");
                    return;
                }
                if (!b.seamContinuous()) {
                    failure.set("binding at S is not seamContinuous — (b) declines this seam and the"
                        + " leg proves nothing");
                    return;
                }
                BlockPos east = b.continuationToward(net.minecraft.core.Direction.EAST);
                BlockPos west = b.continuationToward(net.minecraft.core.Direction.WEST);
                if (!cellD.equals(east)) {
                    failure.set("CROSSING DIRECTION WRONG: continuationToward(EAST)=" + east
                        + " but the far aperture cell is " + cellD);
                    return;
                }
                if (west != null) {
                    failure.set("continuationToward(WEST)=" + west + " on a DISJOINT seam — WEST"
                        + " leads back into the approach, not across the plane");
                    return;
                }
                var farCell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellD);
                var bD = farCell.bindings().stream()
                    .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                    .findFirst().orElse(null);
                BlockPos backWest = bD == null
                    ? null : bD.continuationToward(net.minecraft.core.Direction.WEST);
                if (!cellS.equals(backWest)) {
                    failure.set("TOPOLOGY DOES NOT CLOSE: far cell D derives " + backWest
                        + " as its westward continuation, not S=" + cellS);
                    return;
                }

                // Far track first (plain writes: they are the far side's own scenery).
                ow.setBlock(cellD, Blocks.RAIL.defaultBlockState(), 3);
                ow.setBlock(cellDE, Blocks.RAIL.defaultBlockState(), 3);

                // ---- PHASE GATE ON THE VETO. With the gate ON, laying rail at S while the far
                // side's own rail occupies D must be ALLOWED — that is the exact gesture (b)
                // exists for. Under -PdisableSeamPhaseGate the veto must REFUSE (stock (a)
                // refuse-on-conflict), which reproduces the requirement-denied defect on demand. ----
                boolean mayPlace = com.warwa.seamlessportals.passthrough.SeamMirror.mayPlace(
                    ow, cellS, Blocks.RAIL.defaultBlockState());
                if (phaseGateOn && !mayPlace) {
                    failure.set("PHASE GATE NOT IN EFFECT: mayPlace refused a rail at S while D"
                        + " holds the far side's own track — the requirement is denied");
                    return;
                }
                if (!phaseGateOn) {
                    if (mayPlace) {
                        failure.set("PHASE-GATE INVERSION FAILED (the defect did not reproduce):"
                            + " with -PdisableSeamPhaseGate the veto should refuse on the occupied"
                            + " far cell, but it allowed the placement");
                        return;
                    }
                    inversionNote.set("PHASE-GATE INVERSION PASS — veto refused with the gate"
                        + " disabled, as stock (a) would");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-RAIL-B FAILED: " + failure.get());
            }
            if (inversionNote.get() != null) {
                SeamlessPortalsConstants.LOGGER.info(LOG + "RS-RAIL-B {} — connection legs skipped"
                    + " under -PdisableSeamPhaseGate (unconditional mirroring fights this fixture"
                    + " by design)", inversionNote.get());
                return;
            }

            // ---- B1: STRAIGHT THROUGH — the decisive sub-case. S's only possible EAST_WEST
            // source is the far rail at D: the local east cell is cleared air. ----
            final long hitsBefore =
                com.warwa.seamlessportals.passthrough.SeamRailContinuity.crossHitsCount();
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                writeAsPlayer(ow, cellS, Blocks.RAIL.defaultBlockState());
            });
            context.waitTicks(5);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var st = ow.getBlockState(cellS);
                if (!st.is(Blocks.RAIL)) {
                    failure.set("B1: the rail at S is gone (" + st.getBlock() + ")");
                    return;
                }
                var shape = st.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE);
                long hitsAfter =
                    com.warwa.seamlessportals.passthrough.SeamRailContinuity.crossHitsCount();
                SeamlessPortalsConstants.LOGGER.info(LOG + "RS-RAIL-B B1: S={} shape={} crossHits {} -> {}",
                    cellS, shape, hitsBefore, hitsAfter);
                if (shadowOn) {
                    if (shape != net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST) {
                        failure.set("B1 FAILED: S resolved " + shape + ", expected EAST_WEST —"
                            + " with the local east cell empty, only the far rail at D can produce"
                            + " it. The bridge did not read across.");
                    }
                    else if (hitsAfter <= hitsBefore) {
                        failure.set("B1 COVERAGE FAILED: S is EAST_WEST but crossHits did not move"
                            + " — the shape came from something other than the bridge, and this leg"
                            + " proves nothing");
                    }
                }
                else {
                    if (shape == net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST) {
                        failure.set("B1 INVERSION FAILED (REGRESSION): -PdisableSeamShadow is set"
                            + " but the rail still connected across the seam");
                    }
                    else if (shape != net.minecraft.world.level.block.state.properties.RailShape.NORTH_SOUTH) {
                        failure.set("B1 INVERSION: unexpected vanilla shape " + shape
                            + " (expected the NORTH_SOUTH placement default)");
                    }
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-RAIL-B FAILED: " + failure.get());
            }

            // ---- B2: CURVE AT THE SEAM — a local lateral arm plus the cross arm. The lateral's
            // own placement rewrites S through vanilla connectTo (an un-bracketed neighbour write:
            // exactly the path shape sync exists for; here it must simply not corrupt anything —
            // this seam is unmirrored). D must keep its own legitimate shape. ----
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                writeAsPlayer(ow, cellLat, Blocks.RAIL.defaultBlockState());
            });
            context.waitTicks(5);
            AtomicReference<Object> settledS = new AtomicReference<>(null);
            AtomicReference<Object> settledD = new AtomicReference<>(null);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var sShape = ow.getBlockState(cellS).getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE);
                var dShape = ow.getBlockState(cellD).getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE);
                settledS.set(sShape);
                settledD.set(dShape);
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "RS-RAIL-B B2: S={} D={} (lat placed north of S)", sShape, dShape);
                var expectS = shadowOn
                    ? net.minecraft.world.level.block.state.properties.RailShape.NORTH_EAST
                    : net.minecraft.world.level.block.state.properties.RailShape.NORTH_SOUTH;
                if (sShape != expectS) {
                    failure.set("B2 " + (shadowOn ? "FAILED" : "INVERSION FAILED") + ": S resolved "
                        + sShape + ", expected " + expectS
                        + (shadowOn
                            ? " (north arm local, east arm across the seam)"
                            : " (north arm only — seeing a cross arm here is a REGRESSION)"));
                    return;
                }
                if (dShape != net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST) {
                    failure.set("B2 FAILED: the far side's own rail at D was rewritten to " + dShape
                        + " — (b) must never override the far world's legitimate shape");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-RAIL-B FAILED: " + failure.get());
            }

            // ---- TERMINATION: shapes stable across 40 ticks; budgets untouched. A per-call depth
            // cap cannot catch a cross-tick oscillation — this can. ----
            context.waitTicks(40);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var sShape = ow.getBlockState(cellS).getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE);
                var dShape = ow.getBlockState(cellD).getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE);
                if (sShape != settledS.get() || dShape != settledD.get()) {
                    failure.set("OSCILLATION: shapes moved across 40 idle ticks — S " + settledS.get()
                        + " -> " + sShape + ", D " + settledD.get() + " -> " + dShape);
                    return;
                }
                long depthTrips = com.warwa.seamlessportals.passthrough.SeamRailContinuity.depthCapTrips();
                long budgetTrips = com.warwa.seamlessportals.passthrough.SeamRailContinuity.budgetTrips();
                if (depthTrips != 0 || budgetTrips != 0) {
                    // WHOLE-RUN invariant read from global monotonic counters: a trip here may have
                    // been caused by ANY leg so far, not necessarily this fixture. The counters
                    // string is what localises it.
                    failure.set("BUDGET TRIPPED (whole-run invariant, not necessarily this leg):"
                        + " depthCapTrips=" + depthTrips + " budgetTrips=" + budgetTrips
                        + " — evidence of a design fault, not a licence to run. counters: "
                        + com.warwa.seamlessportals.passthrough.SeamRailContinuity.counters());
                }
                // MISROUTE CANARY: the local cell BEHIND the plane must never be written — a proxy
                // write landing at its local shadow coordinate (instead of the far level) is a
                // phantom rail in the source dimension, invisible to every shape assertion above.
                var behindPlane = ow.getBlockState(new BlockPos(bx, by, bz));
                if (!behindPlane.isAir()) {
                    failure.set("PHANTOM WRITE BEHIND THE PLANE: local cell (" + bx + "," + by + ","
                        + bz + ") holds " + behindPlane.getBlock() + " — a shadow-frame write was"
                        + " executed at its LOCAL coordinate instead of the far level");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-RAIL-B FAILED: " + failure.get());
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-RAIL-B PASS — "
                + (shadowOn
                    ? "rails CONNECT across the boundary-phase seam (straight + curve), far side"
                        + " untouched, stable, budgets zero. counters: "
                    : "INVERSION: vanilla shapes under -PdisableSeamShadow, budgets zero. counters: ")
                + com.warwa.seamlessportals.passthrough.SeamRailContinuity.counters());
        }
        finally {
            try {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    for (var portal : ow.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                        new net.minecraft.world.phys.AABB(bx - 4, by - 4, bz - 4,
                            bx + 66, by + 6, bz + 4), x -> true)) {
                        portal.discard();
                    }
                });
                runCommands(context, List.of(
                    "fill " + (bx - 6) + " " + (by - 1) + " " + (bz - 3) + " "
                        + (bx + 66) + " " + (by + 3) + " " + (bz + 3) + " minecraft:air",
                    "forceload remove " + (bx - 16) + " " + (bz - 16) + " "
                        + (bx + 76) + " " + (bz + 16)
                ));
            }
            catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "RS-RAIL-B cleanup failed", t);
            }
        }
    }

    /**
     * RS (b) RAIL LEG, TOPOLOGY A — an ignited obsidian frame: the shared mid-block slot, kept
     * byte-identical by (a)'s mirror, joining an overworld approach to a nether continuation.
     *
     * <p>The sequencing is deliberate: the seam rail is placed FIRST (resolving straight toward the
     * nether track through the bridge), and a second aperture rail EAST of it is placed AFTER —
     * vanilla then rewrites the seam rail through {@code RailState.connectTo}, an un-bracketed
     * neighbour write. That exercises the SHAPE-SYNC path end-to-end, and the leg's cross-side
     * assertion ({@code nether(D) == ow(S).rotate(R)}) fails without it. Under
     * {@code -PdisableSeamShapeSync} the leg expects the INEQUALITY instead — the divergence
     * reproduced on demand.
     */
    private static void rsRailLegTopologyA(ClientGameTestContext context, int py) {
        if (AperturePassthroughLever.DISABLED) {
            return;
        }
        final boolean shadowOn = !AperturePassthroughLever.DISABLE_SEAM_SHADOW;
        final boolean shapeSyncOn = !AperturePassthroughLever.DISABLE_SEAM_SHAPE_SYNC;
        final int fx = 6000, fz = -6000;   // nether counterpart ~(750,-750): clear of every fixture
        final BlockPos cellSA = new BlockPos(fx, py + 1, fz);       // bottom-left opening cell
        final BlockPos cellSA2 = new BlockPos(fx + 1, py + 1, fz);  // bottom-right opening cell
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<Vec3> destSeen = new AtomicReference<>(null);

        try {
            runCommands(context, List.of(
                "forceload add " + (fx - 16) + " " + (fz - 16) + " " + (fx + 16) + " " + (fz + 16),
                // CLEAR FIRST, BUILD INTO THE CLEARING — a clear placed after the frame fills would
                // wipe the frame columns. The generous box also strips any natural terrain that
                // could donate rail arms or support quirks around the aperture.
                "fill " + (fx - 2) + " " + py + " " + (fz - 2) + " "
                    + (fx + 3) + " " + (py + 5) + " " + (fz + 2) + " minecraft:air",
                fill(fx - 1, py, fz, fx + 2, py, fz),
                fill(fx - 1, py + 4, fz, fx + 2, py + 4, fz),
                fill(fx - 1, py + 1, fz, fx - 1, py + 3, fz),
                fill(fx + 2, py + 1, fz, fx + 2, py + 3, fz)
            ));
            context.waitTicks(20);
            runOnServer(context, server -> {
                boolean fired = qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration
                    .onFireLitOnObsidian(server.getLevel(Level.OVERWORLD),
                        new BlockPos(fx, py + 1, fz), null);
                if (!fired) {
                    failure.set("ignition entry rejected the frame");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-RAIL-A SETUP FAILED: " + failure.get());
            }
            final net.minecraft.world.phys.AABB frameBox = new net.minecraft.world.phys.AABB(
                fx - 8, py - 8, fz - 8, fx + 8, py + 8, fz + 8);
            try {
                context.waitFor(mc -> {
                    MinecraftServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        return false;
                    }
                    return !server.getLevel(Level.OVERWORLD).getEntitiesOfClass(
                        qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                        frameBox, x -> true).isEmpty();
                }, 1200);
            }
            catch (Throwable t) {
                throw new AssertionError(LOG + "RS-RAIL-A FAILED: no NetherPortalEntity generated"
                    + " within 1200 ticks", t);
            }
            runOnServer(context, server -> {
                var portals = server.getLevel(Level.OVERWORLD).getEntitiesOfClass(
                    qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                    frameBox, x -> true);
                destSeen.set(portals.get(0).getDestPos());
            });

            // Wait for the aperture to BIND (tick-signal-driven; poll the precondition).
            AtomicReference<com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding> bindingRef =
                new AtomicReference<>(null);
            for (int attempt = 0; attempt < 30 && bindingRef.get() == null; attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellSA);
                    if (cell == null) {
                        return;
                    }
                    cell.bindings().stream()
                        .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                        .findFirst().ifPresent(bindingRef::set);
                });
                if (bindingRef.get() == null) {
                    context.waitTicks(10);
                }
            }
            var binding = bindingRef.get();
            if (binding == null) {
                throw new AssertionError(LOG + "RS-RAIL-A FAILED: aperture cell " + cellSA
                    + " never bound with a mirrorable binding");
            }
            if (binding.phase() != com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT) {
                throw new AssertionError(LOG + "RS-RAIL-A FAILED: obsidian aperture classified "
                    + binding.phase() + " — an obsidian plane is mid-block and must be COINCIDENT");
            }
            if (!binding.seamContinuous()) {
                throw new AssertionError(LOG + "RS-RAIL-A FAILED: obsidian binding not"
                    + " seamContinuous — (b) would decline its own primary geometry");
            }
            final net.minecraft.core.Direction crossDir = binding.crossDir();
            final BlockPos cellDA = binding.destPos();
            final BlockPos contC = binding.continuationToward(crossDir);
            final net.minecraft.world.level.block.Rotation rotR = binding.stateRotation();
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "RS-RAIL-A geometry: S={} crossDir={} D={} in {} continuation={} R={}",
                cellSA, crossDir, cellDA, binding.destDim().identifier(), contC, rotR);

            // Nether-side continuation track: support cube below it, rail on it, headroom cleared.
            runOnServer(context, server -> {
                ServerLevel nether = server.getLevel(binding.destDim());
                if (nether == null) {
                    failure.set("destination level " + binding.destDim() + " missing");
                    return;
                }
                nether.getChunk(contC.getX() >> 4, contC.getZ() >> 4);
                nether.setBlock(contC.below(), Blocks.STONE.defaultBlockState(), 3);
                nether.setBlock(contC.above(), Blocks.AIR.defaultBlockState(), 3);
                nether.setBlock(contC, Blocks.RAIL.defaultBlockState(), 3);
                if (!nether.getBlockState(contC).is(Blocks.RAIL)) {
                    failure.set("nether continuation rail at " + contC + " did not survive placement"
                        + " (" + nether.getBlockState(contC).getBlock() + ")");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-RAIL-A SETUP FAILED: " + failure.get());
            }

            // ---- Place the seam rail, then the second aperture rail east of it. ----
            final long hitsBefore =
                com.warwa.seamlessportals.passthrough.SeamRailContinuity.crossHitsCount();
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                writeAsPlayer(ow, cellSA, Blocks.RAIL.defaultBlockState());
            });
            context.waitTicks(5);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                writeAsPlayer(ow, cellSA2, Blocks.RAIL.defaultBlockState());
            });
            context.waitTicks(5);

            AtomicReference<Object> settledS = new AtomicReference<>(null);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ServerLevel nether = server.getLevel(binding.destDim());
                var sState = ow.getBlockState(cellSA);
                var dState = nether.getBlockState(cellDA);
                if (!sState.is(Blocks.RAIL)) {
                    failure.set("seam rail at S is gone (" + sState.getBlock() + ")");
                    return;
                }
                var sShape = sState.getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE);
                long hitsAfter =
                    com.warwa.seamlessportals.passthrough.SeamRailContinuity.crossHitsCount();
                settledS.set(sShape);
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "RS-RAIL-A: S={} D={} crossHits {} -> {}",
                    sShape, dState, hitsBefore, hitsAfter);

                var expectCurve = crossDir == net.minecraft.core.Direction.NORTH
                    ? net.minecraft.world.level.block.state.properties.RailShape.NORTH_EAST
                    : net.minecraft.world.level.block.state.properties.RailShape.SOUTH_EAST;
                if (shadowOn) {
                    if (sShape != expectCurve) {
                        failure.set("CURVE FAILED: S resolved " + sShape + ", expected " + expectCurve
                            + " (east arm from the second aperture rail, " + crossDir
                            + " arm from the nether continuation through the bridge)");
                        return;
                    }
                    if (hitsAfter <= hitsBefore) {
                        failure.set("COVERAGE FAILED: the curve appeared but crossHits never moved"
                            + " — the arm did not come from the bridge and this leg proves nothing");
                        return;
                    }
                }
                else {
                    if (sShape != net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST) {
                        failure.set("INVERSION FAILED: with -PdisableSeamShadow S should hold only"
                            + " the local east arm (EAST_WEST), got " + sShape
                            + (sShape == expectCurve ? " — a cross arm survived: REGRESSION" : ""));
                        return;
                    }
                }

                // ---- CROSS-SIDE INVARIANT (the assertion whose absence let a real bug through):
                // the mirrored half must equal the player's half under the binding's rotation, AFTER
                // the un-bracketed connectTo rewrite. This is shape sync end-to-end. ----
                var expectedD = sState.rotate(rotR);
                boolean identical = dState == expectedD;
                if (shapeSyncOn && !identical) {
                    failure.set("CROSS-SIDE DIVERGENCE: nether " + cellDA + " holds " + dState
                        + " but the player's half rotated is " + expectedD
                        + " — the un-bracketed neighbour rewrite was not re-mirrored (shape sync"
                        + " failed), the two halves of one visual block disagree");
                    return;
                }
                if (!shapeSyncOn) {
                    // The inversion must name the divergence it reproduces: the far half still A
                    // RAIL holding the PRE-REWRITE mirrored shape — not merely "anything unequal",
                    // which an absent far half would also satisfy. (Panel hardening, 2026-07-27.)
                    if (!dState.is(Blocks.RAIL)) {
                        failure.set("SHAPE-SYNC INVERSION: the far half at " + cellDA
                            + " is not a rail at all (" + dState.getBlock()
                            + ") — the (a) mirror never wrote it; this is a fixture fault, not the"
                            + " divergence the inversion exists to reproduce");
                        return;
                    }
                    if (identical) {
                        failure.set("SHAPE-SYNC INVERSION FAILED (the defect did not reproduce):"
                            + " with -PdisableSeamShapeSync the halves should diverge after the"
                            + " neighbour rewrite, but they agree — either nothing rewrote S"
                            + " (fixture broken) or the lever does not disable the path");
                        return;
                    }
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-RAIL-A FAILED: " + failure.get());
            }

            // ---- Stability: the pair must not flicker (T2' residual watch). ----
            context.waitTicks(40);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ServerLevel nether = server.getLevel(binding.destDim());
                var sShape = ow.getBlockState(cellSA).getValue(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE);
                if (sShape != settledS.get()) {
                    failure.set("OSCILLATION: S moved " + settledS.get() + " -> " + sShape
                        + " across 40 idle ticks");
                    return;
                }
                if (shapeSyncOn) {
                    var dState = nether.getBlockState(cellDA);
                    var expectedD = ow.getBlockState(cellSA).rotate(rotR);
                    if (dState != expectedD) {
                        failure.set("CROSS-SIDE DRIFT: after 40 idle ticks nether " + cellDA
                            + " holds " + dState + " vs expected " + expectedD);
                        return;
                    }
                }
                long depthTrips = com.warwa.seamlessportals.passthrough.SeamRailContinuity.depthCapTrips();
                long budgetTrips = com.warwa.seamlessportals.passthrough.SeamRailContinuity.budgetTrips();
                if (depthTrips != 0 || budgetTrips != 0) {
                    // Asserted HERE too, not only in RS-RAIL-B: this is the topology where the
                    // mirror is ACTIVE, so cross writes, shape sync and reseeds all ran by now.
                    // (Panel finding: the ceiling was only asserted where the mirror is inactive.)
                    failure.set("BUDGET TRIPPED (whole-run invariant): depthCapTrips=" + depthTrips
                        + " budgetTrips=" + budgetTrips + ". counters: "
                        + com.warwa.seamlessportals.passthrough.SeamRailContinuity.counters());
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-RAIL-A FAILED: " + failure.get());
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-RAIL-A PASS — "
                + (shadowOn
                    ? "obsidian seam rail curves onto the nether continuation"
                    : "INVERSION: local-only shape under -PdisableSeamShadow")
                + (shapeSyncOn
                    ? "; halves byte-identical"
                    : "; halves DIVERGED as the shape-sync inversion expects")
                + "; stable 40 ticks. counters: "
                + com.warwa.seamlessportals.passthrough.SeamRailContinuity.counters());
        }
        finally {
            try {
                runCommands(context, List.of(
                    "fill " + (fx - 2) + " " + (py - 1) + " " + (fz - 2) + " "
                        + (fx + 3) + " " + (py + 5) + " " + (fz + 2) + " minecraft:air",
                    "forceload remove " + (fx - 16) + " " + (fz - 16) + " "
                        + (fx + 16) + " " + (fz + 16)
                ));
                Vec3 d = destSeen.get();
                if (d != null) {
                    int dx = (int) Math.floor(d.x), dy = (int) Math.floor(d.y), dz = (int) Math.floor(d.z);
                    runCommands(context, List.of(
                        "execute in minecraft:the_nether run forceload add " + (dx - 16) + " "
                            + (dz - 16) + " " + (dx + 16) + " " + (dz + 16),
                        inDim("minecraft:the_nether", "fill " + (dx - 5) + " " + (dy - 2) + " "
                            + (dz - 5) + " " + (dx + 5) + " " + (dy + 5) + " " + (dz + 5)
                            + " minecraft:air"),
                        "execute in minecraft:the_nether run forceload remove " + (dx - 16) + " "
                            + (dz - 16) + " " + (dx + 16) + " " + (dz + 16)
                    ));
                }
            }
            catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "RS-RAIL-A cleanup failed", t);
            }
        }
    }

    /**
     * RS (c) SIGNAL LEG, TOPOLOGY A — the powered-rail chain crosses a COINCIDENT (obsidian) seam,
     * then a seam LAMP lights from a far-side source on the same fixture
     * ({@code REDSTONE_C_SPEC.md} §5, arms A + L).
     *
     * <p>Outcome-asserted per the house rule: the verdict reads the FAR world's {@code POWERED} /
     * {@code LIT} block state after the full vanilla cascade — the state the user sees lit and the
     * cart accelerates on — never a value (c) computed. Coverage: the walk-crossing counter must
     * MOVE for the rail pass (power that appeared without the bridge proves nothing), and the
     * NETHER-side binding must exist before anything is asserted (first run's exact failure: the
     * far level had no bindings, so its rails could not resolve across — see the forceload note).
     *
     * <p>Lever-aware: under {@code -PdisableSeamSignal} the source side must power and the mirrored
     * half must show powered (the user's reported (b)-era baseline) while the far continuation
     * stays dark — the reported defect reproduced on demand. The LAMP arm additionally inverts
     * under {@code -PdisableSeamSignalDispatch}: reads alone cannot wake the source half, and the
     * authority rule keeps the far half reverted — both halves provably dark. (The RAIL pass keeps
     * its ON expectations under dispatch-off: the mirror's flags-515 shape-sync write already
     * notifies the far side there, so asserting a rail dispatch inversion in this arm would be a
     * gate that cannot fail.)
     *
     * <p>Geometry pinned (the 8-step walk cap): approach is 2 rails + the seam cell; the far
     * continuation is 2 rails; the deepest walk (B2's) consults B1@0, S'@1, A1@2, A0@3 &lt; 8. The
     * redstone block sits LATERAL beside A0, two cells from the seam — beside the seam cell it
     * would satisfy B1's walk through the R-UNION and the walk-coverage assert would false-fail a
     * working run.
     */
    private static void rsSignalLegCoincident(ClientGameTestContext context, int py) {
        if (AperturePassthroughLever.DISABLED) {
            return;
        }
        if (AperturePassthroughLever.DISABLE_SEAM_SHAPE_SYNC) {
            // Topology A's whole signal chain rides the mirrored half's POWERED state, which IS
            // shape sync — with it disabled the pair diverges by design (RS-RAIL-A asserts that
            // divergence) and every expectation below is undefined. Skip loudly.
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-SIGNAL-A SKIPPED under"
                + " -PdisableSeamShapeSync — the coincident chain consumes shape sync; its"
                + " inversion coverage lives in RS-RAIL-A");
            return;
        }
        final boolean signalOn = !AperturePassthroughLever.DISABLE_SEAM_SIGNAL;
        // The WALK consumes (b)'s SeamShadow primitive, so (b)'s master lever kills it too; the
        // UNION and DISPATCH ride the (a) registry directly and stay live under -PdisableSeamShadow
        // — which the lamp pass then positively proves (extra coverage, not an accident).
        final boolean railBridgeOn = signalOn && !AperturePassthroughLever.DISABLE_SEAM_SHADOW;
        final boolean dispatchOn = signalOn && !AperturePassthroughLever.DISABLE_SEAM_SIGNAL_DISPATCH;
        // Nether counterpart ~(875,-875): 176.8 blocks from RS-RAIL-A's (750,-750) — outside the
        // 128-block (±152 effective) frame-match radius that has false-linked a leg before.
        final int fx = 7000, fz = -7000;
        final BlockPos cellSA = new BlockPos(fx, py + 1, fz);
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<Vec3> destSeen = new AtomicReference<>(null);
        final var POWERED = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;
        final var LIT = net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT;

        try {
            runCommands(context, List.of(
                "forceload add " + (fx - 16) + " " + (fz - 16) + " " + (fx + 16) + " " + (fz + 16),
                "fill " + (fx - 2) + " " + py + " " + (fz - 4) + " "
                    + (fx + 3) + " " + (py + 5) + " " + (fz + 4) + " minecraft:air",
                fill(fx - 1, py, fz, fx + 2, py, fz),
                fill(fx - 1, py + 4, fz, fx + 2, py + 4, fz),
                fill(fx - 1, py + 1, fz, fx - 1, py + 3, fz),
                fill(fx + 2, py + 1, fz, fx + 2, py + 3, fz)
            ));
            context.waitTicks(20);
            runOnServer(context, server -> {
                boolean fired = qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration
                    .onFireLitOnObsidian(server.getLevel(Level.OVERWORLD),
                        new BlockPos(fx, py + 1, fz), null);
                if (!fired) {
                    failure.set("ignition entry rejected the frame");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-A SETUP FAILED: " + failure.get());
            }
            final net.minecraft.world.phys.AABB frameBox = new net.minecraft.world.phys.AABB(
                fx - 8, py - 8, fz - 8, fx + 8, py + 8, fz + 8);
            try {
                context.waitFor(mc -> {
                    MinecraftServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        return false;
                    }
                    return !server.getLevel(Level.OVERWORLD).getEntitiesOfClass(
                        qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                        frameBox, x -> true).isEmpty();
                }, 1200);
            }
            catch (Throwable t) {
                throw new AssertionError(LOG + "RS-SIGNAL-A FAILED: no NetherPortalEntity generated"
                    + " within 1200 ticks", t);
            }
            runOnServer(context, server -> {
                var portals = server.getLevel(Level.OVERWORLD).getEntitiesOfClass(
                    qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                    frameBox, x -> true);
                destSeen.set(portals.get(0).getDestPos());
            });

            AtomicReference<com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding> bindingRef =
                new AtomicReference<>(null);
            for (int attempt = 0; attempt < 30 && bindingRef.get() == null; attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellSA);
                    if (cell == null) {
                        return;
                    }
                    cell.bindings().stream()
                        .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                        .findFirst().ifPresent(bindingRef::set);
                });
                if (bindingRef.get() == null) {
                    context.waitTicks(10);
                }
            }
            var binding = bindingRef.get();
            if (binding == null) {
                throw new AssertionError(LOG + "RS-SIGNAL-A FAILED: aperture cell " + cellSA
                    + " never bound with a mirrorable binding");
            }
            if (binding.phase() != com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT
                || !binding.seamContinuous()) {
                throw new AssertionError(LOG + "RS-SIGNAL-A FAILED: obsidian binding phase="
                    + binding.phase() + " continuous=" + binding.seamContinuous()
                    + " — not the coincident traversable seam this arm exists to test");
            }
            final net.minecraft.core.Direction crossDir = binding.crossDir();
            final net.minecraft.core.Direction approachDir = crossDir.getOpposite();
            final BlockPos destPos = binding.destPos();                       // S' — the far half
            final BlockPos contC = binding.continuationToward(crossDir);      // B1
            final net.minecraft.world.level.block.Rotation rotR = binding.stateRotation();
            final net.minecraft.core.Direction farStep = rotR.rotate(crossDir);
            final BlockPos contC2 = contC.relative(farStep);                  // B2
            final BlockPos behindFar = destPos.relative(farStep.getOpposite());
            final BlockPos a1 = cellSA.relative(approachDir);
            final BlockPos a0 = a1.relative(approachDir);
            final BlockPos powerPos = a0.relative(crossDir.getClockWise());
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "RS-SIGNAL-A geometry: S={} crossDir={} S'={} in {} B1={} B2={} A1={} A0={}"
                    + " power={} R={}",
                cellSA, crossDir, destPos, binding.destDim().identifier(), contC, contC2, a1, a0,
                powerPos, rotR);

            // ★ FORCELOAD THE NETHER SIDE AND WAIT FOR ITS OWN BINDINGS. The far level's rails
            // resolve across the seam through the far level's OWN registry, which binds only while
            // the NETHER-side portal entities TICK — and the mirror's one-off getChunk loads far
            // chunks without making them entity-ticking. First run's exact failure: far rails never
            // powered, walkCrossed frozen, because S' had no SeamCell in the nether. A forceload
            // ticket is a ticking ticket; the poll below is the arm's coverage assertion.
            runCommands(context, List.of(
                "execute in minecraft:the_nether run forceload add "
                    + (destPos.getX() - 16) + " " + (destPos.getZ() - 16) + " "
                    + (destPos.getX() + 16) + " " + (destPos.getZ() + 16)));
            AtomicReference<Boolean> netherBound = new AtomicReference<>(false);
            for (int attempt = 0; attempt < 30 && !netherBound.get(); attempt++) {
                runOnServer(context, server -> {
                    ServerLevel nether = server.getLevel(binding.destDim());
                    if (nether == null) {
                        return;
                    }
                    var farCell = com.warwa.seamlessportals.passthrough.SeamRegistry
                        .lookup(nether, destPos);
                    netherBound.set(farCell != null && farCell.bindings().stream().anyMatch(fb ->
                        fb.isMirrorable() && fb.seamContinuous()));
                });
                if (!netherBound.get()) {
                    context.waitTicks(10);
                }
            }
            if (!netherBound.get()) {
                throw new AssertionError(LOG + "RS-SIGNAL-A SETUP FAILED: the NETHER side of the"
                    + " seam never bound at " + destPos + " — without far-side bindings the far"
                    + " level cannot resolve across and every assertion below is vacuous");
            }

            // Far continuation: supports, headroom, DETERMINISTIC dead-end behind the far plane
            // (the walk recursing past S' must find air there, not whatever the generated nether
            // platform happens to hold — a powered rail there would false-pass the inversion).
            runOnServer(context, server -> {
                ServerLevel nether = server.getLevel(binding.destDim());
                if (nether == null) {
                    failure.set("destination level " + binding.destDim() + " missing");
                    return;
                }
                nether.getChunk(contC.getX() >> 4, contC.getZ() >> 4);
                nether.setBlock(behindFar, Blocks.AIR.defaultBlockState(), 3);
                nether.setBlock(behindFar.below(), Blocks.AIR.defaultBlockState(), 3);
                for (BlockPos p : List.of(contC, contC2)) {
                    nether.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);
                    nether.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
                    nether.setBlock(p, Blocks.POWERED_RAIL.defaultBlockState(), 3);
                }
                if (!nether.getBlockState(contC).is(Blocks.POWERED_RAIL)
                    || !nether.getBlockState(contC2).is(Blocks.POWERED_RAIL)) {
                    failure.set("nether continuation golden rails did not survive placement: B1="
                        + nether.getBlockState(contC).getBlock() + " B2="
                        + nether.getBlockState(contC2).getBlock());
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-A SETUP FAILED: " + failure.get());
            }

            // Source approach: supports + golden rails, then the seam rail AS THE PLAYER (the
            // mirror creates the far half — the pair the user's report is about).
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                for (BlockPos p : List.of(a1, a0)) {
                    ow.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);
                    ow.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 3);
                    ow.setBlock(p, Blocks.POWERED_RAIL.defaultBlockState(), 3);
                }
                writeAsPlayer(ow, cellSA, Blocks.POWERED_RAIL.defaultBlockState());
            });
            context.waitTicks(10);

            // Baseline: everything present and DARK, including the mirrored half.
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ServerLevel nether = server.getLevel(binding.destDim());
                for (BlockPos p : List.of(a0, a1, cellSA)) {
                    var st = ow.getBlockState(p);
                    if (!st.is(Blocks.POWERED_RAIL) || st.getValue(POWERED)) {
                        failure.set("baseline: source rail at " + p + " is " + st);
                        return;
                    }
                }
                var mirrored = nether.getBlockState(destPos);
                if (!mirrored.is(Blocks.POWERED_RAIL)) {
                    failure.set("baseline: the mirrored half at " + destPos + " is "
                        + mirrored.getBlock() + " — (a)'s mirror did not run; this arm cannot"
                        + " test (c) on a fixture where (a) already failed");
                    return;
                }
                for (BlockPos p : List.of(contC, contC2)) {
                    var st = nether.getBlockState(p);
                    if (!st.is(Blocks.POWERED_RAIL) || st.getValue(POWERED)) {
                        failure.set("baseline: far rail at " + p + " is " + st);
                        return;
                    }
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-A FAILED: " + failure.get());
            }

            final long walkBefore =
                com.warwa.seamlessportals.passthrough.SeamSignalContinuity.walkCrossedCount();

            // ---- POWER ON ----
            runOnServer(context, server -> server.getLevel(Level.OVERWORLD)
                .setBlock(powerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3));

            if (railBridgeOn) {
                pollOrFail(context, 20, 5, "RS-SIGNAL-A far rails never powered", server -> {
                    ServerLevel nether = server.getLevel(binding.destDim());
                    return nether.getBlockState(contC).getValue(POWERED)
                        && nether.getBlockState(contC2).getValue(POWERED);
                });
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    ServerLevel nether = server.getLevel(binding.destDim());
                    long walkAfter = com.warwa.seamlessportals.passthrough
                        .SeamSignalContinuity.walkCrossedCount();
                    SeamlessPortalsConstants.LOGGER.info(LOG + "RS-SIGNAL-A ON: src {}/{}/{} S'={}"
                            + " B1={} B2={} walkCrossed {} -> {}",
                        ow.getBlockState(a0).getValue(POWERED),
                        ow.getBlockState(a1).getValue(POWERED),
                        ow.getBlockState(cellSA).getValue(POWERED),
                        nether.getBlockState(destPos).getValue(POWERED),
                        nether.getBlockState(contC).getValue(POWERED),
                        nether.getBlockState(contC2).getValue(POWERED),
                        walkBefore, walkAfter);
                    if (walkAfter <= walkBefore) {
                        failure.set("COVERAGE FAILED: far rails powered but walkCrossed never moved"
                            + " — the power did not come through the bridge and this arm proves"
                            + " nothing. counters: " + com.warwa.seamlessportals.passthrough
                            .SeamSignalContinuity.counters());
                    }
                });
            }
            else {
                context.waitTicks(40);
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    ServerLevel nether = server.getLevel(binding.destDim());
                    boolean srcPowered = ow.getBlockState(a0).getValue(POWERED)
                        && ow.getBlockState(a1).getValue(POWERED)
                        && ow.getBlockState(cellSA).getValue(POWERED);
                    boolean mirroredPowered = nether.getBlockState(destPos).getValue(POWERED);
                    boolean farPowered = nether.getBlockState(contC).getValue(POWERED)
                        || nether.getBlockState(contC2).getValue(POWERED);
                    if (!srcPowered) {
                        failure.set("INVERSION FIXTURE BROKEN: the source chain itself failed to"
                            + " power — that is vanilla, not (c)");
                        return;
                    }
                    if (!mirroredPowered) {
                        failure.set("INVERSION FIXTURE BROKEN: the mirrored half is dark — shape"
                            + " sync should carry POWERED regardless of (c) (the user's baseline)");
                        return;
                    }
                    if (farPowered) {
                        failure.set("INVERSION FAILED (the defect did not reproduce): with "
                            + (signalOn ? "-PdisableSeamShadow" : "-PdisableSeamSignal")
                            + " the far continuation powered anyway — either the lever does not"
                            + " disable the bridge or the fixture leaks power");
                    }
                });
            }
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-A FAILED: " + failure.get());
            }

            // ---- POWER OFF: the far side must go dark again (the direction OFF-state bugs hide in). ----
            runOnServer(context, server -> server.getLevel(Level.OVERWORLD)
                .setBlock(powerPos, Blocks.AIR.defaultBlockState(), 3));
            pollOrFail(context, 20, 5, "RS-SIGNAL-A far rails never unpowered", server -> {
                ServerLevel nether = server.getLevel(binding.destDim());
                return !nether.getBlockState(contC).getValue(POWERED)
                    && !nether.getBlockState(contC2).getValue(POWERED);
            });

            // ---- ARM R: REVERSE ENTRY — power arrives from the MIRROR half's side (the user's
            // live defect, found 2026-07-28: mirror authority ate the power update at the mirror
            // half, so signal entering the pair from that side died at the seam). Same rails,
            // power source at the FAR end of the far line. ----
            final boolean powerWakeOn = railBridgeOn && dispatchOn
                && !AperturePassthroughLever.DISABLE_SEAM_POWER_WAKE;
            final BlockPos farPowerPos = contC2.relative(farStep);
            final long walkBeforeR =
                com.warwa.seamlessportals.passthrough.SeamSignalContinuity.walkCrossedCount();
            runOnServer(context, server -> server.getLevel(binding.destDim())
                .setBlock(farPowerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3));
            if (powerWakeOn) {
                pollOrFail(context, 20, 5,
                    "RS-SIGNAL-R the NEAR line never powered from the far-side source"
                        + " (reverse entry through the mirror half)", server -> {
                        ServerLevel ow = server.getLevel(Level.OVERWORLD);
                        return ow.getBlockState(a1).getValue(POWERED)
                            && ow.getBlockState(a0).getValue(POWERED);
                    });
                runOnServer(context, server -> server.getLevel(binding.destDim())
                    .setBlock(farPowerPos, Blocks.AIR.defaultBlockState(), 3));
                pollOrFail(context, 20, 5,
                    "RS-SIGNAL-R the near line never unpowered after the far source was removed",
                    server -> {
                        ServerLevel ow = server.getLevel(Level.OVERWORLD);
                        return !ow.getBlockState(a1).getValue(POWERED)
                            && !ow.getBlockState(a0).getValue(POWERED);
                    });
            }
            else {
                context.waitTicks(40);
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    ServerLevel nether = server.getLevel(binding.destDim());
                    boolean farLocal = nether.getBlockState(contC2).getValue(POWERED);
                    boolean nearPowered = ow.getBlockState(a1).getValue(POWERED)
                        || ow.getBlockState(a0).getValue(POWERED);
                    if (railBridgeOn && dispatchOn && !farLocal) {
                        failure.set("REVERSE-ENTRY INVERSION FIXTURE BROKEN: the far line itself"
                            + " failed to power locally");
                        return;
                    }
                    if (nearPowered) {
                        failure.set("REVERSE-ENTRY INVERSION FAILED (the defect did not"
                            + " reproduce): the near line powered under the pulled lever ("
                            + (railBridgeOn && dispatchOn ? "-PdisableSeamPowerWake"
                                : "an upstream (c) lever") + ")");
                    }
                });
                runOnServer(context, server -> server.getLevel(binding.destDim())
                    .setBlock(farPowerPos, Blocks.AIR.defaultBlockState(), 3));
                context.waitTicks(10);
            }
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-R FAILED: " + failure.get());
            }
            // RUNAWAY CEILING: the first power-wake build converged and STILL spun ~500k
            // same-drain evaluations before vanilla's chain cap broke the loop — the gates
            // passed because nothing watched the volume. A reverse-entry round is a handful of
            // evaluations; four orders of magnitude of headroom, not six.
            runOnServer(context, server -> {
                long walkDelta = com.warwa.seamlessportals.passthrough
                    .SeamSignalContinuity.walkCrossedCount() - walkBeforeR;
                if (walkDelta > 10_000) {
                    failure.set("RUNAWAY: the reverse-entry arm burned " + walkDelta
                        + " walk crossings — an evaluation loop is spinning even though the"
                        + " states converged. counters: " + com.warwa.seamlessportals.passthrough
                        .SeamSignalContinuity.counters());
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-R FAILED: " + failure.get());
            }

            // ---- ARM S: THE USER'S RITUAL — place the far half as the player, break the pair,
            // re-place from the NEAR side, then power. (Stale-provenance defect, 2026-07-28: the
            // break cleared only the counterpart's mirror-created mark, so the near cell stayed
            // marked from its mirror-created past and the authority rule suppressed the player's
            // own re-placed rail — dark at placement, deaf to its neighbors. The invariant the
            // fix restores: a pair carries AT MOST ONE marked half; a doubly-marked pair is
            // totally deaf — even the power-wake only ping-pongs pokes between two suppressed
            // halves.) ----
            final boolean breakUnmarkOn = !AperturePassthroughLever.DISABLE_SEAM_BREAK_UNMARK;
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                writeAsPlayer(ow, cellSA, Blocks.AIR.defaultBlockState());
            });
            context.waitTicks(5);
            runOnServer(context, server -> {
                ServerLevel nether = server.getLevel(binding.destDim());
                writeAsPlayer(nether, destPos, Blocks.POWERED_RAIL.defaultBlockState());
            });
            context.waitTicks(5);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                if (!ow.getBlockState(cellSA).is(Blocks.POWERED_RAIL)) {
                    failure.set("ARM S fixture: the far-side placement did not mirror onto the"
                        + " near cell (" + ow.getBlockState(cellSA).getBlock() + ")");
                    return;
                }
                writeAsPlayer(ow, cellSA, Blocks.AIR.defaultBlockState());   // break the pair
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-S FAILED: " + failure.get());
            }
            context.waitTicks(5);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                writeAsPlayer(ow, cellSA, Blocks.POWERED_RAIL.defaultBlockState());   // the ritual
            });
            context.waitTicks(5);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                boolean marked = ((com.warwa.seamlessportals.passthrough.SeamIndexHolder) ow)
                    .seamlessportals$mirrorCreatedCells().contains(cellSA.asLong());
                if (breakUnmarkOn && marked) {
                    failure.set("STALE PROVENANCE SURVIVED: the re-placed player cell " + cellSA
                        + " is still marked mirror-created after its break — the authority rule"
                        + " will suppress the player's own rail");
                }
                if (!breakUnmarkOn && !marked) {
                    failure.set("BREAK-UNMARK INVERSION FAILED (the defect did not reproduce):"
                        + " with -PdisableSeamBreakUnmark the stale mark should survive the"
                        + " break, but the cell is clean");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-S FAILED: " + failure.get());
            }
            runOnServer(context, server -> server.getLevel(Level.OVERWORLD)
                .setBlock(powerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3));
            final boolean armSPowered = railBridgeOn && dispatchOn && breakUnmarkOn;
            if (armSPowered) {
                pollOrFail(context, 20, 5,
                    "RS-SIGNAL-S the ritual-re-placed pair never carried power (near half or far"
                        + " line dark)", server -> {
                        ServerLevel ow = server.getLevel(Level.OVERWORLD);
                        ServerLevel nether = server.getLevel(binding.destDim());
                        return ow.getBlockState(cellSA).getValue(POWERED)
                            && nether.getBlockState(destPos).getValue(POWERED)
                            && nether.getBlockState(contC).getValue(POWERED);
                    });
            }
            else {
                context.waitTicks(40);
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    if (!breakUnmarkOn && railBridgeOn && dispatchOn
                        && ow.getBlockState(cellSA).getValue(POWERED)) {
                        failure.set("BREAK-UNMARK BEHAVIORAL INVERSION FAILED: the stale-marked"
                            + " player cell powered anyway — the suppression the lever restores"
                            + " did not bite (note: the power-wake alone cannot heal a"
                            + " doubly-marked pair, so this should stay dark)");
                    }
                });
            }
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-S FAILED: " + failure.get());
            }
            runOnServer(context, server -> server.getLevel(Level.OVERWORLD)
                .setBlock(powerPos, Blocks.AIR.defaultBlockState(), 3));
            context.waitTicks(15);
            // ARM S deliberately manufactures a stale mark under -PdisableSeamBreakUnmark; scrub
            // BOTH cells' marks so the downstream lamp arm tests ITS subject on a hygienic pair
            // (first matrix run: the leaked doubly-marked state made the lamp pair revert-deaf and
            // ARM L failed for ARM S's reason — an evidence state must never perturb a functional
            // leg, the standing harness rule).
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ServerLevel nether = server.getLevel(binding.destDim());
                ((com.warwa.seamlessportals.passthrough.SeamIndexHolder) ow)
                    .seamlessportals$mirrorCreatedCells().remove(cellSA.asLong());
                ((com.warwa.seamlessportals.passthrough.SeamIndexHolder) nether)
                    .seamlessportals$mirrorCreatedCells().remove(destPos.asLong());
            });

            // ---- ARM L: THE SEAM LAMP, same fixture. Rails out (a player break clears both
            // halves), lamp pair in, far-side source beside the FAR half. ----
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                writeAsPlayer(ow, cellSA, Blocks.AIR.defaultBlockState());
                for (BlockPos p : List.of(a0, a1)) {
                    ow.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                }
                ServerLevel nether = server.getLevel(binding.destDim());
                for (BlockPos p : List.of(contC, contC2)) {
                    nether.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                }
            });
            context.waitTicks(5);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                writeAsPlayer(ow, cellSA, Blocks.REDSTONE_LAMP.defaultBlockState());
            });
            context.waitTicks(5);
            runOnServer(context, server -> {
                ServerLevel nether = server.getLevel(binding.destDim());
                if (!nether.getBlockState(destPos).is(Blocks.REDSTONE_LAMP)) {
                    failure.set("ARM L baseline: the far lamp half at " + destPos + " is "
                        + nether.getBlockState(destPos).getBlock() + " — the mirror did not run");
                    return;
                }
                // The far-side source, beside the FAR half only — this is the arm where power
                // ORIGINATES beyond the seam.
                nether.setBlock(contC, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-A FAILED: " + failure.get());
            }

            if (signalOn && dispatchOn) {
                pollOrFail(context, 20, 5,
                    "RS-SIGNAL-L the seam lamp never lit from the far-side source", server -> {
                        ServerLevel ow = server.getLevel(Level.OVERWORLD);
                        ServerLevel nether = server.getLevel(binding.destDim());
                        return ow.getBlockState(cellSA).getValue(LIT)
                            && nether.getBlockState(destPos).getValue(LIT);
                    });
                // OFF: source removed -> both halves dark (two chained 4-tick lamp delays bridged
                // by a tick-end dispatch — ~8-10 ticks, the panel-corrected latency).
                runOnServer(context, server -> server.getLevel(binding.destDim())
                    .setBlock(contC, Blocks.AIR.defaultBlockState(), 3));
                pollOrFail(context, 20, 5,
                    "RS-SIGNAL-L the seam lamp never went dark after the source was removed",
                    server -> {
                        ServerLevel ow = server.getLevel(Level.OVERWORLD);
                        ServerLevel nether = server.getLevel(binding.destDim());
                        return !ow.getBlockState(cellSA).getValue(LIT)
                            && !nether.getBlockState(destPos).getValue(LIT);
                    });
            }
            else {
                // INVERSION (either lever): the far half's own attempt to light is REVERTED by the
                // authority rule, and nothing wakes the player half — both halves provably dark.
                context.waitTicks(40);
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    ServerLevel nether = server.getLevel(binding.destDim());
                    boolean srcLit = ow.getBlockState(cellSA).getValue(LIT);
                    boolean farLit = nether.getBlockState(destPos).getValue(LIT);
                    if (srcLit || farLit) {
                        failure.set("LAMP INVERSION FAILED (the defect did not reproduce): srcLit="
                            + srcLit + " farLit=" + farLit + " under "
                            + (signalOn ? "-PdisableSeamSignalDispatch" : "-PdisableSeamSignal")
                            + " — either the lever leaks or the authority revert did not hold");
                    }
                });
                runOnServer(context, server -> server.getLevel(binding.destDim())
                    .setBlock(contC, Blocks.AIR.defaultBlockState(), 3));
            }
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-A FAILED: " + failure.get());
            }

            // Whole-run queue-loss invariant.
            runOnServer(context, server -> {
                if (com.warwa.seamlessportals.passthrough.SeamSignalContinuity.dispatchDroppedCount() != 0) {
                    failure.set("DISPATCH QUEUE OVERFLOWED (whole-run invariant): counters: "
                        + com.warwa.seamlessportals.passthrough.SeamSignalContinuity.counters());
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-A FAILED: " + failure.get());
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-SIGNAL-A PASS — "
                + (railBridgeOn
                    ? "powered-rail chain crossed the obsidian seam both directions"
                    : "INVERSION: propagation stopped at the seam under "
                        + (signalOn ? "-PdisableSeamShadow" : "-PdisableSeamSignal"))
                + (signalOn && dispatchOn
                    ? "; seam lamp lit from the far side and went dark again"
                    : "; lamp inversion held (both halves dark)")
                + ". counters: "
                + com.warwa.seamlessportals.passthrough.SeamSignalContinuity.counters());
        }
        finally {
            try {
                runCommands(context, List.of(
                    "fill " + (fx - 2) + " " + (py - 1) + " " + (fz - 4) + " "
                        + (fx + 3) + " " + (py + 5) + " " + (fz + 4) + " minecraft:air",
                    "forceload remove " + (fx - 16) + " " + (fz - 16) + " "
                        + (fx + 16) + " " + (fz + 16)
                ));
                Vec3 d = destSeen.get();
                if (d != null) {
                    int dx = (int) Math.floor(d.x), dy = (int) Math.floor(d.y), dz = (int) Math.floor(d.z);
                    runCommands(context, List.of(
                        "execute in minecraft:the_nether run forceload add " + (dx - 16) + " "
                            + (dz - 16) + " " + (dx + 16) + " " + (dz + 16),
                        inDim("minecraft:the_nether", "fill " + (dx - 5) + " " + (dy - 2) + " "
                            + (dz - 5) + " " + (dx + 5) + " " + (dy + 5) + " " + (dz + 5)
                            + " minecraft:air"),
                        "execute in minecraft:the_nether run forceload remove " + (dx - 16) + " "
                            + (dz - 16) + " " + (dx + 16) + " " + (dz + 16)
                    ));
                }
            }
            catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "RS-SIGNAL-A cleanup failed", t);
            }
        }
    }

    /**
     * RS (c) SIGNAL LEG, TOPOLOGY B — the DISJOINT (boundary-phase) seam carries signal with no
     * mirror to lean on ({@code REDSTONE_C_SPEC.md} §5 arm B). This is the arm where the DISPATCH
     * lever inverts for RAILS: in topology A the mirror's flags-515 far write already notifies the
     * far side, so a dispatch-off run still passes there — asserting the rail dispatch inversion in
     * A would be a gate that cannot fail. Here nothing mirrors (phase gate, user-confirmed), so
     * with reads ON and dispatch OFF the far rail can SEE power but is never TOLD to look:
     * provably stale.
     *
     * <p>Fixture order is load-bearing: the far rails are placed BEFORE any power source exists
     * and baseline-asserted dark — placed after the redstone block, their own placement-time
     * evaluation would power them through READS alone (the walk crosses regardless of the dispatch
     * lever) and the dispatch inversion would false-fail.
     */
    private static void rsSignalLegDisjoint(ClientGameTestContext context) {
        if (AperturePassthroughLever.DISABLED) {
            return;
        }
        if (AperturePassthroughLever.DISABLE_SEAM_PHASE_GATE) {
            // Unconditional mirroring fights this fixture by design (the same reason RS-RAIL-B
            // skips its connection legs there): with the gate off, the near placement mirrors
            // ONTO the far side's own track and the 'no mirror involved' premise is gone.
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-SIGNAL-B SKIPPED under"
                + " -PdisableSeamPhaseGate — the boundary-phase fixture's premise (nothing"
                + " mirrors) does not hold there");
            return;
        }
        final boolean signalOn = !AperturePassthroughLever.DISABLE_SEAM_SIGNAL;
        final boolean railBridgeOn = signalOn && !AperturePassthroughLever.DISABLE_SEAM_SHADOW;
        final boolean dispatchOn = signalOn && !AperturePassthroughLever.DISABLE_SEAM_SIGNAL_DISPATCH;
        final int bx = 4200, by = 100, bz = 4200;   // 1000+ from RS-RAIL-B (3200,3200); clear of all
        final BlockPos cellN = new BlockPos(bx - 1, by, bz);        // near, flush west of x=bx
        final BlockPos cellF = new BlockPos(bx + 60, by, bz);       // far, flush east of dest plane
        final BlockPos cellFE = new BlockPos(bx + 61, by, bz);      // far track continuing east
        final BlockPos a1 = new BlockPos(bx - 2, by, bz);
        final BlockPos powerPos = new BlockPos(bx - 3, by, bz);
        AtomicReference<String> failure = new AtomicReference<>(null);
        final var POWERED = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;

        try {
            runCommands(context, List.of(
                "forceload add " + (bx - 16) + " " + (bz - 16) + " " + (bx + 76) + " " + (bz + 16),
                "fill " + (bx - 6) + " " + (by - 1) + " " + (bz - 3) + " "
                    + (bx + 66) + " " + (by - 1) + " " + (bz + 3) + " minecraft:stone",
                "fill " + (bx - 6) + " " + by + " " + (bz - 3) + " "
                    + (bx + 66) + " " + (by + 3) + " " + (bz + 3) + " minecraft:air"
            ));
            context.waitTicks(20);

            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ow.getChunk(bx >> 4, bz >> 4);
                ow.getChunk((bx + 60) >> 4, bz >> 4);
                qouteall.imm_ptl.core.portal.Portal p =
                    qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE.create(
                        ow, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                if (p == null) {
                    failure.set("portal create returned null");
                    return;
                }
                p.setOriginPos(new Vec3(bx, by + 0.5, bz + 0.5));
                p.setDestinationDimension(Level.OVERWORLD);
                p.setDestination(new Vec3(bx + 60, by + 0.5, bz + 0.5));
                p.setOrientationAndSize(new Vec3(0, 0, 1), new Vec3(0, 1, 0), 1, 1);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(p);
                qouteall.imm_ptl.core.portal.Portal q =
                    qouteall.imm_ptl.core.portal.PortalManipulation.createReversePortal(
                        p, qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(q);
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-B SETUP FAILED: " + failure.get());
            }

            AtomicReference<Boolean> bound = new AtomicReference<>(false);
            for (int attempt = 0; attempt < 20 && !bound.get(); attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    bound.set(com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellN) != null
                        && com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellF) != null);
                });
                if (!bound.get()) {
                    context.waitTicks(10);
                }
            }
            if (!bound.get()) {
                throw new AssertionError(LOG + "RS-SIGNAL-B FAILED: seam cells never bound");
            }

            // Coverage preamble: the fixture must actually be DISJOINT and close topologically.
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellN);
                var b = cell.bindings().stream()
                    .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                    .findFirst().orElse(null);
                if (b == null) {
                    failure.set("no mirrorable binding at N=" + cellN);
                    return;
                }
                if (b.phase() != com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.DISJOINT
                    || !b.seamContinuous()) {
                    failure.set("TOPOLOGY B NOT CONSTRUCTED: phase=" + b.phase() + " continuous="
                        + b.seamContinuous());
                    return;
                }
                if (!cellF.equals(b.continuationToward(net.minecraft.core.Direction.EAST))) {
                    failure.set("continuationToward(EAST)=" + b.continuationToward(
                        net.minecraft.core.Direction.EAST) + " != far cell " + cellF);
                    return;
                }
                // Far track and near approach — ALL rails before any power source exists.
                ow.setBlock(cellF, Blocks.POWERED_RAIL.defaultBlockState(), 3);
                ow.setBlock(cellFE, Blocks.POWERED_RAIL.defaultBlockState(), 3);
                ow.setBlock(a1, Blocks.POWERED_RAIL.defaultBlockState(), 3);
                writeAsPlayer(ow, cellN, Blocks.POWERED_RAIL.defaultBlockState());
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-B SETUP FAILED: " + failure.get());
            }
            context.waitTicks(10);

            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                for (BlockPos p : List.of(a1, cellN, cellF, cellFE)) {
                    var st = ow.getBlockState(p);
                    if (!st.is(Blocks.POWERED_RAIL) || st.getValue(POWERED)) {
                        failure.set("baseline: rail at " + p + " is " + st);
                        return;
                    }
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-B FAILED: " + failure.get());
            }

            final long deliveredBefore =
                com.warwa.seamlessportals.passthrough.SeamSignalContinuity.dispatchDeliveredCount();

            // ---- POWER ON ----
            runOnServer(context, server -> server.getLevel(Level.OVERWORLD)
                .setBlock(powerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3));

            if (railBridgeOn && dispatchOn) {
                pollOrFail(context, 20, 5, "RS-SIGNAL-B far rails never powered", server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    return ow.getBlockState(cellF).getValue(POWERED)
                        && ow.getBlockState(cellFE).getValue(POWERED);
                });
                runOnServer(context, server -> {
                    long deliveredAfter = com.warwa.seamlessportals.passthrough
                        .SeamSignalContinuity.dispatchDeliveredCount();
                    if (deliveredAfter <= deliveredBefore) {
                        failure.set("COVERAGE FAILED: far rails powered but no cross-seam dispatch"
                            + " was delivered — the wake-up came from somewhere else and this arm"
                            + " proves nothing. counters: " + com.warwa.seamlessportals.passthrough
                            .SeamSignalContinuity.counters());
                    }
                });
            }
            else {
                context.waitTicks(40);
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    boolean nearPowered = ow.getBlockState(cellN).getValue(POWERED)
                        && ow.getBlockState(a1).getValue(POWERED);
                    boolean farPowered = ow.getBlockState(cellF).getValue(POWERED)
                        || ow.getBlockState(cellFE).getValue(POWERED);
                    if (!nearPowered) {
                        failure.set("INVERSION FIXTURE BROKEN: the near side itself failed to power");
                        return;
                    }
                    if (farPowered) {
                        failure.set("INVERSION FAILED (the defect did not reproduce): far rails"
                            + " powered under "
                            + (!signalOn ? "-PdisableSeamSignal"
                                : !railBridgeOn ? "-PdisableSeamShadow"
                                    : "-PdisableSeamSignalDispatch")
                            + " — the far side was woken by something the lever should have"
                            + " stopped. counters: " + com.warwa.seamlessportals.passthrough
                            .SeamSignalContinuity.counters());
                    }
                });
            }
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-SIGNAL-B FAILED: " + failure.get());
            }

            // ---- POWER OFF (full-levers runs only: with any lever pulled the far side never
            // powered, so there is nothing to un-power). ----
            if (railBridgeOn && dispatchOn) {
                runOnServer(context, server -> server.getLevel(Level.OVERWORLD)
                    .setBlock(powerPos, Blocks.AIR.defaultBlockState(), 3));
                pollOrFail(context, 20, 5, "RS-SIGNAL-B far rails never unpowered",
                    server -> {
                        ServerLevel ow = server.getLevel(Level.OVERWORLD);
                        return !ow.getBlockState(cellF).getValue(POWERED)
                            && !ow.getBlockState(cellFE).getValue(POWERED);
                    });
            }

            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-SIGNAL-B PASS — "
                + (railBridgeOn && dispatchOn
                    ? "signal crossed the boundary-phase seam both directions (no mirror involved)"
                    : "INVERSION: far side provably stale under the pulled lever")
                + ". counters: "
                + com.warwa.seamlessportals.passthrough.SeamSignalContinuity.counters());
        }
        finally {
            try {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    for (var portal : ow.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                        new net.minecraft.world.phys.AABB(bx - 4, by - 4, bz - 4,
                            bx + 66, by + 6, bz + 4), x -> true)) {
                        portal.discard();
                    }
                });
                runCommands(context, List.of(
                    "fill " + (bx - 6) + " " + (by - 1) + " " + (bz - 3) + " "
                        + (bx + 66) + " " + (by + 3) + " " + (bz + 3) + " minecraft:air",
                    "forceload remove " + (bx - 16) + " " + (bz - 16) + " "
                        + (bx + 76) + " " + (bz + 16)
                ));
            }
            catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "RS-SIGNAL-B cleanup failed", t);
            }
        }
    }

    /**
     * DIAGNOSTIC REPRO of the user's 2026-07-27 live report: a SAME-DIMENSION pair whose planes
     * sit MID-BLOCK (make_portal geometry → COINCIDENT phase) with a Y-offset between the ends.
     * Seam rail placed as the player from the SOURCE side (player half = source, mirror half =
     * far), approach + far continuation as scenery, then power ON from the source.
     *
     * <p>Asserts the DESIGN expectation (far rails power, then unpower) and LOGS ITS WORKING —
     * every poll step prints both halves' and both chains' states, so a red run is a state
     * timeline, not a verdict. The user's reported symptoms this must reproduce: propagation
     * stops at the seam (ON never crosses), and once crossed by other means the seam sticks
     * powered after the source is cut.
     */
    private static void rsSignalSameDimCoincidentRepro(ClientGameTestContext context) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
            || AperturePassthroughLever.DISABLE_SEAM_SIGNAL_DISPATCH
            || AperturePassthroughLever.DISABLE_SEAM_SHADOW
            || AperturePassthroughLever.DISABLE_SEAM_SHAPE_SYNC) {
            return;   // diagnostic: canonical-config only
        }
        final int sx = 5200, sy = 100, sz = 5200;      // clear of every existing fixture
        final int dxo = 60, dyo = -50;                 // the user's shape: far end lower + away
        final BlockPos cellS = new BlockPos(sx, sy, sz);            // plane bisects this cell
        final BlockPos cellD = new BlockPos(sx + dxo, sy + dyo, sz);
        final BlockPos a1 = new BlockPos(sx - 1, sy, sz);
        final BlockPos a0 = new BlockPos(sx - 2, sy, sz);
        final BlockPos powerPos = new BlockPos(sx - 3, sy, sz);
        final BlockPos b1 = new BlockPos(sx + dxo + 1, sy + dyo, sz);
        final BlockPos b2 = new BlockPos(sx + dxo + 2, sy + dyo, sz);
        AtomicReference<String> failure = new AtomicReference<>(null);
        final var POWERED = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;

        java.util.function.BiConsumer<MinecraftServer, String> dump = (server, tag) -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "REPRO[{}] a0={} a1={} S={} D={} b1={} b2={} | {}",
                tag,
                ow.getBlockState(a0).is(Blocks.POWERED_RAIL) ? ow.getBlockState(a0).getValue(POWERED) : "-",
                ow.getBlockState(a1).is(Blocks.POWERED_RAIL) ? ow.getBlockState(a1).getValue(POWERED) : "-",
                ow.getBlockState(cellS).is(Blocks.POWERED_RAIL) ? ow.getBlockState(cellS).getValue(POWERED) : "-",
                ow.getBlockState(cellD).is(Blocks.POWERED_RAIL) ? ow.getBlockState(cellD).getValue(POWERED) : "-",
                ow.getBlockState(b1).is(Blocks.POWERED_RAIL) ? ow.getBlockState(b1).getValue(POWERED) : "-",
                ow.getBlockState(b2).is(Blocks.POWERED_RAIL) ? ow.getBlockState(b2).getValue(POWERED) : "-",
                com.warwa.seamlessportals.passthrough.SeamSignalContinuity.counters());
        };

        try {
            runCommands(context, List.of(
                "forceload add " + (sx - 16) + " " + (sz - 16) + " " + (sx + 16) + " " + (sz + 16),
                "forceload add " + (sx + dxo - 16) + " " + (sz - 16) + " " + (sx + dxo + 16) + " " + (sz + 16),
                "fill " + (sx - 6) + " " + (sy - 1) + " " + (sz - 2) + " "
                    + (sx + 6) + " " + (sy - 1) + " " + (sz + 2) + " minecraft:stone",
                "fill " + (sx - 6) + " " + sy + " " + (sz - 2) + " "
                    + (sx + 6) + " " + (sy + 3) + " " + (sz + 2) + " minecraft:air",
                "fill " + (sx + dxo - 6) + " " + (sy + dyo - 1) + " " + (sz - 2) + " "
                    + (sx + dxo + 6) + " " + (sy + dyo - 1) + " " + (sz + 2) + " minecraft:stone",
                "fill " + (sx + dxo - 6) + " " + (sy + dyo) + " " + (sz - 2) + " "
                    + (sx + dxo + 6) + " " + (sy + dyo + 3) + " " + (sz + 2) + " minecraft:air"
            ));
            context.waitTicks(20);

            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ow.getChunk(sx >> 4, sz >> 4);
                ow.getChunk((sx + dxo) >> 4, sz >> 4);
                // MID-BLOCK planes on BOTH ends (make_portal geometry): plane x = cell + 0.5.
                qouteall.imm_ptl.core.portal.Portal p =
                    qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE.create(
                        ow, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                if (p == null) {
                    failure.set("portal create returned null");
                    return;
                }
                p.setOriginPos(new Vec3(sx + 0.5, sy + 0.5, sz + 0.5));
                p.setDestinationDimension(Level.OVERWORLD);
                p.setDestination(new Vec3(sx + dxo + 0.5, sy + dyo + 0.5, sz + 0.5));
                p.setOrientationAndSize(new Vec3(0, 0, 1), new Vec3(0, 1, 0), 1, 1);
                // THE USER'S PORTAL SHAPE: the wand's BI-FACED BI-WAY four-entity cluster — two
                // coincident opposite-normal faces per end, so each seam cell carries TWO bindings
                // with 180°-apart rotations. This is the shape whose first-match binding selection
                // was order-dependent (the crossing-preference fix's subject). The FLIPPED twins
                // are spawned FIRST on purpose: they tick and bind first, so the UNLUCKY binding
                // sits in the cluster's first slot at both ends — the deterministic adversarial
                // order that first-match resolves wrong and the preference fix must survive.
                qouteall.imm_ptl.core.portal.Portal flipped =
                    qouteall.imm_ptl.core.portal.PortalManipulation.createFlippedPortal(
                        p, qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE);
                qouteall.imm_ptl.core.portal.Portal reverse =
                    qouteall.imm_ptl.core.portal.PortalManipulation.createReversePortal(
                        p, qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE);
                qouteall.imm_ptl.core.portal.Portal parallel =
                    qouteall.imm_ptl.core.portal.PortalManipulation.createFlippedPortal(
                        reverse, qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(flipped);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(p);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(parallel);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(reverse);
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "REPRO SETUP FAILED: " + failure.get());
            }

            AtomicReference<com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding> bref =
                new AtomicReference<>(null);
            for (int attempt = 0; attempt < 20 && bref.get() == null; attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                    if (cell != null) {
                        cell.bindings().stream()
                            .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                            .findFirst().ifPresent(bref::set);
                    }
                });
                if (bref.get() == null) {
                    context.waitTicks(10);
                }
            }
            if (bref.get() == null) {
                throw new AssertionError(LOG + "REPRO FAILED: source cell never bound");
            }
            var b = bref.get();
            if (b.phase() != com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT) {
                throw new AssertionError(LOG + "REPRO FIXTURE WRONG: phase=" + b.phase()
                    + " — mid-block planes must classify COINCIDENT (the user's make_portal shape)");
            }
            if (!cellD.equals(b.destPos())) {
                throw new AssertionError(LOG + "REPRO FIXTURE WRONG: destPos=" + b.destPos()
                    + " expected " + cellD);
            }
            // FIXTURE-ADVERSITY COVERAGE: the cluster must hold TWO bindings and the FIRST slot
            // must be the UNLUCKY one (its crossing points back west) — otherwise first-match would
            // resolve correctly by luck and the crossing-preference inversion proves nothing.
            AtomicReference<String> orderNote = new AtomicReference<>("");
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                var bs = cell.bindings();
                if (bs.size() < 2) {
                    failure.set("bi-faced cluster expected TWO bindings at S, got " + bs.size());
                    return;
                }
                orderNote.set("binding[0].crossDir=" + bs.get(0).crossDir()
                    + " binding[1].crossDir=" + bs.get(1).crossDir());
                if (bs.get(0).crossDir() == net.minecraft.core.Direction.EAST) {
                    SeamlessPortalsConstants.LOGGER.warn(LOG + "REPRO NOTE: first binding is the"
                        + " LUCKY one despite adversarial spawn order ({}) — the first-match"
                        + " inversion cannot reproduce on this run", orderNote.get());
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "REPRO FIXTURE WRONG: " + failure.get());
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "REPRO geometry: S={} D={} crossDir={}"
                + " contToward(cross)={} R={} cluster[{}]", cellS, b.destPos(), b.crossDir(),
                b.continuationToward(b.crossDir()), b.stateRotation(), orderNote.get());

            // Scenery first (no power source exists yet), then the seam rail AS THE PLAYER from
            // the SOURCE side — the user's exact provenance layout.
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                for (BlockPos p : List.of(a1, a0)) {
                    ow.setBlock(p, Blocks.POWERED_RAIL.defaultBlockState(), 3);
                }
                for (BlockPos p : List.of(b1, b2)) {
                    ow.setBlock(p, Blocks.POWERED_RAIL.defaultBlockState(), 3);
                }
                writeAsPlayer(ow, cellS, Blocks.POWERED_RAIL.defaultBlockState());
            });
            context.waitTicks(10);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                if (!ow.getBlockState(cellD).is(Blocks.POWERED_RAIL)) {
                    failure.set("mirror half at D=" + cellD + " is "
                        + ow.getBlockState(cellD).getBlock() + " — (a) mirror did not run");
                }
                dump.accept(server, "baseline");
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "REPRO FAILED: " + failure.get());
            }

            // ---- POWER ON from the source ----
            // (History: a "crossing-preference" fix was tried against this adversarial cluster and
            // REFUTED here — under restored first-match the far rails still powered, because the
            // flipped twins SHARE the portal transform and their continuations agree. The fixture
            // stays adversarial as the record and as regression evidence for cluster selection.)
            runOnServer(context, server -> server.getLevel(Level.OVERWORLD)
                .setBlock(powerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3));
            for (int i = 0; i < 12; i++) {
                context.waitTicks(5);
                final int step = i;
                runOnServer(context, server -> dump.accept(server, "on+" + (step * 5 + 5) + "t"));
            }
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                boolean farPowered = ow.getBlockState(b1).getValue(POWERED)
                    && ow.getBlockState(b2).getValue(POWERED);
                if (!farPowered) {
                    failure.set("USER BUG REPRODUCED (ON): far rails dark after 60 ticks — b1="
                        + ow.getBlockState(b1).getValue(POWERED) + " b2="
                        + ow.getBlockState(b2).getValue(POWERED) + " S="
                        + ow.getBlockState(cellS).getValue(POWERED) + " D="
                        + ow.getBlockState(cellD).getValue(POWERED));
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "REPRO: " + failure.get());
            }

            // ---- POWER OFF ----
            runOnServer(context, server -> server.getLevel(Level.OVERWORLD)
                .setBlock(powerPos, Blocks.AIR.defaultBlockState(), 3));
            for (int i = 0; i < 12; i++) {
                context.waitTicks(5);
                final int step = i;
                runOnServer(context, server -> dump.accept(server, "off+" + (step * 5 + 5) + "t"));
            }
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                boolean any = ow.getBlockState(cellS).getValue(POWERED)
                    || ow.getBlockState(cellD).getValue(POWERED)
                    || ow.getBlockState(b1).getValue(POWERED)
                    || ow.getBlockState(b2).getValue(POWERED);
                if (any) {
                    failure.set("USER BUG REPRODUCED (STUCK ON): after the source was cut, S="
                        + ow.getBlockState(cellS).getValue(POWERED) + " D="
                        + ow.getBlockState(cellD).getValue(POWERED) + " b1="
                        + ow.getBlockState(b1).getValue(POWERED) + " b2="
                        + ow.getBlockState(b2).getValue(POWERED));
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "REPRO: " + failure.get());
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "REPRO PASS — same-dim COINCIDENT carried"
                + " and released the signal (design expectation held). counters: "
                + com.warwa.seamlessportals.passthrough.SeamSignalContinuity.counters());

            // ---- PHASE C (EVIDENCE, no hard assert): the FAR END WITHOUT ITS FORCELOAD — the
            // user's live condition (they stood at the source; the far end 40km away lived on
            // whatever IP maintains). Bindings derive per-tick from TICKING portals: if the far
            // end stops entity-ticking, D unbinds and the halves' views go asymmetric — the
            // suspected mechanism behind the live seam-stop/stuck-on/ping-pong. ----
            runCommands(context, List.of(
                "forceload remove " + (sx + dxo - 16) + " " + (sz - 16) + " "
                    + (sx + dxo + 16) + " " + (sz + 16)));
            context.waitTicks(100);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var farCell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellD);
                SeamlessPortalsConstants.LOGGER.info(LOG + "REPRO-C far state after forceload"
                        + " removal +100t: hasChunkAt(D)={} D bound={} bindings={}",
                    ow.hasChunkAt(cellD), farCell != null,
                    farCell == null ? 0 : farCell.bindings().size());
            });
            runOnServer(context, server -> server.getLevel(Level.OVERWORLD)
                .setBlock(powerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3));
            for (int i = 0; i < 12; i++) {
                context.waitTicks(5);
                final int step = i;
                runOnServer(context, server -> dump.accept(server, "farcold-on+" + (step * 5 + 5) + "t"));
            }
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                boolean farPowered = ow.hasChunkAt(b1) && ow.getBlockState(b1).is(Blocks.POWERED_RAIL)
                    && ow.getBlockState(b1).getValue(POWERED);
                var farCell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellD);
                SeamlessPortalsConstants.LOGGER.info(LOG + "REPRO-C VERDICT: farPowered={} S={}"
                        + " (D bound={}) — {}. counters: {}",
                    farPowered,
                    ow.getBlockState(cellS).getValue(POWERED),
                    farCell != null,
                    farPowered
                        ? "IP alone kept the far end ticking; position-dependence EXCLUDED here"
                        : "★ USER'S LIVE CONDITION REPRODUCED: the far end without a forceload"
                            + " cannot cross — this is the live-play mechanism",
                    com.warwa.seamlessportals.passthrough.SeamSignalContinuity.counters());
                ow.setBlock(powerPos, Blocks.AIR.defaultBlockState(), 3);
            });
        }
        finally {
            try {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    for (var portal : ow.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                        new net.minecraft.world.phys.AABB(sx - 4, sy + dyo - 4, sz - 4,
                            sx + dxo + 6, sy + 6, sz + 4), x -> true)) {
                        portal.discard();
                    }
                });
                runCommands(context, List.of(
                    "fill " + (sx - 6) + " " + (sy - 1) + " " + (sz - 2) + " "
                        + (sx + 6) + " " + (sy + 3) + " " + (sz + 2) + " minecraft:air",
                    "fill " + (sx + dxo - 6) + " " + (sy + dyo - 1) + " " + (sz - 2) + " "
                        + (sx + dxo + 6) + " " + (sy + dyo + 3) + " " + (sz + 2) + " minecraft:air",
                    "forceload remove " + (sx - 16) + " " + (sz - 16) + " "
                        + (sx + dxo + 16) + " " + (sz + 16)
                ));
            }
            catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "REPRO cleanup failed", t);
            }
        }
    }

    /**
     * DIAGNOSTIC REPRO 3 — THE REAL COMMAND PATH: the pair is built exactly the way the user
     * builds theirs, {@code /portal make_portal} (orientation derived from the PLAYER'S LOOK,
     * plane mid-block over the aimed floor block) followed by
     * {@code /portal complete_bi_way_bi_faced_portal} (the command's own four-entity completion,
     * including its {@code removeOverlappedPortals} churn) — executed AS the gametest player via
     * {@code /execute as @p}. Everything downstream (seam cells, approach, far continuation) is
     * derived from the LIVE binding, so whatever geometry the command actually produces is what
     * gets tested. Height-2 aperture and a north-south line, both untouched by the other repros.
     */
    private static void rsSignalCommandPairRepro(
        ClientGameTestContext context, int cx, int cy, int cz, int width, int height, String tag
    ) {
        rsSignalCommandPairRepro(context, cx, cy, cz, width, height, 0, tag);
    }

    private static void rsSignalCommandPairRepro(
        ClientGameTestContext context, int cx, int cy, int cz, int width, int height,
        int railXOff, String tag
    ) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
            || AperturePassthroughLever.DISABLE_SEAM_SIGNAL_DISPATCH
            || AperturePassthroughLever.DISABLE_SEAM_SHADOW
            || AperturePassthroughLever.DISABLE_SEAM_SHAPE_SYNC) {
            return;
        }
        // Portal center y = (cy-1) + 1 + h/2; the typed dest must carry the SAME sub-block phase
        // on every axis or the pair classifies OFFSET and (rightly) declines. Far bottom row is
        // cy-50 for every height.
        final Vec3 destCenter = new Vec3(cx + 0.5, cy + height / 2.0 - 50, cz + 60 + 0.5);
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<Vec3> playerBefore = new AtomicReference<>(null);
        final var POWERED = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;

        try {
            runOnServer(context, server -> {
                var players = server.getPlayerList().getPlayers();
                if (!players.isEmpty()) {
                    playerBefore.set(players.get(0).position());
                }
            });
            runCommands(context, List.of(
                "forceload add " + (cx - 16) + " " + (cz - 16) + " " + (cx + 16) + " " + (cz + 76),
                // Near site: a floor strip with a GAP so the player's aim ray hits exactly the
                // target block's top face (a continuous floor would be hit earlier along the ray).
                "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz - 4) + " "
                    + (cx + 6) + " " + (cy + 5) + " " + (cz + 6) + " minecraft:air",
                "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz + 2) + " "
                    + (cx + 6) + " " + (cy - 1) + " " + (cz + 6) + " minecraft:stone",
                "setblock " + cx + " " + (cy - 1) + " " + cz + " minecraft:stone",
                // Far platform under the future far aperture + continuation.
                "fill " + (cx - 6) + " " + (cy - 51) + " " + (cz + 54) + " "
                    + (cx + 6) + " " + (cy - 51) + " " + (cz + 64) + " minecraft:stone",
                "fill " + (cx - 6) + " " + (cy - 50) + " " + (cz + 54) + " "
                    + (cx + 6) + " " + (cy - 45) + " " + (cz + 64) + " minecraft:air",
                // Aim: stand south of the gap, look north and down at the target block's top.
                "tp @p " + (cx + 0.5) + " " + cy + " " + (cz + 3.5) + " 180 27"
            ));
            context.waitTicks(10);
            runCommands(context, List.of(
                "execute as @p at @p run portal make_portal " + width + " " + height
                    + " minecraft:overworld "
                    + destCenter.x + " " + destCenter.y + " " + destCenter.z));
            context.waitTicks(10);
            runCommands(context, List.of(
                // Look at the spawned portal (its center hovers over the target block) and
                // complete it the user's way.
                "tp @p " + (cx + 0.5) + " " + cy + " " + (cz + 3.5) + " 180 5",
                "execute as @p at @p run portal complete_bi_way_bi_faced_portal"));
            context.waitTicks(20);
            // Move the player off the approach lane before rails go down.
            runCommands(context, List.of(
                "tp @p " + (cx + 3.5) + " " + cy + " " + (cz + 4.5) + " 180 0"));

            // Find the seam binding at the chosen LOWER aperture column (railXOff=0 is the
            // aimed/center column; a nonzero offset threads an OFF-CENTER column of a wide
            // aperture — the lateral-mapping case the user's 5-wide portal exercises).
            final BlockPos cellS = new BlockPos(cx + railXOff, cy, cz);
            AtomicReference<com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding> bref =
                new AtomicReference<>(null);
            for (int attempt = 0; attempt < 30 && bref.get() == null; attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                    if (cell != null) {
                        cell.bindings().stream()
                            .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                            .findFirst().ifPresent(bref::set);
                    }
                });
                if (bref.get() == null) {
                    context.waitTicks(10);
                }
            }
            if (bref.get() == null) {
                throw new AssertionError(LOG + "REPRO3[" + tag + "] FAILED: the command-built pair never bound a"
                    + " mirrorable seam at " + cellS + " — either make_portal aimed wrong (fixture)"
                    + " or command-built pairs do not bind (defect)");
            }
            var b = bref.get();
            final net.minecraft.core.Direction crossDir = b.crossDir();
            final net.minecraft.core.Direction approachDir = crossDir.getOpposite();
            final BlockPos destPos = b.destPos();
            final BlockPos contC = b.continuationToward(crossDir);
            final net.minecraft.core.Direction farStep = b.stateRotation().rotate(crossDir);
            final BlockPos contC2 = contC.relative(farStep);
            final BlockPos a1 = cellS.relative(approachDir);
            final BlockPos a0 = a1.relative(approachDir);
            final BlockPos powerPos = a0.relative(approachDir);
            SeamlessPortalsConstants.LOGGER.info(LOG + "REPRO3[" + tag + "] geometry: S={} phase={} crossDir={}"
                    + " D={} B1={} B2={} A1={} A0={} power={} R={} cluster={}",
                cellS, b.phase(), crossDir, destPos, contC, contC2, a1, a0, powerPos,
                b.stateRotation(), com.warwa.seamlessportals.passthrough.SeamRegistry
                    .lookup(McHelper.getServerWorld(Level.OVERWORLD), cellS).bindings().size());
            if (b.phase() != com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT
                || !b.seamContinuous()) {
                throw new AssertionError(LOG + "REPRO3[" + tag + "] FIXTURE WRONG: phase=" + b.phase()
                    + " continuous=" + b.seamContinuous());
            }

            // Rails: far scenery first, then approach, then the seam rail as the player.
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                for (BlockPos p : List.of(contC, contC2)) {
                    ow.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);
                    ow.setBlock(p, Blocks.POWERED_RAIL.defaultBlockState(), 3);
                }
                for (BlockPos p : List.of(a1, a0)) {
                    ow.setBlock(p.below(), Blocks.STONE.defaultBlockState(), 3);
                    ow.setBlock(p, Blocks.POWERED_RAIL.defaultBlockState(), 3);
                }
                // Support under the SEAM cell too — the center column sits on the aimed target
                // block, but an off-center column's below-cell is the gap row (first edge run
                // failed HERE: the seam rail popped for lack of support and the leg misread the
                // missing mirror half as the user's bug — fixture-fails-for-the-wrong-reason).
                ow.setBlock(cellS.below(), Blocks.STONE.defaultBlockState(), 3);
                writeAsPlayer(ow, cellS, Blocks.POWERED_RAIL.defaultBlockState());
            });
            context.waitTicks(10);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                if (!ow.getBlockState(cellS).is(Blocks.POWERED_RAIL)) {
                    failure.set("FIXTURE: the seam rail at S=" + cellS + " did not survive"
                        + " placement (" + ow.getBlockState(cellS).getBlock()
                        + ") — nothing downstream is meaningful");
                    return;
                }
                if (!ow.getBlockState(destPos).is(Blocks.POWERED_RAIL)) {
                    failure.set("mirror half at D=" + destPos + " is "
                        + ow.getBlockState(destPos).getBlock()
                        + " while S holds the player's rail — THE (a) MIRROR DID NOT WRITE");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "REPRO3[" + tag + "] FAILED: " + failure.get());
            }

            // ---- POWER ON ----
            runOnServer(context, server -> server.getLevel(Level.OVERWORLD)
                .setBlock(powerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3));
            context.waitTicks(60);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                SeamlessPortalsConstants.LOGGER.info(LOG + "REPRO3[" + tag + "] ON: a0={} a1={} S={} D={} b1={}"
                        + " b2={} | {}",
                    ow.getBlockState(a0).getValue(POWERED), ow.getBlockState(a1).getValue(POWERED),
                    ow.getBlockState(cellS).getValue(POWERED),
                    ow.getBlockState(destPos).getValue(POWERED),
                    ow.getBlockState(contC).getValue(POWERED),
                    ow.getBlockState(contC2).getValue(POWERED),
                    com.warwa.seamlessportals.passthrough.SeamSignalContinuity.counters());
                if (!ow.getBlockState(contC).getValue(POWERED)
                    || !ow.getBlockState(contC2).getValue(POWERED)) {
                    failure.set("USER BUG REPRODUCED (ON, command pair): far rails dark — S="
                        + ow.getBlockState(cellS).getValue(POWERED) + " D="
                        + ow.getBlockState(destPos).getValue(POWERED) + " b1="
                        + ow.getBlockState(contC).getValue(POWERED) + " b2="
                        + ow.getBlockState(contC2).getValue(POWERED));
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "REPRO3: " + failure.get());
            }

            // ---- POWER OFF ----
            runOnServer(context, server -> server.getLevel(Level.OVERWORLD)
                .setBlock(powerPos, Blocks.AIR.defaultBlockState(), 3));
            context.waitTicks(60);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                boolean any = ow.getBlockState(cellS).getValue(POWERED)
                    || ow.getBlockState(destPos).getValue(POWERED)
                    || ow.getBlockState(contC).getValue(POWERED)
                    || ow.getBlockState(contC2).getValue(POWERED);
                if (any) {
                    failure.set("USER BUG REPRODUCED (STUCK ON, command pair): S="
                        + ow.getBlockState(cellS).getValue(POWERED) + " D="
                        + ow.getBlockState(destPos).getValue(POWERED) + " b1="
                        + ow.getBlockState(contC).getValue(POWERED) + " b2="
                        + ow.getBlockState(contC2).getValue(POWERED) + " | "
                        + com.warwa.seamlessportals.passthrough.SeamSignalContinuity.counters());
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "REPRO3: " + failure.get());
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "REPRO3[" + tag + "] PASS — the command-built bi-faced"
                + " pair carried and released the signal. counters: "
                + com.warwa.seamlessportals.passthrough.SeamSignalContinuity.counters());
        }
        finally {
            try {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    for (var portal : ow.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                        new net.minecraft.world.phys.AABB(cx - 6, cy - 56, cz - 6,
                            cx + 6, cy + 6, cz + 66), x -> true)) {
                        portal.discard();
                    }
                });
                runCommands(context, List.of(
                    "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz - 4) + " "
                        + (cx + 6) + " " + (cy + 5) + " " + (cz + 6) + " minecraft:air",
                    "fill " + (cx - 6) + " " + (cy - 51) + " " + (cz + 54) + " "
                        + (cx + 6) + " " + (cy - 45) + " " + (cz + 64) + " minecraft:air",
                    "forceload remove " + (cx - 16) + " " + (cz - 16) + " "
                        + (cx + 16) + " " + (cz + 76)
                ));
                Vec3 back = playerBefore.get();
                if (back != null) {
                    runCommands(context, List.of(
                        "tp @p " + back.x + " " + back.y + " " + back.z));
                }
            }
            catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "REPRO3[" + tag + "] cleanup failed", t);
            }
        }
    }

    /**
     * DIAGNOSTIC REPRO 2 — the user's OTHER live variable: the rail line THREADS TWO SEAMS. An
     * ignited obsidian nether portal sits mid-line (its aperture cell carries a mirrored rail into
     * the nether), and the line continues east to a same-dim COINCIDENT pair whose far end holds
     * the continuation. Power at the west end must reach the far end's rails through BOTH seams'
     * machinery coexisting on one line — the user's world had exactly this shape, and their laggy
     * levers / stuck states appeared with "a second portal passing another redstone signal".
     */
    private static void rsSignalTwoSeamLineRepro(ClientGameTestContext context, int py) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_SIGNAL
            || AperturePassthroughLever.DISABLE_SEAM_SIGNAL_DISPATCH
            || AperturePassthroughLever.DISABLE_SEAM_SHADOW
            || AperturePassthroughLever.DISABLE_SEAM_SHAPE_SYNC) {
            return;
        }
        final int nx = 8200, nz = -8200, ry = py + 1;   // nether counterpart ~(1025,-1025): clear
        final BlockPos npCell = new BlockPos(nx, ry, nz);            // nether-portal aperture cell
        final BlockPos cellS = new BlockPos(nx + 3, ry, nz);         // same-dim seam, 3 east
        final int dxo = 60, dyo = 30;
        final BlockPos cellD = new BlockPos(nx + 3 + dxo, ry + dyo, nz);
        final BlockPos b1 = cellD.east();
        final BlockPos b2 = b1.east();
        final BlockPos powerPos = new BlockPos(nx - 3, ry, nz);
        final List<BlockPos> lineRails = List.of(
            new BlockPos(nx - 2, ry, nz), new BlockPos(nx - 1, ry, nz),
            new BlockPos(nx + 1, ry, nz), new BlockPos(nx + 2, ry, nz));
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<Vec3> netherDest = new AtomicReference<>(null);
        final var POWERED = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;

        java.util.function.BiConsumer<MinecraftServer, String> dump = (server, tag) -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            StringBuilder sb = new StringBuilder();
            for (int x = nx - 2; x <= nx + 3; x++) {
                var st = ow.getBlockState(new BlockPos(x, ry, nz));
                sb.append(st.is(Blocks.POWERED_RAIL) ? (st.getValue(POWERED) ? "P" : "o") : "?");
            }
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "REPRO2[{}] line[{}..NP..S]={} D={} b1={} b2={} | {}",
                tag, nx - 2, sb,
                ow.getBlockState(cellD).is(Blocks.POWERED_RAIL) ? ow.getBlockState(cellD).getValue(POWERED) : "-",
                ow.getBlockState(b1).is(Blocks.POWERED_RAIL) ? ow.getBlockState(b1).getValue(POWERED) : "-",
                ow.getBlockState(b2).is(Blocks.POWERED_RAIL) ? ow.getBlockState(b2).getValue(POWERED) : "-",
                com.warwa.seamlessportals.passthrough.SeamSignalContinuity.counters());
        };

        try {
            runCommands(context, List.of(
                "forceload add " + (nx - 16) + " " + (nz - 16) + " " + (nx + dxo + 19) + " " + (nz + 16),
                "fill " + (nx - 6) + " " + py + " " + (nz - 3) + " "
                    + (nx + 8) + " " + (py + 5) + " " + (nz + 3) + " minecraft:air",
                "fill " + (nx - 6) + " " + py + " " + (nz - 3) + " "
                    + (nx + 8) + " " + py + " " + (nz + 3) + " minecraft:stone",
                // The obsidian frame, X-normal: sill/lintel along Z at x=nx, columns at nz-1/nz+2.
                fill(nx, py, nz - 1, nx, py, nz + 2),
                fill(nx, py + 4, nz - 1, nx, py + 4, nz + 2),
                fill(nx, py + 1, nz - 1, nx, py + 3, nz - 1),
                fill(nx, py + 1, nz + 2, nx, py + 3, nz + 2),
                // Far-end platform for the same-dim pair.
                "fill " + (nx + dxo - 3) + " " + (ry + dyo - 1) + " " + (nz - 2) + " "
                    + (nx + dxo + 9) + " " + (ry + dyo - 1) + " " + (nz + 2) + " minecraft:stone",
                "fill " + (nx + dxo - 3) + " " + (ry + dyo) + " " + (nz - 2) + " "
                    + (nx + dxo + 9) + " " + (ry + dyo + 3) + " " + (nz + 2) + " minecraft:air"
            ));
            context.waitTicks(20);
            runOnServer(context, server -> {
                boolean fired = qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration
                    .onFireLitOnObsidian(server.getLevel(Level.OVERWORLD), npCell, null);
                if (!fired) {
                    failure.set("nether-portal ignition rejected");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "REPRO2 SETUP FAILED: " + failure.get());
            }
            final net.minecraft.world.phys.AABB npBox = new net.minecraft.world.phys.AABB(
                nx - 8, py - 8, nz - 8, nx + 8, py + 8, nz + 8);
            try {
                context.waitFor(mc -> {
                    MinecraftServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        return false;
                    }
                    return !server.getLevel(Level.OVERWORLD).getEntitiesOfClass(
                        qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                        npBox, x -> true).isEmpty();
                }, 1200);
            }
            catch (Throwable t) {
                throw new AssertionError(LOG + "REPRO2 FAILED: nether portal never generated", t);
            }
            runOnServer(context, server -> {
                var portals = server.getLevel(Level.OVERWORLD).getEntitiesOfClass(
                    qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                    npBox, x -> true);
                netherDest.set(portals.get(0).getDestPos());
            });
            // Far-side bindings for the nether portal (the Arm A lesson).
            Vec3 nd = netherDest.get();
            runCommands(context, List.of(
                "execute in minecraft:the_nether run forceload add "
                    + ((int) nd.x - 16) + " " + ((int) nd.z - 16) + " "
                    + ((int) nd.x + 16) + " " + ((int) nd.z + 16)));

            // The same-dim pair, mid-block planes.
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ow.getChunk((nx + 3 + dxo) >> 4, nz >> 4);
                qouteall.imm_ptl.core.portal.Portal p =
                    qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE.create(
                        ow, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                if (p == null) {
                    failure.set("same-dim portal create returned null");
                    return;
                }
                p.setOriginPos(new Vec3(nx + 3 + 0.5, ry + 0.5, nz + 0.5));
                p.setDestinationDimension(Level.OVERWORLD);
                p.setDestination(new Vec3(nx + 3 + dxo + 0.5, ry + dyo + 0.5, nz + 0.5));
                p.setOrientationAndSize(new Vec3(0, 0, 1), new Vec3(0, 1, 0), 1, 1);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(p);
                qouteall.imm_ptl.core.portal.Portal q =
                    qouteall.imm_ptl.core.portal.PortalManipulation.createReversePortal(
                        p, qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(q);
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "REPRO2 SETUP FAILED: " + failure.get());
            }
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            for (int attempt = 0; attempt < 30 && !ready.get(); attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    var sCell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                    var npC = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, npCell);
                    ready.set(sCell != null && npC != null
                        && sCell.bindings().stream().anyMatch(bb -> bb.isMirrorable()
                            && bb.phase() == com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT));
                });
                if (!ready.get()) {
                    context.waitTicks(10);
                }
            }
            if (!ready.get()) {
                throw new AssertionError(LOG + "REPRO2 FAILED: both seams never bound"
                    + " (nether aperture + same-dim seam)");
            }

            // Scenery, then both seam rails AS THE PLAYER, west to east — the user's order.
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                for (BlockPos p : lineRails) {
                    ow.setBlock(p, Blocks.POWERED_RAIL.defaultBlockState(), 3);
                }
                for (BlockPos p : List.of(b1, b2)) {
                    ow.setBlock(p, Blocks.POWERED_RAIL.defaultBlockState(), 3);
                }
                writeAsPlayer(ow, npCell, Blocks.POWERED_RAIL.defaultBlockState());
                writeAsPlayer(ow, cellS, Blocks.POWERED_RAIL.defaultBlockState());
            });
            context.waitTicks(10);
            runOnServer(context, server -> dump.accept(server, "baseline"));

            // ---- POWER ON at the WEST end: through the nether-portal aperture, then the seam ----
            runOnServer(context, server -> server.getLevel(Level.OVERWORLD)
                .setBlock(powerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3));
            for (int i = 0; i < 12; i++) {
                context.waitTicks(5);
                final int step = i;
                runOnServer(context, server -> dump.accept(server, "on+" + (step * 5 + 5) + "t"));
            }
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                if (!ow.getBlockState(b1).getValue(POWERED) || !ow.getBlockState(b2).getValue(POWERED)) {
                    failure.set("USER BUG REPRODUCED (ON, two-seam line): far rails dark — b1="
                        + ow.getBlockState(b1).getValue(POWERED) + " b2="
                        + ow.getBlockState(b2).getValue(POWERED) + " S="
                        + ow.getBlockState(cellS).getValue(POWERED) + " D="
                        + ow.getBlockState(cellD).getValue(POWERED) + " NP="
                        + ow.getBlockState(npCell).getValue(POWERED));
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "REPRO2: " + failure.get());
            }

            // ---- POWER OFF ----
            runOnServer(context, server -> server.getLevel(Level.OVERWORLD)
                .setBlock(powerPos, Blocks.AIR.defaultBlockState(), 3));
            for (int i = 0; i < 12; i++) {
                context.waitTicks(5);
                final int step = i;
                runOnServer(context, server -> dump.accept(server, "off+" + (step * 5 + 5) + "t"));
            }
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                boolean any = ow.getBlockState(cellS).getValue(POWERED)
                    || ow.getBlockState(cellD).getValue(POWERED)
                    || ow.getBlockState(npCell).getValue(POWERED)
                    || ow.getBlockState(b1).getValue(POWERED)
                    || ow.getBlockState(b2).getValue(POWERED);
                if (any) {
                    failure.set("USER BUG REPRODUCED (STUCK ON, two-seam line): NP="
                        + ow.getBlockState(npCell).getValue(POWERED) + " S="
                        + ow.getBlockState(cellS).getValue(POWERED) + " D="
                        + ow.getBlockState(cellD).getValue(POWERED) + " b1="
                        + ow.getBlockState(b1).getValue(POWERED) + " b2="
                        + ow.getBlockState(b2).getValue(POWERED));
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "REPRO2: " + failure.get());
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "REPRO2 PASS — the two-seam line carried and"
                + " released the signal. counters: "
                + com.warwa.seamlessportals.passthrough.SeamSignalContinuity.counters());
        }
        finally {
            try {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    for (var portal : ow.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                        new net.minecraft.world.phys.AABB(nx - 8, py - 8, nz - 8,
                            nx + dxo + 12, ry + dyo + 8, nz + 8), x -> true)) {
                        portal.discard();
                    }
                });
                runCommands(context, List.of(
                    "fill " + (nx - 6) + " " + (py - 1) + " " + (nz - 3) + " "
                        + (nx + 8) + " " + (py + 5) + " " + (nz + 3) + " minecraft:air",
                    "fill " + (nx + dxo - 3) + " " + (ry + dyo - 1) + " " + (nz - 2) + " "
                        + (nx + dxo + 9) + " " + (ry + dyo + 3) + " " + (nz + 2) + " minecraft:air",
                    "forceload remove " + (nx - 16) + " " + (nz - 16) + " "
                        + (nx + dxo + 19) + " " + (nz + 16)
                ));
                Vec3 d = netherDest.get();
                if (d != null) {
                    int dx = (int) Math.floor(d.x), dy = (int) Math.floor(d.y), dz = (int) Math.floor(d.z);
                    runCommands(context, List.of(
                        inDim("minecraft:the_nether", "fill " + (dx - 5) + " " + (dy - 2) + " "
                            + (dz - 5) + " " + (dx + 5) + " " + (dy + 5) + " " + (dz + 5)
                            + " minecraft:air"),
                        "execute in minecraft:the_nether run forceload remove " + (dx - 16) + " "
                            + (dz - 16) + " " + (dx + 16) + " " + (dz + 16)
                    ));
                }
            }
            catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "REPRO2 cleanup failed", t);
            }
        }
    }

    /**
     * Poll a server-side precondition every {@code stepTicks} up to {@code attempts} times; on
     * exhaustion, THROW naming what never became true. Waits are preconditions, never bare tick
     * counts (house rule). Throws rather than recording: the first build recorded into the shared
     * {@code failure} ref and a later coverage check OVERWROTE the real verdict — the run's actual
     * failure ("far rails never powered") surfaced as an unrelated coverage message.
     */
    private static void pollOrFail(
        ClientGameTestContext context, int attempts, int stepTicks,
        String what, java.util.function.Predicate<MinecraftServer> condition
    ) {
        for (int i = 0; i < attempts; i++) {
            AtomicReference<Boolean> ok = new AtomicReference<>(false);
            runOnServer(context, server -> ok.set(condition.test(server)));
            if (ok.get()) {
                return;
            }
            context.waitTicks(stepTicks);
        }
        throw new AssertionError(LOG + what + " within " + (attempts * stepTicks)
            + " ticks. counters: "
            + com.warwa.seamlessportals.passthrough.SeamSignalContinuity.counters());
    }

    /** Component of a vector along a signed unit axis. */
    private static double component(Vec3 v, Vec3 signedUnitAxis) {
        return v.x * Math.abs(signedUnitAxis.x)
            + v.y * Math.abs(signedUnitAxis.y)
            + v.z * Math.abs(signedUnitAxis.z);
    }

    /**
     * RS-TEARDOWN-TEST — settles, empirically, whether a block in a lit portal's opening actually
     * breaks the portal.
     *
     * <p>WHY THIS EXISTS. {@code migration/REDSTONE_RECON.md} §1 asserts, from reading
     * {@code NetherPortalEntity.isPortalIntactOnThisSide():72-82} and the teardown chain, that a
     * non-placeholder block in ANY opening cell breaks the portal and its cross-dimension twin
     * within at most 233 ticks. That claim has NEVER BEEN OBSERVED: every armed probe run reported
     * {@code intact=true} only, because the suite never puts anything in a real aperture, so the
     * failure path never fired. The user reports not seeing a portal break in play. A code reading
     * does not outrank that, so this leg triggers the path directly.
     *
     * <p>METHOD. Builds its OWN obsidian frame far from every other leg's staging (-600,-600) and
     * ignites it through the same entry the flint/fire mixins use
     * ({@code IntrinsicPortalGeneration.onFireLitOnObsidian}), so the portal under test is a real
     * generated {@code NetherPortalEntity}, not a synthetic {@code Portal}. Then, WITH THE TEARDOWN
     * SUPPRESSOR OFF, it drops a rail into a mid-height opening cell, waits past the 233-tick sweep,
     * and reports three things: whether the rail is still there, whether the portal entity survived,
     * and what the opening cell holds afterwards.
     *
     * <p>Isolated by construction — nothing else references this portal, so whichever way the result
     * falls it cannot perturb another leg. Gated behind its own DEFAULT-OFF lever and fail-soft, so
     * the 8-leg gate stays byte-identical; asserts nothing, it only reports.
     */
    private static void rsTeardownTest(ClientGameTestContext context, int py) {
        if (!AperturePassthroughLever.TEARDOWN_TEST) {
            return;
        }
        // ISOLATION IS LOAD-BEARING, AND -600 WAS NOT FAR ENOUGH. At (-600,-600) the nether
        // counterpart lands at ~(-75,-75), which is INSIDE leg 6a's 128-block match radius around
        // its ideal dest (-25,-25) — so leg 6a matched THIS leg's frame instead of fabricating its
        // own and false-failed with "Last portal seen: the_nether @ (-71,73.5,-73.5)". At
        // (-4000,-4000) the counterpart is ~(-500,-500), ~672 blocks clear of leg 6a's ideal dest
        // and ~5200 from leg 6b's OW window. The frames are ALSO deleted in the finally, because
        // teardown only wipes the opening — the obsidian frame survives and stays matchable.
        final int fx = -4000, fz = -4000;
        Vec3 destSeen = null;
        try {
            // The leg's MEANING INVERTS at step 3. Before IP-core edit 3 the expected verdict was
            // TEARDOWN CONFIRMED (a block in the opening kills the portal); from step 3 the
            // integrity predicate is frame-only and the expected verdict is NO TEARDOWN. Which one
            // is correct depends purely on the master lever, so it is reported alongside.
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "[RS-TEARDOWN-TEST] building + igniting an isolated frame at ({},{})"
                    + " — aperture passthrough is {}; EXPECTED VERDICT = {}",
                fx, fz,
                AperturePassthroughLever.DISABLED ? "DISABLED" : "ENABLED",
                AperturePassthroughLever.DISABLED ? "TEARDOWN CONFIRMED" : "NO TEARDOWN");

            runCommands(context, List.of(
                "forceload add " + (fx - 16) + " " + (fz - 16) + " " + (fx + 16) + " " + (fz + 16),
                fill(fx - 1, py, fz, fx + 2, py, fz),          // base
                fill(fx - 1, py + 4, fz, fx + 2, py + 4, fz),  // lintel
                fill(fx - 1, py + 1, fz, fx - 1, py + 3, fz),  // left column
                fill(fx + 2, py + 1, fz, fx + 2, py + 3, fz),  // right column
                "fill " + fx + " " + (py + 1) + " " + fz + " "
                    + (fx + 1) + " " + (py + 3) + " " + fz + " minecraft:air"
            ));
            context.waitTicks(20);
            runOnServer(context, server ->
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "[RS-TEARDOWN-TEST] ignition fired={}",
                    qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration
                        .onFireLitOnObsidian(server.getLevel(Level.OVERWORLD),
                            new BlockPos(fx, py + 1, fz), null)));

            // MUST wait for the portal to actually EXIST. Destination search is async — leg 6a
            // allows 1200 ticks. A fixed short wait measured zero portals and made the whole test
            // meaningless (the setblock fired before there was anything to break).
            final net.minecraft.world.phys.AABB testBox = new net.minecraft.world.phys.AABB(
                new Vec3(fx - 8, py - 8, fz - 8), new Vec3(fx + 8, py + 8, fz + 8));
            try {
                context.waitFor(mc -> {
                    MinecraftServer s = mc.getSingleplayerServer();
                    if (s == null) return false;
                    ServerLevel ow = s.getLevel(Level.OVERWORLD);
                    return ow != null && !ow.getEntitiesOfClass(
                        qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                        testBox, p -> true).isEmpty();
                }, 1200);
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(
                    LOG + "[RS-TEARDOWN-TEST] ABORT — no portal generated within 1200 ticks;"
                        + " the test proves nothing", t);
                return;
            }

            // Capture the nether counterpart's position BEFORE the teardown, so the finally can
            // delete that frame too — an empty obsidian frame is exactly what the
            // match-existing-frame search looks for.
            AtomicReference<Vec3> destRef = new AtomicReference<>(null);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                if (ow == null) return;
                ow.getEntitiesOfClass(
                    qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                    testBox, p -> true).stream().findFirst()
                    .ifPresent(p -> destRef.set(p.getDestPos()));
            });
            destSeen = destRef.get();
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "[RS-TEARDOWN-TEST] nether counterpart at {} (will be deleted in cleanup)",
                destSeen);

            int before = countTestPortals(context, testBox);
            // Mid-height opening cell — deliberately NOT the bottom row, per the "any height" decision.
            final int cellY = py + 2;
            final BlockPos cell = new BlockPos(fx, cellY, fz);
            // SERVER-side read. The client has no chunks this far out (render distance 6), so a
            // client read returns void_air and tells us nothing.
            String cellBefore = serverBlockAt(context, cell);
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "[RS-TEARDOWN-TEST] BEFORE: portals={} openingCell({},{},{})={}"
                    + " (must be the portal placeholder, else the test is mis-aimed)",
                before, fx, cellY, fz, cellBefore);

            // Attributed to a player: since 2026-07-26 a /setblock is classified COMMAND and
            // declined by SeamMirrorPolicy, so a plain setblock here would leave the mirror gate
            // below asserting against a mirror that was never allowed to run.
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                if (ow != null) {
                    writeAsPlayer(ow, cell, net.minecraft.world.level.block.Blocks.RAIL.defaultBlockState());
                }
            });
            context.waitTicks(5);
            String cellAfterSet = serverBlockAt(context, cell);
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "[RS-TEARDOWN-TEST] after setblock (+5t): openingCell={} portals={}"
                    + " (if this is not a rail, the setblock was rejected and the test proves nothing)",
                cellAfterSet, countTestPortals(context, testBox));

            // Past the 233-tick sweep, so a portal that survives this has genuinely survived.
            context.waitTicks(260);
            int after = countTestPortals(context, testBox);
            String cellFinal = serverBlockAt(context, cell);

            SeamlessPortalsConstants.LOGGER.info(
                LOG + "[RS-TEARDOWN-TEST] VERDICT after 265 ticks: portalsBefore={} portalsAfter={}"
                    + " openingCell={} => {}",
                before, after, cellFinal,
                verdictText(after < before, AperturePassthroughLever.DISABLED));

            // ---- STEPS 5+6 GATE: THE MIRROR ITSELF ----
            // Runs on this same isolated portal, whose opening now holds the rail placed above.
            // Asserts the three things the mirror must do, in order, and FAILS on each — a mirror
            // that silently does nothing is the whole feature silently not existing.
            if (!AperturePassthroughLever.DISABLED && after == before && destSeen != null) {
                // The reverse-direction assertion needs the FAR portal ALIVE AND TICKING, not merely
                // its chunk readable. Bindings are seeded from SERVER_PORTAL_TICK_SIGNAL, so a portal
                // in a non-ticking chunk never binds and a block broken there has no seam to act on.
                // Force-load the counterpart region and give it ticks to bind before asserting.
                int dxc = (int) Math.floor(destSeen.x), dzc = (int) Math.floor(destSeen.z);
                runCommands(context, List.of(inDim("minecraft:the_nether",
                    "forceload add " + (dxc - 16) + " " + (dzc - 16) + " "
                        + (dxc + 16) + " " + (dzc + 16))));
                context.waitTicks(60);
                rsMirrorGate(context, cell);
                // The bracket itself. Runs on the BOTTOM opening row, not the mid-height cell the
                // mirror gate uses: this gate drives the REAL BlockItem.place, which enforces
                // canSurvive, and a rail at mid-height has nothing under it but the noCollision
                // placeholder — vanilla refuses the placement and the gate would blame the policy for
                // a fixture fault. The bottom row rests on the obsidian sill. (Same reasoning, and
                // the same hazard, as rsFrameBreakGate's own comment below.)
                rsPlayerPlaceBracketGate(context, new BlockPos(fx, py + 1, fz));
                // THE FRACTIONAL SEAM COLLISION GATE — the suite's first collision/support/
                // conduction assertion at a seam. Built FIRST (user decision C, 2026-08-02) and
                // asserting today's WHOLE-CUBE truth, so the same leg inverts when the model lands.
                // Placed after the player-place bracket, which leaves both sides AIR, and before the
                // frame-break gate, which stages its own state on this same bottom-row cell.
                rsSeamCollisionGate(context, new BlockPos(fx, py + 1, fz));
                // THE OBJECT BREAK-BOTH GATE — reproduces the 2026-08-02 live round 10 trail
                // headlessly: breaking a crossing (mirror-side) primary must clear the origin
                // primary too, with each side promoting its own surviving secondary.
                rsObjectBreakBothGate(context, new BlockPos(fx, py + 1, fz), "CROSS-DIM");
                // AND THE SAME-DIM DISCRIMINATOR. The cross-dim run went GREEN on the first
                // attempt while the user's live pair — a /portal-made SAME-DIM pair — showed the
                // defect. One variable at a time: identical mechanics, identical assertions, the
                // pair built with the user's exact commands (make_portal +
                // complete_bi_way_bi_faced_portal), differing only in dimension topology.
                rsObjectBreakBothSameDimGate(context);
                // THE PIXEL GATE FOR SYMMETRIC EMPTINESS — user live round 15 (2026-08-03): state
                // and behaviour gates all green while both empty half-spaces PAINTED a full
                // continuous block ("you go to source side b and you see another full continuous
                // block spanning from source side b to dest side a, but its not breakable").
                // Every existing gate reads records; none of them stands where the user stood and
                // looks. This one does — four measured screenshots on the user's own construction.
                rsSeamEmptinessGate(context, py);
                // ★ PARTICLE MEASUREMENT LEG (handoff evidence questions, 2026-08-05) — probe
                // lever only, MEASURES AND ASSERTS NOTHING: a torch at the seam + one
                // deterministic smoke spawn in the open aperture cell, player standing on the
                // empty side, ~45s of [SEAM FRAC][PTCL] counter summaries. Placed after the
                // emptiness gate (which restores the player) and before the frame-break gate
                // (this leg removes its torch and restores the player in its finally).
                if (AperturePassthroughLever.SEAM_FRACTIONAL_PROBE) {
                    rsSeamParticleMeasureLeg(context, fx, py, fz);
                }
                // RELOG PERSISTENCE — stage a two-object cell that SURVIVES the world close, on
                // its own bi-way pair with a PERSISTENT forceload; the assert runs after leg 5's
                // worldSave.open(). Full-suite only: RS-only runs never reopen the save.
                if (!AperturePassthroughLever.RS_ONLY) {
                    rsRelogStage(context);
                }
                // BOTTOM OPENING ROW, not the mid-height cell the mirror gate uses. Support is
                // vanilla (user clarification §0.2): a rail at mid-height sits on another aperture
                // cell holding the noCollision placeholder, so when teardown wipes those cells with
                // setBlockAndUpdate the neighbour update reaches the rail, canSurvive fails and it
                // pops — the rail is gone before the frame-break rule is even observable. The bottom
                // row rests on the obsidian sill and genuinely survives, which is what makes the
                // provenance question testable at all.
                rsFrameBreakGate(context, new BlockPos(fx, py + 1, fz), fx, py, fz);
                runCommands(context, List.of(inDim("minecraft:the_nether",
                    "forceload remove " + (dxc - 16) + " " + (dzc - 16) + " "
                        + (dxc + 16) + " " + (dzc + 16))));
            }
        } catch (AssertionError gateFailure) {
            // A GATE assertion must never be swallowed by this leg's fail-soft handler. Observed:
            // the steps 5+6 mirror gate threw, was caught as "evidence only", and the suite still
            // printed ALL LEGS PASS — a gate that cannot fail the suite is not a gate. Evidence
            // failures stay soft (the catch below); gate failures propagate. Cleanup still runs,
            // because it is in the finally.
            throw gateFailure;
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(
                LOG + "[RS-TEARDOWN-TEST] FAILED (non-fatal, evidence only)", t);
        } finally {
            // MANDATORY CLEANUP. Teardown wipes only the OPENING; the obsidian frame survives, and a
            // bare obsidian frame is precisely what the match-existing-frame search finds. Leaving
            // one behind made leg 6a link to this leg's portal and false-fail. Delete both frames.
            try {
                List<String> clean = new java.util.ArrayList<>();
                clean.add("fill " + (fx - 2) + " " + (py - 1) + " " + (fz - 2) + " "
                    + (fx + 3) + " " + (py + 5) + " " + (fz + 2) + " minecraft:air");
                if (destSeen != null) {
                    int dx = (int) Math.floor(destSeen.x), dy = (int) Math.floor(destSeen.y),
                        dz = (int) Math.floor(destSeen.z);
                    clean.add(inDim("minecraft:the_nether",
                        "fill " + (dx - 4) + " " + (dy - 3) + " " + (dz - 4) + " "
                            + (dx + 4) + " " + (dy + 5) + " " + (dz + 4) + " minecraft:air"));
                }
                clean.add("forceload remove " + (fx - 16) + " " + (fz - 16) + " "
                    + (fx + 16) + " " + (fz + 16));
                runCommands(context, clean);
                context.waitTicks(10);
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "[RS-TEARDOWN-TEST] frames deleted (OW @ {},{} and nether @ {}) —"
                        + " nothing left for another leg's frame-match search to find",
                    fx, fz, destSeen);
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(
                    LOG + "[RS-TEARDOWN-TEST] CLEANUP FAILED — a leftover obsidian frame may"
                        + " false-fail a later ignition leg", t);
            }
        }
    }

    /**
     * ★ THE PARTICLE MEASUREMENT LEG (2026-08-05) — {@code SEAM_FRACTIONAL_PROBE} only,
     * MEASURES AND ASSERTS NOTHING. Exists to answer the handoff's five evidence questions with
     * numbers instead of another blind fix (12 failed rounds; the standing order is INSTRUMENT
     * FIRST). Every deliverable number appears as a {@code [SEAM FRAC][PTCL]} line from
     * {@code SeamParticleProbe}; this leg only stages the user's live scenario headlessly:
     * <ol>
     *   <li>a wall-supported TORCH in the teardown portal's bottom opening row, owned half
     *       forced BEFORE the write (the r29 at-write-time lesson), player standing on the
     *       EMPTY side looking at it — 600 ticks of organic flame+smoke emission;</li>
     *   <li>five DETERMINISTIC smoke particles spawned into the OPEN aperture cell clearly on
     *       the empty-side half — the round-37 bidirectional rule's alleged ping-pong case,
     *       isolated from animateTick randomness.</li>
     * </ol>
     * The torch is removed (PLAYER_BREAK-classified) and the player restored in the finally, so
     * the frame-break gate behind this leg stages on a clean bottom row.
     */
    private static void rsSeamParticleMeasureLeg(
        ClientGameTestContext context, int fx, int py, int fz
    ) {
        final String tag = LOG + "[RS-PTCL-MEASURE] ";
        final BlockPos torchCell = new BlockPos(fx, py + 1, fz);
        final BlockPos openCell = new BlockPos(fx + 1, py + 2, fz);
        String prevDim = context.computeOnClient(mc ->
            mc.level == null ? null : mc.level.dimension().identifier().toString());
        Vec3 prevPos = context.computeOnClient(mc ->
            mc.player == null ? Vec3.ZERO : mc.player.position());
        try {
            SeamlessPortalsConstants.LOGGER.info(tag + "START — torch at {} (owned half forced"
                    + " POSITIVE before the write), deterministic smoke in open cell {}, player"
                    + " on the empty (north) side. All numbers are [SEAM FRAC][PTCL] lines.",
                torchCell, openCell);
            claimOwnerHalfBothSides(context, torchCell,
                com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                if (ow != null) {
                    writeAsPlayer(ow, torchCell,
                        net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState());
                }
            });
            context.waitTicks(10);
            // Evidence, never assertion: what did the machinery actually stage? A surprising
            // occupancy here (e.g. BOTH from a double claim) reframes every number after it.
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ServerLevel nether = server.getLevel(Level.NETHER);
                String destInfo = "no mirrorable binding";
                String destOcc = "?", destBlock = "?";
                var cellRec = ow == null ? null
                    : com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, torchCell);
                if (cellRec != null) {
                    for (var b : cellRec.bindings()) {
                        if (b.isMirrorable() && b.cut() != null && b.destPos() != null
                            && nether != null) {
                            destInfo = b.destDim().identifier() + " " + b.destPos();
                            destOcc = String.valueOf(com.warwa.seamlessportals.passthrough
                                .SeamOccupancy.occupancyOf(nether, b.destPos()));
                            destBlock = nether.getBlockState(b.destPos()).toString();
                            break;
                        }
                    }
                }
                SeamlessPortalsConstants.LOGGER.info(
                    tag + "STAGED: srcBlock={} srcOcc={} | dest={} destOcc={} destBlock={}",
                    ow == null ? "?" : ow.getBlockState(torchCell),
                    ow == null ? "?" : String.valueOf(com.warwa.seamlessportals.passthrough
                        .SeamOccupancy.occupancyOf(ow, torchCell)),
                    destInfo, destOcc, destBlock);
            });
            // The empty-side viewpoint: north of the plane, looking at the torch through the
            // seam — where the user stands when they report the bleed.
            seamStandIn(context, "minecraft:overworld", fx + 0.5, py + 1.0, fz - 3.5);
            aimAt(context, fx + 0.5, py + 1.7, fz + 0.5);
            SeamlessPortalsConstants.LOGGER.info(
                tag + "PHASE A: 600 ticks of organic torch emission");
            context.waitTicks(600);
            SeamlessPortalsConstants.LOGGER.info(
                tag + "PHASE B: 5 deterministic smoke spawns into {}", openCell);
            for (int i = 0; i < 5; i++) {
                context.runOnClient(mc -> {
                    if (mc.level != null) {
                        mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                            fx + 1.5, py + 2.3, fz + 0.25, 0.0, 0.02, 0.0);
                    }
                });
                context.waitTicks(60);
            }
            context.waitTicks(100);
            context.runOnClient(mc -> com.warwa.seamlessportals.render.SeamParticleProbe
                .legReport("[RS-PTCL-MEASURE cross-dim]"));
            // ★ SAME-DIM PHASE — the user's live pairs are /portal-made SAME-DIM pairs, and this
            // suite has already once watched a cross-dim run go green while the same-dim pair
            // showed the defect (the break-both discriminator's reason to exist). Cross-dim
            // arrivals land in the REMOTE level's separately-populated index; same-dim arrivals
            // land in the SAME fully-bound index — the topology where a return trip is possible.
            rsSeamParticleSameDimPhase(context);
            SeamlessPortalsConstants.LOGGER.info(
                tag + "DONE — measurement complete, nothing asserted.");
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(tag + "FAILED (measurement only, never fatal)", t);
        } finally {
            try {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    if (ow != null) {
                        writeAsPlayer(ow, torchCell,
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    }
                });
                context.waitTicks(5);
                if (prevDim != null) {
                    seamStandIn(context, prevDim, prevPos.x, prevPos.y, prevPos.z);
                }
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(tag + "CLEANUP FAILED — the frame-break"
                    + " gate behind this leg may stage on a dirty bottom row", t);
            }
        }
    }

    /**
     * RS-DELIVERY-TEST — does a mirrored write reach the CLIENT, and does it depend on whether the
     * destination is the same dimension?
     *
     * <p><b>Why this leg exists.</b> The open bug is: same-dimension man-made portals do not show
     * mirrored writes live, while obsidian and man-made CROSS-dimension portals do. Server state is
     * always correct. Three fixes were written from three theories of the cause; all three were
     * wrong. Every round of evidence so far came from the user playing, which yields one observation
     * per build — the conditions under which a theory gets shipped without being falsified.
     *
     * <p>The harness already stages the exact controlled pair: portal <b>A</b> is a same-dimension
     * man-made portal and portal <b>B</b> a cross-dimension one, spawned identically, four blocks
     * apart, in one run. The two arms differ in precisely the property the user's observations turn
     * on and in nothing else.
     *
     * <p><b>It reports; it does not assert a delivery verdict.</b> Deliberate. What the correct
     * answer is here is the thing under investigation, and a leg that asserted one would be encoding
     * the theory it is supposed to test. It DOES hard-fail two things that are not in question: a
     * mirror that never wrote server-side, and finding no bound seam cell at all — a run that
     * measured nothing must not read as a run that measured success.
     *
     * <p><b>Coverage is stated, not assumed.</b> The client is read at the destination cell BEFORE
     * the write as well as after. If the client does not hold that chunk, the "after" read returns
     * {@code void_air}, which is indistinguishable from a genuinely empty cell — the false reading
     * that already made one RS-TEARDOWN-TEST run report a nonsense state for a far cell. When the
     * before-read shows the chunk is absent the arm reports {@code INCONCLUSIVE} and says so.
     */
    private static void rsDeliveryTest(
        ClientGameTestContext context, int px, int py, int pz, double planeZ
    ) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_TEST) {
            return;
        }
        // ---- The WAND-SHAPED same-dimension portal, arm 3 ----
        //
        // The harness's portal A is a ONE-WAY, single-entity portal. What the user calls a "man-made
        // portal" is what the portal wand builds, and PortalWandInteraction.java:271-283 builds FOUR
        // entities: the portal, its flipped twin, its REVERSE, and the reverse's flipped twin. That
        // is a materially different object, and the difference is exactly the kind that could decide
        // this bug — a reverse portal in the SAME level means the mirror runs in both directions
        // between two cells of one dimension, which is the one configuration a single static
        // `applying` guard cannot distinguish from an ordinary write.
        //
        // Built well away from every other leg's coordinates, and removed in the finally.
        //
        // The player is MOVED to stand in front of it, because the client only holds the far chunks
        // if it is actually rendering the portal — which is the entire question. Their position is
        // captured here and restored in the finally: legs 3 and 4 run after this one and assume the
        // player is still at the staging area, and an evidence leg that relocates the player would
        // break them exactly the way a leftover staged block already broke the ender-pearl leg once.
        Vec3 playerHome = context.computeOnClient(mc -> mc.player.position());
        final int wx = 2600, wy = 90, wz = 2600;      // source side
        // ★ THE FIXTURE FOR DEFECT A, and its two properties are both load-bearing.
        //
        // NEAR (60 blocks) so the destination is inside the SectionUpdateTracker's window AND inside
        // the ViewArea's grid — a RenderSection exists there and can be rebuilt.
        // OCCLUDED (a sealed chamber 50 blocks underground) so the main camera's SectionOcclusionGraph
        // BFS cannot reach it, which is what keeps the section out of visibleSections and reproduces
        // the real defect: marked dirty, never consumed.
        //
        // An earlier fixture put the destination 600 blocks away. That reproduced a DIFFERENT defect
        // (the out-of-window mark drop) and, worse, one this fix cannot repair: at that range the
        // same-dim ViewArea has no columns at all, because same-dim passes deliberately never call
        // repositionCamera. getRenderSection returns null, there is nothing to rebuild, and nothing
        // renders there either. See the class note on SameDimRemesh for why defect B is out of scope.
        final int wdx = 2660;
        final int wdy = 40;
        runCommands(context, List.of(
            "forceload add " + (wx - 16) + " " + (wz - 16) + " " + (wx + 16) + " " + (wz + 16),
            "forceload add " + (wdx - 16) + " " + (wz - 16) + " " + (wdx + 16) + " " + (wz + 16),
            // source side: a small stone pad with clear air above it
            "fill " + (wx - 3) + " " + (wy - 1) + " " + (wz - 3) + " "
                + (wx + 3) + " " + (wy - 1) + " " + (wz + 3) + " minecraft:stone",
            "fill " + (wx - 3) + " " + wy + " " + (wz - 3) + " "
                + (wx + 3) + " " + (wy + 5) + " " + (wz + 3) + " minecraft:air",
            // destination: SOLID stone, then a sealed chamber carved inside it. The solid shell is
            // the point — it is what stops the main camera's occlusion BFS reaching the chamber, so
            // its sections stay out of visibleSections and the defect is reproduced rather than
            // accidentally avoided.
            "fill " + (wdx - 6) + " " + (wdy - 4) + " " + (wz - 6) + " "
                + (wdx + 6) + " " + (wdy + 8) + " " + (wz + 6) + " minecraft:stone",
            "fill " + (wdx - 2) + " " + wdy + " " + (wz - 2) + " "
                + (wdx + 2) + " " + (wdy + 4) + " " + (wz + 2) + " minecraft:air"
        ));
        context.waitTicks(20);
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow == null) throw new AssertionError(LOG + "[RS-DELIVERY-TEST] no overworld");
            qouteall.imm_ptl.core.portal.Portal p =
                qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE.create(ow, EntitySpawnReason.COMMAND);
            if (p == null) throw new AssertionError(LOG + "[RS-DELIVERY-TEST] portal create returned null");
            p.setOriginPos(new Vec3(wx + 0.5, wy + 1.5, wz + 0.5));
            p.setDestinationDimension(Level.OVERWORLD);
            p.setDestination(new Vec3(wdx + 0.5, wdy + 1.5, wz + 0.5));
            p.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 3, 3);
            // The wand's own four-entity cluster, same calls in the same order
            // (PortalWandInteraction.java:271-283).
            qouteall.imm_ptl.core.portal.Portal flipped =
                qouteall.imm_ptl.core.portal.PortalManipulation.createFlippedPortal(
                    p, qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE);
            qouteall.imm_ptl.core.portal.Portal reverse =
                qouteall.imm_ptl.core.portal.PortalManipulation.createReversePortal(
                    p, qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE);
            qouteall.imm_ptl.core.portal.Portal parallel =
                qouteall.imm_ptl.core.portal.PortalManipulation.createFlippedPortal(
                    reverse, qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE);
            McHelper.spawnServerEntity(p);
            McHelper.spawnServerEntity(flipped);
            McHelper.spawnServerEntity(reverse);
            McHelper.spawnServerEntity(parallel);
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "[RS-DELIVERY-TEST] wand-shaped SAME-DIM cluster spawned: 4 entities,"
                    + " origin ({},{},{}) -> dest ({},{},{})", wx, wy, wz, wdx, wdy, wz);
        });
        context.waitTicks(40);   // let the four entities tick and bind before the registry is read

        // [0] = source cell, [1] = destination cell
        AtomicReference<BlockPos[]> sameDim = new AtomicReference<>(null);
        AtomicReference<BlockPos[]> crossDim = new AtomicReference<>(null);
        AtomicReference<BlockPos[]> wandDim = new AtomicReference<>(null);
        AtomicReference<String> scanned = new AtomicReference<>("");

        // ---- ARM 4: the FAR same-dimension portal, i.e. DEFECT B ----
        //
        // Arm 3 is NEAR and occluded, which is defect A. Defect B is a different wall entirely: at
        // this range ImmPtlViewArea.getRenderSection wraps the node into the main-camera preset and
        // its occupant guard rejects it, so the ONLY way to the section is the coord-pinned
        // provideBuiltChunkByChunkPos fallback — IP's own accessor. Without an arm here that fallback
        // is protected by nothing, and it is precisely the code a future refactor would delete as
        // dead. User-confirmed live on 2026-07-26; this exists so it stays true.
        final int fx = 2600, fy = 90, fz = 2800;   // source, well clear of the arm-3 cluster
        final int fdz = 8000;                       // destination: ~5200 blocks away, far past any RD
        runCommands(context, List.of(
            "forceload add " + (fx - 16) + " " + (fz - 16) + " " + (fx + 16) + " " + (fz + 16),
            "forceload add " + (fx - 16) + " " + (fdz - 16) + " " + (fx + 16) + " " + (fdz + 16),
            "fill " + (fx - 3) + " " + (fy - 1) + " " + (fz - 3) + " "
                + (fx + 3) + " " + (fy - 1) + " " + (fz + 3) + " minecraft:stone",
            "fill " + (fx - 3) + " " + fy + " " + (fz - 3) + " "
                + (fx + 3) + " " + (fy + 5) + " " + (fz + 3) + " minecraft:air",
            "fill " + (fx - 6) + " " + (fy - 4) + " " + (fdz - 6) + " "
                + (fx + 6) + " " + (fy + 8) + " " + (fdz + 6) + " minecraft:stone",
            "fill " + (fx - 2) + " " + fy + " " + (fdz - 2) + " "
                + (fx + 2) + " " + (fy + 4) + " " + (fdz + 2) + " minecraft:air"
        ));
        context.waitTicks(20);
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow == null) throw new AssertionError(LOG + "[RS-DELIVERY-TEST] no overworld");
            qouteall.imm_ptl.core.portal.Portal p =
                qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE.create(ow, EntitySpawnReason.COMMAND);
            if (p == null) throw new AssertionError(LOG + "[RS-DELIVERY-TEST] far portal create null");
            p.setOriginPos(new Vec3(fx + 0.5, fy + 1.5, fz + 0.5));
            p.setDestinationDimension(Level.OVERWORLD);
            p.setDestination(new Vec3(fx + 0.5, fy + 1.5, fdz + 0.5));
            p.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 3, 3);
            McHelper.spawnServerEntity(p);
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "[RS-DELIVERY-TEST] FAR same-dim portal spawned: ({},{},{}) -> ({},{},{})",
                fx, fy, fz, fx, fy, fdz);
        });
        context.waitTicks(40);

        AtomicReference<BlockPos[]> farDim = new AtomicReference<>(null);

        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow == null) return;
            for (int y = wy; y <= wy + 3 && wandDim.get() == null; y++) {
                for (int x = wx - 2; x <= wx + 2 && wandDim.get() == null; x++) {
                    for (int z = wz - 1; z <= wz + 1 && wandDim.get() == null; z++) {
                        BlockPos cell = new BlockPos(x, y, z);
                        var sc = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cell);
                        if (sc == null) continue;
                        for (var b : sc.bindings()) {
                            if (b.isMirrorable() && Level.OVERWORLD.equals(b.destDim())
                                && b.destPos().getX() > wx + 10) {
                                wandDim.set(new BlockPos[]{cell, b.destPos()});
                                break;
                            }
                        }
                    }
                }
            }
            for (int y = fy; y <= fy + 3 && farDim.get() == null; y++) {
                for (int x = fx - 2; x <= fx + 2 && farDim.get() == null; x++) {
                    for (int z = fz - 1; z <= fz + 1 && farDim.get() == null; z++) {
                        BlockPos cell = new BlockPos(x, y, z);
                        var sc = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cell);
                        if (sc == null) continue;
                        for (var b : sc.bindings()) {
                            if (b.isMirrorable() && Level.OVERWORLD.equals(b.destDim())
                                && b.destPos().getZ() > fz + 100) {
                                farDim.set(new BlockPos[]{cell, b.destPos()});
                                break;
                            }
                        }
                    }
                }
            }
        });

        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow == null) throw new AssertionError(LOG + "[RS-DELIVERY-TEST] no overworld");
            StringBuilder sb = new StringBuilder();
            int zc = (int) Math.floor(planeZ);
            int examined = 0;
            for (int z = zc - 1; z <= zc + 1; z++) {
                for (int x = px - 2; x <= px + 7; x++) {
                    for (int y = py; y <= py + 3; y++) {
                        BlockPos cell = new BlockPos(x, y, z);
                        var sc = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cell);
                        if (sc == null) continue;
                        examined++;
                        for (var b : sc.bindings()) {
                            if (!b.isMirrorable()) continue;
                            if (Level.OVERWORLD.equals(b.destDim()) && sameDim.get() == null) {
                                sameDim.set(new BlockPos[]{cell, b.destPos()});
                            }
                            else if (Level.NETHER.equals(b.destDim()) && crossDim.get() == null) {
                                crossDim.set(new BlockPos[]{cell, b.destPos()});
                            }
                        }
                    }
                }
            }
            sb.append("bound cells examined=").append(examined);
            scanned.set(sb.toString());
        });

        // A run that found nothing must fail loudly. "Zero checks ran" caught two false instruments
        // already in this engagement; silence here would look exactly like success.
        if (sameDim.get() == null && crossDim.get() == null && wandDim.get() == null) {
            throw new AssertionError(LOG + "[RS-DELIVERY-TEST] NO BOUND SEAM CELL FOUND around ("
                + px + "," + py + "," + planeZ + ") or the wand cluster at (" + wx + "," + wy + ","
                + wz + ") — the registry indexed no test portal, so this leg measured nothing. "
                + scanned.get());
        }
        // The wand-shaped arm is the one that models what the user actually builds. Its absence is
        // not a detail to be discovered by reading a missing log line later.
        if (wandDim.get() == null) {
            throw new AssertionError(LOG + "[RS-DELIVERY-TEST] the WAND-SHAPED cluster at ("
                + wx + "," + wy + "," + wz + ") bound no mirrorable cell pointing east of "
                + (wx + 10) + " — arm 3, the only arm that models a real man-made portal, would"
                + " silently not run.");
        }
        SeamlessPortalsConstants.LOGGER.info(
            LOG + "[RS-DELIVERY-TEST] {} | sameDim arm={} | crossDim arm={} | wandSameDim arm={}",
            scanned.get(),
            sameDim.get() == null ? "ABSENT" : sameDim.get()[0] + "->" + sameDim.get()[1],
            crossDim.get() == null ? "ABSENT" : crossDim.get()[0] + "->" + crossDim.get()[1],
            wandDim.get()[0] + "->" + wandDim.get()[1]);

        try {
            if (sameDim.get() != null) {
                deliveryArm(context, "SAME-DIM one-way (portal A)", Level.OVERWORLD,
                    sameDim.get()[0], Level.OVERWORLD, sameDim.get()[1]);
            }
            if (crossDim.get() != null) {
                deliveryArm(context, "CROSS-DIM one-way (portal B)", Level.OVERWORLD,
                    crossDim.get()[0], Level.NETHER, crossDim.get()[1]);
            }
            // Arm 3 LAST, and only now is the player moved. Arms 1 and 2 must be measured while the
            // player is still at the staging area — the client holds portal A's and B's destination
            // chunks only because it is rendering those portals, and measuring them from 2600 blocks
            // away would report "not delivered" about a client that was never watching.
            seamStand(context, wx + 0.5, wy, wz + 4.5);
            context.waitTicks(80);
            deliveryArm(context, "SAME-DIM WAND-SHAPED (4 entities, bi-way + bi-faced)",
                Level.OVERWORLD, wandDim.get()[0], Level.OVERWORLD, wandDim.get()[1]);
            context.waitTicks(20);
            sameDimRemeshVerdict(context, wandDim.get()[1]);

            // ---- ARM 4: DEFECT B. Same assertion, a destination no preset can ever cover. ----
            if (farDim.get() == null) {
                throw new AssertionError(LOG + "[RS-DELIVERY-TEST] the FAR portal at (" + fx + ","
                    + fy + "," + fz + ") bound no mirrorable cell with a destination past z="
                    + (fz + 100) + " — arm 4, the ONLY cover for the coord-pinned fallback, would"
                    + " silently not run.");
            }
            seamStand(context, fx + 0.5, fy, fz + 4.5);
            context.waitTicks(60);
            deliveryArm(context, "SAME-DIM FAR (defect B — beyond any render distance)",
                Level.OVERWORLD, farDim.get()[0], Level.OVERWORLD, farDim.get()[1]);
            context.waitTicks(20);
            sameDimRemeshVerdict(context, farDim.get()[1]);
        } finally {
            // Cleanup in a finally, per the hazard that an evidence leg must never perturb a
            // functional one: legs 3 and 4 both use the nether around (0,129,0), which is exactly
            // where portal B's mirror writes.
            List<String> clean = new java.util.ArrayList<>();
            for (AtomicReference<BlockPos[]> arm : List.of(sameDim, crossDim, wandDim)) {
                BlockPos[] a = arm.get();
                if (a == null) continue;
                clean.add("setblock " + a[0].getX() + " " + a[0].getY() + " " + a[0].getZ() + " minecraft:air");
            }
            for (AtomicReference<BlockPos[]> arm : List.of(sameDim, wandDim)) {
                BlockPos[] a = arm.get();
                if (a == null) continue;
                clean.add("setblock " + a[1].getX() + " " + a[1].getY() + " " + a[1].getZ() + " minecraft:air");
            }
            if (crossDim.get() != null) {
                BlockPos d = crossDim.get()[1];
                clean.add(inDim("minecraft:the_nether",
                    "setblock " + d.getX() + " " + d.getY() + " " + d.getZ() + " minecraft:air"));
            }
            runCommands(context, clean);
            // The wand cluster's four entities and its terrain pad go too: a live portal left at
            // (2600,90,2600) would keep force-loading chunks and could be found by a later leg's
            // frame-match or view-culling logic. Same rule as RS-TEARDOWN-TEST's frame deletion.
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                if (ow == null) return;
                int removed = 0;
                for (qouteall.imm_ptl.core.portal.Portal p : ow.getEntitiesOfClass(
                    qouteall.imm_ptl.core.portal.Portal.class,
                    new net.minecraft.world.phys.AABB(
                        wx - 40, wdy - 20, wz - 40, wdx + 40, wy + 30, wz + 40),
                    p -> true)) {
                    p.discard();
                    removed++;
                }
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "[RS-DELIVERY-TEST] wand cluster cleanup: {} portal entities discarded",
                    removed);
            });
            // Arm 4's portal and terrain go too — a live portal left standing keeps force-loading
            // chunks and can be found by a later leg's frame-match search (the hazard that already
            // made leg 6a link to a leftover frame once).
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                if (ow == null) return;
                for (qouteall.imm_ptl.core.portal.Portal p : ow.getEntitiesOfClass(
                    qouteall.imm_ptl.core.portal.Portal.class,
                    new net.minecraft.world.phys.AABB(
                        fx - 40, fy - 40, fz - 40, fx + 40, fy + 40, fz + 40),
                    p -> true)) {
                    p.discard();
                }
            });
            runCommands(context, List.of(
                "fill " + (fx - 3) + " " + (fy - 1) + " " + (fz - 3) + " "
                    + (fx + 3) + " " + (fy + 5) + " " + (fz + 3) + " minecraft:air",
                "forceload remove " + (fx - 16) + " " + (fz - 16) + " "
                    + (fx + 16) + " " + (fz + 16),
                "forceload remove " + (fx - 16) + " " + (fdz - 16) + " "
                    + (fx + 16) + " " + (fdz + 16)));
            runCommands(context, List.of(
                "fill " + (wx - 3) + " " + (wy - 1) + " " + (wz - 3) + " "
                    + (wx + 3) + " " + (wy + 5) + " " + (wz + 3) + " minecraft:air",
                "fill " + (wdx - 6) + " " + (wdy - 4) + " " + (wz - 6) + " "
                    + (wdx + 6) + " " + (wdy + 8) + " " + (wz + 6) + " minecraft:air",
                "forceload remove " + (wx - 16) + " " + (wz - 16) + " "
                    + (wx + 16) + " " + (wz + 16),
                "forceload remove " + (wdx - 16) + " " + (wz - 16) + " "
                    + (wdx + 16) + " " + (wz + 16)));
            seamStand(context, playerHome.x, playerHome.y, playerHome.z);
            context.waitTicks(20);
        }
    }

    /**
     * THE SAME-DIM REMESH INVERSION — the assertion that makes the fix a proof rather than a hope.
     *
     * <p>Its verdict flips on {@code -PdisableSameDimRemesh=true}, in the RS-TEARDOWN-TEST
     * discipline: with the fix ON a mirrored write behind a same-dimension portal must SCHEDULE a
     * rebuild, and with it OFF it must schedule none. A fixed expectation would be actively
     * misleading in whichever configuration it was not written for, and — more to the point — a
     * one-directional assertion cannot tell "the fix works" from "the defect never existed here",
     * which is the failure that let three wrong fixes ship earlier in this engagement.
     *
     * <p><b>Asserted on THE CELL THAT WAS WRITTEN, not on a count and not on a client block read.</b>
     * Two distinct traps, both already sprung in this engagement:
     * <ul>
     *   <li>A client block read proves nothing — the block DATA was correct throughout this bug.</li>
     *   <li>A count delta proves nothing either. The first cut of the fix passed a "&gt; 0 rebuilds
     *       scheduled" assertion while its queue had saturated and <b>dropped the very write under
     *       test</b>. Asserting the specific section is what makes the gate able to fail.</li>
     * </ul>
     */
    private static void sameDimRemeshVerdict(ClientGameTestContext context, BlockPos destCell) {
        boolean fixDisabled = AperturePassthroughLever.DISABLE_SAME_DIM_REMESH;
        String counters = context.computeOnClient(mc ->
            com.warwa.seamlessportals.render.SameDimRemesh.counters());
        boolean compiledThisCell = context.computeOnClient(mc ->
            com.warwa.seamlessportals.render.SameDimRemesh.didCompileSectionAt(
                destCell.getX(), destCell.getY(), destCell.getZ()));

        if (fixDisabled) {
            if (compiledThisCell) {
                throw new AssertionError(LOG + "[RS-DELIVERY-TEST] *** REGRESSION *** the same-dim"
                    + " remesh fix is DISABLED, yet a rebuild COMPLETED for " + destCell
                    + "'s section. The disable lever is not restoring stock behaviour."
                    + " counters: " + counters);
            }
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "[RS-DELIVERY-TEST] SAME-DIM REMESH INVERSION PASS — fix DISABLED and no"
                    + " rebuild completed for {}, which is the defect reproduced on demand."
                    + " counters: {}", destCell, counters);
            return;
        }
        if (!compiledThisCell) {
            throw new AssertionError(LOG + "[RS-DELIVERY-TEST] SAME-DIM REMESH FAILED: a block was"
                + " mirrored to " + destCell + " behind a same-dimension portal and NO rebuild COMPLETED for"
                + " that section, so the window will show stale terrain there."
                + " A non-zero scheduled count in the counters below does NOT excuse this — it"
                + " means other sections were rebuilt while this one was missed."
                + " counters: " + counters);
        }
        SeamlessPortalsConstants.LOGGER.info(
            LOG + "[RS-DELIVERY-TEST] SAME-DIM REMESH PASS — the section holding {} was REBUILT"
                + " — its mesh was actually replaced, which stock 26.2 never does for a"
                + " same-dimension portal’s far side. counters: {}", destCell, counters);
    }

    /**
     * One arm of RS-DELIVERY-TEST: write into {@code sourceCell}, then ask the server AND the client
     * what sits at {@code destCell}.
     *
     * <p>Glass, not rail: a rail needs support and vanilla would pop it, which has already produced
     * one confident-but-wrong gate verdict ("the player's block was deleted") in this engagement.
     * Glass survives anywhere, has no block entity, occupies one cell, and is visibly distinct from
     * both air and the portal placeholder.
     */
    private static void deliveryArm(
        ClientGameTestContext context, String armName,
        net.minecraft.resources.ResourceKey<Level> sourceDim, BlockPos sourceCell,
        net.minecraft.resources.ResourceKey<Level> destDim, BlockPos destCell
    ) {
        String clientBefore = clientBlockAt(context, destDim, destCell);
        String serverBefore = serverBlockAt(context, destDim, destCell);

        // Player-attributed: /setblock is COMMAND and declined since 2026-07-26, and this arm's whole
        // job is to observe a mirrored write travel to the client.
        runOnServer(context, server -> {
            ServerLevel src = server.getLevel(sourceDim);
            if (src != null) {
                writeAsPlayer(src, sourceCell, net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState());
            }
        });
        context.waitTicks(30);

        String serverAfter = serverBlockAt(context, destDim, destCell);
        String clientAfter = clientBlockAt(context, destDim, destCell);
        String sourceAfter = serverBlockAt(context, sourceDim, sourceCell);

        // Not in question, and fatal: if the mirror did not write, there is nothing to deliver and
        // every reading below is about a different problem.
        if (!serverAfter.contains("glass")) {
            throw new AssertionError(LOG + "[RS-DELIVERY-TEST] " + armName + " MIRROR DID NOT WRITE:"
                + " source " + sourceCell + " = " + sourceAfter + " but destination " + destCell
                + " in " + destDim.identifier() + " = " + serverAfter + " (was " + serverBefore
                + "). This leg cannot say anything about DELIVERY until the write itself lands.");
        }

        String verdict;
        if (clientBefore.startsWith("(")) {
            verdict = "INCONCLUSIVE — the client did not hold that chunk before the write ("
                + clientBefore + "), so the after-read cannot distinguish a stale block from an"
                + " absent one";
        }
        else if (clientAfter.contains("glass")) {
            verdict = "DELIVERED — the client sees the mirrored block";
        }
        else {
            verdict = "★ NOT DELIVERED — server has the block, the client holds the chunk, and the"
                + " client still shows " + clientAfter;
        }

        SeamlessPortalsConstants.LOGGER.info(
            LOG + "[RS-DELIVERY-TEST] {} => {}\n"
                + "    source  {} in {} = {}\n"
                + "    dest    {} in {}\n"
                + "    server  before={} after={}\n"
                + "    client  before={} after={}\n"
                + "    mirror counters: {}\n"
                + "    delivery probe : {}",
            armName, verdict,
            sourceCell, sourceDim.identifier(), sourceAfter,
            destCell, destDim.identifier(),
            serverBefore, serverAfter,
            clientBefore, clientAfter,
            com.warwa.seamlessportals.passthrough.SeamMirror.counters(),
            com.warwa.seamlessportals.passthrough.SeamDeliveryProbe.counters());
    }

    /**
     * Read a block state on the CLIENT, in a named dimension, WITHOUT pretending an absent chunk is
     * an empty one.
     *
     * <p>Returns a parenthesised reason rather than a block name when the read cannot be trusted, so
     * a caller cannot accidentally compare {@code void_air} against a real block. This is the exact
     * failure the {@link #serverBlockAt} javadoc records: a client read outside render distance
     * reports {@code void_air}, which reads as "the cell is empty" and is really "I have no idea".
     */
    private static String clientBlockAt(
        ClientGameTestContext context, net.minecraft.resources.ResourceKey<Level> dim, BlockPos pos
    ) {
        return context.computeOnClient(mc -> {
            try {
                net.minecraft.client.multiplayer.ClientLevel cl =
                    mc.level != null && dim.equals(mc.level.dimension())
                        ? mc.level
                        : qouteall.imm_ptl.core.ClientWorldLoader.getWorld(dim);
                if (cl == null) return "(no client level for " + dim.identifier() + ")";
                if (!cl.hasChunkAt(pos)) return "(client holds no chunk at " + pos + ")";
                return cl.getBlockState(pos).getBlock().toString();
            }
            catch (Throwable t) {
                return "(client read failed: " + t + ")";
            }
        });
    }

    /**
     * RS PLAYER-PLACE BRACKET GATE — proves the real {@code BlockItem.place} bracket arms, and that a
     * command write does NOT.
     *
     * <p>{@link #writeAsPlayer} declares provenance rather than producing it, which is right for the
     * mirror gates but would let the bracket itself rot undetected. This drives the actual vanilla
     * path — a {@code BlockPlaceContext} built from the live {@code ServerPlayer}, through
     * {@code BlockItem.place} — and asserts BOTH directions in one run:
     * <ul>
     *   <li>a real player placement into a bound seam cell MIRRORS;</li>
     *   <li>the same write issued by {@code /setblock} at the same cell does NOT.</li>
     * </ul>
     * One without the other proves nothing: "it mirrored" is satisfied by a policy that mirrors
     * everything, and "it did not mirror" is satisfied by a mirror that is simply broken.
     */
    private static void rsPlayerPlaceBracketGate(ClientGameTestContext context, BlockPos cell) {
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<String> detail = new AtomicReference<>("");

        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow == null) { failure.set("no overworld"); return; }
            var seam = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cell);
            if (seam == null) { failure.set("no seam binding at " + cell); return; }
            var binding = seam.bindings().stream()
                .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                .findFirst().orElse(null);
            if (binding == null) { failure.set("no mirrorable binding at " + cell); return; }
            ServerLevel dest = server.getLevel(binding.destDim());
            if (dest == null) { failure.set("destination level missing"); return; }
            BlockPos destPos = binding.destPos();
            dest.getChunk(destPos.getX() >> 4, destPos.getZ() >> 4);

            net.minecraft.server.level.ServerPlayer player =
                server.getPlayerList().getPlayers().isEmpty()
                    ? null : server.getPlayerList().getPlayers().get(0);
            if (player == null) { failure.set("no server player to place as"); return; }

            // ---- (1) COMMAND write must NOT mirror ----
            ow.setBlockAndUpdate(cell, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            writeAsPlayer(dest, destPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            ow.setBlockAndUpdate(cell, net.minecraft.world.level.block.Blocks.RAIL.defaultBlockState());
            // The verdict INVERTS on the lever, in the RS-TEARDOWN-TEST discipline: with player-only
            // OFF the policy mirrors every write, so a command write mirroring is then the CORRECT
            // result and its absence is the regression. A fixed expectation here would be actively
            // wrong in whichever configuration it was not written for.
            boolean playerOnlyDisabled = AperturePassthroughLever.DISABLE_SEAM_PLAYER_ONLY;
            var afterCommand = dest.getBlockState(destPos);
            boolean commandMirrored = afterCommand.is(net.minecraft.world.level.block.Blocks.RAIL);
            if (!playerOnlyDisabled && commandMirrored) {
                failure.set("A COMMAND WRITE MIRRORED. /setblock at " + cell + " produced a rail at "
                    + destPos + ", but SeamMirrorPolicy declines non-player writes. Either the write"
                    + " source is misclassified or the policy is not being consulted.");
                return;
            }
            if (playerOnlyDisabled && !commandMirrored) {
                failure.set("*** REGRESSION *** player-only is DISABLED, so a /setblock at " + cell
                    + " should have mirrored to " + destPos + " (pre-2026-07-26 behaviour), but it"
                    + " did not. The disable lever is not restoring the old policy.");
                return;
            }

            // ---- (2) REAL player placement through BlockItem.place MUST mirror ----
            ow.setBlockAndUpdate(cell, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            writeAsPlayer(dest, destPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());

            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.RAIL));
            // Clicking the (air) cell itself: air is replaceable, so BlockPlaceContext.getClickedPos()
            // resolves to this very cell rather than the neighbour (REF BlockPlaceContext.java:47-49).
            net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                Vec3.atCenterOf(cell), net.minecraft.core.Direction.UP, cell, false);
            var useCtx = new net.minecraft.world.item.context.UseOnContext(
                player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            ((net.minecraft.world.item.BlockItem) net.minecraft.world.item.Items.RAIL)
                .place(new net.minecraft.world.item.context.BlockPlaceContext(useCtx));

            var placed = ow.getBlockState(cell);
            var mirrored = dest.getBlockState(destPos);
            detail.set("cell=" + cell + " placed=" + placed.getBlock()
                + " dest=" + destPos + " mirrored=" + mirrored.getBlock());
            if (!placed.is(net.minecraft.world.level.block.Blocks.RAIL)) {
                failure.set("the player placement did not land at all (" + placed.getBlock()
                    + " at " + cell + ") — the fixture is wrong, not the policy");
                return;
            }
            if (!mirrored.is(net.minecraft.world.level.block.Blocks.RAIL)) {
                failure.set("A REAL PLAYER PLACEMENT DID NOT MIRROR. " + cell + " holds a rail but "
                    + destPos + " holds " + mirrored.getBlock() + ". The BlockItem.place bracket"
                    + " (MixinBlockItemPlaceSource) is not arming SeamWriteContext.");
                return;
            }
            // Leave both sides AIR: the frame-break gate runs next on this same bottom-row cell and
            // stages its own state. An evidence leg must never hand the next one a dirty fixture.
            writeAsPlayer(ow, cell, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            writeAsPlayer(dest, destPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        });

        // ★ THE END-TO-END OCCUPANCY ASSERTION — added after FOUR live rounds each died one hop
        // further down the same pipe (claim ordering → server-only claim → no receiver in the
        // flag-ON branch → wrong level resolver). Every prior check stopped at "the server did its
        // part"; this one asserts the CLIENT can answer, which is the thing the user's eyes read.
        // The real place above ran the real pipe: place → mirror CROSS claim → broadcast →
        // receiver → ClientWorldLoader resolve. Nothing here stages occupancy by hand.
        if (failure.get() == null) {
            AtomicReference<String> e2e = new AtomicReference<>(null);
            AtomicReference<String> e2eDetail = new AtomicReference<>("");
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var seam = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cell);
                var b = seam == null ? null : seam.bindings().stream()
                    .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                    .findFirst().orElse(null);
                if (b == null) {
                    e2e.set("no mirrorable binding at " + cell + " for the end-to-end check");
                    return;
                }
                e2eDetail.set(b.destDim().identifier().toString() + "|" + b.destPos().asLong()
                    + "|" + b.destPos());
            });
            if (e2e.get() == null) {
                // ★ ITS OWN PLACEMENT, not the main body's. Step (2) above ends by airing both
                // sides — and the break-release added alongside this gate now correctly CLEARS
                // occupancy on that air write and broadcasts zero. Reading after that cleanup would
                // fail on hygiene working as intended (caught before it ran, for once: the same
                // assume-the-starting-state shape as the five earlier gate defects, spotted in
                // review rather than by a red). So this check drives its own real place.
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    net.minecraft.server.level.ServerPlayer player =
                        server.getPlayerList().getPlayers().isEmpty()
                            ? null : server.getPlayerList().getPlayers().get(0);
                    if (player == null) {
                        e2e.set("no server player for the end-to-end place");
                        return;
                    }
                    player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.RAIL));
                    net.minecraft.world.phys.BlockHitResult hit =
                        new net.minecraft.world.phys.BlockHitResult(
                            Vec3.atCenterOf(cell), net.minecraft.core.Direction.UP, cell, false);
                    ((net.minecraft.world.item.BlockItem) net.minecraft.world.item.Items.RAIL)
                        .place(new net.minecraft.world.item.context.BlockPlaceContext(
                            new net.minecraft.world.item.context.UseOnContext(
                                player, net.minecraft.world.InteractionHand.MAIN_HAND, hit)));
                    if (!ow.getBlockState(cell).is(net.minecraft.world.level.block.Blocks.RAIL)) {
                        e2e.set("the end-to-end place did not land at " + cell
                            + " — fixture fault, not a pipe fault");
                    }
                });
                if (e2e.get() != null) {
                    throw new AssertionError(LOG + "RS PLAYER-PLACE BRACKET GATE (END-TO-END"
                        + " OCCUPANCY) FAILED: " + e2e.get());
                }
                // Give the broadcast a tick to land client-side.
                context.waitTicks(2);
                String[] parts = e2eDetail.get().split("\\|");
                long destKey = Long.parseLong(parts[1]);
                AtomicReference<Boolean> recorded = new AtomicReference<>(false);
                AtomicReference<Boolean> sourceRecorded = new AtomicReference<>(false);
                context.runOnClient(mc -> {
                    var destDim = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.Identifier.parse(parts[0]));
                    recorded.set(com.warwa.seamlessportals.passthrough.SeamOccupancyClient
                        .isRecordedClientSide(destDim, destKey));
                    // NOTE: the gametest's place runs on the SERVER thread only — there is no
                    // client-side prediction here — so the source half reaching mc.level proves the
                    // PACKET path for the source broadcast too, not the prediction path.
                    sourceRecorded.set(mc.level != null
                        && com.warwa.seamlessportals.passthrough.SeamOccupancy
                            .occupancyOf(mc.level, cell) != 0);
                });
                if (!com.warwa.seamlessportals.passthrough.SeamFractional.active()) {
                    // ★ LEVER-AWARE, INVERTING (the master-off matrix row went red demanding a
                    // claim the lever correctly forbids — the seventh assume-the-state defect of
                    // the day, this time caught by the matrix before commit). With the fractional
                    // model OFF nothing may claim: asserting ZERO recorded occupancy proves the
                    // lever cleanly severs the whole pipe, which is worth more than skipping.
                    if (sourceRecorded.get() || recorded.get()) {
                        e2e.set("*** REGRESSION *** the fractional model is DISABLED, yet occupancy"
                            + " was recorded (source=" + sourceRecorded.get() + " crossing="
                            + recorded.get() + "). -PdisableSeamFractional is not severing the"
                            + " claim pipe.");
                    }
                } else if (!sourceRecorded.get()) {
                    e2e.set("SOURCE half not recorded on the CLIENT at " + cell + " after a real"
                        + " BlockItem.place — the client-side claim in the place bracket did not"
                        + " run or did not stick.");
                } else if (!recorded.get()) {
                    e2e.set("CROSSING half not recorded on the CLIENT for dest " + parts[2]
                        + " in " + parts[0] + " after a real place + mirror. The server claimed it"
                        + " (or should have) — so the packet, the receiver, or the level resolver"
                        + " dropped it. This is the exact pipe that failed four live rounds.");
                }
            }
            // Clean up the rail the end-to-end place left, then re-assert emptiness for the next leg.
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                writeAsPlayer(ow, cell, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            });
            if (e2e.get() != null) {
                throw new AssertionError(LOG + "RS PLAYER-PLACE BRACKET GATE (END-TO-END OCCUPANCY)"
                    + " FAILED: " + e2e.get());
            }
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "RS PLAYER-PLACE BRACKET GATE — END-TO-END OCCUPANCY PASS: source half on the"
                    + " client and crossing half reachable client-side ({}).", e2eDetail.get());
        }

        String f = failure.get();
        if (f != null) {
            throw new AssertionError(LOG + "RS PLAYER-PLACE BRACKET GATE FAILED: " + f);
        }
        SeamlessPortalsConstants.LOGGER.info(
            LOG + "RS PLAYER-PLACE BRACKET GATE PASS — {}. {} | counters: {}",
            AperturePassthroughLever.DISABLE_SEAM_PLAYER_ONLY
                ? "player-only DISABLED, so a /setblock mirrored too — the old policy is restored"
                : "a real BlockItem.place mirrored and a /setblock at the same cell did not",
            detail.get(), com.warwa.seamlessportals.passthrough.SeamMirror.counters());
    }

    /**
     * ★ THE FRAGMENT ARITHMETIC GATE — the fractional model's foundation, gated as PURE ARITHMETIC.
     *
     * <p><b>Why this leg exists, and why it needs no portal.</b> The 8-area blast-radius map
     * (2026-08-02) found the live discriminating fixture blocked FOUR independent ways: an
     * independent-offset pair (.3/.21) is not lattice-aligned, so it classifies OFFSET and
     * {@code SeamMirrorPolicy} declines it; the decline nulls {@code destPos} and clears
     * {@code seamContinuous}, so every assertion about it would have no subject;
     * {@code -PdisableSeamExactOnly} re-admits it only through the greatest-overlap ROUNDING the new
     * model exists to replace; and {@code SeamMirror.isPhaseGated} classifies the .3 side COINCIDENT
     * while the .21 side is DISJOINT, so mirroring is one-directional before any fragment arithmetic
     * runs. Waiting for that fixture would leave the model's core unfalsifiable indefinitely.
     *
     * <p><b>The decomposition is pure geometry</b> — no world, no portal, no side effects — so it can
     * be pinned exactly, today, and it is the thing every other front consumes. This leg is the
     * falsifier for the arithmetic; the live fixture, when it exists, is the falsifier for the
     * plumbing. They are different questions and this one is answerable now.
     *
     * <p>Asserts, on {@code FRACTIONAL_DESIGN.md} §2a:
     * <ol>
     *   <li><b>The user's worked example, exactly.</b> Source plane 0.3 keeping {@code [0, 0.3]}
     *       sends 0.7; destination plane 0.79 keeping {@code [0, 0.79]} yields
     *       {@code D0[0.79, 1.0]} = 0.21 and {@code D1[0.0, 0.49]} = 0.49.</li>
     *   <li><b>Conservation</b> — kept + crossed = 1.0 exactly, and the fragments sum to the
     *       crossing thickness, swept across many offset pairs.</li>
     *   <li><b>The ≤2 proof</b> — no offset pair may ever produce a third fragment. This is the
     *       claim the whole model's cost estimate rests on, so it is swept, not spot-checked.</li>
     *   <li><b>Orientation symmetry</b> — POSITIVE and NEGATIVE facings must be mirror images. A
     *       sign error here would be invisible on obsidian pairs (which are symmetric) and wrong on
     *       every wand pair.</li>
     *   <li><b>Fragments stay inside their cell</b> — every interval within {@code [0, 1]}, and
     *       contiguous across the cell boundary when there are two.</li>
     * </ol>
     *
     * <p>⚠ It asserts NOTHING about mirroring, placement or rendering. Those are separate fronts with
     * separate falsifiers; a green run here means the geometry is right, not that the feature works.
     */
    private static void rsFragmentArithmeticGate(ClientGameTestContext context) {
        final double eps = 1.0e-9;
        BlockPos d0 = new BlockPos(10, 70, 10);

        // ---- (1) THE USER'S WORKED EXAMPLE, PINNED EXACTLY ----
        // Source keeps [0, 0.3] => NEGATIVE facing, offset 0.3. Destination keeps [0, 0.79] =>
        // NEGATIVE facing, offset 0.79, so the material runs POSITIVE from 0.79.
        double kept = com.warwa.seamlessportals.passthrough.SeamFractional
            .keptThickness(net.minecraft.core.Direction.NORTH, 0.3);
        double cross = com.warwa.seamlessportals.passthrough.SeamFractional
            .crossingThickness(net.minecraft.core.Direction.NORTH, 0.3);
        if (Math.abs(kept - 0.3) > eps || Math.abs(cross - 0.7) > eps) {
            throw new AssertionError(LOG + "RS FRAGMENT ARITHMETIC GATE FAILED: a NEGATIVE-facing"
                + " plane at 0.3 must keep 0.3 and cross 0.7; got kept=" + kept + " cross=" + cross);
        }
        var frags = com.warwa.seamlessportals.passthrough.SeamFractional
            .decomposeDestination(d0, net.minecraft.core.Direction.NORTH, 0.79, cross);
        if (frags.size() != 2) {
            throw new AssertionError(LOG + "RS FRAGMENT ARITHMETIC GATE FAILED: the worked example"
                + " must produce exactly TWO destination fragments, got " + frags.size()
                + " => " + frags);
        }
        var f0 = frags.get(0);
        var f1 = frags.get(1);
        if (!f0.cell().equals(d0)
            || Math.abs(f0.lo() - 0.79) > 1.0e-6 || Math.abs(f0.hi() - 1.0) > 1.0e-6
            || Math.abs(f0.length() - 0.21) > 1.0e-6) {
            throw new AssertionError(LOG + "RS FRAGMENT ARITHMETIC GATE FAILED: first fragment must"
                + " be D0[0.79, 1.0] (length 0.21); got " + f0);
        }
        BlockPos d1 = d0.relative(net.minecraft.core.Direction.SOUTH);
        if (!f1.cell().equals(d1)
            || Math.abs(f1.lo() - 0.0) > 1.0e-6 || Math.abs(f1.hi() - 0.49) > 1.0e-6) {
            throw new AssertionError(LOG + "RS FRAGMENT ARITHMETIC GATE FAILED: second fragment must"
                + " be D1[0.0, 0.49] in the NEXT cell along the run; got " + f1
                + " (expected cell " + d1 + ")");
        }

        // ---- (2)(3)(4)(5) SWEEP — every offset pair on a fine grid, both orientations ----
        int checked = 0;
        int twoFragmentCases = 0;
        for (net.minecraft.core.Direction srcF : new net.minecraft.core.Direction[]{
            net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
            net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST}) {
            for (int si = 1; si < 100; si++) {
                double sOff = si / 100.0;
                double k = com.warwa.seamlessportals.passthrough.SeamFractional
                    .keptThickness(srcF, sOff);
                double c = com.warwa.seamlessportals.passthrough.SeamFractional
                    .crossingThickness(srcF, sOff);
                // (2) CONSERVATION at the source cut.
                if (Math.abs((k + c) - 1.0) > eps) {
                    throw new AssertionError(LOG + "RS FRAGMENT ARITHMETIC GATE FAILED:"
                        + " kept+crossed must be exactly 1.0; facing=" + srcF + " offset=" + sOff
                        + " kept=" + k + " crossed=" + c);
                }
                // (4) ORIENTATION SYMMETRY — the opposite facing at the mirrored offset must keep
                // the same thickness. A sign error is invisible on symmetric obsidian pairs.
                double mirrored = com.warwa.seamlessportals.passthrough.SeamFractional
                    .keptThickness(srcF.getOpposite(), 1.0 - sOff);
                if (Math.abs(mirrored - k) > eps) {
                    throw new AssertionError(LOG + "RS FRAGMENT ARITHMETIC GATE FAILED: orientation"
                        + " asymmetry — facing=" + srcF + " offset=" + sOff + " keeps " + k
                        + " but " + srcF.getOpposite() + " at " + (1.0 - sOff) + " keeps " + mirrored);
                }
                for (net.minecraft.core.Direction dstF : new net.minecraft.core.Direction[]{
                    net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH}) {
                    for (int di = 1; di < 100; di++) {
                        double dOff = di / 100.0;
                        var fs = com.warwa.seamlessportals.passthrough.SeamFractional
                            .decomposeDestination(d0, dstF, dOff, c);
                        checked++;
                        // (3) THE <=2 PROOF — the model's whole cost estimate rests on this.
                        if (fs.size() > 2) {
                            throw new AssertionError(LOG + "RS FRAGMENT ARITHMETIC GATE FAILED: the"
                                + " decomposition produced " + fs.size() + " fragments (>2) at"
                                + " srcFacing=" + srcF + " srcOffset=" + sOff + " dstFacing=" + dstF
                                + " dstOffset=" + dOff + " => " + fs);
                        }
                        if (fs.size() == 2) {
                            twoFragmentCases++;
                        }
                        // (2) CONSERVATION across the boundary.
                        double total = com.warwa.seamlessportals.passthrough.SeamFractional
                            .totalLength(fs);
                        if (Math.abs(total - c) > 1.0e-9) {
                            throw new AssertionError(LOG + "RS FRAGMENT ARITHMETIC GATE FAILED:"
                                + " material not conserved — crossed " + c + " but fragments hold "
                                + total + " at srcFacing=" + srcF + " srcOffset=" + sOff
                                + " dstFacing=" + dstF + " dstOffset=" + dOff + " => " + fs);
                        }
                        // (5) EVERY INTERVAL INSIDE ITS CELL.
                        for (var fr : fs) {
                            if (fr.lo() < -1.0e-9 || fr.hi() > 1.0 + 1.0e-9 || fr.length() < -1.0e-9) {
                                throw new AssertionError(LOG + "RS FRAGMENT ARITHMETIC GATE FAILED:"
                                    + " fragment escapes its cell — " + fr + " at srcOffset=" + sOff
                                    + " dstFacing=" + dstF + " dstOffset=" + dOff);
                            }
                        }
                        // (5) CONTIGUITY — two fragments must meet exactly at the shared boundary,
                        // or the run has a gap the player would see and walk through.
                        if (fs.size() == 2) {
                            var a = fs.get(0);
                            var b = fs.get(1);
                            boolean meet = (Math.abs(a.hi() - 1.0) < 1.0e-9 && Math.abs(b.lo()) < 1.0e-9)
                                || (Math.abs(a.lo()) < 1.0e-9 && Math.abs(b.hi() - 1.0) < 1.0e-9);
                            if (!meet) {
                                throw new AssertionError(LOG + "RS FRAGMENT ARITHMETIC GATE FAILED:"
                                    + " the two fragments do not meet at the cell boundary — " + a
                                    + " then " + b + " at srcOffset=" + sOff + " dstOffset=" + dOff);
                            }
                        }
                    }
                }
            }
        }
        // COVERAGE — a sweep that never produced a two-fragment case would have proved nothing about
        // the split, which is the entire point of the model.
        if (twoFragmentCases == 0) {
            throw new AssertionError(LOG + "RS FRAGMENT ARITHMETIC GATE VACUOUS: " + checked
                + " decompositions and not one of them split across a cell boundary. The sweep is"
                + " not exercising the case the model exists for.");
        }
        SeamlessPortalsConstants.LOGGER.info(
            LOG + "RS FRAGMENT ARITHMETIC GATE PASS — worked example exact (D0[0.79,1.0]=0.21 +"
                + " D1[0.0,0.49]=0.49); {} decompositions swept, {} of them two-fragment; material"
                + " conserved, <=2 proven, orientations symmetric, all intervals in-cell and"
                + " contiguous.", checked, twoFragmentCases);
    }

    /**
     * ★ Claim an owner half on BOTH sides — the renderer reads the CLIENT's copy.
     *
     * <p>{@link com.warwa.seamlessportals.passthrough.SeamOccupancy} is per-{@code Level}, and a real
     * placement populates both sides for free because {@code BlockItem.place} runs on the client for
     * prediction as well as on the server. A gametest's {@code writeAsPlayer} is a raw
     * {@code setBlock} on the server only, so the client's map stays empty — and the seam clip runs
     * on the render thread against {@code mc.level}. The first fix for the pixel gate claimed
     * server-side alone and still failed with {@code cellsDrawn=48 ownPlaneDraws=0}: every cell drew,
     * none of them clipped, because the client had no owner recorded.
     *
     * <p>⚠ This is the fixture standing in for a mechanism that does not exist yet. Occupancy from
     * OTHER players, or from before you joined, or from before a reload, still has no carrier — the
     * packet and {@code SavedData} recorded as required in {@code FRACTIONAL_DESIGN.md} §3.
     */
    private static void claimOwnerHalfBothSides(
        ClientGameTestContext context, BlockPos cell, byte half
    ) {
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow != null) {
                com.warwa.seamlessportals.passthrough.SeamOccupancy.claim(ow, cell, half);
            }
        });
        context.runOnClient(mc -> {
            if (mc.level != null) {
                com.warwa.seamlessportals.passthrough.SeamOccupancy.claim(mc.level, cell, half);
            }
        });
    }

    /**
     * The collision extent of one cell along one axis, measured THROUGH THE REAL FUNNEL
     * ({@code getBlockCollisions} → {@code BlockCollisions:93} → the 3-arg
     * {@code CollisionContext.getCollisionShape}). Deliberately not the 2-arg cached accessor, which
     * reads the position-blind per-blockstate cache and would report a whole cube no matter what.
     * Returns 0 when the funnel yields nothing.
     */
    private static double seamAxisSpan(
        ServerLevel level, BlockPos cell, net.minecraft.core.Direction.Axis axis
    ) {
        net.minecraft.world.phys.AABB probe =
            new net.minecraft.world.phys.AABB(cell).deflate(1.0E-3);
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (net.minecraft.world.phys.shapes.VoxelShape s : level.getBlockCollisions(null, probe)) {
            if (s.isEmpty()) {
                continue;
            }
            net.minecraft.world.phys.AABB b = s.bounds();
            lo = Math.min(lo, axis.choose(b.minX, b.minY, b.minZ));
            hi = Math.max(hi, axis.choose(b.maxX, b.maxY, b.maxZ));
        }
        return hi < lo ? 0.0 : hi - lo;
    }

    /**
     * ★ THE FRAGMENT BINDING GATE — connects the pure arithmetic to REAL portal geometry.
     *
     * <p>{@link #rsFragmentArithmeticGate} proves the decomposition is self-consistent on synthetic
     * numbers. That is necessary and not sufficient: it would stay green if
     * {@code SeamFractional.planeOffsetOf} derived the offset with the wrong SIGN, because the sweep
     * never asks a real portal anything. This leg closes that gap by asserting against portals the
     * suite actually built.
     *
     * <p><b>The load-bearing assertion is #3.</b> An obsidian frame's plane is mid-block BY
     * CONSTRUCTION ({@code IntBox.getCenterVec} → {@code (l+h+1)/2}), so every COINCIDENT binding
     * must compute {@code srcPlaneOffset ≈ 0.5}. If the {@code 0.5 − d·nA} derivation has its sign
     * backwards, a portal facing one way still yields 0.5 while one facing the other yields −0.5 or
     * 1.5 — invisible to the synthetic sweep, caught here. That is exactly the class of error the
     * javadoc's "worked both ways" note exists for, and this is its falsifier.
     *
     * <p>Asserts: every binding carries a cut; every {@code srcPlaneOffset} lies in {@code (0,1)};
     * COINCIDENT bindings are within ε of the cell centre; and where a destination is known, the
     * decomposition conserves material and produces at most two fragments. Fails VACUOUS if it
     * examined no bindings — a scan-based gate that saw nothing is not a passing gate
     * ({@code getEntitiesOfClass} silently skips unloaded chunks, which has bitten this suite before).
     */
    private static void rsFragmentBindingGate(ClientGameTestContext context) {
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<String> detail = new AtomicReference<>("");

        runOnServer(context, server -> {
            int examined = 0;
            int coincident = 0;
            int withDestination = 0;
            int twoFragment = 0;
            double minOff = Double.POSITIVE_INFINITY;
            double maxOff = Double.NEGATIVE_INFINITY;

            for (ServerLevel level : server.getAllLevels()) {
                var holder = (com.warwa.seamlessportals.passthrough.SeamIndexHolder) level;
                var cells = holder.seamlessportals$seamCells();
                for (var entry : cells.long2ObjectEntrySet()) {
                    BlockPos cell = BlockPos.of(entry.getLongKey());
                    for (var binding : entry.getValue().bindings()) {
                        if (binding == null) {
                            continue;
                        }
                        examined++;
                        var cut = binding.cut();
                        if (cut == null) {
                            failure.set("a binding at " + cell + " in " + level.dimension()
                                + " carries NO CUT. bind() must populate it for every binding — the"
                                + " fractional model cannot divide a block it has no plane for.");
                            return;
                        }
                        double off = cut.srcPlaneOffset();
                        minOff = Math.min(minOff, off);
                        maxOff = Math.max(maxOff, off);
                        if (off < -1.0e-6 || off > 1.0 + 1.0e-6) {
                            failure.set("srcPlaneOffset=" + off + " at " + cell + " is outside [0,1]."
                                + " The plane must fall within the cell it was bound for; a value"
                                + " outside means planeOffsetOf's derivation or the cell choice is"
                                + " wrong. facing=" + binding.srcFacing()
                                + " phase=" + binding.phase());
                            return;
                        }
                        // ★ THE CROSS-CHECK, stated in the MODEL's own terms rather than as a raw
                        // offset range — the two phases make opposite predictions about how much
                        // material crosses, so asserting both directions catches a convention error
                        // that either one alone would let through.
                        //
                        // ⚠ AN EARLIER BUILD OF THIS GATE ASSERTED off IN (0,1) AND WENT RED on a
                        // perfectly correct DISJOINT binding (off = 0.0 at facing=south). DISJOINT
                        // means the plane lies ON the cell boundary — 0.0 is the right answer there,
                        // and the ASSERTION was wrong, not the code. Recorded because that is the
                        // house failure mode: a gate whose expectation is narrower than the truth.
                        double kept = com.warwa.seamlessportals.passthrough.SeamFractional
                            .keptThickness(binding.srcFacing(), off);
                        double cross = com.warwa.seamlessportals.passthrough.SeamFractional
                            .crossingThickness(binding.srcFacing(), off);
                        if (binding.phase()
                            == com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT) {
                            coincident++;
                            // COINCIDENT = the plane BISECTS this cell, so BOTH sides get material.
                            // On an obsidian frame (mid-block by construction) this pins ~0.5, which
                            // is what makes it a sign check: a flipped derivation yields -0.5 or 1.5.
                            if (Math.abs(off - 0.5) > 0.25 + 1.0e-6) {
                                failure.set("a COINCIDENT binding at " + cell + " computed"
                                    + " srcPlaneOffset=" + off + ", but COINCIDENT means the plane is"
                                    + " within 0.25 of the cell CENTRE, so it must lie in"
                                    + " [0.25,0.75]. planeOffsetOf disagrees with phaseOf — one of"
                                    + " them has its sign or its reference point wrong. facing="
                                    + binding.srcFacing());
                                return;
                            }
                            if (kept <= 1.0e-6 || cross <= 1.0e-6) {
                                failure.set("a COINCIDENT binding at " + cell + " keeps " + kept
                                    + " and crosses " + cross + " — one of them is zero, so nothing"
                                    + " actually straddles. COINCIDENT means the plane bisects the"
                                    + " cell and BOTH sides must get material. offset=" + off
                                    + " facing=" + binding.srcFacing());
                                return;
                            }
                        } else {
                            // DISJOINT = the plane lies on this cell's FACE, so nothing straddles:
                            // the source keeps the whole cell and zero crosses. This is the
                            // complementary half of the cross-check, and it is what caught the
                            // wrong assertion above.
                            if (Math.abs(kept - 1.0) > 1.0e-6 || cross > 1.0e-6) {
                                failure.set("a DISJOINT binding at " + cell + " keeps " + kept
                                    + " and crosses " + cross + ", but DISJOINT means the plane is on"
                                    + " the cell FACE — nothing straddles, so the source must keep"
                                    + " the WHOLE cell and cross exactly 0. offset=" + off
                                    + " facing=" + binding.srcFacing() + ". Either the offset"
                                    + " convention or the facing convention is inverted.");
                                return;
                            }
                        }
                        if (cut.hasDestination() && binding.destPos() != null) {
                            withDestination++;
                            var frags = com.warwa.seamlessportals.passthrough.SeamFractional
                                .destinationFragments(binding);
                            if (frags.size() > 2) {
                                failure.set("a real binding at " + cell + " decomposed into "
                                    + frags.size() + " fragments (>2): " + frags);
                                return;
                            }
                            if (frags.size() == 2) {
                                twoFragment++;
                            }
                            double total = com.warwa.seamlessportals.passthrough.SeamFractional
                                .totalLength(frags);
                            if (Math.abs(total - cross) > 1.0e-6) {
                                failure.set("material not conserved on a REAL binding at " + cell
                                    + ": crossing thickness " + cross + " but fragments hold " + total
                                    + " (srcOffset=" + off + " destOffset=" + cut.destPlaneOffset()
                                    + " destFacing=" + cut.destFacing() + ") => " + frags);
                                return;
                            }
                        }
                    }
                }
            }
            // ★ LEVER-AWARE, AND IT INVERTS. Under the master off-switch nothing binds at all, so
            // demanding coverage would be asserting the feature is on while the user turned it off —
            // which is how a gate ends up red for the right reason at the wrong time. The correct
            // expectation there is the OPPOSITE one, and asserting it is worth more than skipping:
            // it proves -PdisableAperturePassthrough really does stop every bind, rather than
            // leaving stale bindings behind for the fragment layer to read.
            if (AperturePassthroughLever.DISABLED) {
                if (examined != 0) {
                    failure.set("*** REGRESSION *** aperture passthrough is DISABLED, so no seam"
                        + " binding should exist anywhere — but " + examined + " were found. The"
                        + " master lever is not stopping bind(), and the fractional model would be"
                        + " reading cuts the user switched off.");
                }
                return;
            }
            // COVERAGE — a scan that examined nothing proves nothing.
            if (examined == 0) {
                failure.set("VACUOUS — no seam bindings were examined in any level. The scan saw"
                    + " nothing, so every assertion above was skipped.");
                return;
            }
            if (coincident == 0) {
                failure.set("VACUOUS — " + examined + " bindings examined but NONE were COINCIDENT,"
                    + " so the sign check against real mid-block obsidian geometry never ran. That"
                    + " check is the only reason this leg exists.");
                return;
            }
            detail.set("examined=" + examined + " coincident=" + coincident
                + " withDestination=" + withDestination + " twoFragment=" + twoFragment
                + " srcPlaneOffset range=[" + String.format("%.4f", minOff)
                + ", " + String.format("%.4f", maxOff) + "]");
        });

        String f = failure.get();
        if (f != null) {
            throw new AssertionError(LOG + "RS FRAGMENT BINDING GATE FAILED: " + f);
        }
        SeamlessPortalsConstants.LOGGER.info(
            LOG + "RS FRAGMENT BINDING GATE PASS — {}. {}",
            AperturePassthroughLever.DISABLED
                ? "INVERSION: passthrough disabled and NO binding exists anywhere, so the master"
                    + " lever cleanly stops every bind"
                : "the cut is derived from real portal geometry and agrees with phaseOf",
            detail.get());
    }

    /**
     * ★ THE FRACTIONAL SEAM COLLISION GATE — the first gate in this suite to assert collision at a
     * seam at all, and the first thing built for the fractional model (user decision C, 2026-08-02:
     * GATE → STORAGE → COLLISION → render flip LAST). Spec: {@code migration/FRACTIONAL_DESIGN.md} §7.
     *
     * <p><b>It asserts TODAY'S WHOLE-CUBE TRUTH, on purpose.</b> Nothing in the suite has ever
     * asserted collision, support or conduction at a seam cell, so the fractional model would
     * otherwise ship with zero coverage on the exact three properties it changes. This leg pins all
     * three now, so that when the model lands the SAME leg inverts — which is what makes it a proof
     * rather than a hope.
     *
     * <p><b>Lever-aware from its first run, without a hardcoded expectation.</b> The expectation is
     * read from {@link com.warwa.seamlessportals.passthrough.SeamFractional#active()}, never
     * hardcoded, and the verdict line REPORTS which branch ran
     * ({@code SeamFractional.describe()}). Until front 3 flips {@code CUT_IMPLEMENTED} that branch
     * is always the whole-cube one — said out loud rather than passing vacuously, because a gate
     * that cannot tell "the fix works" from "the defect never existed here" is not a gate.
     *
     * <p>Three arms, because collision at a seam is not one property (§4 of the spec):
     * <ol>
     *   <li><b>MOVEMENT</b> — tier (i). Measures the block's collision extent along the seam axis
     *       THROUGH THE REAL FUNNEL ({@code getBlockCollisions} → {@code BlockCollisions:93} →
     *       {@code CollisionContext.getCollisionShape}, the one call that HAS the position), not
     *       through the 2-arg cached accessor. Whole cube ⇒ span 1.0; cut ⇒ span
     *       {@code keptThickness}.</li>
     *   <li><b>SUPPORT / RAIL SURVIVAL</b> — tier (ii). A rail on a seam-cell support block. This is
     *       the (b) use case, and the model's sharpest hazard: {@code BaseRailBlock.canSurvive} is
     *       {@code canSupportRigidBlock(pos.below())}, which reaches the CACHED {@code faceSturdy[]}.
     *       Also records the contested {@code getFaceShape(UP)} geometry (see below).</li>
     *   <li><b>SUFFOCATION / CONDUCTION</b> — tier (ii), the predicate seam. Uses
     *       {@code Blocks.SOUL_SAND} as well as stone, because soul sand is vanilla's own proof that
     *       the cached full-block flag and the conduction predicate can DISAGREE: its collision
     *       shape is 14/16 (so {@code isCollisionShapeFullBlock} is false) while
     *       {@code isRedstoneConductor} is forced true by an overridden {@code StatePredicate}. A
     *       side table that intercepts only the cached flag would silently miss it and every one of
     *       the ~34 vanilla blocks shaped like it. Spec §4a.</li>
     * </ol>
     *
     * <p>⚠ <b>It also settles a question this project deliberately refused to settle by reading.</b>
     * Two independent traces of {@code VoxelShape.calculateFace} → {@code SliceShape} say
     * {@code getFaceShape(UP)} of a half-height box is {@code Shapes.empty()} — which would mean a
     * bottom slab supports nothing, contradicting observed game behaviour. Arm 2 asks the engine
     * directly and logs the answer. No design decision rests on resolving it by argument.
     *
     * <p>Runs on the BOTTOM opening row for the same reason {@link #rsPlayerPlaceBracketGate} and
     * {@link #rsFrameBreakGate} do: a rail at mid-height sits on another aperture cell holding the
     * noCollision placeholder, so it would pop for a fixture reason and the gate would blame the
     * model. Restores both cells to air in its {@code finally} — {@code rsFrameBreakGate} runs next
     * on this very cell and must not inherit a dirty fixture.
     */
    private static void rsSeamCollisionGate(ClientGameTestContext context, BlockPos cell) {
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<String> detail = new AtomicReference<>("");

        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow == null) { failure.set("no overworld"); return; }
            BlockPos above = cell.above();
            try {
                // ---- PRECONDITIONS. Assert the FIXTURE, not just the outcome: a leg that runs on a
                // cell with no mirrorable COINCIDENT binding is measuring ordinary terrain and would
                // report a confident, meaningless PASS.
                var seam = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cell);
                if (seam == null) { failure.set("no seam binding at " + cell); return; }
                var binding = seam.bindings().stream()
                    .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                    .filter(b -> b.phase()
                        == com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT)
                    .findFirst().orElse(null);
                if (binding == null) {
                    failure.set("no mirror-admitted COINCIDENT binding at " + cell
                        + " — the fixture is wrong, not the model. Bindings: " + seam.bindings());
                    return;
                }
                if (!com.warwa.seamlessportals.passthrough.SeamFractional.cuts(ow, cell)) {
                    failure.set("SeamFractional.cuts() says this cell is not a cut candidate at "
                        + cell + ", but the binding above is mirror-admitted and COINCIDENT."
                        + " The predicate and the fixture disagree.");
                    return;
                }
                net.minecraft.core.Direction.Axis axis = binding.srcFacing().getAxis();
                boolean cutExpected =
                    com.warwa.seamlessportals.passthrough.SeamFractional.collisionActive();
                // ★ THE SOUL-SAND RULE (user decision 2026-08-02, superseding the original
                // decision B this gate was first written for). Support, rail survival and redstone
                // conduction report WHOLE whether or not the cut is active — the two halves are one
                // block seen from two sides, and vanilla already ships exactly this shape in
                // SOUL_SAND (partial collision, getBlockSupportShape overridden back to a full
                // cube, both predicates forced true). So these expectations are CONSTANT across
                // every lever position, and that is the assertion: turning the cut on must NOT
                // change them. The earlier build tied them to supportActive() and would have gone
                // green while rails popped.
                boolean supportCutExpected = false;
                // Suffocation is the one deliberate divergence from soul sand: it follows THE CUT,
                // because the removed part is a doorway the player walks through and reporting
                // whole would damage them for using the portal.
                boolean suffocationSuppressed = cutExpected;

                // ================= ARM 1 — MOVEMENT (tier i, through the real funnel) =============
                writeAsPlayer(ow, above, Blocks.AIR.defaultBlockState());
                writeAsPlayer(ow, cell, Blocks.STONE.defaultBlockState());
                if (!ow.getBlockState(cell).is(Blocks.STONE)) {
                    failure.set("the stone staging did not land at " + cell + " (found "
                        + ow.getBlockState(cell).getBlock() + ") — fixture fault, not a model fault");
                    return;
                }
                // ★ ESTABLISH THE STARTING STATE ONCE, HERE. rsPlayerPlaceBracketGate drives a REAL
                // BlockItem.place on this same cell immediately before this leg, so an owner half is
                // already recorded — twice now this gate has gone red measuring that leftover
                // instead of its own fixture. This leg stages its block with a raw setBlock, which
                // records no placement, so "no owner" IS the correct state for it; make that true
                // rather than assume it. (Both reds were the same shape: assuming a precondition
                // instead of creating it.)
                com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(ow, cell);
                // Deflated to this cell alone, so the measured span is THIS block's and not a
                // neighbour's. getBlockCollisions is the entity-movement funnel: it routes through
                // BlockCollisions -> CollisionContext.getCollisionShape(state, getter, POS), the
                // 3-arg form that has no cache branch. Deliberately NOT the 2-arg accessor.
                net.minecraft.world.phys.AABB probe =
                    new net.minecraft.world.phys.AABB(cell).deflate(1.0E-3);
                double lo = Double.POSITIVE_INFINITY;
                double hi = Double.NEGATIVE_INFINITY;
                int shapesSeen = 0;
                for (net.minecraft.world.phys.shapes.VoxelShape s
                        : ow.getBlockCollisions(null, probe)) {
                    if (s.isEmpty()) continue;
                    shapesSeen++;
                    net.minecraft.world.phys.AABB b = s.bounds();
                    lo = Math.min(lo, axis.choose(b.minX, b.minY, b.minZ));
                    hi = Math.max(hi, axis.choose(b.maxX, b.maxY, b.maxZ));
                }
                // COVERAGE, not just result: zero shapes means the funnel never saw our block and
                // every assertion below would be vacuous.
                if (shapesSeen == 0) {
                    failure.set("ARM 1 VACUOUS — the collision funnel returned no shape for a stone"
                        + " block at " + cell + ". The gate measured nothing.");
                    return;
                }
                double span = hi - lo;

                // ★ ARM 1b — THE OWNER HALF (FRACTIONAL_DESIGN.md §2a.0). The live round proved the
                // cut cannot be derived from the binding: an obsidian portal is bi-faced, so the
                // cell has two bindings with opposite facings and picking one gave the WRONG half.
                // The half is recorded at placement from the crosshair hit point. This arm drives
                // that store directly through its three states, which is the only way to falsify
                // the rule without a real click.
                if (cutExpected && binding.cut() != null) {
                    double off2 = binding.cut().srcPlaneOffset();
                    // ★ ESTABLISH THE PRECONDITION, DO NOT ASSUME IT. rsPlayerPlaceBracketGate runs
                    // a REAL BlockItem.place on this very cell immediately before this leg, so an
                    // owner half is already recorded here — the first build of this arm read the
                    // span from arm 1 and asserted "no owner", then went red at 0.5 because there
                    // WAS one. (That red is also the proof the placement capture works.) Clear the
                    // store and re-measure so each state below is the one being tested.
                    com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(ow, cell);
                    double unowned = seamAxisSpan(ow, cell, axis);
                    // (i) NO OWNER — a pre-existing block must stay WHOLE. Guessing a half here is
                    // exactly what the live defect looked like.
                    if (Math.abs(unowned - 1.0) > 1.0e-4) {
                        failure.set("ARM 1b — with NO owner half recorded, the cell must stay WHOLE"
                            + " (span 1.0), but measured " + unowned + ". A block with no recorded"
                            + " placement side must not be cut on a guess.");
                        return;
                    }
                    // (ii) ONE HALF owned — cut to exactly that half, and to the OTHER one when the
                    // other bit is set. Asserting both directions is what catches an inverted
                    // convention, which is the defect that shipped.
                    for (byte owned : new byte[]{
                        com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_NEGATIVE,
                        com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE}) {
                        com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(ow, cell);
                        com.warwa.seamlessportals.passthrough.SeamOccupancy.claim(ow, cell, owned);
                        boolean pos2 = owned
                            == com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE;
                        double want = pos2 ? 1.0 - off2 : off2;
                        double got = seamAxisSpan(ow, cell, axis);
                        if (Math.abs(got - want) > 1.0e-4) {
                            failure.set("ARM 1b — owning the "
                                + (pos2 ? "POSITIVE" : "NEGATIVE") + " " + axis + " half must keep "
                                + want + " (planeOffset=" + off2 + "), but the collision funnel"
                                + " measured " + got + ". The owner-half convention is inverted or"
                                + " ignored — this is the live 2026-08-02 defect.");
                            return;
                        }
                    }
                    // (iii) BOTH halves owned — two objects meeting at the plane, materially whole.
                    com.warwa.seamlessportals.passthrough.SeamOccupancy.claim(ow, cell,
                        com.warwa.seamlessportals.passthrough.SeamOccupancy.BOTH);
                    double bothSpan = seamAxisSpan(ow, cell, axis);
                    if (Math.abs(bothSpan - 1.0) > 1.0e-4) {
                        failure.set("ARM 1b — with BOTH halves owned the cell is materially whole"
                            + " again (two objects meeting at the plane), so the span must be 1.0;"
                            + " measured " + bothSpan);
                        return;
                    }
                    com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(ow, cell);
                }
                // ★ A block staged with a raw setBlock has NO recorded owner half, and the model's
                // safe answer for that is WHOLE — so the baseline expectation is 1.0 in every lever
                // position. Arm 1b below drives the occupancy store through its three states to
                // assert the cut itself. (An earlier build expected keptThickness here and derived
                // it from the BINDING, which is precisely the bug the live round found: a bi-faced
                // portal's binding cannot say which half is ours.)
                double expectedSpan = 1.0;
                if (Math.abs(span - expectedSpan) > 1.0E-4) {
                    failure.set("ARM 1 (MOVEMENT) — collision span along " + axis + " at " + cell
                        + " is " + span + ", expected " + expectedSpan + " ("
                        + (cutExpected ? "the cut half" : "a whole cube")
                        + "). shapes=" + shapesSeen + " lo=" + lo + " hi=" + hi);
                    return;
                }

                // ================= ARM 2 — SUPPORT / RAIL SURVIVAL (tier ii) ======================
                // The direct predicate first, then the gameplay outcome it decides. Asserting only
                // the rail would not distinguish "support reports whole" from "the rail never got
                // placed"; asserting only the predicate stops one step short of what the user sees.
                var stone = ow.getBlockState(cell);
                boolean rigid = stone.isFaceSturdy(ow, cell, net.minecraft.core.Direction.UP,
                    net.minecraft.world.level.block.SupportType.RIGID);
                if (rigid == supportCutExpected) {
                    failure.set("ARM 2 (SUPPORT) — SupportType.RIGID on the UP face of the seam cell "
                        + cell + " reported " + rigid + "; expected " + (!supportCutExpected)
                        + ". RIGID requires the 2px PERIMETER frame, so a cut at any fraction < 1"
                        + " must fail it and a whole cube must pass it.");
                    return;
                }
                // ⚠ THE PREDICATE IS THE LOAD-BEARING ASSERTION, and a raw write is NOT.
                // Blocks.RAIL is the curvable rail, so BaseRailBlock.isStraight is FALSE — which
                // means onPlace -> updateState does NOT call neighborChanged (BaseRailBlock:70-76),
                // and nothing consults canSurvive at write time. A setBlock'd rail therefore sits
                // there until some LATER neighbour update pops it. Reading "is a rail still at this
                // position" immediately after the write would pass in BOTH lever directions once
                // the model lands — the vacuous-pass family this project has been bitten by five
                // times. So: assert canSurvive directly (deterministic, position-live, and exactly
                // what shouldBeRemoved consults), THEN drive a real neighbour update and assert the
                // visible outcome as well.
                boolean railCanSurvive = Blocks.RAIL.defaultBlockState().canSurvive(ow, above);
                if (railCanSurvive == supportCutExpected) {
                    failure.set("ARM 2 (RAIL canSurvive) — Blocks.RAIL.canSurvive at " + above
                        + " reported " + railCanSurvive + ", expected " + (!supportCutExpected)
                        + ". BaseRailBlock.canSurvive is canSupportRigidBlock(pos.below()), so it"
                        + " must track the RIGID verdict on " + cell + " (which was " + rigid + ").");
                    return;
                }
                writeAsPlayer(ow, above, Blocks.RAIL.defaultBlockState());
                // The update the raw write does not perform. Without this the read below is timing-
                // dependent rather than a verdict.
                ow.updateNeighborsAt(cell, ow.getBlockState(cell).getBlock(), null);
                boolean railSurvived = ow.getBlockState(above).is(Blocks.RAIL);
                if (railSurvived == supportCutExpected) {
                    failure.set("ARM 2 (RAIL SURVIVAL) — rail at " + above + " on a seam-cell support"
                        + " block: survived=" + railSurvived + " after a real neighbour update,"
                        + " expected " + (!supportCutExpected) + " to match canSurvive="
                        + railCanSurvive + ". Found " + ow.getBlockState(above).getBlock());
                    return;
                }

                // ⚠ REPORT-ONLY, and the reason it is here: two independent source traces of
                // VoxelShape.calculateFace -> SliceShape say getFaceShape(UP) of a half-height box
                // is Shapes.empty(), which would mean a bottom slab supports nothing — contradicting
                // observed game behaviour. Ask the engine instead of arguing about it.
                // ⚠ AND IT IS MEASURED AT THE OUTCOME LEVEL, NOT THE PREDICATE LEVEL. The first
                // build of this probe read isFaceSturdy only and got CENTER=false for a bottom slab
                // — which would mean a torch cannot be placed on a slab, contradicting observable
                // game behaviour. A predicate reading that fails a sanity check is the probe
                // confessing, not the engine: "assert the outcome the user can see, not the request
                // your code issued". So place a REAL slab and put real blocks on it.
                var slab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
                boolean slabRigid = slab.isFaceSturdy(ow, cell, net.minecraft.core.Direction.UP,
                    net.minecraft.world.level.block.SupportType.RIGID);
                boolean slabCenter = slab.isFaceSturdy(ow, cell, net.minecraft.core.Direction.UP,
                    net.minecraft.world.level.block.SupportType.CENTER);
                // ⚠ SCRATCH COLUMN — LATERALLY clear of the aperture, never BELOW it. The first
                // build used cell.offset(0,-6,0), which for this fixture is y=-65: one block under
                // the overworld floor, so setBlock silently did nothing and the "outcome" was read
                // against empty space. It still PASSED, because the coverage was logged and not
                // asserted. Hence both changes here: a position inside the build range, and a hard
                // assertion that the slab actually landed.
                BlockPos slabAt = cell.offset(6, 2, 6);
                BlockPos onSlab = slabAt.above();
                var priorSlabAt = ow.getBlockState(slabAt);
                var priorOnSlab = ow.getBlockState(onSlab);
                boolean slabRailSurvives;
                boolean slabTorchSurvives;
                boolean slabPlaced;
                try {
                    writeAsPlayer(ow, onSlab, Blocks.AIR.defaultBlockState());
                    writeAsPlayer(ow, slabAt, slab);
                    slabPlaced = ow.getBlockState(slabAt).is(Blocks.SMOOTH_STONE_SLAB);
                    slabRailSurvives = Blocks.RAIL.defaultBlockState().canSurvive(ow, onSlab);
                    slabTorchSurvives = Blocks.TORCH.defaultBlockState().canSurvive(ow, onSlab);
                } finally {
                    // Restore what was there, not blanket AIR — this column is outside the fixture
                    // and punching a hole in it would perturb a later leg for no reason.
                    writeAsPlayer(ow, onSlab, priorOnSlab);
                    writeAsPlayer(ow, slabAt, priorSlabAt);
                }
                if (!slabPlaced) {
                    failure.set("THE SLAB PROBE MEASURED NOTHING — no slab landed at " + slabAt
                        + " (found " + ow.getBlockState(slabAt).getBlock() + ", world floor is "
                        + ow.getMinY() + "). The §1.3 outcome reading would be vacuous, and a"
                        + " vacuous reading of a contested question is worse than no reading.");
                    return;
                }
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "[SEAM FRAC] CONTESTED GEOMETRY (FRACTIONAL_DESIGN.md §1.3) — bottom slab"
                        + " at {}: predicate isFaceSturdy RIGID={} CENTER={}; OUTCOME on a real"
                        + " placed slab (placed={}) rail.canSurvive={} torch.canSurvive={}."
                        + " If the OUTCOMES are true while the predicates are false, the predicate"
                        + " reading is the wrong instrument and the half-height-box trace does NOT"
                        + " describe what the game does.",
                    slabAt, slabRigid, slabCenter, slabPlaced, slabRailSurvives, slabTorchSurvives);

                // ============ ARM 3 — SUFFOCATION / CONDUCTION (tier ii, predicate seam) =========
                writeAsPlayer(ow, above, Blocks.AIR.defaultBlockState());
                boolean stoneSuffocates = stone.isSuffocating(ow, cell);
                boolean stoneConducts = stone.isRedstoneConductor(ow, cell);
                // CONDUCTION follows the soul-sand rule: always whole, in every lever position.
                if (!stoneConducts) {
                    failure.set("ARM 3 (CONDUCTION) — stone at seam cell " + cell + " reported"
                        + " isRedstoneConductor=false. Under the soul-sand rule a seam block"
                        + " conducts as a WHOLE block regardless of the cut, because the two halves"
                        + " are one block seen from two sides. cutActive=" + cutExpected);
                    return;
                }
                // SUFFOCATION is the deliberate exception and INVERTS with the cut.
                if (stoneSuffocates == suffocationSuppressed) {
                    failure.set("ARM 3 (SUFFOCATION) — stone at seam cell " + cell
                        + " reported isSuffocating=" + stoneSuffocates + ", expected "
                        + (!suffocationSuppressed) + ". Suffocation follows THE CUT: with the model"
                        + " active a player standing in the removed part is in a doorway, not in a"
                        + " wall, and must not take damage. cutActive=" + cutExpected);
                    return;
                }
                // THE PREDICATE-SEAM WITNESS. This is a VANILLA invariant, so it is a precondition:
                // if soul sand ever stops disagreeing with its own cached full-block flag, this arm
                // has stopped covering the ~34 blocks that override the predicate and must say so.
                writeAsPlayer(ow, cell, Blocks.SOUL_SAND.defaultBlockState());
                var soul = ow.getBlockState(cell);
                if (!soul.is(Blocks.SOUL_SAND)) {
                    failure.set("the soul sand staging did not land at " + cell + " (found "
                        + soul.getBlock() + ") — fixture fault");
                    return;
                }
                boolean soulFullBlock = soul.isCollisionShapeFullBlock(ow, cell);
                boolean soulConducts = soul.isRedstoneConductor(ow, cell);
                if (soulFullBlock) {
                    failure.set("ARM 3 COVERAGE LOST — soul sand reported isCollisionShapeFullBlock="
                        + "true. Its collision shape is column(16,0,14), so this arm no longer covers"
                        + " the case where the cached flag and the conduction predicate disagree.");
                    return;
                }
                if (soulConducts == supportCutExpected) {
                    failure.set("ARM 3 (PREDICATE SEAM) — soul sand at " + cell
                        + " isRedstoneConductor=" + soulConducts + ", expected "
                        + (!supportCutExpected) + ". Soul sand overrides the predicate to always;"
                        + " a side table that intercepts only isCollisionShapeFullBlock would miss"
                        + " this and every one of the ~34 vanilla blocks shaped like it.");
                    return;
                }

                detail.set("cell=" + cell + " axis=" + axis + " facing=" + binding.srcFacing()
                    + " span=" + span + "/" + expectedSpan + " shapes=" + shapesSeen
                    + " cutActive=" + cutExpected + " keptExpected=" + expectedSpan
                    + " rigid=" + rigid + " railCanSurvive=" + railCanSurvive
                    + " railSurvived=" + railSurvived
                    + " stone[suffocate=" + stoneSuffocates + " conduct=" + stoneConducts + "]"
                    + " soulSand[fullBlock=" + soulFullBlock + " conduct=" + soulConducts + "]"
                    + " slab[RIGID=" + slabRigid + " CENTER=" + slabCenter + "]");
            } finally {
                // MANDATORY — rsFrameBreakGate runs next on this very cell and stages its own state.
                try {
                    writeAsPlayer(ow, cell.above(), Blocks.AIR.defaultBlockState());
                    writeAsPlayer(ow, cell, Blocks.AIR.defaultBlockState());
                } catch (Throwable t) {
                    SeamlessPortalsConstants.LOGGER.warn(
                        LOG + "[SEAM FRAC] CLEANUP FAILED — the frame-break gate may see a dirty"
                            + " fixture at " + cell, t);
                }
            }
        });

        String f = failure.get();
        if (f != null) {
            throw new AssertionError(LOG + "RS SEAM COLLISION GATE FAILED: " + f);
        }
        SeamlessPortalsConstants.LOGGER.info(
            LOG + "RS SEAM COLLISION GATE PASS — model is {}. {}",
            com.warwa.seamlessportals.passthrough.SeamFractional.describe(), detail.get());
    }

    /**
     * ★ THE OBJECT BREAK-BOTH GATE — the live round 10 defect, reproduced headlessly from its own
     * probe trail (23:25:40: PROMOTE fired at the broken cell, but NO "cleared counterpart" line
     * and zero events at the origin cell — object 1's origin half survived the breaking of its
     * crossing half, and every later placement was adjudicated against diverged object records).
     *
     * <p>Stages the full two-object state exactly as the placement paths build it, then breaks the
     * CROSSING side's primary with a real bracketed break ({@code writeAsPlayer} arms the same
     * PLAYER_BREAK context as {@code ServerPlayerGameMode}), and asserts the whole of user decision
     * §2a.0: the broken object's OTHER half clears, and EACH side promotes its own surviving
     * secondary. Lever-aware trivially: under {@code -PdisableSeamFractional} there are no owner
     * halves and the leg self-skips with a log line rather than passing vacuously.
     */
    private static void rsObjectBreakBothGate(
        ClientGameTestContext context, BlockPos cell, String tag
    ) {
        if (!com.warwa.seamlessportals.passthrough.SeamFractional.active()) {
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS OBJECT BREAK-BOTH GATE [" + tag
                + "] SKIPPED — the fractional model is disabled, so owner halves do not exist in"
                + " this configuration.");
            return;
        }
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<String> detail = new AtomicReference<>("");

        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow == null) { failure.set("no overworld"); return; }
            var seam = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cell);
            var binding = seam == null ? null : seam.bindings().stream()
                .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                .filter(b -> b.cut() != null)
                .findFirst().orElse(null);
            if (binding == null) { failure.set("no mirrorable binding with a cut at " + cell); return; }
            ServerLevel dest = server.getLevel(binding.destDim());
            if (dest == null) { failure.set("destination level missing"); return; }
            BlockPos destPos = binding.destPos();
            dest.getChunk(destPos.getX() >> 4, destPos.getZ() >> 4);
            var axis = binding.srcFacing().getAxis();
            try {
                // ---- STAGE object 1 exactly as placement builds it: claim the owner half FIRST
                // (the HEAD-claim ordering), then the bracketed write, which mirrors and claims the
                // crossing half.
                writeAsPlayer(ow, cell, Blocks.AIR.defaultBlockState());
                writeAsPlayer(dest, destPos, Blocks.AIR.defaultBlockState());
                com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(ow, cell);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, cell, null);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(dest, destPos);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(dest, destPos, null);

                byte srcHalf = com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE;
                com.warwa.seamlessportals.passthrough.SeamOccupancy.claim(ow, cell, srcHalf);
                writeAsPlayer(ow, cell, Blocks.STONE.defaultBlockState());
                byte destOwned = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .occupancyOf(dest, destPos);
                // PRECONDITION, not assumption: the mirror wrote and the crossing half was claimed.
                if (!dest.getBlockState(destPos).is(Blocks.STONE)
                    || (destOwned != com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE
                        && destOwned != com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_NEGATIVE)) {
                    failure.set("STAGING — object 1 did not mirror+claim: destState="
                        + dest.getBlockState(destPos).getBlock() + " destOwned=" + destOwned);
                    return;
                }
                // ---- STAGE object 2 as the secondary path builds it (both sides + complements).
                byte secSrcHalf = com.warwa.seamlessportals.passthrough.SeamOccupancy.otherHalf(srcHalf);
                byte secDestHalf = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .otherHalf(destOwned);
                var gold = Blocks.GOLD_BLOCK.defaultBlockState();
                com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, cell,
                    new com.warwa.seamlessportals.passthrough.SeamOccupancy.Secondary(gold, secSrcHalf));
                com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(dest, destPos,
                    new com.warwa.seamlessportals.passthrough.SeamOccupancy.Secondary(
                        gold.rotate(binding.stateRotation()), secDestHalf));

                // ---- THE BREAK, on the CROSSING side — the direction the live round proved broken.
                writeAsPlayer(dest, destPos, Blocks.AIR.defaultBlockState());

                var destAfter = dest.getBlockState(destPos);
                var srcAfter = ow.getBlockState(cell);
                byte srcOwnedAfter = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .occupancyOf(ow, cell);
                var srcSecAfter = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .secondaryOf(ow, cell);
                detail.set("destAfter=" + destAfter.getBlock() + " srcAfter=" + srcAfter.getBlock()
                    + " srcOwnedAfter=" + srcOwnedAfter + " srcSecAfter="
                    + (srcSecAfter == null ? "null" : srcSecAfter.state().getBlock()));
                // (1) the broken cell promotes its own secondary
                if (!destAfter.is(Blocks.GOLD_BLOCK)) {
                    failure.set("the BROKEN cell did not promote its surviving secondary — expected"
                        + " gold block at " + destPos + ", found " + destAfter.getBlock());
                    return;
                }
                // (2) ★ THE DEFECT: the object's ORIGIN half must clear, and the origin cell must
                // promote ITS secondary — stone gone, pink standing.
                if (srcAfter.is(Blocks.STONE)) {
                    failure.set("*** THE LIVE ROUND 10 DEFECT *** breaking the CROSSING half left"
                        + " the ORIGIN half standing — stone survives at " + cell + " after its"
                        + " counterpart at " + destPos + " was broken. Break-both is"
                        + " direction-dependent.");
                    return;
                }
                if (!srcAfter.is(Blocks.GOLD_BLOCK)) {
                    failure.set("the origin cleared but did not promote its secondary — expected"
                        + " gold block at " + cell + ", found " + srcAfter.getBlock());
                    return;
                }
                if (srcSecAfter != null) {
                    failure.set("the origin promoted but its secondary record was not consumed"
                        + " (still " + srcSecAfter.state().getBlock() + ")");
                    return;
                }
            } finally {
                // Restore for the frame-break gate that runs next on this cell.
                writeAsPlayer(ow, cell, Blocks.AIR.defaultBlockState());
                writeAsPlayer(dest, destPos, Blocks.AIR.defaultBlockState());
                com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(ow, cell);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, cell, null);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(dest, destPos);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(dest, destPos, null);
            }
        });

        String f = failure.get();
        if (f != null) {
            throw new AssertionError(LOG + "RS OBJECT BREAK-BOTH GATE [" + tag + "] FAILED: " + f);
        }
        SeamlessPortalsConstants.LOGGER.info(
            LOG + "RS OBJECT BREAK-BOTH GATE [" + tag + "] PASS — breaking the crossing half"
                + " cleared the origin and both sides promoted their secondaries. {}", detail.get());
    }

    /** Far-pair relog fixture, set by {@link #rsRelogFarStage}, read after the world reopen. */
    private static BlockPos relogFarCell = null;
    private static BlockPos relogFarDest = null;

    /**
     * ★ FAR-PAIR RELOG STAGE — the user's decoded live topology: a same-dim bi-way bi-faced pair
     * whose destination sits at z = 7,000,000, staged with a TEMPORARY forceload that is removed
     * before the close (the live world has none), the player left standing at the near portal
     * (where the live user logs out). After reopen the far side comes back only if IP's own
     * chunk-ticket revival does its job — which is exactly the variable every other relog fixture
     * held constant by forceloading.
     */
    private static void rsRelogFarStage(ClientGameTestContext context) {
        if (!com.warwa.seamlessportals.passthrough.SeamFractional.active()
            || AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SEAM_MIRROR) {
            return;
        }
        final int cx = 9600, cy = 100, cz = 9600;
        final int fz = 7000000;
        final Vec3 destCenter = new Vec3(cx + 0.5, cy + 1.0, fz + 0.5);
        final BlockPos cellS = new BlockPos(cx, cy, cz);
        // Staging recipe = the same-dim break gate's, coordinates swapped; far pad included.
        runCommands(context, List.of(
            "forceload add " + (cx - 16) + " " + (cz - 16) + " " + (cx + 16) + " " + (cz + 16),
            "forceload add " + (cx - 16) + " " + (fz - 16) + " " + (cx + 16) + " " + (fz + 16),
            "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz - 4) + " "
                + (cx + 6) + " " + (cy + 5) + " " + (cz + 6) + " minecraft:air",
            "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz + 2) + " "
                + (cx + 6) + " " + (cy - 1) + " " + (cz + 6) + " minecraft:stone",
            "setblock " + cx + " " + (cy - 1) + " " + cz + " minecraft:stone",
            "fill " + (cx - 6) + " " + (cy - 1) + " " + (fz - 6) + " "
                + (cx + 6) + " " + (cy - 1) + " " + (fz + 6) + " minecraft:stone",
            "fill " + (cx - 6) + " " + cy + " " + (fz - 6) + " "
                + (cx + 6) + " " + (cy + 5) + " " + (fz + 6) + " minecraft:air",
            "tp @p " + (cx + 0.5) + " " + cy + " " + (cz + 3.5) + " 180 27"
        ));
        context.waitTicks(10);
        runCommands(context, List.of(
            "execute as @p at @p run portal make_portal 1 2 minecraft:overworld "
                + destCenter.x + " " + destCenter.y + " " + destCenter.z));
        context.waitTicks(10);
        runCommands(context, List.of(
            "tp @p " + (cx + 0.5) + " " + cy + " " + (cz + 3.5) + " 180 5",
            "execute as @p at @p run portal complete_bi_way_bi_faced_portal"));
        context.waitTicks(20);
        AtomicReference<BlockPos> destRef = new AtomicReference<>(null);
        for (int attempt = 0; attempt < 30 && destRef.get() == null; attempt++) {
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                if (cell == null) {
                    return;
                }
                for (var b : cell.bindings()) {
                    if (b.isMirrorable() && b.cut() != null && b.destPos() != null) {
                        destRef.set(b.destPos());
                        return;
                    }
                }
            });
            if (destRef.get() == null) {
                context.waitTicks(10);
            }
        }
        if (destRef.get() == null) {
            throw new AssertionError(LOG + "RS FAR-PAIR RELOG STAGE FAILED — the 7M pair never"
                + " bound while staged under forceload; the reopen assert would be meaningless.");
        }
        final BlockPos destPos = destRef.get();
        // Claim then write, the real-place order (the mirror derives the crossing half from the
        // source claim).
        claimOwnerHalfBothSides(context, cellS,
            com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE);
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            writeAsPlayer(ow, cellS,
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        });
        context.waitTicks(20);
        AtomicReference<String> stageState = new AtomicReference<>("");
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            byte sm = com.warwa.seamlessportals.passthrough.SeamOccupancy.occupancyOf(ow, cellS);
            byte dm = com.warwa.seamlessportals.passthrough.SeamOccupancy.occupancyOf(ow, destPos);
            String far = ow.getBlockState(destPos).getBlock().toString();
            String store = com.warwa.seamlessportals.passthrough.SeamOccupancySavedData
                .get(ow).debugDump();
            stageState.set("srcMask=" + sm + " destMask=" + dm + " farState=" + far
                + " store=" + store);
        });
        if (!stageState.get().contains("farState=Block{minecraft:stone}")
            || stageState.get().contains("destMask=0")
            || !stageState.get().contains(String.valueOf(destPos.asLong()))) {
            throw new AssertionError(LOG + "RS FAR-PAIR RELOG STAGE FAILED — the crossing never"
                + " reached the 7M side or its store record is missing: " + stageState.get());
        }
        // Object 2 on both cells — the user's live pair was TWO-OBJECT (their store dump held
        // 2 masks + 2 secondaries; "hydrated 4"). The single-object variant of this fixture went
        // green on the first run, so the secondaries are a held-apart variable, not decoration.
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            var gold = Blocks.GOLD_BLOCK.defaultBlockState();
            byte dm = com.warwa.seamlessportals.passthrough.SeamOccupancy.occupancyOf(ow, destPos);
            com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, cellS,
                new com.warwa.seamlessportals.passthrough.SeamOccupancy.Secondary(gold,
                    com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_NEGATIVE));
            com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, destPos,
                new com.warwa.seamlessportals.passthrough.SeamOccupancy.Secondary(gold,
                    com.warwa.seamlessportals.passthrough.SeamOccupancy.otherHalf(dm)));
            com.warwa.seamlessportals.passthrough.SeamOccupancy.broadcast(ow, cellS);
            com.warwa.seamlessportals.passthrough.SeamOccupancy.broadcast(ow, destPos);
        });
        context.waitTicks(10);
        // THE LIVE CONDITION: no forceload survives the close; the player parks at the portal.
        runCommands(context, List.of(
            "forceload remove " + (cx - 16) + " " + (cz - 16) + " " + (cx + 16) + " " + (cz + 16),
            "forceload remove " + (cx - 16) + " " + (fz - 16) + " " + (cx + 16) + " " + (fz + 16),
            "tp @p " + (cx + 0.5) + " " + cy + " " + (cz + 3.5) + " 180 0"
        ));
        context.waitTicks(10);
        relogFarCell = cellS;
        relogFarDest = destPos;
        SeamlessPortalsConstants.LOGGER.info(LOG + "RS FAR-PAIR RELOG STAGE — {} / {} staged,"
            + " forceloads removed, player parked at the near portal for the close. {}",
            cellS, destPos, stageState.get());
    }

    /**
     * ★ FAR-PAIR RELOG ASSERT — after the reopen, WITHOUT any forceload: the near portal's own
     * IP chunk tickets must revive the 7M partner, the binding must re-form, the records must be
     * visible on both sides, and break-both must still hold across the pair. Reproduces the
     * user's live relog corpse variable-for-variable; a red here names the first broken link in
     * that chain instead of the downstream symptom soup.
     */
    private static void rsRelogFarAssert(ClientGameTestContext context) {
        if (relogFarCell == null || relogFarDest == null) {
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS FAR-PAIR RELOG ASSERT SKIPPED —"
                + " nothing staged.");
            return;
        }
        final BlockPos cellS = relogFarCell;
        final BlockPos destPos = relogFarDest;
        final int cx = cellS.getX(), cy = cellS.getY(), cz = cellS.getZ();
        final int fz = destPos.getZ();
        AtomicReference<String> failure = new AtomicReference<>(null);
        try {
            // The player reopened parked at the near portal (position persists in the save).
            // a) THE REBIND — with no forceload, only IP's ticket revival can load the far side.
            AtomicReference<String> bindView = new AtomicReference<>("no lookup yet");
            AtomicReference<Boolean> bound = new AtomicReference<>(false);
            for (int attempt = 0; attempt < 120 && !bound.get(); attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    if (ow == null) {
                        return;
                    }
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                    boolean nearBound = cell != null && cell.bindings().stream()
                        .anyMatch(b -> b.isMirrorable() && b.cut() != null);
                    boolean farLoaded = ow.isLoaded(destPos);
                    var farCell = com.warwa.seamlessportals.passthrough.SeamRegistry
                        .lookup(ow, destPos);
                    boolean farBound = farCell != null && farCell.bindings().stream()
                        .anyMatch(b -> b.isMirrorable() && b.cut() != null);
                    bindView.set("nearBound=" + nearBound + " farChunkLoaded=" + farLoaded
                        + " farBound=" + farBound);
                    bound.set(nearBound && farLoaded && farBound);
                });
                if (!bound.get()) {
                    context.waitTicks(10);
                }
            }
            if (!bound.get()) {
                failure.set("THE FAR PAIR NEVER CAME BACK after the reopen (60s): " + bindView.get()
                    + " — with no forceload, this is IP ticket revival / far-portal re-tick"
                    + " failing, and every live post-relog symptom (one-sided outline, unbreakable"
                    + " far half, replaces going everywhere) is downstream of it.");
                return;
            }
            // b) records, both sides, dimension-correct.
            context.waitTicks(40);
            AtomicReference<String> stateView = new AtomicReference<>("");
            AtomicReference<Boolean> stateOk = new AtomicReference<>(false);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                byte sm = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .occupancyOf(ow, cellS);
                byte dm = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .occupancyOf(ow, destPos);
                var ss = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .secondaryOf(ow, cellS);
                var ds = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .secondaryOf(ow, destPos);
                stateView.set("server: srcMask=" + sm + " destMask=" + dm + " srcSec="
                    + (ss == null ? "null" : ss.state().getBlock() + "@" + ss.half())
                    + " destSec=" + (ds == null ? "null" : ds.state().getBlock() + "@" + ds.half())
                    + " farState=" + ow.getBlockState(destPos).getBlock());
                stateOk.set(sm != 0 && dm != 0 && ss != null && ds != null
                    && ow.getBlockState(destPos).is(net.minecraft.world.level.block.Blocks.STONE));
            });
            Boolean clientOk = context.computeOnClient(mc -> {
                var src = com.warwa.seamlessportals.passthrough.SeamOccupancyClient
                    .clientRecordOf(Level.OVERWORLD, cellS.asLong());
                var dst = com.warwa.seamlessportals.passthrough.SeamOccupancyClient
                    .clientRecordOf(Level.OVERWORLD, destPos.asLong());
                return src.mask() != 0 && src.secondary() != null
                    && dst.mask() != 0 && dst.secondary() != null;
            });
            if (!stateOk.get() || !Boolean.TRUE.equals(clientOk)) {
                failure.set("PAIR REBOUND BUT THE STATE DID NOT — " + stateView.get()
                    + " clientOk=" + clientOk);
                return;
            }
            // b2) THE CLIENT-SIDE BIND — every symptom in the user's live list (one-sided
            // outline, unbreakable far half, replaces going everywhere) runs off the CLIENT's
            // seam registry, and its binding's cut needs the REVERSE portal entity, which lives
            // 7M away and reaches the client only through portal-view entity sync. The server
            // rebinding proves nothing about this half.
            AtomicReference<Boolean> clientBound = new AtomicReference<>(false);
            AtomicReference<String> clientBindView = new AtomicReference<>("never checked");
            for (int attempt = 0; attempt < 60 && !clientBound.get(); attempt++) {
                context.runOnClient(mc -> {
                    if (mc.level == null) {
                        clientBindView.set("mc.level null");
                        return;
                    }
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry
                        .lookup(mc.level, cellS);
                    if (cell == null) {
                        // Discriminating diagnostics: portals=0 → entity sync never delivered the
                        // near portals post-reopen; portals>0 & indexed=0 → the client tick
                        // signal/bind path is dead; indexed>0 elsewhere → positional mismatch.
                        int portalsNear = mc.level.getEntitiesOfClass(
                            qouteall.imm_ptl.core.portal.Portal.class,
                            new net.minecraft.world.phys.AABB(cellS).inflate(8), p -> true).size();
                        int indexed = ((com.warwa.seamlessportals.passthrough.SeamIndexHolder)
                            mc.level).seamlessportals$seamCells().size();
                        clientBindView.set("client: no seam cell at " + cellS
                            + " | portalsNearCell=" + portalsNear
                            + " totalIndexedCells=" + indexed);
                        return;
                    }
                    boolean cut = cell.bindings().stream()
                        .anyMatch(b -> b.isMirrorable() && b.cut() != null && b.destPos() != null);
                    clientBindView.set("client: bindings=" + cell.bindings().size()
                        + " anyWithCut=" + cut);
                    clientBound.set(cut);
                });
                if (!clientBound.get()) {
                    context.waitTicks(10);
                }
            }
            if (!clientBound.get()) {
                failure.set("THE CLIENT NEVER REBOUND (30s) — server bindings are back but the"
                    + " client's seam registry has no cut at the near cell (the reverse portal"
                    + " entity did not re-sync through the view), which is exactly the one-sided"
                    + " outline + unbreakable far half. " + clientBindView.get());
                return;
            }
            // c) behaviour: break the FAR (mirror-side) half — the near half must clear too.
            //    This is the user's "cannot break from 2nd side", exercised server-side.
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                writeAsPlayer(ow, destPos,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            });
            context.waitTicks(20);
            AtomicReference<String> breakView = new AtomicReference<>("");
            AtomicReference<Boolean> breakOk = new AtomicReference<>(false);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                // Two-object semantics: breaking the far primary kills the OBJECT (near primary
                // clears too) and BOTH sides promote their surviving gold secondaries.
                boolean nearGold = ow.getBlockState(cellS)
                    .is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK);
                boolean farGold = ow.getBlockState(destPos)
                    .is(net.minecraft.world.level.block.Blocks.GOLD_BLOCK);
                byte sm = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .occupancyOf(ow, cellS);
                breakView.set("after far break: nearState=" + ow.getBlockState(cellS).getBlock()
                    + " nearMask=" + sm + " farState=" + ow.getBlockState(destPos).getBlock());
                breakOk.set(nearGold && farGold
                    && sm == com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_NEGATIVE);
            });
            if (!breakOk.get()) {
                failure.set("BREAK-BOTH BROKE ACROSS THE RELOG — breaking the far half did not"
                    + " clear the near half and promote both secondaries: " + breakView.get());
                return;
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS FAR-PAIR RELOG GATE PASS — 7M pair"
                + " rebound without forceloads, records on both sides, break-both intact."
                + " " + stateView.get() + " | " + breakView.get());
        } finally {
            // Cleanup needs the far chunks; borrow a forceload for the wipe, then drop it.
            runCommands(context, List.of(
                "forceload add " + (cx - 16) + " " + (fz - 16) + " " + (cx + 16) + " "
                    + (fz + 16)));
            context.waitTicks(5);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(ow, cellS);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, cellS, null);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(ow, destPos);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, destPos, null);
                try {
                    ow.setBlockAndUpdate(cellS, net.minecraft.world.level.block.Blocks.AIR
                        .defaultBlockState());
                    ow.setBlockAndUpdate(destPos, net.minecraft.world.level.block.Blocks.AIR
                        .defaultBlockState());
                } catch (Throwable ignored) {
                }
                for (var portal : ow.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                    new net.minecraft.world.phys.AABB(cx - 8, cy - 8, cz - 8,
                        cx + 8, cy + 8, cz + 8), p -> true)) {
                    portal.discard();
                }
                for (var portal : ow.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                    new net.minecraft.world.phys.AABB(cx - 8, cy - 8, fz - 8,
                        cx + 8, cy + 8, fz + 8), p -> true)) {
                    portal.discard();
                }
            });
            runCommands(context, List.of(
                "forceload remove " + (cx - 16) + " " + (fz - 16) + " " + (cx + 16) + " "
                    + (fz + 16),
                "forceload remove " + (cx - 16) + " " + (cz - 16) + " " + (cx + 16) + " "
                    + (cz + 16)));
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS FAR-PAIR RELOG GATE FAILED: " + failure.get());
            }
        }
    }

    /** Relog fixture coordinates, set by {@link #rsRelogStage}, read after the world reopen. */
    private static BlockPos relogCell = null;
    private static BlockPos relogDest = null;

    /**
     * ★ RELOG STAGE — builds a persisted two-object cell the world close cannot erase: a bi-way
     * same-dim pair (the user's construction), object 1 placed+mirrored+claimed, object 2 as a
     * different-type secondary on both sides, forceload left ON (forceloads persist in the save).
     * The teardown fixture cannot host this — its cleanup deletes the frames before the close.
     */
    private static void rsRelogStage(ClientGameTestContext context) {
        if (!com.warwa.seamlessportals.passthrough.SeamFractional.active()
            || AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SEAM_MIRROR) {
            return;
        }
        final int cx = 9200, cy = 100, cz = 9200;
        final Vec3 destCenter = new Vec3(cx + 0.5, cy + 1.0 - 50, cz + 60 + 0.5);
        AtomicReference<Vec3> playerBefore = new AtomicReference<>(null);
        runOnServer(context, server -> {
            var players = server.getPlayerList().getPlayers();
            if (!players.isEmpty()) {
                playerBefore.set(players.get(0).position());
            }
        });
        runCommands(context, List.of(
            "forceload add " + (cx - 16) + " " + (cz - 16) + " " + (cx + 16) + " " + (cz + 76),
            "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz - 4) + " "
                + (cx + 6) + " " + (cy + 5) + " " + (cz + 6) + " minecraft:air",
            "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz + 2) + " "
                + (cx + 6) + " " + (cy - 1) + " " + (cz + 6) + " minecraft:stone",
            "setblock " + cx + " " + (cy - 1) + " " + cz + " minecraft:stone",
            "fill " + (cx - 6) + " " + (cy - 51) + " " + (cz + 54) + " "
                + (cx + 6) + " " + (cy - 51) + " " + (cz + 64) + " minecraft:stone",
            "fill " + (cx - 6) + " " + (cy - 50) + " " + (cz + 54) + " "
                + (cx + 6) + " " + (cy - 45) + " " + (cz + 64) + " minecraft:air",
            "tp @p " + (cx + 0.5) + " " + cy + " " + (cz + 3.5) + " 180 27"
        ));
        context.waitTicks(10);
        runCommands(context, List.of(
            "execute as @p at @p run portal make_portal 1 2 minecraft:overworld "
                + destCenter.x + " " + destCenter.y + " " + destCenter.z));
        context.waitTicks(10);
        runCommands(context, List.of(
            "tp @p " + (cx + 0.5) + " " + cy + " " + (cz + 3.5) + " 180 5",
            "execute as @p at @p run portal complete_bi_way_bi_faced_portal"));
        context.waitTicks(20);

        final BlockPos cellS = new BlockPos(cx, cy, cz);
        AtomicReference<BlockPos> destRef = new AtomicReference<>(null);
        for (int attempt = 0; attempt < 30 && destRef.get() == null; attempt++) {
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                if (cell != null) {
                    cell.bindings().stream()
                        .filter(b -> b.isMirrorable() && b.cut() != null && b.destPos() != null)
                        .findFirst().ifPresent(b -> destRef.set(b.destPos()));
                }
            });
            if (destRef.get() == null) {
                context.waitTicks(10);
            }
        }
        if (destRef.get() == null) {
            throw new AssertionError(LOG + "RS RELOG STAGE FAILED — the persistence pair never"
                + " bound at " + cellS);
        }
        AtomicReference<String> failure = new AtomicReference<>(null);
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            BlockPos destPos = destRef.get();
            // Object 1: HEAD-claim then bracketed write, exactly as placement builds it.
            com.warwa.seamlessportals.passthrough.SeamOccupancy.claim(ow, cellS,
                com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE);
            writeAsPlayer(ow, cellS, Blocks.STONE.defaultBlockState());
            byte destOwned = com.warwa.seamlessportals.passthrough.SeamOccupancy
                .occupancyOf(ow, destPos);
            if (!ow.getBlockState(destPos).is(Blocks.STONE)
                || com.warwa.seamlessportals.passthrough.SeamOccupancy.otherHalf(destOwned) == 0) {
                failure.set("staging precondition — object 1 did not mirror+claim (destState="
                    + ow.getBlockState(destPos).getBlock() + " destOwned=" + destOwned + ")");
                return;
            }
            // Object 2: different-type secondaries, complements on both sides.
            var gold = Blocks.GOLD_BLOCK.defaultBlockState();
            com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, cellS,
                new com.warwa.seamlessportals.passthrough.SeamOccupancy.Secondary(gold,
                    com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_NEGATIVE));
            com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, destPos,
                new com.warwa.seamlessportals.passthrough.SeamOccupancy.Secondary(gold,
                    com.warwa.seamlessportals.passthrough.SeamOccupancy.otherHalf(destOwned)));
            // ★ CHURN — live round 14 found the pristine-staged gate green while the user's world
            // lost ONE SIDE of each pair at quit: their session BROKE and RE-PLACED before quitting,
            // and somewhere in that cycle a side's write-through goes missing. Reproduce the churn:
            // break the second object (both dimensions), re-place it, so the store has absorbed a
            // remove-then-re-add on every record class before the close.
            net.minecraft.server.level.ServerPlayer churnPlayer =
                server.getPlayerList().getPlayers().isEmpty()
                    ? null : server.getPlayerList().getPlayers().get(0);
            if (churnPlayer != null) {
                com.warwa.seamlessportals.passthrough.SeamFractional
                    .breakSecondary(ow, cellS, churnPlayer);
                var gold2 = Blocks.GOLD_BLOCK.defaultBlockState();
                com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, cellS,
                    new com.warwa.seamlessportals.passthrough.SeamOccupancy.Secondary(gold2,
                        com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_NEGATIVE));
                com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, destPos,
                    new com.warwa.seamlessportals.passthrough.SeamOccupancy.Secondary(gold2,
                        com.warwa.seamlessportals.passthrough.SeamOccupancy.otherHalf(destOwned)));
            }
            // PRECONDITION — the store must hold BOTH SIDES' full records after the churn, or the
            // reopen assert measures the wrong thing. This is exactly the quantity the live round
            // lost, so assert it BEFORE the close too: a red here means the write-through drops
            // records at mutation time; a red only after reopen means the save/load loses them.
            var store = com.warwa.seamlessportals.passthrough.SeamOccupancySavedData.get(ow);
            if (store == null) {
                failure.set("staging precondition — no SavedData store on the overworld");
                return;
            }
            String storeDump = store.debugDump();
            if (!storeDump.contains(cellS.asLong() + ":") || !storeDump.contains(destPos.asLong() + ":")) {
                failure.set("PRE-CLOSE STORE ALREADY MISSING A SIDE (the live round 14 defect, at"
                    + " mutation time not save time) — store=" + storeDump
                    + " cellS=" + cellS.asLong() + " destPos=" + destPos.asLong());
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(LOG + "RS RELOG STAGE FAILED: " + failure.get());
        }
        relogCell = cellS;
        relogDest = destRef.get();
        Vec3 back = playerBefore.get();
        if (back != null) {
            runCommands(context, List.of("tp @p " + back.x + " " + back.y + " " + back.z));
        }
        SeamlessPortalsConstants.LOGGER.info(LOG + "RS RELOG STAGE — two-object cell persisted at"
            + " {} / {} (forceload left ON deliberately; the reopen assert consumes it)",
            relogCell, relogDest);
    }

    /**
     * ★ RELOG ASSERT — runs AFTER {@code worldSave.open()}: the SavedData hydrated, the pair
     * rebound, and the object model must behave IDENTICALLY to before the close. Reproduces the
     * user's live relog round headlessly: "cant break seam block unless both sides are broken,
     * seam block gets replaced by the other side, replace goes to both sides" — all three are
     * post-reload state corruption, and this leg measures state AND behaviour.
     */
    private static void rsRelogAssert(ClientGameTestContext context) {
        if (relogCell == null || relogDest == null) {
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS RELOG ASSERT SKIPPED — nothing staged"
                + " (fractional off or staging failed earlier, which already threw).");
            return;
        }
        final BlockPos cellS = relogCell;
        final BlockPos destPos = relogDest;
        // Wait for the reopened world to rebind the pair and hydrate the store.
        AtomicReference<Boolean> ready = new AtomicReference<>(false);
        for (int attempt = 0; attempt < 90 && !ready.get(); attempt++) {
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                if (ow == null) {
                    return;
                }
                var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                boolean bound = cell != null && cell.bindings().stream()
                    .anyMatch(b -> b.isMirrorable() && b.cut() != null);
                boolean hydrated = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .occupancyOf(ow, cellS) != 0;
                ready.set(bound && hydrated);
            });
            if (!ready.get()) {
                context.waitTicks(10);
            }
        }
        // ★ THE CLIENT-SIDE HALF (live round 14, after the churned server gate stayed GREEN): the
        // user's post-relog symptoms — far half unoutlined, second side unbreakable, replaces
        // going everywhere — are what a client MISSING the records looks like while the server is
        // right. Assert what the CLIENT knows, after giving the join burst + tick flush time to
        // land.
        //
        // ⚠ READ THE CELL'S DIMENSION, NOT mc.level. This assert's first form read mc.level and
        // went red TWICE on a WORKING pipe (churn4/churn5, 2026-08-03): the player had logged out
        // in the NETHER (leg 4's pearl leaves them there), so post-relog mc.level was the nether
        // and these overworld cells were asked of the wrong level's duck maps. The records were
        // sitting exactly where the renderer/collision consumers read them — the loader's
        // secondary overworld ("Client World Created minecraft:overworld" one line before the
        // APPLIED probes). clientRecordOf resolves in the consumers' order and reports which
        // store answered, so a red here now means the CLIENT genuinely cannot see the record.
        context.waitTicks(40);
        AtomicReference<String> clientView = new AtomicReference<>("");
        AtomicReference<Boolean> clientOk = new AtomicReference<>(false);
        context.runOnClient(mc -> {
            var src = com.warwa.seamlessportals.passthrough.SeamOccupancyClient
                .clientRecordOf(Level.OVERWORLD, cellS.asLong());
            var dst = com.warwa.seamlessportals.passthrough.SeamOccupancyClient
                .clientRecordOf(Level.OVERWORLD, destPos.asLong());
            clientView.set("client(played dim=" + (mc.level == null ? "null"
                    : mc.level.dimension().identifier()) + "): srcMask=" + src.mask()
                + " srcSec=" + (src.secondary() == null ? "null"
                    : src.secondary().state().getBlock() + "@" + src.secondary().half())
                + " [" + src.source() + "] destMask=" + dst.mask()
                + " destSec=" + (dst.secondary() == null ? "null"
                    : dst.secondary().state().getBlock() + "@" + dst.secondary().half())
                + " [" + dst.source() + "]");
            clientOk.set(src.mask() != 0 && src.secondary() != null
                && dst.mask() != 0 && dst.secondary() != null);
        });
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<String> detail = new AtomicReference<>("");
        if (!clientOk.get()) {
            failure.set("THE CLIENT CANNOT SEE POST-RELOG RECORDS in any store (played level,"
                + " loader secondary level, pending stash) — " + clientView.get());
        }
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            try {
                if (failure.get() != null) {
                    // Client assert already failed — still read server detail for the report,
                    // then fall through to cleanup in the finally.
                }
                byte srcOwned = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .occupancyOf(ow, cellS);
                var srcSec = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .secondaryOf(ow, cellS);
                byte destOwned = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .occupancyOf(ow, destPos);
                var destSec = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .secondaryOf(ow, destPos);
                detail.set("srcOwned=" + srcOwned + " srcSec="
                    + (srcSec == null ? "null" : srcSec.state().getBlock() + "@" + srcSec.half())
                    + " destOwned=" + destOwned + " destSec="
                    + (destSec == null ? "null" : destSec.state().getBlock() + "@" + destSec.half())
                    + " srcState=" + ow.getBlockState(cellS).getBlock()
                    + " destState=" + ow.getBlockState(destPos).getBlock());
                // ---- STATE SURVIVED THE RELOAD ----
                if (srcOwned != com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE) {
                    failure.set("RELOAD LOST THE OWNER HALF — source mask is " + srcOwned
                        + ", staged POSITIVE. Hydration or the write-through dropped it.");
                    return;
                }
                if (srcSec == null || !srcSec.state().is(Blocks.GOLD_BLOCK)
                    || srcSec.half() != com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_NEGATIVE) {
                    failure.set("RELOAD LOST THE SECOND OBJECT (source side) — " + detail.get());
                    return;
                }
                if (com.warwa.seamlessportals.passthrough.SeamOccupancy.otherHalf(destOwned) == 0
                    || destSec == null || !destSec.state().is(Blocks.GOLD_BLOCK)
                    || destSec.half() != com.warwa.seamlessportals.passthrough.SeamOccupancy
                        .otherHalf(destOwned)) {
                    failure.set("RELOAD LOST THE DEST RECORDS — " + detail.get());
                    return;
                }
                // ---- BEHAVIOUR SURVIVED THE RELOAD: the break-both + promote cycle, the exact
                // operations the user reported broken after their live relog. ----
                writeAsPlayer(ow, destPos, Blocks.AIR.defaultBlockState());
                if (ow.getBlockState(cellS).is(Blocks.STONE)) {
                    failure.set("POST-RELOAD BREAK-BOTH BROKEN — breaking the crossing half left the"
                        + " origin standing (the user's 'cant break unless both sides are broken').");
                    return;
                }
                if (!ow.getBlockState(cellS).is(Blocks.GOLD_BLOCK)
                    || !ow.getBlockState(destPos).is(Blocks.GOLD_BLOCK)) {
                    failure.set("POST-RELOAD PROMOTE BROKEN — expected both sides to promote the"
                        + " surviving gold; src=" + ow.getBlockState(cellS).getBlock()
                        + " dest=" + ow.getBlockState(destPos).getBlock());
                    return;
                }
            } finally {
                // Full teardown — this fixture must not leak into anything after leg 5.
                writeAsPlayer(ow, cellS, Blocks.AIR.defaultBlockState());
                writeAsPlayer(ow, destPos, Blocks.AIR.defaultBlockState());
                com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(ow, cellS);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, cellS, null);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(ow, destPos);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, destPos, null);
                for (var portal : ow.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                    new net.minecraft.world.phys.AABB(9200 - 6, 100 - 56, 9200 - 6,
                        9200 + 6, 100 + 6, 9200 + 66), x -> true)) {
                    portal.discard();
                }
            }
        });
        runCommands(context, List.of(
            "forceload remove " + (9200 - 16) + " " + (9200 - 16) + " "
                + (9200 + 16) + " " + (9200 + 76)));
        if (failure.get() != null) {
            throw new AssertionError(LOG + "RS RELOG PERSISTENCE GATE FAILED: "
                + (ready.get() ? "" : "(fixture never became ready after reopen — binding or"
                    + " hydration absent) ") + failure.get() + " | server: " + detail.get()
                + " | " + clientView.get());
        }
        SeamlessPortalsConstants.LOGGER.info(LOG + "RS RELOG PERSISTENCE GATE PASS — two-object"
            + " state and break-both behaviour survived the world reopen, and the CLIENT holds the"
            + " records. server: {} | {}", detail.get(), clientView.get());
    }

    /**
     * ★ THE SAME-DIM DISCRIMINATOR — stages the user's EXACT live construction ({@code portal
     * make_portal} + {@code complete_bi_way_bi_faced_portal}, both cells in the overworld) and runs
     * the identical break-both body on it. Exists because the cross-dim run went green while the
     * live same-dim pair showed the defect: one variable, held apart deliberately.
     */
    private static void rsObjectBreakBothSameDimGate(ClientGameTestContext context) {
        if (!com.warwa.seamlessportals.passthrough.SeamFractional.active()
            || AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SEAM_MIRROR) {
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS OBJECT BREAK-BOTH GATE [SAME-DIM]"
                + " SKIPPED — fractional or mirroring disabled.");
            return;
        }
        final int cx = 8200, cy = 100, cz = 8200;
        final Vec3 destCenter = new Vec3(cx + 0.5, cy + 1.0 - 50, cz + 60 + 0.5);
        AtomicReference<Vec3> playerBefore = new AtomicReference<>(null);
        try {
            runOnServer(context, server -> {
                var players = server.getPlayerList().getPlayers();
                if (!players.isEmpty()) {
                    playerBefore.set(players.get(0).position());
                }
            });
            // The repro3 staging recipe verbatim (floor gap so the aim ray hits the target's top).
            runCommands(context, List.of(
                "forceload add " + (cx - 16) + " " + (cz - 16) + " " + (cx + 16) + " " + (cz + 76),
                "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz - 4) + " "
                    + (cx + 6) + " " + (cy + 5) + " " + (cz + 6) + " minecraft:air",
                "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz + 2) + " "
                    + (cx + 6) + " " + (cy - 1) + " " + (cz + 6) + " minecraft:stone",
                "setblock " + cx + " " + (cy - 1) + " " + cz + " minecraft:stone",
                "fill " + (cx - 6) + " " + (cy - 51) + " " + (cz + 54) + " "
                    + (cx + 6) + " " + (cy - 51) + " " + (cz + 64) + " minecraft:stone",
                "fill " + (cx - 6) + " " + (cy - 50) + " " + (cz + 54) + " "
                    + (cx + 6) + " " + (cy - 45) + " " + (cz + 64) + " minecraft:air",
                "tp @p " + (cx + 0.5) + " " + cy + " " + (cz + 3.5) + " 180 27"
            ));
            context.waitTicks(10);
            runCommands(context, List.of(
                "execute as @p at @p run portal make_portal 1 2 minecraft:overworld "
                    + destCenter.x + " " + destCenter.y + " " + destCenter.z));
            context.waitTicks(10);
            runCommands(context, List.of(
                "tp @p " + (cx + 0.5) + " " + cy + " " + (cz + 3.5) + " 180 5",
                "execute as @p at @p run portal complete_bi_way_bi_faced_portal"));
            context.waitTicks(20);
            runCommands(context, List.of(
                "tp @p " + (cx + 3.5) + " " + cy + " " + (cz + 4.5) + " 180 0"));

            final BlockPos cellS = new BlockPos(cx, cy, cz);
            AtomicReference<Boolean> bound = new AtomicReference<>(false);
            for (int attempt = 0; attempt < 30 && !bound.get(); attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                    if (cell != null && cell.bindings().stream().anyMatch(b ->
                        b.isMirrorable() && b.cut() != null)) {
                        bound.set(true);
                    }
                });
                if (!bound.get()) {
                    context.waitTicks(10);
                }
            }
            if (!bound.get()) {
                throw new AssertionError(LOG + "RS OBJECT BREAK-BOTH GATE [SAME-DIM] FIXTURE"
                    + " INVALID — the command-built same-dim pair never bound a mirrorable seam"
                    + " with a cut at " + cellS);
            }
            rsObjectBreakBothGate(context, cellS, "SAME-DIM");
        } finally {
            try {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    for (var portal : ow.getEntitiesOfClass(
                        qouteall.imm_ptl.core.portal.Portal.class,
                        new net.minecraft.world.phys.AABB(cx - 6, cy - 56, cz - 6,
                            cx + 6, cy + 6, cz + 66), x -> true)) {
                        portal.discard();
                    }
                });
                runCommands(context, List.of(
                    "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz - 4) + " "
                        + (cx + 6) + " " + (cy + 5) + " " + (cz + 6) + " minecraft:air",
                    "fill " + (cx - 6) + " " + (cy - 51) + " " + (cz + 54) + " "
                        + (cx + 6) + " " + (cy - 45) + " " + (cz + 64) + " minecraft:air",
                    "forceload remove " + (cx - 16) + " " + (cz - 16) + " "
                        + (cx + 16) + " " + (cz + 76)
                ));
                Vec3 back = playerBefore.get();
                if (back != null) {
                    runCommands(context, List.of(
                        "tp @p " + back.x + " " + back.y + " " + back.z));
                }
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(
                    LOG + "RS OBJECT BREAK-BOTH GATE [SAME-DIM] cleanup failed", t);
            }
        }
    }

    /**
     * ★ SAME-DIM half of the particle measurement leg ({@code SEAM_FRACTIONAL_PROBE} only,
     * measures and asserts nothing). Stages the user's EXACT live construction — the break-both
     * discriminator's verbatim recipe ({@code portal make_portal 1 2} +
     * {@code complete_bi_way_bi_faced_portal}, both cells in the overworld) at fresh coordinates —
     * then repeats the torch phase and the deterministic open-cell smoke spawns. Both seam cells
     * live in ONE client-level index here, fully bi-faced-bound, so if open-cell arrivals can
     * re-cross (the ping-pong shape), THIS is the topology that shows it; the cross-dim phase
     * cannot (its arrivals land in the remote index).
     */
    private static void rsSeamParticleSameDimPhase(ClientGameTestContext context) {
        final String tag = LOG + "[RS-PTCL-MEASURE][SAME-DIM] ";
        final int cx = 8600, cy = 100, cz = 8600;
        final Vec3 destCenter = new Vec3(cx + 0.5, cy + 1.0 - 50, cz + 60 + 0.5);
        final BlockPos cellS = new BlockPos(cx, cy, cz);
        final BlockPos openCell = new BlockPos(cx, cy + 1, cz);
        AtomicReference<Vec3> playerBefore = new AtomicReference<>(null);
        try {
            runOnServer(context, server -> {
                var players = server.getPlayerList().getPlayers();
                if (!players.isEmpty()) {
                    playerBefore.set(players.get(0).position());
                }
            });
            // The discriminator's staging verbatim, plus a north-side floor strip so the
            // empty-side viewpoint has ground to stand on.
            runCommands(context, List.of(
                "forceload add " + (cx - 16) + " " + (cz - 16) + " " + (cx + 16) + " " + (cz + 76),
                "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz - 4) + " "
                    + (cx + 6) + " " + (cy + 5) + " " + (cz + 6) + " minecraft:air",
                "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz + 2) + " "
                    + (cx + 6) + " " + (cy - 1) + " " + (cz + 6) + " minecraft:stone",
                "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz - 4) + " "
                    + (cx + 6) + " " + (cy - 1) + " " + (cz - 1) + " minecraft:stone",
                "setblock " + cx + " " + (cy - 1) + " " + cz + " minecraft:stone",
                "fill " + (cx - 6) + " " + (cy - 51) + " " + (cz + 54) + " "
                    + (cx + 6) + " " + (cy - 51) + " " + (cz + 64) + " minecraft:stone",
                "fill " + (cx - 6) + " " + (cy - 50) + " " + (cz + 54) + " "
                    + (cx + 6) + " " + (cy - 45) + " " + (cz + 64) + " minecraft:air",
                "tp @p " + (cx + 0.5) + " " + cy + " " + (cz + 3.5) + " 180 27"
            ));
            context.waitTicks(10);
            runCommands(context, List.of(
                "execute as @p at @p run portal make_portal 1 2 minecraft:overworld "
                    + destCenter.x + " " + destCenter.y + " " + destCenter.z));
            context.waitTicks(10);
            runCommands(context, List.of(
                "tp @p " + (cx + 0.5) + " " + cy + " " + (cz + 3.5) + " 180 5",
                "execute as @p at @p run portal complete_bi_way_bi_faced_portal"));
            context.waitTicks(20);
            AtomicReference<Boolean> bound = new AtomicReference<>(false);
            for (int attempt = 0; attempt < 30 && !bound.get(); attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                    if (cell != null && cell.bindings().stream().anyMatch(b ->
                        b.isMirrorable() && b.cut() != null)) {
                        bound.set(true);
                    }
                });
                if (!bound.get()) {
                    context.waitTicks(10);
                }
            }
            if (!bound.get()) {
                SeamlessPortalsConstants.LOGGER.warn(tag + "FIXTURE NEVER BOUND — same-dim"
                    + " measurement skipped (this itself is evidence: the command-built pair"
                    + " did not produce a mirrorable cut binding).");
                return;
            }
            claimOwnerHalfBothSides(context, cellS,
                com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                if (ow != null) {
                    writeAsPlayer(ow, cellS,
                        net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState());
                }
            });
            context.waitTicks(10);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                String destInfo = "no mirrorable binding";
                String destOcc = "?", destBlock = "?";
                var cellRec = ow == null ? null
                    : com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                if (cellRec != null) {
                    for (var b : cellRec.bindings()) {
                        if (b.isMirrorable() && b.cut() != null && b.destPos() != null) {
                            destInfo = b.destDim().identifier() + " " + b.destPos();
                            destOcc = String.valueOf(com.warwa.seamlessportals.passthrough
                                .SeamOccupancy.occupancyOf(ow, b.destPos()));
                            destBlock = ow.getBlockState(b.destPos()).toString();
                            break;
                        }
                    }
                }
                SeamlessPortalsConstants.LOGGER.info(
                    tag + "STAGED: srcBlock={} srcOcc={} | dest={} destOcc={} destBlock={}",
                    ow == null ? "?" : ow.getBlockState(cellS),
                    ow == null ? "?" : String.valueOf(com.warwa.seamlessportals.passthrough
                        .SeamOccupancy.occupancyOf(ow, cellS)),
                    destInfo, destOcc, destBlock);
            });
            // Empty-side viewpoint: owned half is POSITIVE (south), so the empty side is north.
            seamStandIn(context, "minecraft:overworld", cx + 0.5, cy, cz - 2.5);
            aimAt(context, cx + 0.5, cy + 0.7, cz + 0.5);
            SeamlessPortalsConstants.LOGGER.info(
                tag + "PHASE A2: 400 ticks of organic torch emission");
            context.waitTicks(400);
            SeamlessPortalsConstants.LOGGER.info(
                tag + "PHASE B2: 5 deterministic smoke spawns into {}", openCell);
            for (int i = 0; i < 5; i++) {
                context.runOnClient(mc -> {
                    if (mc.level != null) {
                        mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                            cx + 0.5, cy + 1.3, cz + 0.25, 0.0, 0.02, 0.0);
                    }
                });
                context.waitTicks(40);
            }
            context.waitTicks(80);
            context.runOnClient(mc -> com.warwa.seamlessportals.render.SeamParticleProbe
                .legReport("[RS-PTCL-MEASURE same-dim]"));
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(tag + "FAILED (measurement only, never fatal)", t);
        } finally {
            try {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    if (ow == null) {
                        return;
                    }
                    writeAsPlayer(ow, cellS,
                        net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(ow, cellS);
                    for (var portal : ow.getEntitiesOfClass(
                        qouteall.imm_ptl.core.portal.Portal.class,
                        new net.minecraft.world.phys.AABB(cx - 6, cy - 56, cz - 6,
                            cx + 6, cy + 6, cz + 66), x -> true)) {
                        portal.discard();
                    }
                });
                runCommands(context, List.of(
                    "fill " + (cx - 6) + " " + (cy - 1) + " " + (cz - 4) + " "
                        + (cx + 6) + " " + (cy + 5) + " " + (cz + 6) + " minecraft:air",
                    "fill " + (cx - 6) + " " + (cy - 51) + " " + (cz + 54) + " "
                        + (cx + 6) + " " + (cy - 45) + " " + (cz + 64) + " minecraft:air",
                    "forceload remove " + (cx - 16) + " " + (cz - 16) + " "
                        + (cx + 16) + " " + (cz + 76)
                ));
                Vec3 back = playerBefore.get();
                if (back != null) {
                    runCommands(context, List.of(
                        "tp @p " + back.x + " " + back.y + " " + back.z));
                    context.waitTicks(10);
                }
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(tag + "cleanup failed", t);
            }
        }
    }

    /**
     * A world write ATTRIBUTED TO A PLAYER, for the RS gates.
     *
     * <p>Needed since 2026-07-26, when mirroring was narrowed to player actions
     * ({@code SeamMirrorPolicy}). Every RS gate used to write with {@code /setblock}, which is now
     * correctly classified {@code COMMAND} and declined — so without this the gates would go on
     * passing while testing a mirror that never fires. That is the exact failure this suite has
     * already been bitten by three times, so it is worth being explicit: <b>a gate that stops
     * exercising its subject must fail, not adapt silently.</b>
     *
     * <p>This declares the write's provenance rather than faking a player. The REAL bracket
     * ({@code BlockItem.place} → {@code MixinBlockItemPlaceSource}) is covered separately by
     * {@link #rsPlayerPlaceBracketGate}, so the two together cover both "the bracket arms" and "the
     * mirror acts on an armed write" without either standing in for the other.
     */
    /**
     * RS SEAM-CLIP GATE — the suite's first gate asserting FRAMEBUFFER PIXELS, the last object
     * before the user's eyes ({@code migration/SEAM_CLIP_DESIGN.md} §5; the assert-the-outcome
     * rule applied one step beyond {@code setSectionMesh}).
     *
     * <p><b>Fixture</b> (panel-corrected — the v1 framed/in-window fixture could not invert): a
     * FRAMELESS, EXACT-aligned same-dim portal (integral dest offset ⇒ COINCIDENT, mirror-admitted
     * — asserted, not assumed), a GOLD block in the aperture's +X EDGE cell, a blue backdrop wall
     * behind the plane. The camera stands east of the window edge, so its sight line to the far
     * half crosses the plane OUTSIDE the window quad (crossing x ≈ qx+7.3 &gt; window edge qx+6) —
     * in-window crossings are repainted by the dest pass in BOTH lever states and prove nothing.
     *
     * <p><b>Sampling:</b> the crosshair is aimed at a WORLD POINT (client-computed eye→target
     * angles), so the sampled patch is the image CENTER — no projection math, no fixed screen
     * fractions. Far point {@code (qx+6, qy+1.5, plane−0.4)} sits 0.4 blocks past the cut on the
     * block's east face (≈30+ px of margin at any test resolution); near point mirrors it on the
     * kept side as the leg's own calibration: near must read GOLD in BOTH lever states (block
     * rendered, camera aimed — and with the fix ON it can only come from the DYNAMIC draw, since
     * the mesh no longer contains the block).
     *
     * <p><b>Verdicts:</b> fix ON — far NOT-gold + near gold + {@code cellsDrawn/cellsExcluded > 0};
     * clip OFF (the DEFAULT since 2026-07-27) — far GOLD (whole cube) + near gold +
     * counters 0. Full-suite note: the player is in the nether by this point — the leg records
     * their whereabouts and restores them in the {@code finally}, along with the staging, the
     * mirrored far half, the portal and {@code hideGui}.
     */
    /**
     * RS-CART-A (gate, COINCIDENT/obsidian/cross-dim) — regression coverage for the crossing the
     * 2026-07-28 instrument round measured CLEAN STOCK (zero {@code comeOffTrack}; the stranded
     * tick resolves the seam cell's own near rail because the plane is mid-block; arrival on the
     * far rail at riding height). Because it works without the (d) fix, this arm asserts the SAME
     * outcome in every lever direction — it is the guard that (d) and later work never break the
     * already-good topology, not the fix's proof (that is RS-CART-B).
     *
     * <p>Fixture: fresh ignited obsidian frame at (9000,-9000) (nether counterpart ~(1125,-1125),
     * >128 blocks clear of every other fixture's). A straight regular-rail line runs through the
     * seam: 6-cell near approach (own stone supports), the seam cell laid {@code writeAsPlayer}
     * (mirror creates the far half), a 7-cell nether continuation (own stone supports, headroom,
     * chunks forceloaded so the arriving cart TICKS — the (c) far-side lesson) ending in a stone
     * BUFFER STOP so the cart halts ON the track deterministically instead of running off its end.
     * An empty minecart is spawned 5 cells out, settled 10 ticks (the fresh-entity guard reads
     * xo/yo/zo), shoved at 0.4 toward the plane.
     *
     * <p>ASSERTED OUTCOME (the thing the user sees): the cart ARRIVES in the far dimension ON
     * RAILS and has ROLLED ≥2 cells down the far track. Fixture faults THROW separately (a curved
     * or missing rail voids the arm — the (b) support scar). Extra per-tick logging under
     * {@code -PseamCartProbe}.
     */
    private static void rsCartLegCoincident(ClientGameTestContext context, int py) {
        if (AperturePassthroughLever.DISABLED) {
            return;
        }
        // No shadow-off skip: the seam rail resolves straight with or without its across arm
        // (single south arm → NORTH_SOUTH), and the crossing works stock — this arm's outcome
        // holds in EVERY matrix row, which is exactly its value as regression coverage.
        final int fx = 9000, fz = -9000;
        final BlockPos cellS = new BlockPos(fx, py + 1, fz);
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<Integer> cartId = new AtomicReference<>(null);
        AtomicReference<BlockPos> destForCleanup = new AtomicReference<>(null);
        AtomicReference<String> destDimForCleanup = new AtomicReference<>(null);
        try {
            runCommands(context, List.of(
                "forceload add " + (fx - 16) + " " + (fz - 16) + " " + (fx + 16) + " " + (fz + 16),
                "fill " + (fx - 2) + " " + py + " " + (fz - 8) + " "
                    + (fx + 3) + " " + (py + 5) + " " + (fz + 8) + " minecraft:air",
                fill(fx - 1, py, fz, fx + 2, py, fz),
                fill(fx - 1, py + 4, fz, fx + 2, py + 4, fz),
                fill(fx - 1, py + 1, fz, fx - 1, py + 3, fz),
                fill(fx + 2, py + 1, fz, fx + 2, py + 3, fz)
            ));
            context.waitTicks(20);
            runOnServer(context, server -> {
                boolean fired = qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration
                    .onFireLitOnObsidian(server.getLevel(Level.OVERWORLD), cellS, null);
                if (!fired) {
                    failure.set("ignition entry rejected the frame");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-A SETUP FAILED: " + failure.get());
            }
            final net.minecraft.world.phys.AABB frameBox = new net.minecraft.world.phys.AABB(
                fx - 8, py - 8, fz - 8, fx + 8, py + 8, fz + 8);
            try {
                context.waitFor(mc -> {
                    MinecraftServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        return false;
                    }
                    return !server.getLevel(Level.OVERWORLD).getEntitiesOfClass(
                        qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                        frameBox, x -> true).isEmpty();
                }, 1200);
            }
            catch (Throwable t) {
                throw new AssertionError(LOG + "RS-CART-A FAILED: no NetherPortalEntity generated"
                    + " within 1200 ticks", t);
            }

            AtomicReference<com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding> bindingRef =
                new AtomicReference<>(null);
            for (int attempt = 0; attempt < 30 && bindingRef.get() == null; attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                    if (cell == null) {
                        return;
                    }
                    cell.bindings().stream()
                        .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                        .findFirst().ifPresent(bindingRef::set);
                });
                if (bindingRef.get() == null) {
                    context.waitTicks(10);
                }
            }
            var binding = bindingRef.get();
            if (binding == null) {
                throw new AssertionError(LOG + "RS-CART-A FAILED: seam cell " + cellS
                    + " never bound with a mirrorable binding");
            }
            if (binding.phase() != com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT
                || !binding.seamContinuous()) {
                throw new AssertionError(LOG + "RS-CART-A FAILED: expected COINCIDENT+continuous, got "
                    + binding.phase() + " continuous=" + binding.seamContinuous());
            }
            final net.minecraft.core.Direction crossDir = binding.crossDir();
            final net.minecraft.core.Direction backDir = crossDir.getOpposite();
            final net.minecraft.core.Direction farDir =
                com.warwa.seamlessportals.passthrough.SeamRegistry.mapDir(binding, crossDir);
            final BlockPos cellD = binding.destPos();
            final BlockPos contC = binding.continuationToward(crossDir);
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "RS-CART-A geometry: S={} crossDir={} D={} in {} continuation={} farDir={} rot={}",
                cellS, crossDir, cellD, binding.destDim().identifier(), contC, farDir,
                binding.stateRotation());

            // Far landing chunks must ENTITY-TICK or the arriving cart freezes — the (c) far-side
            // lesson, cart edition.
            destForCleanup.set(cellD);
            destDimForCleanup.set(binding.destDim().identifier().toString());
            runCommands(context, List.of(inDim(binding.destDim().identifier().toString(),
                "forceload add " + (cellD.getX() - 16) + " " + (cellD.getZ() - 16) + " "
                    + (cellD.getX() + 16) + " " + (cellD.getZ() + 16))));

            // Far continuation first, then the near approach, then the seam rail LAST (both arms
            // present when its shape resolves).
            runOnServer(context, server -> {
                ServerLevel far = server.getLevel(binding.destDim());
                if (far == null) {
                    failure.set("destination level " + binding.destDim() + " missing");
                    return;
                }
                for (int k = 0; k < 7; k++) {
                    BlockPos fp = contC.relative(farDir, k);
                    far.getChunk(fp.getX() >> 4, fp.getZ() >> 4);
                    far.setBlock(fp.below(), Blocks.STONE.defaultBlockState(), 3);
                    far.setBlock(fp.above(), Blocks.AIR.defaultBlockState(), 3);
                    far.setBlock(fp, Blocks.RAIL.defaultBlockState(), 3);
                }
                // BUFFER STOP: the cart must halt ON the track, deterministically — without this
                // it can coast off the far end mid-report and flake the on-rails assertion.
                BlockPos stop = contC.relative(farDir, 7);
                far.setBlock(stop.below(), Blocks.STONE.defaultBlockState(), 3);
                far.setBlock(stop, Blocks.STONE.defaultBlockState(), 3);
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                for (int k = 1; k <= 6; k++) {
                    BlockPos ap = cellS.relative(backDir, k);
                    ow.setBlock(ap.below(), Blocks.STONE.defaultBlockState(), 3);
                    ow.setBlock(ap.above(), Blocks.AIR.defaultBlockState(), 3);
                    ow.setBlock(ap, Blocks.RAIL.defaultBlockState(), 3);
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-A SETUP FAILED: " + failure.get());
            }
            runOnServer(context, server -> writeAsPlayer(server.getLevel(Level.OVERWORLD), cellS,
                Blocks.RAIL.defaultBlockState()));
            context.waitTicks(10);

            // Fixture-fails-right: every cell of the line must hold a STRAIGHT rail along the
            // crossing axis — a popped or curved rail voids the experiment silently otherwise.
            final var expectStraight = crossDir.getAxis() == net.minecraft.core.Direction.Axis.Z
                ? net.minecraft.world.level.block.state.properties.RailShape.NORTH_SOUTH
                : net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST;
            final var expectFarStraight = farDir.getAxis() == net.minecraft.core.Direction.Axis.Z
                ? net.minecraft.world.level.block.state.properties.RailShape.NORTH_SOUTH
                : net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST;
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                for (int k = 6; k >= 0; k--) {
                    BlockPos ap = k == 0 ? cellS : cellS.relative(backDir, k);
                    var st = ow.getBlockState(ap);
                    if (!st.is(Blocks.RAIL) || st.getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE)
                        != expectStraight) {
                        failure.set("near cell " + ap + " is " + st + ", expected straight "
                            + expectStraight + " RAIL — fixture void");
                        return;
                    }
                }
                ServerLevel far = server.getLevel(binding.destDim());
                for (int k = 0; k < 7; k++) {
                    BlockPos fp = contC.relative(farDir, k);
                    var st = far.getBlockState(fp);
                    if (!st.is(Blocks.RAIL) || st.getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE)
                        != expectFarStraight) {
                        failure.set("far cell " + fp + " is " + st + ", expected straight "
                            + expectFarStraight + " RAIL — fixture void");
                        return;
                    }
                }
                var dState = far.getBlockState(cellD);
                if (!dState.is(Blocks.RAIL)) {
                    failure.set("mirrored seam half at " + cellD + " is " + dState
                        + " — the (a) mirror never wrote it; fixture void");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-A FIXTURE FAULT: " + failure.get());
            }

            // Spawn 5 cells out, settle (the fresh-entity guard reads xo/yo/zo), then shove.
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var cart = EntityTypes.MINECART.create(ow, EntitySpawnReason.COMMAND);
                if (cart == null) {
                    failure.set("minecart create returned null");
                    return;
                }
                BlockPos start = cellS.relative(backDir, 5);
                cart.snapTo(start.getX() + 0.5, start.getY() + 0.1, start.getZ() + 0.5, 0f, 0f);
                ow.addFreshEntity(cart);
                com.warwa.seamlessportals.passthrough.SeamCartProbe.watch(cart.getId());
                cartId.set(cart.getId());
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-A SETUP FAILED: " + failure.get());
            }
            context.waitTicks(10);
            runOnServer(context, server -> {
                var cart = server.getLevel(Level.OVERWORLD).getEntity(cartId.get());
                if (cart == null) {
                    failure.set("cart vanished before the shove");
                    return;
                }
                cart.setDeltaMovement(Vec3.atLowerCornerOf(crossDir.getUnitVec3i()).scale(0.4));
                SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-A shoved cart id={} {} at 0.4",
                    cartId.get(), crossDir);
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-A SETUP FAILED: " + failure.get());
            }

            // Observe. Crossing is expected within ~20 ticks; poll generously, settle, ASSERT.
            AtomicReference<Boolean> crossed = new AtomicReference<>(false);
            for (int i = 0; i < 30 && !crossed.get(); i++) {
                runOnServer(context, server -> {
                    ServerLevel far = server.getLevel(binding.destDim());
                    crossed.set(far != null && far.getEntity(cartId.get()) != null);
                });
                if (!crossed.get()) {
                    context.waitTicks(5);
                }
            }
            context.waitTicks(30);
            AtomicReference<String> verdict = new AtomicReference<>(null);
            runOnServer(context, server -> {
                ServerLevel far = server.getLevel(binding.destDim());
                Entity cart = far == null ? null : far.getEntity(cartId.get());
                if (!(cart instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart mcart)) {
                    String where = "GONE";
                    for (ServerLevel l : server.getAllLevels()) {
                        if (l.getEntity(cartId.get()) != null) {
                            where = l.dimension().identifier().toString();
                        }
                    }
                    verdict.set("the cart never arrived in " + binding.destDim().identifier()
                        + " (crossed=" + crossed.get() + ", cart in " + where
                        + ") — the live teleport machinery did not carry it");
                    return;
                }
                var u = farDir.getUnitVec3i();
                double advance = (mcart.getX() - (contC.getX() + 0.5)) * u.getX()
                    + (mcart.getZ() - (contC.getZ() + 0.5)) * u.getZ();
                SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-A REPORT: pos={} vel={}"
                        + " hSpeed={} onRails={} advance={} {}",
                    mcart.position(), mcart.getDeltaMovement(),
                    String.format(java.util.Locale.ROOT, "%.4f",
                        mcart.getDeltaMovement().horizontalDistance()),
                    mcart.isOnRails(),
                    String.format(java.util.Locale.ROOT, "%.2f", advance),
                    com.warwa.seamlessportals.passthrough.SeamCartContinuity.counters());
                if (!mcart.isOnRails()) {
                    verdict.set("the cart arrived but is OFF RAILS at " + mcart.position()
                        + " — the clean COINCIDENT crossing regressed");
                }
                else if (advance < 2.0) {
                    verdict.set("the cart is on rails but did not ROLL ON (advance=" + advance
                        + " cells past the continuation) — it arrived dead");
                }
            });
            if (verdict.get() != null) {
                throw new AssertionError(LOG + "RS-CART-A FAILED: " + verdict.get());
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-A PASS — cart crossed the"
                + " COINCIDENT seam and kept rolling on the far track (every lever direction"
                + " asserts this same outcome; the crossing works stock — instrument round"
                + " 2026-07-28)");
        }
        finally {
            // Full teardown, not just the cart: a leg that leaves forceloads, a live portal
            // cluster and an ignited frame behind taxes every later leg and keeps feeding seam
            // bindings into later global-counter gates (adversarial panel, 2026-07-28).
            try {
                runOnServer(context, server -> {
                    Integer id = cartId.get();
                    if (id != null) {
                        for (ServerLevel l : server.getAllLevels()) {
                            Entity e = l.getEntity(id);
                            if (e != null) {
                                e.discard();
                            }
                        }
                    }
                    com.warwa.seamlessportals.passthrough.SeamCartProbe.clear();
                    for (ServerLevel l : server.getAllLevels()) {
                        for (var portal : l.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                            new net.minecraft.world.phys.AABB(fx - 12, py - 12, fz - 12,
                                fx + 12, py + 12, fz + 12), x -> true)) {
                            portal.discard();
                        }
                    }
                });
                runCommands(context, List.of(
                    "fill " + (fx - 2) + " " + py + " " + (fz - 8) + " "
                        + (fx + 3) + " " + (py + 5) + " " + (fz + 8) + " minecraft:air",
                    "forceload remove " + (fx - 16) + " " + (fz - 16) + " "
                        + (fx + 16) + " " + (fz + 16)
                ));
                cleanupFarObsidianSide(context, destDimForCleanup.get(), destForCleanup.get());
            }
            catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "RS-CART-A cleanup failed", t);
            }
        }
    }

    /**
     * RS-CART-B (gate, DISJOINT/boundary-phase/same-dim) — THE (d) FIX'S PROOF, and its inversion.
     *
     * <p>The measured defect (2026-07-28 instrument round, this exact fixture): past the flush
     * plane the near dimension's next cell holds no rail, so the ONE stranded behaviour tick
     * between crossing and the END_SERVER_TICK teleport {@code comeOffTrack}s — the cart leaves
     * rail height, the teleport transfers the corrupted Y (arrival epsilon BELOW the far rail's
     * cell), {@code getCurrentBlockPosOrRailBelow} floors into the stone below forever, and the
     * cart halts ~0.4 blocks past the far plane, off-rail beside a good rail, speed halved to
     * zero. With the READ bridge ON the stranded tick stays on rails at riding height and the
     * far side re-mounts cleanly.
     *
     * <p>ASSERTED OUTCOME, lever-aware in this same leg: fix ON (default) → the cart arrives ON
     * RAILS and ROLLS ≥3 cells past the far plane, with bridge coverage
     * ({@code bridgeHits} moved) and a runaway ceiling ({@code bridgeReads} delta bounded). Fix
     * OFF ({@code -PdisableSeamCartRail}, or {@code -PdisableSeamShadow} which the bridge
     * consumes) → the measured defect reproduces on demand: cart crossed but OFF RAILS, halted
     * short. Portal pair is the RS-SIGNAL-B recipe (1×1 window, dest +60x, reverse twin) at
     * fresh coordinates (9600,100,9600), far track ending in a stone buffer stop.
     */
    private static void rsCartLegDisjoint(ClientGameTestContext context) {
        if (AperturePassthroughLever.DISABLED) {
            return;
        }
        if (AperturePassthroughLever.DISABLE_SEAM_PHASE_GATE) {
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-B SKIPPED under"
                + " -PdisableSeamPhaseGate — the boundary-phase fixture's premise does not hold");
            return;
        }
        final boolean cartFixOn = !AperturePassthroughLever.DISABLE_SEAM_CART_RAIL
            && !AperturePassthroughLever.DISABLE_SEAM_SHADOW;
        final int bx = 9600, by = 100, bz = 9600;
        final BlockPos cellN = new BlockPos(bx - 1, by, bz);
        final BlockPos cellF = new BlockPos(bx + 60, by, bz);
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<Integer> cartId = new AtomicReference<>(null);
        try {
            runCommands(context, List.of(
                "forceload add " + (bx - 16) + " " + (bz - 16) + " " + (bx + 92) + " " + (bz + 16),
                "fill " + (bx - 8) + " " + (by - 1) + " " + (bz - 3) + " "
                    + (bx + 76) + " " + (by - 1) + " " + (bz + 3) + " minecraft:stone",
                "fill " + (bx - 8) + " " + by + " " + (bz - 3) + " "
                    + (bx + 76) + " " + (by + 3) + " " + (bz + 3) + " minecraft:air"
            ));
            context.waitTicks(20);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ow.getChunk(bx >> 4, bz >> 4);
                ow.getChunk((bx + 60) >> 4, bz >> 4);
                qouteall.imm_ptl.core.portal.Portal p =
                    qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE.create(
                        ow, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                if (p == null) {
                    failure.set("portal create returned null");
                    return;
                }
                p.setOriginPos(new Vec3(bx, by + 0.5, bz + 0.5));
                p.setDestinationDimension(Level.OVERWORLD);
                p.setDestination(new Vec3(bx + 60, by + 0.5, bz + 0.5));
                p.setOrientationAndSize(new Vec3(0, 0, 1), new Vec3(0, 1, 0), 1, 1);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(p);
                qouteall.imm_ptl.core.portal.Portal q =
                    qouteall.imm_ptl.core.portal.PortalManipulation.createReversePortal(
                        p, qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(q);
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-B SETUP FAILED: " + failure.get());
            }

            AtomicReference<Boolean> bound = new AtomicReference<>(false);
            for (int attempt = 0; attempt < 20 && !bound.get(); attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    bound.set(com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellN)
                        != null);
                });
                if (!bound.get()) {
                    context.waitTicks(10);
                }
            }
            if (!bound.get()) {
                throw new AssertionError(LOG + "RS-CART-B FAILED: seam cell never bound");
            }
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellN);
                var b = cell.bindings().stream()
                    .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                    .findFirst().orElse(null);
                if (b == null) {
                    failure.set("no mirrorable binding at N=" + cellN);
                    return;
                }
                if (b.phase() != com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.DISJOINT
                    || !b.seamContinuous()) {
                    failure.set("TOPOLOGY B NOT CONSTRUCTED: phase=" + b.phase() + " continuous="
                        + b.seamContinuous());
                    return;
                }
                if (!cellF.equals(b.continuationToward(net.minecraft.core.Direction.EAST))) {
                    failure.set("continuationToward(EAST)=" + b.continuationToward(
                        net.minecraft.core.Direction.EAST) + " != far cell " + cellF);
                    return;
                }
                // Track: near approach + flush cell (as player), far side's own continuation,
                // ending in a stone buffer stop (deterministic halt ON the track — the cart's
                // post-teleport coast can exceed 9 blocks with IP's slow-cart ×2 boost).
                for (int x = bx - 7; x <= bx - 2; x++) {
                    ow.setBlock(new BlockPos(x, by, bz), Blocks.RAIL.defaultBlockState(), 3);
                }
                writeAsPlayer(ow, cellN, Blocks.RAIL.defaultBlockState());
                for (int x = bx + 60; x <= bx + 72; x++) {
                    ow.setBlock(new BlockPos(x, by, bz), Blocks.RAIL.defaultBlockState(), 3);
                }
                ow.setBlock(new BlockPos(bx + 73, by, bz), Blocks.STONE.defaultBlockState(), 3);
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-B SETUP FAILED: " + failure.get());
            }
            context.waitTicks(10);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                for (int x = bx - 7; x <= bx - 1; x++) {
                    var st = ow.getBlockState(new BlockPos(x, by, bz));
                    if (!st.is(Blocks.RAIL) || st.getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE)
                        != net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST) {
                        failure.set("near rail at x=" + x + " is " + st + " — fixture void");
                        return;
                    }
                }
                for (int x = bx + 60; x <= bx + 72; x++) {
                    var st = ow.getBlockState(new BlockPos(x, by, bz));
                    if (!st.is(Blocks.RAIL) || st.getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE)
                        != net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST) {
                        failure.set("far rail at x=" + x + " is " + st + " — fixture void");
                        return;
                    }
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-B FIXTURE FAULT: " + failure.get());
            }

            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var cart = EntityTypes.MINECART.create(ow, EntitySpawnReason.COMMAND);
                if (cart == null) {
                    failure.set("minecart create returned null");
                    return;
                }
                cart.snapTo(bx - 6 + 0.5, by + 0.1, bz + 0.5, 0f, 0f);
                ow.addFreshEntity(cart);
                com.warwa.seamlessportals.passthrough.SeamCartProbe.watch(cart.getId());
                cartId.set(cart.getId());
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-B SETUP FAILED: " + failure.get());
            }
            context.waitTicks(10);
            final long hitsBefore =
                com.warwa.seamlessportals.passthrough.SeamCartContinuity.bridgeHitsCount();
            final long readsBefore =
                com.warwa.seamlessportals.passthrough.SeamCartContinuity.bridgeReadsCount();
            runOnServer(context, server -> {
                var cart = server.getLevel(Level.OVERWORLD).getEntity(cartId.get());
                if (cart == null) {
                    failure.set("cart vanished before the shove");
                    return;
                }
                cart.setDeltaMovement(new Vec3(0.4, 0, 0));
                SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-B shoved cart id={} EAST at 0.4",
                    cartId.get());
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-B SETUP FAILED: " + failure.get());
            }

            // Same-dim: "crossed" = the cart's x jumped past the destination plane region.
            AtomicReference<Boolean> crossed = new AtomicReference<>(false);
            for (int i = 0; i < 30 && !crossed.get(); i++) {
                runOnServer(context, server -> {
                    Entity e = server.getLevel(Level.OVERWORLD).getEntity(cartId.get());
                    crossed.set(e != null && e.getX() > bx + 50);
                });
                if (!crossed.get()) {
                    context.waitTicks(5);
                }
            }
            context.waitTicks(30);
            AtomicReference<String> verdict = new AtomicReference<>(null);
            runOnServer(context, server -> {
                Entity cart = server.getLevel(Level.OVERWORLD).getEntity(cartId.get());
                long hitsDelta = com.warwa.seamlessportals.passthrough.SeamCartContinuity
                    .bridgeHitsCount() - hitsBefore;
                long readsDelta = com.warwa.seamlessportals.passthrough.SeamCartContinuity
                    .bridgeReadsCount() - readsBefore;
                if (!(cart instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart mcart)) {
                    verdict.set("cart vanished (" + cart + ")");
                    return;
                }
                SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-B REPORT (fix {}): crossed={}"
                        + " pos={} vel={} hSpeed={} onRails={} bridgeHitsDelta={} bridgeReadsDelta={}",
                    cartFixOn ? "ON" : "OFF", crossed.get(), mcart.position(),
                    mcart.getDeltaMovement(),
                    String.format(java.util.Locale.ROOT, "%.4f",
                        mcart.getDeltaMovement().horizontalDistance()),
                    mcart.isOnRails(), hitsDelta, readsDelta);
                if (!crossed.get()) {
                    verdict.set("the cart never crossed at all (x=" + mcart.getX()
                        + ") — the teleport machinery itself failed, which is upstream of (d)");
                    return;
                }
                if (cartFixOn) {
                    if (!mcart.isOnRails()) {
                        verdict.set("fix ON but the cart arrived OFF RAILS at " + mcart.position()
                            + " — the stranded-tick corruption survived the bridge");
                    }
                    else if (mcart.getX() < bx + 63) {
                        verdict.set("fix ON but the cart halted at x=" + mcart.getX()
                            + " (< " + (bx + 63) + ") — arrived but did not keep rolling");
                    }
                    else if (hitsDelta <= 0) {
                        verdict.set("COVERAGE FAILED: the crossing looks clean but the bridge"
                            + " never fired (bridgeHitsDelta=0) — the outcome came from somewhere"
                            + " else and this arm proves nothing about (d)");
                    }
                    else if (readsDelta > 5000) {
                        verdict.set("RUNAWAY: bridgeReadsDelta=" + readsDelta
                            + " for a single crossing — the volume ceiling (5000) tripped");
                    }
                }
                else {
                    // INVERSION: the measured defect must reproduce on demand — crossed but
                    // off-rail, halted just past the far plane (measured: x≈9660.4, speed→0).
                    if (mcart.isOnRails() && mcart.getX() >= bx + 63) {
                        verdict.set("INVERSION FAILED (the defect did not reproduce): fix OFF but"
                            + " the cart crossed clean to x=" + mcart.getX() + " onRails=true —"
                            + " either the lever is not wired through or the defect never existed"
                            + " here (arrival-epsilon luck is the known variable; the 2026-07-28"
                            + " measurement landed epsilon-low deterministically)");
                    }
                    else {
                        SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-B INVERSION — the"
                            + " measured defect reproduced on demand (onRails={}, x={})",
                            mcart.isOnRails(), mcart.getX());
                    }
                }
            });
            if (verdict.get() != null) {
                throw new AssertionError(LOG + "RS-CART-B FAILED: " + verdict.get());
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-B PASS (fix {})",
                cartFixOn ? "ON — cart crossed the DISJOINT seam and kept rolling, bridge"
                    + " coverage moved, volume bounded" : "OFF — defect reproduced on demand");
        }
        finally {
            try {
                runOnServer(context, server -> {
                    Integer id = cartId.get();
                    if (id != null) {
                        for (ServerLevel l : server.getAllLevels()) {
                            Entity e = l.getEntity(id);
                            if (e != null) {
                                e.discard();
                            }
                        }
                    }
                    com.warwa.seamlessportals.passthrough.SeamCartProbe.clear();
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    for (var portal : ow.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                        new net.minecraft.world.phys.AABB(bx - 4, by - 4, bz - 4,
                            bx + 76, by + 6, bz + 4), x -> true)) {
                        portal.discard();
                    }
                });
                runCommands(context, List.of(
                    "fill " + (bx - 8) + " " + (by - 1) + " " + (bz - 3) + " "
                        + (bx + 76) + " " + (by + 3) + " " + (bz + 3) + " minecraft:air",
                    "forceload remove " + (bx - 16) + " " + (bz - 16) + " "
                        + (bx + 92) + " " + (bz + 16)
                ));
            }
            catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "RS-CART-B cleanup failed", t);
            }
        }
    }

    /**
     * RS-CART-D (MEASUREMENT leg, probe-gated, report-only) — the RIDDEN crossing window.
     *
     * <p>Why it exists. A cart carrying a player never uses the regular-entity teleport pipeline
     * the 2026-07-28 instrument round measured: {@code startTeleportingRegularEntity} skips it
     * every tick on the vehicle/player-cluster gate ((d)'s own probe line names that gate), and
     * the cart is carried only when the PLAYER's client-detected crossing round-trips to the
     * server. So the entity path's "&le;1 stranded tick" bound — the whole basis for a one-cell
     * bridge — does not apply, and the adversarial panel flagged the mismatch as the most likely
     * thing a live user hits first. Whether the window actually exceeds one cell is a question
     * about client/server timing, which is measured, not argued (the house rule that decided the
     * design in the first place).
     *
     * <p>What it records, per tick, through {@link
     * com.warwa.seamlessportals.passthrough.SeamCartProbe}: the cart's near-level SAMPLE lines
     * while the player's crossing is in flight, any COME-OFF-TRACK with its failing cell, and the
     * VEHICLE-CARRY EVT lines from {@code ServerTeleportationManager} that mark the instant the
     * cart is actually moved — the difference between the first past-plane SAMPLE and that line
     * IS the window.
     *
     * <p>Report-only by design: fixture faults throw, but cart/player behaviour is logged, not
     * asserted. Promote to an asserting gate once the measurement says what the correct outcome
     * is — the same sequence arms A/B went through.
     */
    private static void rsCartLegRiddenProbe(ClientGameTestContext context, int py) {
        if (AperturePassthroughLever.DISABLED || AperturePassthroughLever.DISABLE_SEAM_SHADOW) {
            return;
        }
        final int fx = 11200, fz = -11200;
        final BlockPos cellS = new BlockPos(fx, py + 1, fz);
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<Integer> cartId = new AtomicReference<>(null);
        AtomicReference<BlockPos> destForCleanup = new AtomicReference<>(null);
        AtomicReference<String> destDimForCleanup = new AtomicReference<>(null);
        String prevDim = context.computeOnClient(mc ->
            mc.level == null ? null : mc.level.dimension().identifier().toString());
        Vec3 prevPos = context.computeOnClient(mc ->
            mc.player == null ? Vec3.ZERO : mc.player.position());
        try {
            runCommands(context, List.of(
                "execute in minecraft:overworld run forceload add " + (fx - 16) + " " + (fz - 16)
                    + " " + (fx + 16) + " " + (fz + 16),
                "execute in minecraft:overworld run fill " + (fx - 2) + " " + py + " " + (fz - 12)
                    + " " + (fx + 3) + " " + (py + 5) + " " + (fz + 12) + " minecraft:air",
                inDim("minecraft:overworld", fill(fx - 1, py, fz, fx + 2, py, fz)),
                inDim("minecraft:overworld", fill(fx - 1, py + 4, fz, fx + 2, py + 4, fz)),
                inDim("minecraft:overworld", fill(fx - 1, py + 1, fz, fx - 1, py + 3, fz)),
                inDim("minecraft:overworld", fill(fx + 2, py + 1, fz, fx + 2, py + 3, fz))
            ));
            context.waitTicks(20);
            runOnServer(context, server -> {
                boolean fired = qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration
                    .onFireLitOnObsidian(server.getLevel(Level.OVERWORLD), cellS, null);
                if (!fired) {
                    failure.set("ignition entry rejected the frame");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-D SETUP FAILED: " + failure.get());
            }
            final net.minecraft.world.phys.AABB frameBox = new net.minecraft.world.phys.AABB(
                fx - 8, py - 8, fz - 8, fx + 8, py + 8, fz + 8);
            try {
                context.waitFor(mc -> {
                    MinecraftServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        return false;
                    }
                    return !server.getLevel(Level.OVERWORLD).getEntitiesOfClass(
                        qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                        frameBox, x -> true).isEmpty();
                }, 1200);
            }
            catch (Throwable t) {
                throw new AssertionError(LOG + "RS-CART-D FAILED: no NetherPortalEntity within"
                    + " 1200 ticks", t);
            }

            AtomicReference<com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding> bindingRef =
                new AtomicReference<>(null);
            for (int attempt = 0; attempt < 30 && bindingRef.get() == null; attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                    if (cell == null) {
                        return;
                    }
                    cell.bindings().stream()
                        .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                        .findFirst().ifPresent(bindingRef::set);
                });
                if (bindingRef.get() == null) {
                    context.waitTicks(10);
                }
            }
            var binding = bindingRef.get();
            if (binding == null) {
                throw new AssertionError(LOG + "RS-CART-D FAILED: seam cell never bound");
            }
            final net.minecraft.core.Direction crossDir = binding.crossDir();
            final net.minecraft.core.Direction backDir = crossDir.getOpposite();
            final net.minecraft.core.Direction farDir =
                com.warwa.seamlessportals.passthrough.SeamRegistry.mapDir(binding, crossDir);
            final BlockPos cellD = binding.destPos();
            final BlockPos contC = binding.continuationToward(crossDir);
            final String destDimId = binding.destDim().identifier().toString();
            destForCleanup.set(cellD);
            destDimForCleanup.set(destDimId);
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-D geometry: S={} crossDir={} D={}"
                + " in {} continuation={} farDir={}", cellS, crossDir, cellD, destDimId, contC,
                farDir);

            // Far side: forceload, carve a rider-sized corridor (the arrival must be survivable
            // and lava-free — a nether counterpart lands wherever the matcher puts it).
            runCommands(context, List.of(
                inDim(destDimId, "forceload add " + (cellD.getX() - 16) + " " + (cellD.getZ() - 16)
                    + " " + (cellD.getX() + 16) + " " + (cellD.getZ() + 16))));
            runOnServer(context, server -> {
                ServerLevel far = server.getLevel(binding.destDim());
                if (far == null) {
                    failure.set("destination level missing");
                    return;
                }
                for (int k = 0; k < 10; k++) {
                    BlockPos fp = contC.relative(farDir, k);
                    far.getChunk(fp.getX() >> 4, fp.getZ() >> 4);
                    for (int lat = -2; lat <= 2; lat++) {
                        BlockPos w = fp.relative(farDir.getClockWise(), lat);
                        far.setBlock(w.below(), Blocks.STONE.defaultBlockState(), 3);
                        for (int up = 0; up <= 3; up++) {
                            far.setBlock(w.above(up), Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                    far.setBlock(fp, Blocks.RAIL.defaultBlockState(), 3);
                }
                BlockPos stop = contC.relative(farDir, 10);
                far.setBlock(stop.below(), Blocks.STONE.defaultBlockState(), 3);
                far.setBlock(stop, Blocks.STONE.defaultBlockState(), 3);

                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                for (int k = 1; k <= 8; k++) {
                    BlockPos ap = cellS.relative(backDir, k);
                    ow.setBlock(ap.below(), Blocks.STONE.defaultBlockState(), 3);
                    ow.setBlock(ap.above(), Blocks.AIR.defaultBlockState(), 3);
                    ow.setBlock(ap, Blocks.RAIL.defaultBlockState(), 3);
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-D SETUP FAILED: " + failure.get());
            }
            runOnServer(context, server -> writeAsPlayer(server.getLevel(Level.OVERWORLD), cellS,
                Blocks.RAIL.defaultBlockState()));
            context.waitTicks(10);

            // Bring the real player to the track and let the client load in — the crossing under
            // test is CLIENT-detected, so the client must actually be here and ticking.
            BlockPos start = cellS.relative(backDir, 7);
            runCommands(context, List.of(
                "execute in minecraft:overworld run tp @p " + (start.getX() + 0.5) + " "
                    + (start.getY()) + " " + (start.getZ() + 0.5) + " "
                    + yawOf(crossDir) + " 0"));
            context.waitTicks(60);

            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var player = server.getPlayerList().getPlayers().get(0);
                if (player.level() != ow) {
                    failure.set("player is in " + player.level().dimension().identifier()
                        + ", not the overworld — cannot stage the ride");
                    return;
                }
                var cart = EntityTypes.MINECART.create(ow, EntitySpawnReason.COMMAND);
                if (cart == null) {
                    failure.set("minecart create returned null");
                    return;
                }
                cart.snapTo(start.getX() + 0.5, start.getY() + 0.1, start.getZ() + 0.5, 0f, 0f);
                ow.addFreshEntity(cart);
                com.warwa.seamlessportals.passthrough.SeamCartProbe.watch(cart.getId());
                cartId.set(cart.getId());
                if (!player.startRiding(cart, true, true)) {
                    failure.set("player refused to mount the cart");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-D SETUP FAILED: " + failure.get());
            }
            context.waitTicks(20);
            runOnServer(context, server -> {
                var player = server.getPlayerList().getPlayers().get(0);
                if (player.getVehicle() == null) {
                    failure.set("the player is not riding after 20 ticks — mount did not stick");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-D SETUP FAILED: " + failure.get());
            }

            runOnServer(context, server -> {
                var cart = server.getLevel(Level.OVERWORLD).getEntity(cartId.get());
                if (cart == null) {
                    failure.set("cart vanished before the shove");
                    return;
                }
                cart.setDeltaMovement(Vec3.atLowerCornerOf(crossDir.getUnitVec3i()).scale(0.4));
                SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-D shoved the RIDDEN cart id={}"
                    + " {} at 0.4 — measuring the crossing window", cartId.get(), crossDir);
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-D SETUP FAILED: " + failure.get());
            }

            AtomicReference<Boolean> playerCrossed = new AtomicReference<>(false);
            for (int i = 0; i < 40 && !playerCrossed.get(); i++) {
                runOnServer(context, server -> {
                    var player = server.getPlayerList().getPlayers().get(0);
                    playerCrossed.set(player.level().dimension().equals(binding.destDim()));
                });
                if (!playerCrossed.get()) {
                    context.waitTicks(5);
                }
            }
            context.waitTicks(40);
            runOnServer(context, server -> {
                var player = server.getPlayerList().getPlayers().get(0);
                Entity cart = null;
                String cartWhere = "GONE";
                for (ServerLevel l : server.getAllLevels()) {
                    Entity e = l.getEntity(cartId.get());
                    if (e != null) {
                        cart = e;
                        cartWhere = l.dimension().identifier().toString();
                        break;
                    }
                }
                int offs = com.warwa.seamlessportals.passthrough.SeamCartProbe
                    .comeOffTrackCount(cartId.get());
                if (cart instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart mcart) {
                    var u = farDir.getUnitVec3i();
                    double advance = (mcart.getX() - (contC.getX() + 0.5)) * u.getX()
                        + (mcart.getZ() - (contC.getZ() + 0.5)) * u.getZ();
                    SeamlessPortalsConstants.LOGGER.info(LOG + "★ RS-CART-D MEASUREMENT:"
                            + " playerCrossed={} playerDim={} cartDim={} cartPos={} vel={}"
                            + " onRails={} stillRidden={} advance={} comeOffTracks={} {}",
                        playerCrossed.get(), player.level().dimension().identifier(), cartWhere,
                        mcart.position(), mcart.getDeltaMovement(), mcart.isOnRails(),
                        player.getVehicle() == mcart,
                        String.format(java.util.Locale.ROOT, "%.2f", advance), offs,
                        com.warwa.seamlessportals.passthrough.SeamCartContinuity.counters());
                }
                else {
                    SeamlessPortalsConstants.LOGGER.info(LOG + "★ RS-CART-D MEASUREMENT:"
                            + " playerCrossed={} playerDim={} cart={} comeOffTracks={}",
                        playerCrossed.get(), player.level().dimension().identifier(), cartWhere,
                        offs);
                }
            });

            // ===== RS-CART-D CLIENT ARM (the (e) requirement) ===================================
            // Until now this leg ASSERTED NOTHING about the outcome — it logged a MEASUREMENT line
            // and threw only on setup failure, so it could not have gone red no matter what the
            // crossing did. That is why it reported stillRidden=true onRails=true advance=9.01 for
            // the very crossing the user watched break: not merely because its assertions read
            // server state, but because it had none. Both halves are fixed here — the arm asserts,
            // and it asserts on the CLIENT.
            //
            // COVERAGE FIRST: playerCrossed was polled in a bounded loop above and then only
            // LOGGED. Without this throw, a run where the crossing never happened reaches the arm
            // with a stationary ridden cart on the NEAR side — which satisfies riding, riderGap and
            // a zero carry drift trivially. RS-CART-E asserts its crossing server-side before its
            // arm; RS-CART-D did not, so the arm could have passed on a no-crossing run.
            if (!playerCrossed.get()) {
                throw new AssertionError(LOG + "RS-CART-D FAILED: the ridden player never reached "
                    + binding.destDim().identifier() + " — the crossing under test did not happen,"
                    + " so the client arm would be judging a cart still on the near side");
            }
            String clientVerdict = clientRiddenArmVerdict(context, cartId.get());
            if (clientVerdict != null) {
                throw new AssertionError(LOG + "RS-CART-D CLIENT ARM FAILED: " + clientVerdict);
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-D PASS ({})",
                AperturePassthroughLever.DISABLE_CROSS_DIM_POSITION_CODEC_SYNC
                    ? "codec rebase OFF — the stale relative-move base reproduced on demand"
                    : "codec rebase ON — the client rider arrived with the cart");
        }
        finally {
            try {
                runOnServer(context, server -> {
                    var players = server.getPlayerList().getPlayers();
                    if (!players.isEmpty() && players.get(0).getVehicle() != null) {
                        players.get(0).stopRiding();
                    }
                    Integer id = cartId.get();
                    if (id != null) {
                        for (ServerLevel l : server.getAllLevels()) {
                            Entity e = l.getEntity(id);
                            if (e != null) {
                                e.discard();
                            }
                        }
                    }
                    com.warwa.seamlessportals.passthrough.SeamCartProbe.clear();
                    for (ServerLevel l : server.getAllLevels()) {
                        for (var portal : l.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                            new net.minecraft.world.phys.AABB(fx - 12, py - 12, fz - 12,
                                fx + 12, py + 12, fz + 12), x -> true)) {
                            portal.discard();
                        }
                    }
                });
                if (prevDim != null) {
                    runCommands(context, List.of("execute in " + prevDim + " run tp @p "
                        + prevPos.x + " " + prevPos.y + " " + prevPos.z));
                    context.waitTicks(20);
                }
                runCommands(context, List.of(
                    "execute in minecraft:overworld run fill " + (fx - 2) + " " + py + " "
                        + (fz - 12) + " " + (fx + 3) + " " + (py + 5) + " " + (fz + 12)
                        + " minecraft:air",
                    "execute in minecraft:overworld run forceload remove " + (fx - 16) + " "
                        + (fz - 16) + " " + (fx + 16) + " " + (fz + 16)
                ));
                cleanupFarObsidianSide(context, destDimForCleanup.get(), destForCleanup.get());
            }
            catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "RS-CART-D cleanup failed", t);
            }
        }
    }

    /**
     * The (e) CLIENT ARM shared by RS-CART-D (cross-dim) and RS-CART-E (same-dim): assert on the
     * side of the wire the rider's eyes are on.
     *
     * <p>Returns {@code null} on pass, or the failure text. Lever-aware: with
     * {@code -PdisableCrossDimPositionCodecSync} it asserts the OPPOSITE — that the stale base is
     * reachable again — so a green run cannot mean "the defect never existed here".
     *
     * <p>Reads {@code Entity.getPositionCodec().getBase()} (javap: {@code VecDeltaCodec.getBase}
     * is public) on the CLIENT's own copy of the cart. That base is what
     * {@code ClientPacketListener.handleMoveEntity} decodes relative-move deltas against, so a
     * stale one is precisely the precondition for the 40,000-block yank that stranded the rider.
     */
    private static String clientRiddenArmVerdict(ClientGameTestContext context, Integer cartId) {
        if (cartId == null) {
            return "COVERAGE FAILED: no cart id — the client arm judged nothing";
        }
        String[] report = context.computeOnClient(mc -> {
            if (mc.player == null || mc.level == null) {
                return new String[] {"COVERAGE", "no client player/level"};
            }
            Entity clientCart = mc.level.getEntity(cartId);
            if (clientCart == null) {
                return new String[] {"COVERAGE",
                    "the client has no entity with id " + cartId + " — the arm cannot judge"};
            }
            Vec3 cartPos = clientCart.position();
            Vec3 playerPos = mc.player.position();
            boolean riding = mc.player.getVehicle() != null
                && mc.player.getVehicle().getId() == cartId.intValue();
            double riderGap = playerPos.distanceTo(cartPos);
            // LATCHED at the carry, not read here. A live read of getPositionCodec().getBase() is
            // worthless post-hoc: vanilla rebases it on every position packet, so it measured ~0
            // with the fix OFF while the rider was stranded 13,863 blocks away.
            double carryDrift =
                com.warwa.seamlessportals.passthrough.SeamRideProbe.lastCarryBaseDrift();
            int carrySamples =
                com.warwa.seamlessportals.passthrough.SeamRideProbe.carrySamples();
            double speed = clientCart.getDeltaMovement().length();
            return new String[] {"OK",
                "riding=" + riding + " cartPos=" + cartPos
                    + " riderGap=" + String.format(java.util.Locale.ROOT, "%.3f", riderGap)
                    + " cartSpeed=" + String.format(java.util.Locale.ROOT, "%.3f", speed)
                    + " carryBaseDrift(latched)="
                    + String.format(java.util.Locale.ROOT, "%.3f", carryDrift)
                    + " carrySamples=" + carrySamples,
                String.valueOf(riding), String.valueOf(carryDrift),
                String.valueOf(riderGap), String.valueOf(carrySamples),
                String.valueOf(speed)};
        });
        if (report == null || "COVERAGE".equals(report[0])) {
            return "COVERAGE FAILED: " + (report == null ? "computeOnClient returned null"
                : report[1]);
        }
        SeamlessPortalsConstants.LOGGER.info(LOG + "★ RS-CART CLIENT ARM: {}", report[1]);
        boolean riding = Boolean.parseBoolean(report[2]);
        double carryDrift = Double.parseDouble(report[3]);
        double riderGap = Double.parseDouble(report[4]);
        int carrySamples = Integer.parseInt(report[5]);
        double speed = Double.parseDouble(report[6]);

        // COVERAGE FIRST: without an observed client-side carry, neither arm below judged anything.
        if (carrySamples == 0) {
            return "COVERAGE FAILED: no client-side portal carry was recorded, so the arm has"
                + " nothing to judge — the crossing did not go through a carry site on the client";
        }

        if (!AperturePassthroughLever.DISABLE_CROSS_DIM_POSITION_CODEC_SYNC) {
            if (!riding) {
                return "the CLIENT is not riding the cart after the crossing (server said it was)";
            }
            // THE OUTCOME THE RIDER SEES. Measured 0.412 (the attachment offset) with the fix and
            // 13863.613 without it, so the threshold separates them by four orders of magnitude.
            // Scaled with the cart's speed rather than fixed at 1.0: the client cart is
            // non-authoritative, so while it is MOVING its position legitimately trails the base
            // each packet sets (updateInterval 3 ticks x DEFAULT_INTERPOLATION_STEPS 3), and a
            // fixed tolerance is a latent spurious red on a fixture that samples mid-roll.
            double gapTolerance = 1.0 + 6.0 * speed;
            if (riderGap > gapTolerance) {
                return "the CLIENT's player is " + riderGap + " blocks from the cart it is"
                    + " riding (tolerance " + gapTolerance + " at speed " + speed + ") — the rider"
                    + " has been dragged off the carry position";
            }
            // THE MECHANISM, latched at the carry. Deterministic: the rebase either happened or
            // it did not.
            if (carryDrift > 0.001) {
                return "the carried cart's relative-move base was " + carryDrift + " blocks from"
                    + " its carry position AT THE CARRY — the rebase did not happen, and the next"
                    + " MoveEntity packet would decode its deltas against that stale base";
            }
            return null;
        }

        // Lever ON: the pre-fix behaviour must be reachable, or this arm proves nothing.
        // Assert on the LATCHED drift, which the carry sets deterministically. Do NOT assert on
        // riderGap here: the stranding needs a relative-move packet to land in the window after
        // the carry, which is a race — it reproduced at 13863.613 on 2026-08-01, but a gate that
        // demanded it would flake.
        if (carryDrift <= 1.0) {
            return "INVERSION FAILED: with -PdisableCrossDimPositionCodecSync the carried cart's"
                + " relative-move base should still hold its PRE-CARRY position at the moment of"
                + " the carry, but the latched drift is only " + carryDrift + " — either the"
                + " fixture stopped exercising the carry path or the lever is dead code";
        }
        SeamlessPortalsConstants.LOGGER.info(LOG + "★ RS-CART CLIENT ARM: inversion reproduced the"
            + " stale base at the carry (latched drift {}, riderGap {})", carryDrift, riderGap);
        return null;
    }

    /**
     * RS-CART-F (probe-gated) — DEFECT A: does a FAR same-dimension portal window keep its
     * entities? Closes the coverage gap the {@code build.gradle} comment used to claim was already
     * closed.
     *
     * <p><b>What it asserts, and why in this shape.</b> The defect is that
     * {@code LevelExtractor.isEntityVisible}'s trailing compiled-section test resolved through
     * {@code ViewArea.getRenderSectionAt}, which wraps modulo the preset grid; for a same-dim
     * destination outside that window it answered about an unrelated section near the player,
     * normally UNCOMPILED, so every entity in the window was culled while its terrain drew.
     *
     * <p>A pixel arm is the obvious instinct and is a trap here: it needs the aliased section to
     * be uncompiled AND the destination outside the window AND the sampled patch to land on the
     * entity, and a fixture missing any of those photographs a wall and calls it proof. So this
     * asserts the gate's own decisions instead, which are exact:
     * <ul>
     *   <li>COVERAGE — {@code gateCalls > 0}: the dest-pass entity gate actually ran.</li>
     *   <li>PRECONDITION — {@code flipped > 0}: at least one entity was in a section whose WRAPPED
     *       lookup said "uncompiled, cull" while its EXACT lookup said "compiled, keep". That is
     *       the defect condition itself, asserted rather than assumed — if the fixture fails to
     *       produce it the leg says so instead of passing.</li>
     *   <li>OUTCOME — {@code aliasedKept > 0} with the fix, {@code == 0} under
     *       {@code -PdisableDestEntitySectionExact}. Both lookups are computed in both directions,
     *       so what inverts is which verdict was USED: the entities the fix rescued.</li>
     * </ul>
     *
     * <p>Destination is +400 blocks and 80 blocks DOWN. The Y drop is load-bearing: a horizontal
     * offset alone aliases onto open-air sections near the player, which the occlusion graph
     * compiles to empty meshes ({@code mesh != UNCOMPILED}), and the defect would not reproduce.
     */
    private static void rsCartWindowEntityGate(ClientGameTestContext context) {
        if (AperturePassthroughLever.DISABLED) {
            return;
        }
        final int bx = 9600, by = 100, bz = 9600;
        final int ddz = 400;
        // The Y is chosen so the ALIAS lands where the client never meshes. The wrap folds X/Z into
        // the preset window but PRESERVES the section-Y index, so the aliased section sits at the
        // destination's own Y near the player. First build used dy=20 (section-Y 1, ~80 blocks
        // below a y=100 player): measured gateCalls=120, disagree=120 — the aliasing fired every
        // time — but flipped=0, because those shallow sections are still within the occlusion
        // graph's reach and get COMPILED, so both lookups answered "keep" and nothing was culled.
        // dy=-60 puts the alias at section-Y -4, ~160 blocks below the player and far outside the
        // 6-chunk render distance, which the BFS never queues.
        final int dy = -60;
        final BlockPos cellD = new BlockPos(bx, dy, bz + ddz);
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<Integer> cartId = new AtomicReference<>(null);
        String prevDim = context.computeOnClient(mc ->
            mc.level == null ? null : mc.level.dimension().identifier().toString());
        Vec3 prevPos = context.computeOnClient(mc ->
            mc.player == null ? Vec3.ZERO : mc.player.position());
        try {
            runCommands(context, List.of(
                "execute in minecraft:overworld run forceload add " + (bx - 16) + " " + (bz - 16)
                    + " " + (bx + 16) + " " + (bz + 16),
                "execute in minecraft:overworld run forceload add " + (bx - 16) + " "
                    + (bz + ddz - 16) + " " + (bx + 16) + " " + (bz + ddz + 16),
                // near platform
                inDim("minecraft:overworld", fill(bx - 6, by - 1, bz - 8, bx + 6, by - 1, bz + 4)),
                "execute in minecraft:overworld run fill " + (bx - 6) + " " + by + " " + (bz - 8)
                    + " " + (bx + 6) + " " + (by + 6) + " " + (bz + 4) + " minecraft:air",
                // destination chamber, 80 blocks lower
                inDim("minecraft:overworld",
                    fill(bx - 6, dy - 1, bz + ddz - 6, bx + 6, dy - 1, bz + ddz + 8)),
                "execute in minecraft:overworld run fill " + (bx - 6) + " " + dy + " "
                    + (bz + ddz - 6) + " " + (bx + 6) + " " + (dy + 6) + " " + (bz + ddz + 8)
                    + " minecraft:air"
            ));
            context.waitTicks(20);

            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ow.getChunk(bx >> 4, bz >> 4);
                ow.getChunk(bx >> 4, (bz + ddz) >> 4);
                qouteall.imm_ptl.core.portal.Portal p =
                    qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE.create(
                        ow, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                if (p == null) {
                    failure.set("portal create returned null");
                    return;
                }
                p.setOriginPos(new Vec3(bx + 0.5, by + 1.5, bz + 0.5));
                p.setDestinationDimension(Level.OVERWORLD);
                p.setDestination(new Vec3(bx + 0.5, dy + 1.5, bz + ddz + 0.5));
                p.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 5, 5);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(p);
                // BI-FACED, so the leg does not depend on which way the normal came out.
                // setOrientationAndSize((1,0,0),(0,1,0)) yields a +Z normal, i.e. the front face is
                // only visible from +Z — and the first build stood the player at -Z looking at the
                // BACK face, rendered nothing, and reported gateCalls=0. That is the same mistake
                // rsCartLegRiddenSameDimProbe's own comment records ("straight into the portal's
                // BACK face"). A flipped twin at the same origin removes the dependency entirely
                // rather than making the fixture author get the cross product right.
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(
                    qouteall.imm_ptl.core.portal.PortalManipulation.createFlippedPortal(
                        p, qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE));

                // The entity the window must show, parked just past the destination plane.
                var cart = EntityTypes.MINECART.create(
                    ow, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                if (cart == null) {
                    failure.set("minecart create returned null");
                    return;
                }
                cart.snapTo(bx + 0.5, dy + 0.1, bz + ddz + 3.5, 0f, 0f);
                ow.addFreshEntity(cart);
                cartId.set(cart.getId());
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-F SETUP FAILED: " + failure.get());
            }
            context.waitTicks(30);

            // Stand in front of the near plane looking through it (+Z is yaw 0).
            runCommands(context, List.of(
                "execute in minecraft:overworld run tp @p " + (bx + 0.5) + " " + by + " "
                    + (bz - 5.5) + " 0 0"));
            context.waitTicks(60);

            // Reset AFTER staging so the counters describe only this leg's frames — a leg that
            // inherits another's counters cannot assert coverage.
            context.runOnClient(mc ->
                com.warwa.seamlessportals.render.CartWindowProbe.reset());
            context.waitTicks(80);

            long gateCalls = context.computeOnClient(mc ->
                com.warwa.seamlessportals.render.CartWindowProbe.gateCalls());
            long flipped = context.computeOnClient(mc ->
                com.warwa.seamlessportals.render.CartWindowProbe.flipped());
            long aliasedKept = context.computeOnClient(mc ->
                com.warwa.seamlessportals.render.CartWindowProbe.aliasedKept());
            String counters = context.computeOnClient(mc ->
                com.warwa.seamlessportals.render.CartWindowProbe.counters());
            SeamlessPortalsConstants.LOGGER.info(LOG + "★ RS-CART-F MEASUREMENT: {}", counters);

            if (gateCalls == 0) {
                throw new AssertionError(LOG + "RS-CART-F COVERAGE FAILED: the dest-pass entity"
                    + " visibility gate never ran — the portal was not rendered, so nothing here"
                    + " judged anything (" + counters + ")");
            }
            if (flipped == 0) {
                throw new AssertionError(LOG + "RS-CART-F FIXTURE FAILED: no entity was in a"
                    + " section whose wrapped lookup disagreed with the exact one, so the aliasing"
                    + " condition this leg exists to test was never present. Do NOT read this as a"
                    + " pass: the destination must be outside the ViewArea preset window AND its"
                    + " aliased section must be UNCOMPILED (" + counters + ")");
            }
            if (!AperturePassthroughLever.DISABLE_DEST_ENTITY_SECTION_EXACT) {
                if (aliasedKept == 0) {
                    throw new AssertionError(LOG + "RS-CART-F FAILED: " + flipped + " entity"
                        + " verdicts were reachable only through the exact lookup, but NONE was"
                        + " kept — the far same-dim window is dropping its entities again ("
                        + counters + ")");
                }
                SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-F PASS (fix ON — {} aliased"
                    + " entity verdicts rescued)", aliasedKept);
            }
            else {
                if (aliasedKept != 0) {
                    throw new AssertionError(LOG + "RS-CART-F INVERSION FAILED: with"
                        + " -PdisableDestEntitySectionExact the wrapped lookup should decide, so"
                        + " no aliased entity should be kept — but " + aliasedKept + " were ("
                        + counters + ")");
                }
                SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-F PASS (lever ON — the far"
                    + " same-dim window dropped all {} aliased entity verdicts, defect"
                    + " reproduced)", flipped);
            }
        }
        finally {
            try {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    Integer id = cartId.get();
                    if (id != null) {
                        Entity e = ow.getEntity(id);
                        if (e != null) {
                            e.discard();
                        }
                    }
                    for (var portal : ow.getEntitiesOfClass(
                        qouteall.imm_ptl.core.portal.Portal.class,
                        new net.minecraft.world.phys.AABB(bx - 12, dy - 12, bz - 12,
                            bx + 12, by + 12, bz + ddz + 12), x -> true)) {
                        portal.discard();
                    }
                });
                context.runOnClient(mc ->
                    com.warwa.seamlessportals.render.CartWindowProbe.reset());
                if (prevDim != null) {
                    runCommands(context, List.of("execute in " + prevDim + " run tp @p "
                        + prevPos.x + " " + prevPos.y + " " + prevPos.z));
                    context.waitTicks(20);
                }
                runCommands(context, List.of(
                    "execute in minecraft:overworld run fill " + (bx - 6) + " " + (by - 1) + " "
                        + (bz - 8) + " " + (bx + 6) + " " + (by + 6) + " " + (bz + 4)
                        + " minecraft:air",
                    "execute in minecraft:overworld run fill " + (bx - 6) + " " + (dy - 1) + " "
                        + (bz + ddz - 6) + " " + (bx + 6) + " " + (dy + 6) + " "
                        + (bz + ddz + 8) + " minecraft:air",
                    "execute in minecraft:overworld run forceload remove " + (bx - 16) + " "
                        + (bz - 16) + " " + (bx + 16) + " " + (bz + 16),
                    "execute in minecraft:overworld run forceload remove " + (bx - 16) + " "
                        + (bz + ddz - 16) + " " + (bx + 16) + " " + (bz + ddz + 16)
                ));
            }
            catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "RS-CART-F cleanup failed — later legs"
                    + " may see leftover staging", t);
            }
        }
    }

    /**
     * Tear down the FAR half of an obsidian cart fixture: portal entities near the destination
     * aperture, the generated frame, our track, and the forceload. The near-side box never
     * contains the counterpart (a nether twin sits at 1/8 the overworld coordinates), so a leg
     * that only cleans its own side leaves a live portal and a MATCHABLE obsidian frame behind —
     * which has already made one later leg link to the wrong portal in this suite's history.
     */
    private static void cleanupFarObsidianSide(
        ClientGameTestContext context, String destDimId, BlockPos cellD
    ) {
        if (destDimId == null || cellD == null) {
            return;
        }
        runOnServer(context, server -> {
            for (ServerLevel l : server.getAllLevels()) {
                if (!l.dimension().identifier().toString().equals(destDimId)) {
                    continue;
                }
                for (var portal : l.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                    new net.minecraft.world.phys.AABB(
                        cellD.getX() - 16, cellD.getY() - 16, cellD.getZ() - 16,
                        cellD.getX() + 16, cellD.getY() + 16, cellD.getZ() + 16), x -> true)) {
                    portal.discard();
                }
            }
        });
        runCommands(context, List.of(
            inDim(destDimId, "fill " + (cellD.getX() - 12) + " " + (cellD.getY() - 3) + " "
                + (cellD.getZ() - 12) + " " + (cellD.getX() + 12) + " " + (cellD.getY() + 8) + " "
                + (cellD.getZ() + 12) + " minecraft:air"),
            inDim(destDimId, "forceload remove " + (cellD.getX() - 16) + " " + (cellD.getZ() - 16)
                + " " + (cellD.getX() + 16) + " " + (cellD.getZ() + 16))
        ));
    }

    /**
     * RS-CART-E (probe-gated) — THE USER'S OWN GEOMETRY: a RIDDEN cart through a SAME-DIMENSION
     * COINCIDENT pair, which is where the 2026-07-28 live round showed every ridden arrival
     * landing 0.1875 ABOVE rail riding height (y=…250 instead of …063), in both directions,
     * five times out of five — while the CROSS-DIM ridden arm (RS-CART-D) landed exactly on the
     * rail. Two different carry paths: cross-dim goes through {@code changePlayerDimension}
     * (which dismounts, moves, remounts), same-dim never does and relies on {@code adjustVehicle}
     * alone.
     *
     * <p>Both arrival heights are {@code player.position() + attachment} by construction, so the
     * discriminator is the PLAYER's arrival height; the CARRY-TERMS probe lines dump every term.
     * Fixture mirrors the user's pair: planes at cell centres (mid-block ⇒ COINCIDENT), rails
     * straight through, destination far enough that the two ends never share chunks.
     */
    private static void rsCartLegRiddenSameDimProbe(ClientGameTestContext context) {
        if (AperturePassthroughLever.DISABLED) {
            return;
        }
        final int bx = 12800, by = 100, bz = 12800;
        final int ddz = 400;                       // destination 400 blocks down +Z
        final BlockPos cellS = new BlockPos(bx, by, bz);
        final BlockPos cellD = new BlockPos(bx, by, bz + ddz);
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<Integer> cartId = new AtomicReference<>(null);
        String prevDim = context.computeOnClient(mc ->
            mc.level == null ? null : mc.level.dimension().identifier().toString());
        Vec3 prevPos = context.computeOnClient(mc ->
            mc.player == null ? Vec3.ZERO : mc.player.position());
        try {
            runCommands(context, List.of(
                "execute in minecraft:overworld run forceload add " + (bx - 16) + " " + (bz - 16)
                    + " " + (bx + 16) + " " + (bz + ddz + 16),
                "execute in minecraft:overworld run fill " + (bx - 3) + " " + (by - 1) + " "
                    + (bz - 12) + " " + (bx + 3) + " " + (by - 1) + " " + (bz + ddz + 12)
                    + " minecraft:stone",
                "execute in minecraft:overworld run fill " + (bx - 3) + " " + by + " "
                    + (bz - 12) + " " + (bx + 3) + " " + (by + 4) + " " + (bz + ddz + 12)
                    + " minecraft:air"
            ));
            context.waitTicks(20);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ow.getChunk(bx >> 4, bz >> 4);
                ow.getChunk(bx >> 4, (bz + ddz) >> 4);
                // Planes at CELL CENTRES => the plane bisects the cell => COINCIDENT, the user's
                // topology (a /portal-made mid-block pair), not the flush topology of RS-CART-B.
                qouteall.imm_ptl.core.portal.Portal p =
                    qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE.create(
                        ow, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                if (p == null) {
                    failure.set("portal create returned null");
                    return;
                }
                p.setOriginPos(new Vec3(bx + 0.5, by + 1.5, bz + 0.5));
                p.setDestinationDimension(Level.OVERWORLD);
                p.setDestination(new Vec3(bx + 0.5, by + 1.5, bz + ddz + 0.5));
                p.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 3, 3);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(p);
                qouteall.imm_ptl.core.McHelper.spawnServerEntity(
                    qouteall.imm_ptl.core.portal.PortalManipulation.createReversePortal(
                        p, qouteall.imm_ptl.core.portal.Portal.ENTITY_TYPE));
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-E SETUP FAILED: " + failure.get());
            }
            context.waitTicks(30);

            // Which way actually CROSSES is the binding's business, not the fixture author's: the
            // first build laid track along +Z and shoved the cart that way, straight into the
            // portal's BACK face, and it sailed through the plane without ever teleporting
            // (crossed=false). Derive the direction the way arms A and D do.
            AtomicReference<com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding> bindingRef =
                new AtomicReference<>(null);
            for (int attempt = 0; attempt < 30 && bindingRef.get() == null; attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                    if (cell == null) {
                        return;
                    }
                    cell.bindings().stream()
                        .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                        .findFirst().ifPresent(bindingRef::set);
                });
                if (bindingRef.get() == null) {
                    context.waitTicks(10);
                }
            }
            var binding = bindingRef.get();
            if (binding == null) {
                throw new AssertionError(LOG + "RS-CART-E FAILED: seam cell " + cellS
                    + " never bound with a mirrorable binding");
            }
            final net.minecraft.core.Direction crossDir = binding.crossDir();
            final net.minecraft.core.Direction backDir = crossDir.getOpposite();
            final net.minecraft.core.Direction farDir =
                com.warwa.seamlessportals.passthrough.SeamRegistry.mapDir(binding, crossDir);
            final BlockPos contC = binding.continuationToward(crossDir);
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-E geometry: S={} phase={} crossDir={}"
                + " D={} continuation={} farDir={}", cellS, binding.phase(), crossDir,
                binding.destPos(), contC, farDir);
            if (binding.phase() != com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT) {
                throw new AssertionError(LOG + "RS-CART-E FAILED: fixture is " + binding.phase()
                    + ", not COINCIDENT — this leg exists to mirror the user's mid-block pair");
            }
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                for (int k = 1; k <= 12; k++) {
                    ow.setBlock(cellS.relative(backDir, k), Blocks.RAIL.defaultBlockState(), 3);
                }
                for (int k = 0; k <= 14; k++) {
                    BlockPos fp = contC.relative(farDir, k);
                    ow.getChunk(fp.getX() >> 4, fp.getZ() >> 4);
                    ow.setBlock(fp, Blocks.RAIL.defaultBlockState(), 3);
                }
                writeAsPlayer(ow, cellS, Blocks.RAIL.defaultBlockState());
            });
            context.waitTicks(10);

            BlockPos start = cellS.relative(backDir, 7);
            runCommands(context, List.of(
                "execute in minecraft:overworld run tp @p " + (start.getX() + 0.5) + " "
                    + start.getY() + " " + (start.getZ() + 0.5) + " 0 0"));
            context.waitTicks(60);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var player = server.getPlayerList().getPlayers().get(0);
                var cart = EntityTypes.MINECART.create(ow, EntitySpawnReason.COMMAND);
                if (cart == null) {
                    failure.set("minecart create returned null");
                    return;
                }
                cart.snapTo(start.getX() + 0.5, start.getY() + 0.1, start.getZ() + 0.5, 0f, 0f);
                ow.addFreshEntity(cart);
                com.warwa.seamlessportals.passthrough.SeamCartProbe.watch(cart.getId());
                cartId.set(cart.getId());
                if (!player.startRiding(cart, true, true)) {
                    failure.set("player refused to mount");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-E SETUP FAILED: " + failure.get());
            }
            context.waitTicks(20);
            runOnServer(context, server -> {
                var cart = server.getLevel(Level.OVERWORLD).getEntity(cartId.get());
                if (cart == null) {
                    failure.set("cart vanished before the shove");
                    return;
                }
                cart.setDeltaMovement(Vec3.atLowerCornerOf(crossDir.getUnitVec3i()).scale(0.4));
                SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-E shoved the RIDDEN cart id={}"
                    + " {} at 0.4 (same-dim COINCIDENT, the user's topology)",
                    cartId.get(), crossDir);
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-E SETUP FAILED: " + failure.get());
            }

            // "Crossed" = the cart is now on the far side of the pair, measured along the far
            // continuation's own axis rather than a hard-coded +Z.
            AtomicReference<Boolean> crossed = new AtomicReference<>(false);
            AtomicReference<Double> arrivalY = new AtomicReference<>(null);
            for (int i = 0; i < 40 && !crossed.get(); i++) {
                runOnServer(context, server -> {
                    Entity e = server.getLevel(Level.OVERWORLD).getEntity(cartId.get());
                    if (e == null) {
                        return;
                    }
                    boolean past = e.position().distanceToSqr(Vec3.atCenterOf(contC)) < 400;
                    if (past) {
                        crossed.set(true);
                        if (arrivalY.get() == null) {
                            arrivalY.set(e.getY());   // FIRST reading after arrival, before it
                                                      // has had time to fall back onto the rail
                        }
                    }
                });
                if (!crossed.get()) {
                    context.waitTicks(2);
                }
            }
            context.waitTicks(30);
            AtomicReference<String> verdict = new AtomicReference<>(null);
            runOnServer(context, server -> {
                Entity cart = server.getLevel(Level.OVERWORLD).getEntity(cartId.get());
                int offs = com.warwa.seamlessportals.passthrough.SeamCartProbe
                    .comeOffTrackCount(cartId.get());
                double ridingHeight = by + 0.0625;
                if (!(cart instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart mcart)) {
                    verdict.set("the cart vanished (" + cart + ")");
                    return;
                }
                // THE PLACEMENT, not a later sample: an off-rail arrival is snapped back by
                // moveAlongTrack within one tick, so a polled position cannot judge it (the
                // first build's inversion passed on exactly that race).
                Vec3 placed = com.warwa.seamlessportals.passthrough.SeamCartContinuity
                    .lastVehicleCarry(cartId.get());
                Double ay = placed == null ? arrivalY.get() : placed.y;
                SeamlessPortalsConstants.LOGGER.info(LOG + "★ RS-CART-E MEASUREMENT (same-dim"
                        + " ridden): crossed={} placedAt={} placementErrorVsRidingHeight={}"
                        + " firstPolledY={} settledPos={} onRails={} stillRidden={}"
                        + " comeOffTracks={} {}",
                    crossed.get(), placed,
                    ay == null ? "n/a"
                        : String.format(java.util.Locale.ROOT, "%+.4f", ay - ridingHeight),
                    arrivalY.get(), mcart.position(), mcart.isOnRails(),
                    !mcart.getPassengers().isEmpty(), offs,
                    com.warwa.seamlessportals.passthrough.SeamCartContinuity.counters());
                if (placed == null) {
                    verdict.set("COVERAGE FAILED: no vehicle-carry placement was recorded for the"
                        + " ridden cart — the crossing did not go through the carry path at all,"
                        + " so this arm cannot judge the arrival height");
                    return;
                }
                if (!crossed.get()) {
                    verdict.set("the RIDDEN cart never crossed the same-dim pair at all"
                        + " (settled at " + mcart.position() + ")");
                    return;
                }
                if (!AperturePassthroughLever.DISABLE_SEAM_VEHICLE_ATTACH) {
                    // THE OUTCOME THE RIDER SEES: they arrive ON the rail, not above it.
                    if (Math.abs(ay - ridingHeight) > 0.02) {
                        verdict.set("the ridden cart was PLACED at y=" + ay + ", off rail riding"
                            + " height " + ridingHeight + " by "
                            + String.format(java.util.Locale.ROOT, "%+.4f", ay - ridingHeight)
                            + " — the vehicle-carry offset is wrong again (see"
                            + " McHelper.getVehicleOffsetFromPassenger)");
                    }
                    if (offs > 0) {
                        verdict.set("the ridden cart derailed " + offs + " time(s) crossing the"
                            + " seam — the mid-crossing mark did not cover the ridden window");
                    }
                }
                else if (Math.abs(ay - ridingHeight) < 0.05) {
                    verdict.set("INVERSION FAILED: with -PdisableSeamVehicleAttach the ridden"
                        + " cart should be PLACED the vehicle's own passenger-attachment offset"
                        + " above riding height, but it was placed at " + ay + " (if this now"
                        + " matches, the one-term offset is no longer reachable and the lever is"
                        + " dead code)");
                }
            });
            if (verdict.get() != null) {
                throw new AssertionError(LOG + "RS-CART-E FAILED: " + verdict.get());
            }

            // ===== RS-CART-E CLIENT ARM (the (e) requirement) ===================================
            // Everything above reads SERVER state, and a server-side assertion cannot see the
            // symptom the user reported: on 2026-08-01 this exact crossing reported
            // stillRidden=true onRails=true server-side while the CLIENT stranded the rider at an
            // interpolated point 13,000 blocks along the line between the two portal endpoints.
            //
            // Asserted here, on the client:
            //   (1) THE OUTCOME the rider sees — still riding, and standing where the server says
            //       they are rather than somewhere along a lerp.
            //   (2) THE MECHANISM the fix installs — the carried vehicle's relative-move base
            //       equals its position. This is the deterministic half: the stranding itself is a
            //       RACE (it needs a relative-move packet to land in the window after the carry),
            //       so an outcome-only gate could pass on a lucky run. The codec base is exact,
            //       inverts cleanly under the lever, and is the precondition the stranding needs.
            String clientVerdict = clientRiddenArmVerdict(context, cartId.get());
            if (clientVerdict != null) {
                throw new AssertionError(LOG + "RS-CART-E CLIENT ARM FAILED: " + clientVerdict);
            }

            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-E PASS ({})",
                AperturePassthroughLever.DISABLE_SEAM_VEHICLE_ATTACH
                    ? "attach fix OFF — the ridden arrival hop reproduced on demand"
                    : "attach fix ON — the ridden cart arrived ON the rail");
        }
        finally {
            try {
                runOnServer(context, server -> {
                    var players = server.getPlayerList().getPlayers();
                    if (!players.isEmpty() && players.get(0).getVehicle() != null) {
                        players.get(0).stopRiding();
                    }
                    Integer id = cartId.get();
                    if (id != null) {
                        for (ServerLevel l : server.getAllLevels()) {
                            Entity e = l.getEntity(id);
                            if (e != null) {
                                e.discard();
                            }
                        }
                    }
                    com.warwa.seamlessportals.passthrough.SeamCartProbe.clear();
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    for (var portal : ow.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                        new net.minecraft.world.phys.AABB(bx - 6, by - 6, bz - 6,
                            bx + 6, by + 8, bz + ddz + 6), x -> true)) {
                        portal.discard();
                    }
                });
                if (prevDim != null) {
                    runCommands(context, List.of("execute in " + prevDim + " run tp @p "
                        + prevPos.x + " " + prevPos.y + " " + prevPos.z));
                    context.waitTicks(20);
                }
                runCommands(context, List.of(
                    "execute in minecraft:overworld run fill " + (bx - 3) + " " + (by - 1) + " "
                        + (bz - 12) + " " + (bx + 3) + " " + (by + 4) + " " + (bz + ddz + 12)
                        + " minecraft:air",
                    "execute in minecraft:overworld run forceload remove " + (bx - 16) + " "
                        + (bz - 16) + " " + (bx + 16) + " " + (bz + ddz + 16)
                ));
            }
            catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "RS-CART-E cleanup failed", t);
            }
        }
    }

    /** Vanilla yaw for a horizontal direction (south=0, west=90, north=180, east=270). */
    private static int yawOf(net.minecraft.core.Direction dir) {
        return switch (dir) {
            case SOUTH -> 0;
            case WEST -> 90;
            case NORTH -> 180;
            case EAST -> 270;
            default -> 0;
        };
    }

    /**
     * RS-CART-C (gate, COINCIDENT) — THE PHANTOM RAIL, found by the adversarial panel before
     * commit (2026-07-28) and made reproducible on demand.
     *
     * <p>The defect: {@code SeamBinding.continuationToward} answers BOTH directions along the
     * seam axis on a COINCIDENT binding — for {@code dir == srcFacing} it returns the far world's
     * cell CO-LOCATED with this side's approach, a fallback (b)'s SHAPE resolver legitimately
     * consults when the local approach is empty. (d)'s first build read that as PHYSICAL RAIL
     * PRESENCE, so on a pair whose far side has an approach rail but whose near approach is
     * unrailed, a cart on the near approach cell — a cell entirely in FRONT of the plane —
     * resolved "on rails" and hovered there forever instead of falling.
     *
     * <p>The fixture builds exactly that asymmetry: an ignited obsidian frame, a rail (on its own
     * support) at the far co-located fallback cell, NOTHING on the near approach, and a cart
     * dropped onto the near approach cell over open air.
     *
     * <p>ASSERTED OUTCOME, inverting in this same leg: narrowing ON (default) → the cart FALLS
     * (gravity, no rail anywhere near it). Narrowing OFF ({@code -PdisableSeamCartCrossOnly}) →
     * the cart LEVITATES at the phantom rail's height with {@code onRails=true}, reproducing the
     * defect. Under the (d) master lever there is no bridge at all, so the arm asserts the fall.
     */
    private static void rsCartLegPhantomRail(ClientGameTestContext context, int py) {
        if (AperturePassthroughLever.DISABLED) {
            return;
        }
        // The phantom needs the bridge to exist at all: under (d)'s master lever or (b)'s shadow
        // lever nothing is bridged, so the cart falls for a reason that has nothing to do with
        // the narrowing — the arm would pass without testing anything. Assert the fall anyway
        // (it is still valid regression coverage), but say so in the log.
        final boolean bridgeLive = !AperturePassthroughLever.DISABLE_SEAM_CART_RAIL
            && !AperturePassthroughLever.DISABLE_SEAM_SHADOW;
        // THE GUARD THIS FIXTURE EXERCISES IS THE STRADDLE TEST, not the direction narrowing.
        // The frame is an obsidian cluster, so it is BI-FACED: the reverse face's own crossDir
        // points back out of the portal, and the near approach cell is legitimately "one cell past
        // the plane" for THAT face. The narrowing therefore passes it (measured: the first build
        // hovered with -PdisableSeamCartCrossOnly not set at all), and only the straddle test —
        // "is this cart physically ON the seam?" — refuses. Under -PdisableSeamCartCrossOnly this
        // arm still asserts the fall; that lever's own reproduction needs a COINCIDENT
        // SINGLE-FACED portal with a straddling cart reading backward, which no fixture builds
        // yet (recorded in the handoff, defence-in-depth).
        final boolean phantomExpected = bridgeLive
            && AperturePassthroughLever.DISABLE_SEAM_CART_STRADDLE;
        final int fx = 10400, fz = -10400;
        // THE MIDDLE aperture row, not the bottom one. The cart is spawned deliberately close to
        // the plane (see the adversarial placement below), so its 0.98-wide box overhangs the
        // frame column — and from the BOTTOM row it simply came to rest on the frame's own
        // obsidian sill, which made the leg report "held up by something the fixture did not
        // intend" instead of measuring anything. One row up there is open aperture below it.
        final BlockPos ignitePos = new BlockPos(fx, py + 1, fz);
        final BlockPos cellS = new BlockPos(fx, py + 2, fz);
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<Integer> cartId = new AtomicReference<>(null);
        AtomicReference<BlockPos> nearApproach = new AtomicReference<>(null);
        AtomicReference<BlockPos> destForCleanup = new AtomicReference<>(null);
        AtomicReference<String> destDimForCleanup = new AtomicReference<>(null);
        try {
            runCommands(context, List.of(
                "forceload add " + (fx - 16) + " " + (fz - 16) + " " + (fx + 16) + " " + (fz + 16),
                "fill " + (fx - 2) + " " + py + " " + (fz - 6) + " "
                    + (fx + 3) + " " + (py + 5) + " " + (fz + 6) + " minecraft:air",
                fill(fx - 1, py, fz, fx + 2, py, fz),
                fill(fx - 1, py + 4, fz, fx + 2, py + 4, fz),
                fill(fx - 1, py + 1, fz, fx - 1, py + 3, fz),
                fill(fx + 2, py + 1, fz, fx + 2, py + 3, fz)
            ));
            context.waitTicks(20);
            runOnServer(context, server -> {
                boolean fired = qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration
                    .onFireLitOnObsidian(server.getLevel(Level.OVERWORLD), ignitePos, null);
                if (!fired) {
                    failure.set("ignition entry rejected the frame");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-C SETUP FAILED: " + failure.get());
            }
            final net.minecraft.world.phys.AABB frameBox = new net.minecraft.world.phys.AABB(
                fx - 8, py - 8, fz - 8, fx + 8, py + 8, fz + 8);
            try {
                context.waitFor(mc -> {
                    MinecraftServer server = mc.getSingleplayerServer();
                    if (server == null) {
                        return false;
                    }
                    return !server.getLevel(Level.OVERWORLD).getEntitiesOfClass(
                        qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                        frameBox, x -> true).isEmpty();
                }, 1200);
            }
            catch (Throwable t) {
                throw new AssertionError(LOG + "RS-CART-C FAILED: no NetherPortalEntity generated"
                    + " within 1200 ticks", t);
            }

            AtomicReference<com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding> bindingRef =
                new AtomicReference<>(null);
            for (int attempt = 0; attempt < 30 && bindingRef.get() == null; attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, cellS);
                    if (cell == null) {
                        return;
                    }
                    cell.bindings().stream()
                        .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                        .findFirst().ifPresent(bindingRef::set);
                });
                if (bindingRef.get() == null) {
                    context.waitTicks(10);
                }
            }
            var binding = bindingRef.get();
            if (binding == null) {
                throw new AssertionError(LOG + "RS-CART-C FAILED: seam cell " + cellS
                    + " never bound with a mirrorable binding");
            }
            if (binding.phase() != com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT) {
                throw new AssertionError(LOG + "RS-CART-C FAILED: expected COINCIDENT, got "
                    + binding.phase());
            }
            final net.minecraft.core.Direction srcFacing = binding.crossDir().getOpposite();
            final BlockPos nearN = cellS.relative(srcFacing);
            nearApproach.set(nearN);
            final BlockPos farFallback = binding.continuationToward(srcFacing);
            if (farFallback == null) {
                throw new AssertionError(LOG + "RS-CART-C FAILED: COINCIDENT binding gave no"
                    + " backward-fallback cell for " + srcFacing + " — the defect's precondition"
                    + " is gone, so this arm can no longer reproduce it (if continuationToward was"
                    + " deliberately narrowed, retire this leg; do not weaken it)");
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-C geometry: S={} srcFacing={}"
                + " nearApproach={} farFallback={} in {}", cellS, srcFacing, nearN, farFallback,
                binding.destDim().identifier());

            destForCleanup.set(farFallback);
            destDimForCleanup.set(binding.destDim().identifier().toString());
            runCommands(context, List.of(inDim(binding.destDim().identifier().toString(),
                "forceload add " + (farFallback.getX() - 16) + " " + (farFallback.getZ() - 16) + " "
                    + (farFallback.getX() + 16) + " " + (farFallback.getZ() + 16))));

            // The far side's own approach rail — the block the buggy read served near-side.
            runOnServer(context, server -> {
                ServerLevel far = server.getLevel(binding.destDim());
                if (far == null) {
                    failure.set("destination level missing");
                    return;
                }
                far.getChunk(farFallback.getX() >> 4, farFallback.getZ() >> 4);
                far.setBlock(farFallback.below(), Blocks.STONE.defaultBlockState(), 3);
                far.setBlock(farFallback.above(), Blocks.AIR.defaultBlockState(), 3);
                far.setBlock(farFallback, Blocks.RAIL.defaultBlockState(), 3);
                if (!far.getBlockState(farFallback).is(Blocks.RAIL)) {
                    failure.set("far fallback rail at " + farFallback + " did not survive ("
                        + far.getBlockState(farFallback).getBlock() + ") — fixture void");
                    return;
                }
                // …and the near approach must be EMPTY, over open air, or the discriminator dies.
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                if (!ow.getBlockState(nearN).isAir() || !ow.getBlockState(nearN.below()).isAir()) {
                    failure.set("near approach " + nearN + " / below is not air ("
                        + ow.getBlockState(nearN).getBlock() + " / "
                        + ow.getBlockState(nearN.below()).getBlock()
                        + ") — a cart there would rest for the wrong reason; fixture void");
                }
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-C SETUP FAILED: " + failure.get());
            }

            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var cart = EntityTypes.MINECART.create(ow, EntitySpawnReason.COMMAND);
                if (cart == null) {
                    failure.set("minecart create returned null");
                    return;
                }
                // ADVERSARIAL PLACEMENT, not the cell centre. The first build's guard tested the
                // cart's 0.98-wide COLLISION BOX against the seam cell, which admitted any cart
                // whose centre came within 0.49 of the boundary — a half-block band inside this
                // very cell. At the cell centre (0.50 away) this gate passed by 0.02 blocks of
                // luck (adversarial panel, round 2). Spawning 0.30 from the boundary puts the
                // cart INSIDE that old band, so the leg now fails against the old rule and
                // passes only for the right reason: the cart's CENTRE was never on the seam.
                double towardSeam = 0.5 - 0.30;
                cart.snapTo(
                    nearN.getX() + 0.5 + srcFacing.getOpposite().getStepX() * towardSeam,
                    nearN.getY() + 0.1,
                    nearN.getZ() + 0.5 + srcFacing.getOpposite().getStepZ() * towardSeam, 0f, 0f);
                ow.addFreshEntity(cart);
                com.warwa.seamlessportals.passthrough.SeamCartProbe.watch(cart.getId());
                cartId.set(cart.getId());
            });
            if (failure.get() != null) {
                throw new AssertionError(LOG + "RS-CART-C SETUP FAILED: " + failure.get());
            }
            context.waitTicks(60);

            AtomicReference<String> verdict = new AtomicReference<>(null);
            runOnServer(context, server -> {
                Entity cart = server.getLevel(Level.OVERWORLD).getEntity(cartId.get());
                if (!(cart instanceof net.minecraft.world.entity.vehicle.minecart.AbstractMinecart mcart)) {
                    verdict.set("the cart vanished (" + cart + ") — fixture void");
                    return;
                }
                double dropped = (nearN.getY() + 0.1) - mcart.getY();
                SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-C REPORT (straddle test {}):"
                        + " y={} (spawned {}) dropped={} onRails={} {}",
                    phantomExpected ? "OFF" : "ON",
                    String.format(java.util.Locale.ROOT, "%.4f", mcart.getY()),
                    nearN.getY() + 0.1,
                    String.format(java.util.Locale.ROOT, "%.3f", dropped), mcart.isOnRails(),
                    com.warwa.seamlessportals.passthrough.SeamCartContinuity.counters());
                if (phantomExpected) {
                    if (dropped > 0.5 || !mcart.isOnRails()) {
                        verdict.set("INVERSION FAILED (the hover did not reproduce): with"
                            + " -PdisableSeamCartStraddle the cart should ride the far world's"
                            + " approach rail at " + nearN + " through the reverse face's"
                            + " binding, but it dropped " + dropped + " and onRails="
                            + mcart.isOnRails() + " — either the bi-faced backward answer is gone"
                            + " or the lever is not wired through");
                    }
                }
                else {
                    if (mcart.isOnRails()) {
                        verdict.set("PHANTOM RAIL: the cart at " + nearN + " — a cell entirely in"
                            + " FRONT of the plane, with no rail in this world — reports"
                            + " onRails=true. The COINCIDENT backward fallback is being read as"
                            + " physical rail presence again (crossingOnly regressed)");
                    }
                    else if (dropped < 0.5) {
                        verdict.set("the cart neither fell nor reported rails (dropped=" + dropped
                            + ") — it is held up by something the fixture did not intend;"
                            + " this arm proves nothing until that is explained");
                    }
                }
            });
            if (verdict.get() != null) {
                throw new AssertionError(LOG + "RS-CART-C FAILED: " + verdict.get());
            }
            SeamlessPortalsConstants.LOGGER.info(LOG + "RS-CART-C PASS ({})", phantomExpected
                ? "straddle test OFF — the hovering cart reproduced on demand"
                : bridgeLive
                    ? "straddle test ON — no rail is served to a cart that is not on the seam;"
                        + " the cart fell"
                    : "bridge not live under this lever row; the cart fell, regression coverage only");
        }
        finally {
            try {
                runOnServer(context, server -> {
                    Integer id = cartId.get();
                    if (id != null) {
                        for (ServerLevel l : server.getAllLevels()) {
                            Entity e = l.getEntity(id);
                            if (e != null) {
                                e.discard();
                            }
                        }
                    }
                    com.warwa.seamlessportals.passthrough.SeamCartProbe.clear();
                    // Discard BOTH sides' portal entities, then strip the frame: a surviving
                    // obsidian frame stays matchable and has already made one later leg link to
                    // the wrong portal (the handoff's isolation scar).
                    for (ServerLevel l : server.getAllLevels()) {
                        for (var portal : l.getEntitiesOfClass(qouteall.imm_ptl.core.portal.Portal.class,
                            new net.minecraft.world.phys.AABB(fx - 12, py - 12, fz - 12,
                                fx + 12, py + 12, fz + 12), x -> true)) {
                            portal.discard();
                        }
                    }
                });
                runCommands(context, List.of(
                    "fill " + (fx - 2) + " " + py + " " + (fz - 6) + " "
                        + (fx + 3) + " " + (py + 5) + " " + (fz + 6) + " minecraft:air",
                    "forceload remove " + (fx - 16) + " " + (fz - 16) + " "
                        + (fx + 16) + " " + (fz + 16)
                ));
                cleanupFarObsidianSide(context, destDimForCleanup.get(), destForCleanup.get());
            }
            catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(LOG + "RS-CART-C cleanup failed", t);
            }
        }
    }

    /**
     * ★ RS SEAM EMPTINESS GATE — the PIXEL gate for symmetric emptiness (user live round 15,
     * 2026-08-03). Every state gate was green while the user saw both EMPTY half-spaces painted
     * as a full continuous block from source side B and dest far side ("not breakable from
     * source side B, or dest side A, only source side a and dest side b" — breaking was right
     * because the STATE was right; only pixels were wrong). No existing leg stands where the
     * user stood: the clip gate's fixture is single-faced (no B-face window at all), and the
     * break/relog gates never render. This leg stages the user's own construction — an IGNITED
     * cross-dim bi-faced portal — places gold claiming the +Z half, asserts the full state
     * precondition on BOTH sides first (so a red is unambiguously the RENDERER), then takes four
     * crosshair-aimed measured screenshots:
     * <ol>
     *   <li>source, owner side → GOLD (calibration; red here = fixture fault, not verdict)</li>
     *   <li>source, far side → NOT gold (the empty half + through-window empty dest half)</li>
     *   <li>dest, arrive side → GOLD (calibration)</li>
     *   <li>dest, far side → NOT gold</li>
     * </ol>
     * Blue backdrops on both sides of both frames make "empty" measurable: an empty view lands
     * on blue through the window; the phantom paints gold in front of it.
     */
    private static void rsSeamEmptinessGate(ClientGameTestContext context, int py) {
        final String tag = LOG + "[RS-SEAM-EMPTINESS] ";
        if (!com.warwa.seamlessportals.passthrough.SeamFractional.active()
            || AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SEAM_MIRROR) {
            SeamlessPortalsConstants.LOGGER.info(tag + "SKIPPED — fractional or mirroring"
                + " disabled; there is no owner half whose emptiness could be rendered.");
            return;
        }
        if (!com.warwa.seamlessportals.render.SeamClipRenderer.active()) {
            SeamlessPortalsConstants.LOGGER.info(tag + "SKIPPED — the seam clip renderer is"
                + " forced off (-PdisableSeamClip, or sodium self-gate); with the model's"
                + " renderer disabled every seam cell legitimately draws whole, which is the"
                + " configuration under test in that row, not a defect this gate judges.");
            return;
        }
        // Isolation (teardown lesson at -600): nether counterpart lands at ~(-500,-750) — 250
        // blocks from the -4000 fixture's (-500,-500) and far outside leg 6a/6b's match windows.
        final int ex = -4000, ez = -6000;
        final BlockPos srcCell = new BlockPos(ex, py + 1, ez);
        final Vec3 srcCenter = Vec3.atCenterOf(srcCell);
        String prevDim = context.computeOnClient(mc ->
            mc.level == null ? null : mc.level.dimension().identifier().toString());
        Vec3 prevPos = context.computeOnClient(mc ->
            mc.player == null ? Vec3.ZERO : mc.player.position());
        AtomicReference<Vec3> destSeen = new AtomicReference<>(null);
        AtomicReference<BlockPos> destCellRef = new AtomicReference<>(null);
        AtomicReference<String> destDimId = new AtomicReference<>(null);
        try {
            // ---- Source site: pad, air, frame, backdrops, light — in that order (the air fill
            // would wipe a frame built first) ----
            runCommands(context, List.of(
                "forceload add " + (ex - 16) + " " + (ez - 16) + " " + (ex + 16) + " " + (ez + 16),
                "execute in minecraft:overworld run fill " + (ex - 4) + " " + py + " " + (ez - 9)
                    + " " + (ex + 5) + " " + py + " " + (ez + 9) + " minecraft:obsidian",
                "execute in minecraft:overworld run fill " + (ex - 4) + " " + (py + 1) + " "
                    + (ez - 9) + " " + (ex + 5) + " " + (py + 8) + " " + (ez + 9)
                    + " minecraft:air",
                fill(ex - 1, py, ez, ex + 2, py, ez),          // base
                fill(ex - 1, py + 4, ez, ex + 2, py + 4, ez),  // lintel
                fill(ex - 1, py + 1, ez, ex - 1, py + 3, ez),  // left column
                fill(ex + 2, py + 1, ez, ex + 2, py + 3, ez),  // right column
                // Backdrops: corridor-width blue at z = ez±8, behind both camera spots.
                "execute in minecraft:overworld run fill " + (ex - 1) + " " + (py + 1) + " "
                    + (ez - 8) + " " + (ex + 2) + " " + (py + 4) + " " + (ez - 8)
                    + " minecraft:blue_concrete",
                "execute in minecraft:overworld run fill " + (ex - 1) + " " + (py + 1) + " "
                    + (ez + 8) + " " + (ex + 2) + " " + (py + 4) + " " + (ez + 8)
                    + " minecraft:blue_concrete",
                "execute in minecraft:overworld run setblock " + (ex + 4) + " " + py + " "
                    + (ez - 5) + " minecraft:glowstone",
                "execute in minecraft:overworld run setblock " + (ex + 4) + " " + py + " "
                    + (ez + 5) + " minecraft:glowstone",
                "execute in minecraft:overworld run effect give @p minecraft:night_vision"
                    + " 3600 0 true"
            ));
            context.waitTicks(10);
            runOnServer(context, server ->
                SeamlessPortalsConstants.LOGGER.info(tag + "ignition fired={}",
                    qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration
                        .onFireLitOnObsidian(server.getLevel(Level.OVERWORLD),
                            new BlockPos(ex, py + 1, ez), null)));
            final net.minecraft.world.phys.AABB srcBox = new net.minecraft.world.phys.AABB(
                new Vec3(ex - 8, py - 8, ez - 8), new Vec3(ex + 8, py + 8, ez + 8));
            context.waitFor(mc -> {
                MinecraftServer s = mc.getSingleplayerServer();
                if (s == null) return false;
                ServerLevel ow = s.getLevel(Level.OVERWORLD);
                return ow != null && ow.getEntitiesOfClass(
                    qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                    srcBox, p -> true).size() >= 2;   // BI-FACED is load-bearing: shot 2 looks
                                                      // through the B face; one entity = no window
            }, 1200);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ow.getEntitiesOfClass(
                        qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                        srcBox, p -> true).stream().findFirst()
                    .ifPresent(p -> destSeen.set(p.getDestPos()));
            });
            if (destSeen.get() == null) {
                throw new AssertionError(tag + "FIXTURE INVALID — portal exists but has no"
                    + " destination position");
            }
            int dxc = (int) Math.floor(destSeen.get().x), dzc = (int) Math.floor(destSeen.get().z);
            // Bindings need the far portal ALIVE AND TICKING (mirror-family staging lesson).
            runCommands(context, List.of(inDim("minecraft:the_nether",
                "forceload add " + (dxc - 16) + " " + (dzc - 16) + " "
                    + (dxc + 16) + " " + (dzc + 16))));
            context.waitTicks(60);

            // ---- Binding precondition + destination discovery ----
            AtomicReference<String> stageErr = new AtomicReference<>(null);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, srcCell);
                if (cell == null) {
                    stageErr.set("no seam binding at " + srcCell);
                    return;
                }
                for (var b : cell.bindings()) {
                    if (b.isMirrorable() && b.cut() != null && b.destPos() != null) {
                        destCellRef.set(b.destPos());
                        destDimId.set(b.destDim().identifier().toString());
                        return;
                    }
                }
                stageErr.set("no mirrorable binding with a cut at " + srcCell + ": " + cell);
            });
            if (stageErr.get() != null) {
                throw new AssertionError(tag + "FIXTURE INVALID — " + stageErr.get());
            }
            final BlockPos destCell = destCellRef.get();
            final String destDim = destDimId.get();

            // ---- Nether site: pad, corridors along the dest normal, backdrops, light ----
            AtomicReference<net.minecraft.core.Direction.Axis> destAxisRef =
                new AtomicReference<>(null);
            runOnServer(context, server -> {
                ServerLevel nether = server.getLevel(
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.Identifier.parse(destDim)));
                var cell = com.warwa.seamlessportals.passthrough.SeamRegistry
                    .lookup(nether, destCell);
                if (cell != null) {
                    for (var b : cell.bindings()) {
                        if (b.isMirrorable()) {
                            destAxisRef.set(b.srcFacing().getAxis());
                            return;
                        }
                    }
                }
            });
            if (destAxisRef.get() == null) {
                throw new AssertionError(tag + "FIXTURE INVALID — destination cell " + destCell
                    + " has no mirrorable binding; the far side never bound");
            }
            final net.minecraft.core.Direction.Axis dAxis = destAxisRef.get();
            final int dx = destCell.getX(), dy = destCell.getY(), dz = destCell.getZ();
            final int sx = dAxis == net.minecraft.core.Direction.Axis.X ? 1 : 0;
            final int sz = dAxis == net.minecraft.core.Direction.Axis.Z ? 1 : 0;
            if (sx + sz == 0) {
                throw new AssertionError(tag + "FIXTURE INVALID — vertical dest plane axis "
                    + dAxis + "; this leg's camera geometry is horizontal-only");
            }
            // SEALED obsidian tunnels, not open-air corridors: the first cut opened into a lava
            // cave and shot 3's camera drowned in raw terrain 5 blocks from the frame (screenshot
            // 0002: netherrack and a lava lake, no fixture in sight). Shell first, then hollow.
            List<String> netherStage = new java.util.ArrayList<>();
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                int ax1 = dx + sx * sgn, az1 = dz + sz * sgn;           // 1 out from the face
                int ax8 = dx + sx * sgn * 8, az8 = dz + sz * sgn * 8;   // corridor end
                netherStage.add(inDim(destDim, "fill "
                    + (Math.min(ax1, ax8) - sz * 2) + " " + (dy - 1) + " "
                    + (Math.min(az1, az8) - sx * 2) + " "
                    + (Math.max(ax1, ax8) + sz * 3) + " " + (dy + 4) + " "
                    + (Math.max(az1, az8) + sx * 3)
                    + " minecraft:obsidian"));
                netherStage.add(inDim(destDim, "fill "
                    + Math.min(ax1 - sz, ax8 - sz) + " " + dy + " " + Math.min(az1 - sx, az8 - sx)
                    + " "
                    + Math.max(ax1 + 2 * sz, ax8 + 2 * sz) + " " + (dy + 3) + " "
                    + Math.max(az1 + 2 * sx, az8 + 2 * sx)
                    + " minecraft:air"));
                netherStage.add(inDim(destDim, "fill "
                    + (ax8 - sz) + " " + dy + " " + (az8 - sx) + " "
                    + (ax8 + 2 * sz) + " " + (dy + 3) + " " + (az8 + 2 * sx)
                    + " minecraft:blue_concrete"));
                netherStage.add(inDim(destDim, "setblock "
                    + (dx + sx * sgn * 3 + 2 * sz) + " " + (dy - 1) + " "
                    + (dz + sz * sgn * 3 + 2 * sx) + " minecraft:glowstone"));
            }
            runCommands(context, netherStage);
            context.waitTicks(10);

            // ---- Claim, then write (the real place path claims at HEAD, and the mirror derives
            // the crossing half FROM the source claim — order is load-bearing) ----
            com.warwa.seamlessportals.render.SeamClipRenderer.resetMeshTracking();
            claimOwnerHalfBothSides(context, srcCell,
                com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                writeAsPlayer(ow, srcCell,
                    net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
            });
            context.waitTicks(20);

            // ---- STATE PRECONDITION on both sides, so a red below is the RENDERER, not the
            // pipe (the user confirmed breaking behaves correctly while the pixels lie) ----
            AtomicReference<String> stateView = new AtomicReference<>("");
            AtomicReference<Byte> destMaskRef = new AtomicReference<>((byte) 0);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ServerLevel nether = server.getLevel(
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.Identifier.parse(destDim)));
                byte sm = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .occupancyOf(ow, srcCell);
                byte dm = com.warwa.seamlessportals.passthrough.SeamOccupancy
                    .occupancyOf(nether, destCell);
                destMaskRef.set(dm);
                stateView.set("server: srcMask=" + sm + " destMask=" + dm
                    + " destState=" + nether.getBlockState(destCell).getBlock());
                if (sm != com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE
                    || (dm != com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE
                        && dm != com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_NEGATIVE)
                    || !nether.getBlockState(destCell).is(
                        net.minecraft.world.level.block.Blocks.GOLD_BLOCK)) {
                    stageErr.set("state precondition failed — " + stateView.get());
                }
            });
            if (stageErr.get() != null) {
                throw new AssertionError(tag + "FIXTURE INVALID — " + stageErr.get());
            }
            // ★ THE STORE ASSERT (live round 16, "the relog still sucks — read logs"): the user's
            // world save held TWO overworld cells and a nether store with ZERO cells (gzip-dumped
            // from New World (8) — "cells" list empty at 43 bytes) while the live duck maps had
            // answered every in-session query. The same-dim relog gate could never see this: its
            // whole fixture persists into ONE dimension's store. This cross-dim fixture has a
            // crossing claim in a SECOND dimension's store — assert the write-through reached it
            // NOW, without waiting for a reopen to launder the loss into downstream symptoms.
            AtomicReference<String> storeView = new AtomicReference<>("");
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                ServerLevel nether = server.getLevel(
                    net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.Identifier.parse(destDim)));
                String owDump = com.warwa.seamlessportals.passthrough.SeamOccupancySavedData
                    .get(ow).debugDump();
                String nDump = com.warwa.seamlessportals.passthrough.SeamOccupancySavedData
                    .get(nether).debugDump();
                storeView.set("stores: ow=" + owDump + " dest=" + nDump);
                if (!owDump.contains(String.valueOf(srcCell.asLong()))
                    || !nDump.contains(String.valueOf(destCell.asLong()))) {
                    stageErr.set("SAVEDDATA IS MISSING A LIVE RECORD — the duck maps answer but"
                        + " the per-dimension store never got the write-through, which is exactly"
                        + " the user's empty live nether store at relog. srcCell="
                        + srcCell.asLong() + " destCell=" + destCell.asLong() + " | "
                        + storeView.get());
                }
            });
            if (stageErr.get() != null) {
                throw new AssertionError(tag + "STORE WRITE-THROUGH HOLE — " + stageErr.get());
            }
            SeamlessPortalsConstants.LOGGER.info(tag + "STORE ASSERT PASS — {}", storeView.get());
            final net.minecraft.resources.ResourceKey<Level> destKey = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.parse(destDim));
            Boolean clientReady = context.computeOnClient(mc ->
                com.warwa.seamlessportals.passthrough.SeamOccupancyClient
                    .clientRecordOf(Level.OVERWORLD, srcCell.asLong()).mask() != 0
                && com.warwa.seamlessportals.passthrough.SeamOccupancyClient
                    .clientRecordOf(destKey, destCell.asLong()).mask() != 0);
            if (!Boolean.TRUE.equals(clientReady)) {
                throw new AssertionError(tag + "FIXTURE INVALID — the client does not hold both"
                    + " occupancy records (the sync pipe has its own gate; this leg only judges"
                    + " pixels on correct state). " + stateView.get());
            }

            // ---- Shot 1+2: source side. EVERY inter-stand hop goes via a waypoint 12 blocks
            // off-axis: the first run's straight setPos from z+5.5 to z-4.5 passed THROUGH the
            // portal quad, the client-first crossing detector fired (screenshot showed the "We
            // Need to Go Deeper" advancement), and two of the four cameras photographed the wrong
            // dimension. A 12-block lateral offset makes every cross-plane segment miss the
            // 2-wide frame by ~8 blocks. Wait for the section recompile first (a stale mesh
            // screenshot proves nothing — clip-gate lesson). ----
            long srcDrawBase = com.warwa.seamlessportals.render.SeamClipRenderer.cellsDrawnCount();
            seamStandIn(context, "minecraft:overworld", ex + 12.5, py + 1, ez + 5.5);
            seamStandIn(context, "minecraft:overworld", ex + 0.5, py + 1, ez + 5.5);
            boolean recompiled = false;
            for (int i = 0; i < 40 && !recompiled; i++) {
                context.waitTicks(5);
                recompiled = com.warwa.seamlessportals.render.SeamClipRenderer.meshReplacedAt(
                    srcCell.getX(), srcCell.getY(), srcCell.getZ());
            }
            if (!recompiled) {
                throw new AssertionError(tag + "the gold cell's section never recompiled after"
                    + " placement (200 ticks); counters: "
                    + com.warwa.seamlessportals.render.SeamClipRenderer.counters());
            }
            // ★ RENDERER ENGAGEMENT — the round-15 lesson, pixelised: every state gate can be
            // green while the fractional RENDERER is idle and vanilla paints whole cubes
            // (measured: cellsDrawn=0 across all four shots). Demand the dynamic draw is
            // actually running at this fixture before trusting any screenshot.
            try {
                context.waitFor(mc -> com.warwa.seamlessportals.render.SeamClipRenderer
                    .cellsDrawnCount() > srcDrawBase, 200);
            } catch (Throwable t) {
                throw new AssertionError(tag + "THE FRACTIONAL RENDERER IS IDLE at the source"
                    + " fixture — no dynamic seam draw in 200 ticks with the camera on it; every"
                    + " seam cell is rendering as vanilla's whole cube. counters: "
                    + com.warwa.seamlessportals.render.SeamClipRenderer.counters(), t);
            }
            aimAt(context, ex + 0.5, py + 1.7, ez + 1.0);
            context.waitTicks(10);
            double srcOwnerGold = goldFractionAtCenter(context, "rs-seam-empt-src-owner", tag);
            seamStandIn(context, "minecraft:overworld", ex + 12.5, py + 1, ez - 4.5);
            seamStandIn(context, "minecraft:overworld", ex + 0.5, py + 1, ez - 4.5);
            aimAt(context, ex + 0.5, py + 1.7, ez + 0.25);
            context.waitTicks(10);
            double srcFarGold = goldFractionAtCenter(context, "rs-seam-empt-src-far", tag);

            // ---- Shot 3+4: destination side. Camera on the material side first (calibration),
            // then the empty side. matSign: which side of the dest plane holds the material.
            // Night vision is re-given IN the destination dimension (the first run gave it via
            // an overworld-scoped @p and the nether shots went dark), and the engagement wait
            // repeats with a fresh baseline — the destination level has its own seam index. ----
            int matSign = destMaskRef.get()
                == com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE ? 1 : -1;
            double dcx = dx + 0.5, dcz = dz + 0.5;
            double wpx = 12.0 * sz, wpz = 12.0 * sx;   // perpendicular to the dest plane normal
            runCommands(context, List.of(inDim(destDim,
                "effect give @a minecraft:night_vision 3600 0 true")));
            long destDrawBase = com.warwa.seamlessportals.render.SeamClipRenderer.cellsDrawnCount();
            seamStandIn(context, destDim,
                dcx + sx * matSign * 5.0 + wpx, dy, dcz + sz * matSign * 5.0 + wpz);
            seamStandIn(context, destDim,
                dcx + sx * matSign * 5.0, dy, dcz + sz * matSign * 5.0);
            try {
                context.waitFor(mc -> com.warwa.seamlessportals.render.SeamClipRenderer
                    .cellsDrawnCount() > destDrawBase, 200);
            } catch (Throwable t) {
                throw new AssertionError(tag + "THE FRACTIONAL RENDERER IS IDLE at the"
                    + " destination fixture — the dest level's seam index never engaged the"
                    + " dynamic draw (client-side binding or recompile scheduling defect)."
                    + " counters: "
                    + com.warwa.seamlessportals.render.SeamClipRenderer.counters(), t);
            }
            // ★ THE DISCRIMINATOR PROBE — one line that splits the anti-mask-paint hypothesis
            // space: if the client's dest record disagrees with the server's mask, the sync/claim
            // pipe wrote the wrong half and the renderer is innocent; if they agree, the
            // renderer's kept-side derivation or plane math flipped the draw.
            context.runOnClient(mc -> {
                var rec = com.warwa.seamlessportals.passthrough.SeamOccupancyClient
                    .clientRecordOf(destKey, destCell.asLong());
                byte direct = mc.level == null ? -1
                    : com.warwa.seamlessportals.passthrough.SeamOccupancy
                        .occupancyOf(mc.level, destCell);
                SeamlessPortalsConstants.LOGGER.info(tag + "CLIENT DEST RECORD before shots:"
                    + " resolved mask={} [{}] | direct mc.level({}) read={} | server said {}",
                    rec.mask(), rec.source(),
                    mc.level == null ? "null" : mc.level.dimension().identifier(),
                    direct, destMaskRef.get());
            });
            aimAt(context, dcx + sx * matSign * 0.5, dy + 0.7, dcz + sz * matSign * 0.5);
            context.waitTicks(10);
            double destOwnerGold = goldFractionAtCenter(context, "rs-seam-empt-dest-owner", tag);
            seamStandIn(context, destDim,
                dcx - sx * matSign * 4.5 + wpx, dy, dcz - sz * matSign * 4.5 + wpz);
            seamStandIn(context, destDim,
                dcx - sx * matSign * 4.5, dy, dcz - sz * matSign * 4.5);
            aimAt(context, dcx - sx * matSign * 0.25, dy + 0.7, dcz - sz * matSign * 0.25);
            context.waitTicks(10);
            double destFarGold = goldFractionAtCenter(context, "rs-seam-empt-dest-far", tag);

            SeamlessPortalsConstants.LOGGER.info(tag
                + "srcOwner={} srcFar={} destOwner={} destFar={} destAxis={} matSign={} | {} |"
                + " counters: {}",
                String.format("%.2f", srcOwnerGold), String.format("%.2f", srcFarGold),
                String.format("%.2f", destOwnerGold), String.format("%.2f", destFarGold),
                dAxis, matSign, stateView.get(),
                com.warwa.seamlessportals.render.SeamClipRenderer.counters());

            // ---- Verdicts: calibration first (a mis-built fixture must not masquerade as a
            // phantom verdict), then the two emptiness assertions the user's report names. ----
            if (srcOwnerGold < 0.5 || destOwnerGold < 0.5) {
                throw new AssertionError(tag + "CALIBRATION FAILED — the OWNED half is not gold"
                    + " from its own side (srcOwner=" + srcOwnerGold + " destOwner="
                    + destOwnerGold + "); the emptiness verdicts below would be meaningless.");
            }
            if (srcFarGold > 0.15 || destFarGold > 0.15) {
                throw new AssertionError(tag + "THE PHANTOM — an EMPTY half-space painted the"
                    + " block (srcFar=" + srcFarGold + " destFar=" + destFarGold + "; both must"
                    + " be ~0 — the view should pass through the window to the blue backdrop)."
                    + " This is the user's round-15 report reproduced: state green, pixels lying."
                    + " " + stateView.get());
            }
            SeamlessPortalsConstants.LOGGER.info(tag + "PASS — owned halves gold from their own"
                + " sides, empty half-spaces genuinely empty from the other two vantage points.");
        } finally {
            // Break the object (clears the mirror half too), then wipe everything this leg built.
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                try {
                    writeAsPlayer(ow, srcCell, net.minecraft.world.level.block.Blocks.AIR
                        .defaultBlockState());
                } catch (Throwable ignored) {
                }
                com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(ow, srcCell);
                com.warwa.seamlessportals.passthrough.SeamOccupancy.setSecondary(ow, srcCell, null);
                for (var portal : ow.getEntitiesOfClass(
                    qouteall.imm_ptl.core.portal.Portal.class,
                    new net.minecraft.world.phys.AABB(new Vec3(ex - 8, py - 8, ez - 8),
                        new Vec3(ex + 8, py + 8, ez + 8)), p -> true)) {
                    portal.discard();
                }
                if (destCellRef.get() != null && destDimId.get() != null) {
                    ServerLevel nether = server.getLevel(
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                            net.minecraft.resources.Identifier.parse(destDimId.get())));
                    if (nether != null) {
                        BlockPos dc = destCellRef.get();
                        com.warwa.seamlessportals.passthrough.SeamOccupancy.clear(nether, dc);
                        com.warwa.seamlessportals.passthrough.SeamOccupancy
                            .setSecondary(nether, dc, null);
                        for (var portal : nether.getEntitiesOfClass(
                            qouteall.imm_ptl.core.portal.Portal.class,
                            new net.minecraft.world.phys.AABB(Vec3.atCenterOf(dc), Vec3.atCenterOf(dc))
                                .inflate(8), p -> true)) {
                            portal.discard();
                        }
                    }
                }
            });
            List<String> cleanup = new java.util.ArrayList<>();
            cleanup.add("execute in minecraft:overworld run fill " + (ex - 4) + " " + py + " "
                + (ez - 9) + " " + (ex + 5) + " " + (py + 8) + " " + (ez + 9) + " minecraft:air");
            if (destCellRef.get() != null && destDimId.get() != null) {
                BlockPos dc = destCellRef.get();
                cleanup.add(inDim(destDimId.get(), "fill " + (dc.getX() - 9) + " "
                    + (dc.getY() - 2) + " " + (dc.getZ() - 9) + " " + (dc.getX() + 9) + " "
                    + (dc.getY() + 5) + " " + (dc.getZ() + 9) + " minecraft:air"));
                cleanup.add(inDim(destDimId.get(), "forceload remove "
                    + ((int) Math.floor(destSeen.get().x) - 16) + " "
                    + ((int) Math.floor(destSeen.get().z) - 16) + " "
                    + ((int) Math.floor(destSeen.get().x) + 16) + " "
                    + ((int) Math.floor(destSeen.get().z) + 16)));
            }
            cleanup.add("forceload remove " + (ex - 16) + " " + (ez - 16) + " "
                + (ex + 16) + " " + (ez + 16));
            cleanup.add("effect clear @p minecraft:night_vision");
            if (prevDim != null) {
                cleanup.add(inDim(prevDim, "tp @p " + prevPos.x + " " + prevPos.y + " "
                    + prevPos.z));
            }
            runCommands(context, cleanup);
            context.waitTicks(10);
        }
    }

    /** Cross-dim variant of {@link #seamClipStand}: stand at exact coordinates in ANY dimension. */
    private static void seamStandIn(
        ClientGameTestContext context, String dim, double x, double y, double z
    ) {
        runOnServer(context, server -> {
            CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(src,
                "execute in " + dim + " run tp @p " + x + " " + y + " " + z + " 90 0");
        });
        context.waitTicks(30);   // cross-dim arrival is packet-driven; give it real time
        context.runOnClient(mc -> {
            if (mc.player != null) {
                mc.player.setPos(x, y, z);
                mc.player.xo = x;
                mc.player.yo = y;
                mc.player.zo = z;
                mc.player.setDeltaMovement(Vec3.ZERO);
            }
        });
        context.waitTicks(5);
    }

    private static void rsSeamClipGate(ClientGameTestContext context, int px, int py, int pz) {
        final String tag = LOG + "[RS-SEAM-CLIP] ";
        // Master lever: with the whole passthrough stack disabled there is no seam registry, no
        // binding and nothing for a clip to gate — the fixture-validity check would (correctly)
        // refuse to run. The master-lever row proves stock-IP restoration; this leg's own
        // clip-ON row is -PenableSeamClip, which keeps the stack alive.
        if (AperturePassthroughLever.DISABLED) {
            SeamlessPortalsConstants.LOGGER.info(tag + "SKIPPED — master lever"
                + " (-PdisableAperturePassthrough) disables the seam stack this gate rides on;"
                + " the clip's own enable row is -PenableSeamClip");
            return;
        }
        final boolean clipOn = !AperturePassthroughLever.DISABLE_SEAM_CLIP;

        // Fixture site: 40 blocks east of the main staging, own platform, clear of every other
        // leg's fixtures (portals A/B at px±5; disjoint-gate portal at 2000,100,2000).
        final int qx = px + 40, qy = py, qz = pz;
        final double planeZ = qz - 5.5;          // bisects cell layer z = qz-6 → COINCIDENT
        final int apertureZ = qz - 6;
        final BlockPos goldCell = new BlockPos(qx + 5, qy + 1, apertureZ);
        final BlockPos mirrorCell = new BlockPos(qx + 5, qy + 1, apertureZ - 50);
        final Vec3 portalOrigin = new Vec3(qx + 4.5, qy + 1.5, planeZ);
        final Vec3 portalDest = new Vec3(qx + 4.5, qy + 1.5, planeZ - 50.0);  // integral ⇒ EXACT
        // Camera + the two sample points on the gold block's EAST face (x = qx+6):
        final double camX = qx + 10.5, camY = qy, camZ = qz - 4.5;
        // Aim slightly above mid-face; the sample patch sits BELOW the crosshair (26.2 has no
        // Options.hideGui field), landing ~mid-face on the SAME side of the cut at any resolution.
        final double faceX = qx + 6.0, sampleY = qy + 1.7;
        final double farZ = planeZ - 0.4;    // 0.4 past the cut — removed half
        final double nearZ = planeZ + 0.25;  // kept half — the calibration point

        String prevDim = context.computeOnClient(mc ->
            mc.level == null ? null : mc.level.dimension().identifier().toString());
        Vec3 prevPos = context.computeOnClient(mc ->
            mc.player == null ? Vec3.ZERO : mc.player.position());

        try {
            // ---- Stage (all in the overworld regardless of where the player is) ----
            runCommands(context, List.of(
                "execute in minecraft:overworld run fill " + (qx - 14) + " " + (qy - 1) + " "
                    + (qz - 12) + " " + (qx + 10) + " " + (qy - 1) + " " + (qz + 4)
                    + " minecraft:obsidian",
                "execute in minecraft:overworld run fill " + (qx - 14) + " " + qy + " "
                    + (qz - 12) + " " + (qx + 10) + " " + (qy + 8) + " " + (qz + 4)
                    + " minecraft:air",
                // backdrop AFTER the clear; wide, so the fix-ON through-ray (which exits the open
                // block heading west-north) still lands on blue rather than escaping to terrain
                "execute in minecraft:overworld run fill " + (qx - 14) + " " + (qy - 1) + " "
                    + (qz - 9) + " " + (qx + 10) + " " + (qy + 6) + " " + (qz - 9)
                    + " minecraft:blue_concrete"
            ));
            // Player to the camera spot FIRST (cross-dim in the full suite), so the section is in
            // view and the placement recompile is consumed by the normal extract path.
            seamClipStand(context, camX, camY, camZ);
            boolean inOverworld = Boolean.TRUE.equals(context.computeOnClient(mc ->
                mc.level != null && mc.level.dimension().equals(Level.OVERWORLD)));
            if (!inOverworld) {
                throw new AssertionError(tag + "FIXTURE INVALID — player did not arrive in the"
                    + " overworld; the screenshot would show the wrong dimension");
            }
            // Deterministic light: the world is a fresh random seed each run, so the site's
            // natural light varies — a dark fixture would fail the colour vote for the wrong
            // reason. Glowstone lights the sampled face's neighbour cell; night vision flattens
            // the lightmap for the shots (cleared in the finally).
            runCommands(context, List.of(
                "execute in minecraft:overworld run setblock " + (qx + 8) + " " + qy + " "
                    + (qz - 6) + " minecraft:glowstone",
                "execute in minecraft:overworld run effect give @p minecraft:night_vision"
                    + " 3600 0 true"
            ));

            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                spawnTestPortal(ow, portalOrigin, Level.OVERWORLD, portalDest);
            });
            context.waitTicks(20);

            // ---- Fixture-validity gate: COINCIDENT + mirror-admitted, or the leg says so ----
            AtomicReference<String> bindErr = new AtomicReference<>(null);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, goldCell);
                if (cell == null) {
                    bindErr.set("no seam binding at " + goldCell);
                    return;
                }
                boolean ok = false;
                for (var b : cell.bindings()) {
                    if (b.phase() == com.warwa.seamlessportals.passthrough.SeamMap.SeamPhase.COINCIDENT
                        && b.isMirrorable()) {
                        ok = true;
                    }
                }
                if (!ok) {
                    bindErr.set("binding is not COINCIDENT+mirror-admitted (query-only fixture"
                        + " proves nothing): " + cell);
                }
            });
            if (bindErr.get() != null) {
                throw new AssertionError(tag + "FIXTURE INVALID — " + bindErr.get());
            }

            // ---- Place the gold, then wait for the section's mesh to actually be REPLACED ----
            com.warwa.seamlessportals.render.SeamClipRenderer.resetMeshTracking();
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                writeAsPlayer(ow, goldCell,
                    net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
                // ★ CLAIM THE OWNER HALF — a precondition since 2026-08-02, not a weakening of the
                // assertion. The clip no longer picks the kept half from the CAMERA (that was the
                // walk-around swap the user declined); it reads the half recorded when the block was
                // PLACED. writeAsPlayer is a raw setBlock and records nothing, so without this the
                // cell has no owner, the clip correctly declines to cut it, and the gate fails with
                // farGold=1.0 — which is exactly what it did on the first run after the change.
                //
                // POSITIVE z is the half this fixture keeps, and that is not a guess: nearZ is
                // defined as planeZ + 0.25 (the calibration point, expected GOLD) and farZ as
                // planeZ - 0.4 (expected NOT gold), so the kept side is the +z one by construction.
            });
            // BOTH SIDES — the clip runs on the render thread against mc.level, and a server-only
            // claim leaves the client with no owner (measured: cellsDrawn=48, ownPlaneDraws=0).
            claimOwnerHalfBothSides(context, goldCell,
                com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE);
            boolean recompiled = false;
            for (int i = 0; i < 40 && !recompiled; i++) {
                context.waitTicks(5);
                recompiled = com.warwa.seamlessportals.render.SeamClipRenderer.meshReplacedAt(
                    goldCell.getX(), goldCell.getY(), goldCell.getZ());
            }
            if (!recompiled) {
                throw new AssertionError(tag + "the gold cell's section never recompiled after"
                    + " placement (200 ticks) — a screenshot now would show a stale mesh and prove"
                    + " nothing. counters: "
                    + com.warwa.seamlessportals.render.SeamClipRenderer.counters());
            }

            // ---- The two shots, crosshair-aimed at world points, sampled just below centre ----
            aimAt(context, faceX, sampleY, farZ);
            context.waitTicks(10);
            double farGold = goldFractionAtCenter(context,
                "rs-seam-clip-far-" + (clipOn ? "on" : "off"), tag);
            aimAt(context, faceX, sampleY, nearZ);
            context.waitTicks(10);
            double nearGold = goldFractionAtCenter(context,
                "rs-seam-clip-near-" + (clipOn ? "on" : "off"), tag);

            long cellsDrawn = com.warwa.seamlessportals.render.SeamClipRenderer.cellsDrawnCount();
            long cellsExcluded =
                com.warwa.seamlessportals.render.SeamClipRenderer.cellsExcludedCount();
            SeamlessPortalsConstants.LOGGER.info(
                tag + "clipOn={} farGold={} nearGold={} counters: {}",
                clipOn, String.format("%.2f", farGold), String.format("%.2f", nearGold),
                com.warwa.seamlessportals.render.SeamClipRenderer.counters());

            // ---- Verdicts ----
            if (nearGold < 0.5) {
                throw new AssertionError(tag + "CALIBRATION FAILED — the NEAR (kept-half) sample"
                    + " is not gold (" + nearGold + "); the block did not render or the camera is"
                    + " mis-aimed, so the far-half verdict below would be meaningless."
                    + (clipOn ? " With the fix ON this also means the DYNAMIC draw did not draw"
                        + " the excluded block — the exact hole the lever must never leave." : ""));
            }
            if (clipOn) {
                if (farGold > 0.2) {
                    throw new AssertionError(tag + "SEAM CLIP FAILED — the far half is still"
                        + " drawn from the side (farGold=" + farGold + "): the pixels the user"
                        + " sees did not change");
                }
                if (cellsDrawn == 0 || cellsExcluded == 0) {
                    throw new AssertionError(tag + "COVERAGE FAILED — pixels pass but the"
                        + " mechanism never ran (cellsDrawn=" + cellsDrawn + " cellsExcluded="
                        + cellsExcluded + "); the verdict would be passing for the wrong reason");
                }
                SeamlessPortalsConstants.LOGGER.info(tag + "PASS — far half GONE from the side"
                    + " view, near half present via the dynamic draw, coverage confirmed");
            } else {
                if (farGold < 0.5) {
                    throw new AssertionError(tag + "INVERSION FAILED (REGRESSION) — with the clip"
                        + " disabled the far half must be visible from the side again (farGold="
                        + farGold + "); either the lever does not restore the old path or the"
                        + " fixture stopped reproducing the defect");
                }
                if (cellsDrawn != 0 || cellsExcluded != 0) {
                    throw new AssertionError(tag + "INVERSION FAILED — the clip is OFF but the"
                        + " mechanism still ran (cellsDrawn=" + cellsDrawn + " cellsExcluded="
                        + cellsExcluded + ")");
                }
                SeamlessPortalsConstants.LOGGER.info(tag + "INVERSION PASS — whole cube visible"
                    + " from the side with the clip off (the default), mechanism fully idle");
            }
        } finally {
            try {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    if (ow != null) {
                        for (var portal : ow.getEntitiesOfClass(
                            qouteall.imm_ptl.core.portal.Portal.class,
                            new net.minecraft.world.phys.AABB(qx - 6, qy - 8, qz - 12,
                                qx + 12, qy + 10, qz + 6), p -> true)) {
                            portal.discard();
                        }
                    }
                });
                runCommands(context, List.of(
                    "execute in minecraft:overworld run fill " + (qx - 14) + " " + (qy - 1) + " "
                        + (qz - 12) + " " + (qx + 10) + " " + (qy + 8) + " " + (qz + 4)
                        + " minecraft:air",
                    // the mirrored far half (player-only policy declines the COMMAND clear above,
                    // so the mirror at the dest cell must be removed explicitly)
                    "execute in minecraft:overworld run setblock " + mirrorCell.getX() + " "
                        + mirrorCell.getY() + " " + mirrorCell.getZ() + " minecraft:air"
                ));
                runCommands(context, List.of("effect clear @p minecraft:night_vision"));
                if (prevDim != null) {
                    runCommands(context, List.of(
                        "execute in " + prevDim + " run tp @p " + prevPos.x + " " + prevPos.y
                            + " " + prevPos.z));
                    context.waitTicks(10);
                }
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(tag + "CLEANUP FAILED — later legs may see"
                    + " leftover staging at x=" + qx, t);
            }
        }
    }

    /**
     * RS SEAM-CLIP ARC EVIDENCE — reproduces the user's 2026-07-27 walk-around report with a
     * fixed camera arc. Same fixture recipe as the gate (frameless EXACT same-dim portal, gold
     * in the +X edge aperture cell, glowstone + night vision; NO backdrop — position 8 stands
     * where the gate's wall was). Eight shots, front → side → across the plane → behind, each
     * aimed at the seam cell's centre. Asserts nothing; screenshots lever only; fail-soft;
     * cleanup in finally including player-whereabouts restore.
     */
    private static void rsSeamClipArcEvidence(ClientGameTestContext context, int px, int py, int pz) {
        if (!screenshotsLeverOn() || AperturePassthroughLever.DISABLED) {
            return;
        }
        final String tag = LOG + "[RS-SEAM-CLIP-ARC] ";
        final int rx = px + 64, ry = py, rz = pz;
        final double planeZ = rz - 5.5;
        final int apertureZ = rz - 6;
        final BlockPos goldCell = new BlockPos(rx + 5, ry + 1, apertureZ);
        final BlockPos mirrorCell = new BlockPos(rx + 5, ry + 1, apertureZ - 50);
        final Vec3 portalOrigin = new Vec3(rx + 4.5, ry + 1.5, planeZ);
        final Vec3 portalDest = new Vec3(rx + 4.5, ry + 1.5, planeZ - 50.0);
        final double aimX = rx + 5.5, aimY = ry + 1.5, aimZ = planeZ;

        String prevDim = context.computeOnClient(mc ->
            mc.level == null ? null : mc.level.dimension().identifier().toString());
        Vec3 prevPos = context.computeOnClient(mc ->
            mc.player == null ? Vec3.ZERO : mc.player.position());
        try {
            runCommands(context, List.of(
                "execute in minecraft:overworld run fill " + (rx - 4) + " " + (ry - 1) + " "
                    + (rz - 14) + " " + (rx + 12) + " " + (ry - 1) + " " + (rz + 2)
                    + " minecraft:obsidian",
                "execute in minecraft:overworld run fill " + (rx - 4) + " " + ry + " "
                    + (rz - 14) + " " + (rx + 12) + " " + (ry + 8) + " " + (rz + 2)
                    + " minecraft:air"
            ));
            seamClipStand(context, rx + 5.5, ry, rz - 1.5);
            runCommands(context, List.of(
                "execute in minecraft:overworld run setblock " + (rx + 8) + " " + ry + " "
                    + (rz - 6) + " minecraft:glowstone",
                "execute in minecraft:overworld run effect give @p minecraft:night_vision"
                    + " 3600 0 true"
            ));
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                spawnTestPortal(ow, portalOrigin, Level.OVERWORLD, portalDest);
            });
            context.waitTicks(20);
            runOnServer(context, server -> {
                ServerLevel ow = server.getLevel(Level.OVERWORLD);
                writeAsPlayer(ow, goldCell,
                    net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState());
            });
            // Same precondition as the pixel gate, and on BOTH sides: the clip reads the owner half
            // recorded at PLACEMENT from mc.level, and a raw setBlock records nothing anywhere.
            claimOwnerHalfBothSides(context, goldCell,
                com.warwa.seamlessportals.passthrough.SeamOccupancy.HALF_POSITIVE);
            context.waitTicks(40);

            // The arc: player feet positions walking around the +X side of the portal.
            // planeZ = rz-5.5; positions 1-4 are FRONT of the plane, 5-8 BEHIND.
            double[][] arc = {
                { rx + 5.5, rz - 1.5 },   // 1 front, head-on
                { rx + 9.5, rz - 2.5 },   // 2 front-oblique
                { rx + 10.0, rz - 4.5 },  // 3 side, clearly front (the gate's own camera)
                { rx + 10.0, rz - 5.3 },  // 4 side, 0.2 before the plane
                { rx + 10.0, rz - 5.7 },  // 5 side, 0.2 past the plane
                { rx + 10.0, rz - 6.5 },  // 6 side, clearly behind
                { rx + 9.5, rz - 8.5 },   // 7 back-oblique
                { rx + 5.5, rz - 9.5 },   // 8 behind, head-on (through the back window)
            };
            for (int i = 0; i < arc.length; i++) {
                seamClipStand(context, arc[i][0], ry, arc[i][1]);
                aimAt(context, aimX, aimY, aimZ);
                context.waitTicks(8);
                maybeScreenshot(context, String.format("rs-seam-clip-arc-%d", i + 1));
            }
            SeamlessPortalsConstants.LOGGER.info(tag + "8 arc shots captured; counters: {}",
                com.warwa.seamlessportals.render.SeamClipRenderer.counters());
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(tag + "FAILED (non-fatal, evidence only)", t);
        } finally {
            try {
                runOnServer(context, server -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    if (ow != null) {
                        for (var portal : ow.getEntitiesOfClass(
                            qouteall.imm_ptl.core.portal.Portal.class,
                            new net.minecraft.world.phys.AABB(rx - 4, ry - 8, rz - 14,
                                rx + 12, ry + 10, rz + 2), p -> true)) {
                            portal.discard();
                        }
                    }
                });
                runCommands(context, List.of(
                    "execute in minecraft:overworld run fill " + (rx - 4) + " " + (ry - 1) + " "
                        + (rz - 14) + " " + (rx + 12) + " " + (ry + 8) + " " + (rz + 2)
                        + " minecraft:air",
                    "execute in minecraft:overworld run setblock " + mirrorCell.getX() + " "
                        + mirrorCell.getY() + " " + mirrorCell.getZ() + " minecraft:air",
                    "effect clear @p minecraft:night_vision"
                ));
                if (prevDim != null) {
                    runCommands(context, List.of(
                        "execute in " + prevDim + " run tp @p " + prevPos.x + " " + prevPos.y
                            + " " + prevPos.z));
                }
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(tag + "CLEANUP FAILED", t);
            }
        }
    }

    /** Cross-dim-safe stand: force the overworld, pin the client copy (anti rubber-band). */
    private static void seamClipStand(
        ClientGameTestContext context, double x, double y, double z
    ) {
        runOnServer(context, server -> {
            CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(src,
                "execute in minecraft:overworld run tp @p " + x + " " + y + " " + z + " 90 0");
        });
        context.waitTicks(20);   // cross-dim arrival is packet-driven; give it real time
        context.runOnClient(mc -> {
            if (mc.player != null) {
                mc.player.setPos(x, y, z);
                mc.player.xo = x;
                mc.player.yo = y;
                mc.player.zo = z;
                mc.player.setDeltaMovement(Vec3.ZERO);
            }
        });
        context.waitTicks(5);
    }

    /**
     * Aim the crosshair at an exact WORLD point, from the player's ACTUAL eye position (client
     * side — no eye-height estimate), so the sampled patch is always the image centre.
     */
    private static void aimAt(ClientGameTestContext context, double tx, double ty, double tz) {
        context.runOnClient(mc -> {
            if (mc.player == null) {
                return;
            }
            Vec3 eye = mc.player.getEyePosition();
            double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.yRotO = yaw;
            mc.player.xRotO = pitch;
        });
        context.waitTicks(3);
    }

    /**
     * Screenshot → read back → fraction of gold-ish pixels in the 9×9 centre patch. Gold-ish is a
     * hue test (strongly red-over-blue, green-over-blue) that survives vanilla face shading and
     * AO; the blue backdrop and nether/terrain backgrounds all fail it. Throws if the PNG never
     * becomes readable — an unreadable shot must fail the gate, not pass it vacuously.
     */
    private static double goldFractionAtCenter(
        ClientGameTestContext context, String name, String tag
    ) {
        java.nio.file.Path shot;
        try {
            shot = context.takeScreenshot(name);
        } catch (Throwable t) {
            throw new AssertionError(tag + "takeScreenshot('" + name + "') failed", t);
        }
        java.awt.image.BufferedImage img = null;
        for (int i = 0; i < 10 && img == null; i++) {
            try {
                img = javax.imageio.ImageIO.read(shot.toFile());
            } catch (Throwable ignored) {
                img = null;
            }
            if (img == null) {
                context.waitTicks(2);
            }
        }
        if (img == null) {
            throw new AssertionError(tag + "screenshot unreadable after retries: " + shot);
        }
        // Patch centre sits BELOW the crosshair (which is not hidden — 26.2 dropped the
        // Options.hideGui field): max(20, h/24) px ≈ 0.15-0.38 blocks below the aim point at any
        // test resolution/gui scale — still on the sampled face, same side of the cut.
        int cx = img.getWidth() / 2;
        int cy = img.getHeight() / 2 + Math.max(20, img.getHeight() / 24);
        int r = 4;
        int gold = 0, total = 0;
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                int rgb = img.getRGB(x, y);
                int rr = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, bb = rgb & 0xFF;
                total++;
                if (rr > 100 && rr > bb + 40 && gg > bb + 20) {
                    gold++;
                }
            }
        }
        double fraction = total == 0 ? 0 : gold / (double) total;
        SeamlessPortalsConstants.LOGGER.info(tag + "shot {} centre {}x{} goldFraction={} ({})",
            name, img.getWidth(), img.getHeight(), String.format("%.2f", fraction), shot);
        return fraction;
    }

    private static void writeAsPlayer(
        net.minecraft.server.level.ServerLevel level, BlockPos pos,
        net.minecraft.world.level.block.state.BlockState state
    ) {
        Object[] saved = com.warwa.seamlessportals.passthrough.SeamWriteContext.push(
            state.isAir()
                ? com.warwa.seamlessportals.passthrough.SeamWriteSource.PLAYER_BREAK
                : com.warwa.seamlessportals.passthrough.SeamWriteSource.PLAYER_PLACE,
            pos);
        try {
            level.setBlockAndUpdate(pos, state);
        }
        finally {
            com.warwa.seamlessportals.passthrough.SeamWriteContext.pop(saved);
        }
    }

    /**
     * RS-A STEPS 5+6 GATE — proves the mirror actually mirrors.
     *
     * <p>Three assertions, in causal order, on a cell that already holds a rail:
     * <ol>
     *   <li><b>Mirror wrote.</b> The destination's coincident cell must now hold the same block. If
     *       it does not, the seam does not exist and every later sub-feature is built on nothing.</li>
     *   <li><b>Provenance cleared.</b> Breaking the source half must clear the mirrored counterpart —
     *       and only because provenance recorded that WE created it. Without provenance the same code
     *       would delete a block the player built from the far side, which inverts the user's rule.</li>
     *   <li><b>Refuse-on-conflict.</b> With the destination cell occupied by something we did not
     *       create, {@code SeamMirror.mayPlace} must refuse — no source-only half.</li>
     * </ol>
     * Asserts rather than reports: unlike the evidence legs, this is a gate.
     */
    private static void rsMirrorGate(ClientGameTestContext context, BlockPos sourceCell) {
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<String> detail = new AtomicReference<>("");

        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow == null) { failure.set("no overworld"); return; }

            var cell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, sourceCell);
            if (cell == null) {
                failure.set("no seam binding at " + sourceCell + " — the registry did not index the"
                    + " cell the rail was placed in, so the mirror had nothing to act on");
                return;
            }
            var binding = cell.bindings().stream()
                .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                .findFirst().orElse(null);
            if (binding == null) {
                failure.set("seam at " + sourceCell + " has no MIRRORABLE binding");
                return;
            }
            ServerLevel dest = server.getLevel(binding.destDim());
            if (dest == null) { failure.set("destination level missing"); return; }
            BlockPos destPos = binding.destPos();
            dest.getChunk(destPos.getX() >> 4, destPos.getZ() >> 4);

            // (1) the mirror wrote
            net.minecraft.world.level.block.state.BlockState mirrored = dest.getBlockState(destPos);
            detail.set("source=" + sourceCell + " dest=" + destPos + " in "
                + dest.dimension().identifier() + " mirroredState=" + mirrored.getBlock());
            if (!mirrored.is(net.minecraft.world.level.block.Blocks.RAIL)) {
                failure.set("MIRROR DID NOT WRITE — destination " + destPos + " in "
                    + dest.dimension().identifier() + " holds " + mirrored.getBlock()
                    + ", expected minecraft:rail. The seam does not exist.");
                return;
            }

            // ASSERT THE SHAPE, NOT JUST THE BLOCK. Checking is(Blocks.RAIL) alone hid a real defect:
            // LevelChunk.setBlockState runs onPlace INSIDE its body, so a rail resolves its shape via
            // a NESTED setBlock, and the OUTER inject then re-mirrored its own pre-resolution
            // parameter — the far rail was a rail, but with the wrong shape. A block-identity
            // assertion cannot see that.
            net.minecraft.world.level.block.state.BlockState localState = ow.getBlockState(sourceCell);
            var shapeProp = net.minecraft.world.level.block.state.properties.BlockStateProperties.RAIL_SHAPE;
            if (localState.hasProperty(shapeProp) && mirrored.hasProperty(shapeProp)) {
                var localShape = localState.getValue(shapeProp);
                var farShape = mirrored.getValue(shapeProp);
                var expected = localShape;   // identity rotation for an unrotated pair
                if (binding.stateRotation() != net.minecraft.world.level.block.Rotation.NONE) {
                    expected = localState.rotate(binding.stateRotation()).getValue(shapeProp);
                }
                if (farShape != expected) {
                    failure.set("MIRRORED SHAPE MISMATCH — source " + sourceCell + " is " + localShape
                        + " but destination " + destPos + " is " + farShape + " (expected " + expected
                        + " under rotation " + binding.stateRotation() + "). The far rail is a rail"
                        + " but the WRONG rail: shape resolution happens in a NESTED setBlock and the"
                        + " outer mirror must not overwrite it with its pre-resolution parameter.");
                    return;
                }
            }

            // (2) breaking the source clears the mirrored counterpart (provenance)
            writeAsPlayer(ow, sourceCell, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            net.minecraft.world.level.block.state.BlockState afterBreak = dest.getBlockState(destPos);
            if (!afterBreak.isAir()) {
                failure.set("PROVENANCE CLEAR FAILED — source half was broken but destination "
                    + destPos + " still holds " + afterBreak.getBlock());
                return;
            }

            // (2b) THE REVERSE DIRECTION — breaking the MIRRORED half must clear the SOURCE half.
            // The user found this by hand because the original gate only ever broke the source: with
            // the clear gated on provenance, breaking the mirrored side left the source standing and
            // the player could not replace their own block ("places and instantly disappears").
            // Symmetric behaviour is not optional — a seam has no privileged side.
            writeAsPlayer(ow, sourceCell, net.minecraft.world.level.block.Blocks.RAIL.defaultBlockState());
            if (!dest.getBlockState(destPos).is(net.minecraft.world.level.block.Blocks.RAIL)) {
                failure.set("re-place did not re-mirror; cannot test the reverse direction");
                return;
            }
            writeAsPlayer(dest, destPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            net.minecraft.world.level.block.state.BlockState sourceAfterReverse = ow.getBlockState(sourceCell);
            if (!sourceAfterReverse.isAir()) {
                failure.set("REVERSE CLEAR FAILED — the MIRRORED half was broken but the source half "
                    + sourceCell + " still holds " + sourceAfterReverse.getBlock()
                    + "; the player cannot replace their own block because the seam stays occupied");
                return;
            }

            // (3) refuse-on-conflict: occupy the destination with something we did NOT create
            dest.setBlockAndUpdate(destPos, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            boolean allowed = com.warwa.seamlessportals.passthrough.SeamMirror.mayPlace(
                ow, sourceCell, net.minecraft.world.level.block.Blocks.RAIL.defaultBlockState());
            if (allowed) {
                failure.set("REFUSE-ON-CONFLICT FAILED — destination " + destPos
                    + " is occupied by stone we did not create, yet mayPlace allowed the placement;"
                    + " that produces exactly the source-only half the user's rule forbids");
                return;
            }
            writeAsPlayer(dest, destPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        });

        String f = failure.get();
        if (f != null) {
            throw new AssertionError(LOG + "RS-A steps 5+6 MIRROR GATE FAILED: " + f);
        }
        SeamlessPortalsConstants.LOGGER.info(
            LOG + "RS-A steps 5+6 MIRROR GATE PASS — wrote, provenance-cleared, and refused on"
                + " conflict. {} | counters: {}",
            detail.get(), com.warwa.seamlessportals.passthrough.SeamMirror.counters());
    }

    /**
     * RS-A STEP-7 GATE — the FRAME-BREAK RULE (user decision, {@code REDSTONE_RECON.md} §0.8).
     *
     * <p>On a frame break the originally-placed block survives in its own dimension and its MIRROR is
     * removed. The user chose this over keep-both precisely because keep-both turns one placed block
     * into two — a duplication route.
     *
     * <p><b>This is the only consumer of provenance, and the only test of it.</b> After a break the
     * two halves are indistinguishable by inspection: same block, same state, one per side. Only
     * {@code mirrorCreatedCells} records which half this level RECEIVED rather than had built in it.
     * If provenance is wrong or missing, this gate fails in the most informative way possible —
     * either the player's block vanishes (rule inverted) or the mirror survives (duplication).
     *
     * <p>Written because the first step-7 run reported {@code frameBreakCleared=0}: the rule was
     * shipped-but-never-executed, the same coverage trap that has already produced a probe which only
     * logged its passing case and a gate that skipped its own central assertion.
     */
    private static void rsFrameBreakGate(
        ClientGameTestContext context, BlockPos sourceCell, int fx, int py, int fz
    ) {
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<BlockPos> destRef = new AtomicReference<>(null);
        AtomicReference<net.minecraft.resources.ResourceKey<Level>> destDimRef = new AtomicReference<>(null);

        // Place a rail: it mirrors, and the destination copy is recorded as mirror-created.
        // Player-attributed — a /setblock is classified COMMAND and declined since 2026-07-26, which
        // would leave this gate's setup silently unmirrored and its verdict meaningless.
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow != null) {
                writeAsPlayer(ow, sourceCell, net.minecraft.world.level.block.Blocks.RAIL.defaultBlockState());
            }
        });
        context.waitTicks(10);

        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            var scell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow, sourceCell);
            if (scell == null) { failure.set("seam binding vanished before the frame-break test"); return; }
            var b = scell.bindings().stream()
                .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                .findFirst().orElse(null);
            if (b == null) { failure.set("no mirrorable binding for the frame-break test"); return; }
            destRef.set(b.destPos());
            destDimRef.set(b.destDim());
            ServerLevel dest = server.getLevel(b.destDim());
            dest.getChunk(b.destPos().getX() >> 4, b.destPos().getZ() >> 4);
            if (!dest.getBlockState(b.destPos()).is(net.minecraft.world.level.block.Blocks.RAIL)) {
                failure.set("setup failed: the rail did not mirror, so the frame-break rule cannot"
                    + " be tested");
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(LOG + "RS-A step-7 FRAME-BREAK GATE SETUP FAILED: " + failure.get());
        }

        // Break ONE obsidian of the frame — a genuine frame break, the trigger the rule is written for.
        runCommands(context, List.of(
            "setblock " + (fx - 1) + " " + (py + 2) + " " + fz + " minecraft:air"));

        // WAIT FOR THE FAR PORTAL TO ACTUALLY TEAR DOWN, do not assume a tick count. The frame-break
        // rule fires from the DESTINATION portal's own teardown, and cross-dimension propagation runs
        // through markShouldBreak -> a deferred ServerTaskList task that RETRIES while the far side
        // is not ready. A fixed 60-tick wait asserted before that had happened and reported "THE
        // MIRROR SURVIVED" — a precondition failure wearing a defect's clothes, and it pointed
        // straight at innocent provenance code.
        final BlockPos farCell = destRef.get();
        final net.minecraft.resources.ResourceKey<Level> farDim = destDimRef.get();
        try {
            context.waitFor(mc -> {
                MinecraftServer s = mc.getSingleplayerServer();
                if (s == null) return false;
                ServerLevel d = s.getLevel(farDim);
                if (d == null) return false;
                return d.getEntitiesOfClass(
                    qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                    new net.minecraft.world.phys.AABB(
                        Vec3.atCenterOf(farCell).subtract(8, 8, 8),
                        Vec3.atCenterOf(farCell).add(8, 8, 8)),
                    p -> true).isEmpty();
            }, 600);
        } catch (Throwable t) {
            throw new AssertionError(LOG + "RS-A step-7 FRAME-BREAK GATE FAILED: the destination"
                + " portal never tore down within 600 ticks of the source frame being broken —"
                + " cross-dimension teardown propagation did not reach it, so the frame-break rule"
                + " never ran. This is a propagation failure, NOT a provenance failure.", t);
        }
        context.waitTicks(10);

        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            ServerLevel dest = server.getLevel(destDimRef.get());
            BlockPos destPos = destRef.get();
            dest.getChunk(destPos.getX() >> 4, destPos.getZ() >> 4);

            net.minecraft.world.level.block.state.BlockState sourceState = ow.getBlockState(sourceCell);
            net.minecraft.world.level.block.state.BlockState destState = dest.getBlockState(destPos);

            if (!sourceState.is(net.minecraft.world.level.block.Blocks.RAIL)) {
                failure.set("THE PLAYER'S BLOCK WAS DELETED — source " + sourceCell + " holds "
                    + sourceState.getBlock() + " after the frame break, expected minecraft:rail."
                    + " §0.4 says blocks survive in their own dimension; provenance is inverted.");
                return;
            }
            if (!destState.isAir()) {
                failure.set("THE MIRROR SURVIVED — destination " + destPos + " still holds "
                    + destState.getBlock() + " after the frame break. §0.8 says the mirrored half is"
                    + " cleared; leaving it turns one placed block into two (duplication).");
            }
        });

        String f = failure.get();
        if (f != null) {
            throw new AssertionError(LOG + "RS-A step-7 FRAME-BREAK GATE FAILED: " + f);
        }
        SeamlessPortalsConstants.LOGGER.info(
            LOG + "RS-A step-7 FRAME-BREAK GATE PASS — player-placed half survived at {}, mirrored"
                + " half cleared at {} | counters: {}",
            sourceCell, destRef.get(), com.warwa.seamlessportals.passthrough.SeamMirror.counters());

        // ---- FRAME MIRRORING GATE ----
        // Runs HERE, immediately after the frame-break gate, because the state it needs is exactly
        // the one that gate leaves behind: a broken frame with BOTH portals dead. That is the case
        // frame mirroring exists for, and the case live portal bindings cannot serve.
        rsFrameMirrorGate(context, fx, py, fz);
    }

    /**
     * FRAME MIRRORING GATE — proves the user's frame rule works with NO PORTAL ALIVE.
     *
     * <p>The rule: breaking obsidian on one side breaks the corresponding obsidian on the other, and
     * repairing one side repairs the other. The difficulty is never the mirroring — it is that
     * {@link com.warwa.seamlessportals.passthrough.SeamRegistry} bindings are DERIVED from live
     * portals, so once both are torn down nothing knows which obsidian pairs with which. That is what
     * the persisted {@code SeamFrameLink} is for, and this gate is the only thing that proves it.
     *
     * <p><b>Precondition, asserted rather than assumed:</b> both portals must already be dead. If a
     * portal were still alive the test could pass on live bindings and prove nothing about the
     * dormant path — the same self-consistency trap that let the mirror off-by-one survive.
     *
     * <p>Two assertions: the break already mirrored (the frame-break gate broke ONE obsidian on the
     * source side; its partner must be gone too), and a REPAIR mirrors back.
     */
    private static void rsFrameMirrorGate(ClientGameTestContext context, int fx, int py, int fz) {
        if (AperturePassthroughLever.DISABLED) {
            return;
        }
        final BlockPos brokenFrame = new BlockPos(fx - 1, py + 2, fz);
        AtomicReference<String> failure = new AtomicReference<>(null);
        AtomicReference<String> detail = new AtomicReference<>("");
        AtomicReference<BlockPos> partnerRef = new AtomicReference<>(null);
        AtomicReference<net.minecraft.resources.ResourceKey<Level>> partnerDim = new AtomicReference<>(null);

        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            if (ow == null) { failure.set("no overworld"); return; }

            // PRECONDITION: no portal may be alive at this frame, or the test proves nothing.
            boolean anyAlive = !ow.getEntitiesOfClass(
                qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                new net.minecraft.world.phys.AABB(fx - 8, py - 8, fz - 8, fx + 8, py + 8, fz + 8),
                p -> true).isEmpty();
            if (anyAlive) {
                failure.set("a portal is still alive at the test frame — frame mirroring would be"
                    + " exercised through LIVE bindings, proving nothing about the dormant link");
                return;
            }

            var link = com.warwa.seamlessportals.passthrough.SeamFrameLink.lookup(ow, brokenFrame);
            if (link == null) {
                failure.set("NO DORMANT FRAME LINK at " + brokenFrame + " — the pairing was not"
                    + " recorded while the portals were alive, so a repair has nothing to mirror"
                    + " through. This is the whole mechanism failing.");
                return;
            }
            partnerRef.set(link.to());
            partnerDim.set(link.toDim());
            ServerLevel far = server.getLevel(link.toDim());
            far.getChunk(link.to().getX() >> 4, link.to().getZ() >> 4);

            // (1) the break mirrored: the frame-break gate broke this obsidian; its partner must be gone
            net.minecraft.world.level.block.state.BlockState partnerState = far.getBlockState(link.to());
            detail.set("broken=" + brokenFrame + " partner=" + link.to() + " in "
                + link.toDim().identifier() + " partnerState=" + partnerState.getBlock());
            if (!partnerState.isAir()) {
                failure.set("FRAME BREAK DID NOT MIRROR — " + brokenFrame + " was broken but its"
                    + " partner " + link.to() + " in " + link.toDim().identifier() + " still holds "
                    + partnerState.getBlock());
                return;
            }

            // (2) repair the near side — this must NOT mirror yet
            ow.setBlockAndUpdate(brokenFrame, net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
        });
        if (failure.get() != null) {
            throw new AssertionError(LOG + "RS-A FRAME MIRROR GATE FAILED: " + failure.get());
        }
        context.waitTicks(20);

        // (2a) REPAIRS ARE STAGED, NOT INSTANT (user decision 2026-07-26). A half-rebuilt frame is a
        // construction site; reaching into another dimension to place blocks the player has not asked
        // for yet is surprising. The far side must still be broken at this point.
        runOnServer(context, server -> {
            ServerLevel far = server.getLevel(partnerDim.get());
            far.getChunk(partnerRef.get().getX() >> 4, partnerRef.get().getZ() >> 4);
            if (!far.getBlockState(partnerRef.get()).isAir()) {
                failure.set("REPAIR MIRRORED TOO EARLY — the near frame was repaired but not lit, yet"
                    + " the partner " + partnerRef.get() + " is already restored. Repairs must be"
                    + " staged until ignition.");
            }
        });
        if (failure.get() != null) {
            throw new AssertionError(LOG + "RS-A FRAME MIRROR GATE FAILED: " + failure.get());
        }

        // (2b) IGNITION restores the far frame, and must do so BEFORE the destination match search —
        // otherwise generation fabricates a new portal elsewhere instead of relinking.
        runOnServer(context, server ->
            qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration
                .onFireLitOnObsidian(server.getLevel(Level.OVERWORLD),
                    new BlockPos(fx, py + 1, fz), null));
        context.waitTicks(80);

        // WAIT FOR THE PRECONDITION, NOT A TICK COUNT. The re-bind happens on the portal TICK
        // signal, so the fixed 80 ticks above is a hope, not a guarantee: this gate failed once with
        // "no seam binding at the surviving rail after relight" and passed on an identical re-run.
        // A gate that fails intermittently teaches people to re-run it, which is how a real
        // regression eventually gets waved through. Poll for the binding and let the assertion below
        // speak only once it is genuinely absent.
        {
            AtomicReference<Boolean> bound = new AtomicReference<>(false);
            BlockPos survivorProbe = new BlockPos(fx, py + 1, fz);
            for (int attempt = 0; attempt < 20 && !bound.get(); attempt++) {
                runOnServer(context, server -> {
                    ServerLevel ow3 = server.getLevel(Level.OVERWORLD);
                    bound.set(ow3 != null
                        && com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow3, survivorProbe) != null);
                });
                if (!bound.get()) {
                    context.waitTicks(10);
                }
            }
            SeamlessPortalsConstants.LOGGER.info(
                LOG + "RS-A FRAME MIRROR GATE: re-bind after relight observed={} at {}",
                bound.get(), survivorProbe);
        }

        runOnServer(context, server -> {
            ServerLevel far = server.getLevel(partnerDim.get());
            far.getChunk(partnerRef.get().getX() >> 4, partnerRef.get().getZ() >> 4);
            net.minecraft.world.level.block.state.BlockState s = far.getBlockState(partnerRef.get());
            if (s.isAir()) {
                failure.set("IGNITION DID NOT RESTORE THE FAR FRAME — the partner "
                    + partnerRef.get() + " is still air after the near side was repaired AND lit."
                    + " The dormant link is the only path that can do this with both portals dead.");
                return;
            }

            // BIND-TIME RECONCILIATION. A rail that SURVIVED the frame break is already sitting in
            // the aperture when the portal re-lights, so it never changes and change-driven mirroring
            // never carries it — the user's "relight with a rail on the portal floor and half the
            // rail gets cut off". The surviving source rail must have a counterpart again.
            ServerLevel ow2 = server.getLevel(Level.OVERWORLD);
            BlockPos survivor = new BlockPos(fx, py + 1, fz);
            if (ow2.getBlockState(survivor).is(net.minecraft.world.level.block.Blocks.RAIL)) {
                var reCell = com.warwa.seamlessportals.passthrough.SeamRegistry.lookup(ow2, survivor);
                if (reCell == null) {
                    failure.set("no seam binding at the surviving rail " + survivor + " after relight");
                    return;
                }
                var reBind = reCell.bindings().stream()
                    .filter(com.warwa.seamlessportals.passthrough.SeamRegistry.SeamBinding::isMirrorable)
                    .findFirst().orElse(null);
                if (reBind != null) {
                    ServerLevel d2 = server.getLevel(reBind.destDim());
                    d2.getChunk(reBind.destPos().getX() >> 4, reBind.destPos().getZ() >> 4);
                    if (!d2.getBlockState(reBind.destPos())
                            .is(net.minecraft.world.level.block.Blocks.RAIL)) {
                        failure.set("BIND RECONCILIATION FAILED — a rail survived the frame break at "
                            + survivor + " and the portal was re-lit, but its counterpart "
                            + reBind.destPos() + " in " + reBind.destDim().identifier()
                            + " holds " + d2.getBlockState(reBind.destPos()).getBlock()
                            + ". Mirroring is change-driven, so a pre-existing block is only carried"
                            + " across by the bind-time pass — half the seam is missing without it.");
                    }
                }
            }
        });

        String f = failure.get();
        if (f != null) {
            throw new AssertionError(LOG + "RS-A FRAME MIRROR GATE FAILED: " + f);
        }
        SeamlessPortalsConstants.LOGGER.info(
            LOG + "RS-A FRAME MIRROR GATE PASS — break mirrored instantly; repair STAGED until"
                + " ignition, then restored the far frame via the dormant link with no portal alive."
                + " {} | counters: {}",
            detail.get(), com.warwa.seamlessportals.passthrough.SeamMirror.counters());
    }

    /**
     * The RS-TEARDOWN-TEST verdict, read against what the lever says SHOULD happen.
     *
     * <p>This leg's expected result inverts at step 3. With aperture passthrough DISABLED, stock IP
     * applies and a block in the opening must destroy the portal; with it ENABLED the integrity
     * predicate is frame-only and the portal must survive. So "no teardown" is a PASS in one
     * configuration and a REGRESSION in the other, and a fixed verdict string would be actively
     * misleading in whichever config it was not written for.
     */
    private static String verdictText(boolean toreDown, boolean passthroughDisabled) {
        if (passthroughDisabled) {
            return toreDown
                ? "TEARDOWN CONFIRMED — stock IP behaviour, as expected with passthrough DISABLED"
                : "*** REGRESSION *** passthrough is DISABLED so stock IP should have destroyed the"
                    + " portal, but it survived — the disable lever is not restoring stock behaviour";
        }
        return toreDown
            ? "*** REGRESSION *** passthrough is ENABLED so the portal should have SURVIVED a block"
                + " in its opening, but it was destroyed — IP-core edit 3 (frame-only integrity) is"
                + " not taking effect"
            : "NO TEARDOWN — the portal SURVIVED a block in its opening. This is the (a) feature"
                + " working: verify openingCell above really holds the placed block, else the"
                + " setblock was rejected and the leg proves nothing";
    }

    /**
     * Read a block state on the SERVER thread. Never use {@code computeOnClient} for this: the
     * client only holds chunks inside its render distance, and an unloaded client chunk reports
     * {@code void_air}, which is indistinguishable from a genuinely empty cell. That mistake made
     * the first RS-TEARDOWN-TEST run report {@code void_air} for a cell 600 blocks from the player.
     */
    private static String serverBlockAt(ClientGameTestContext context, BlockPos pos) {
        return serverBlockAt(context, Level.OVERWORLD, pos);
    }

    /** As above, in a named dimension — RS-DELIVERY-TEST's cross-dim arm reads the nether. */
    private static String serverBlockAt(
        ClientGameTestContext context, net.minecraft.resources.ResourceKey<Level> dim, BlockPos pos
    ) {
        AtomicReference<String> out = new AtomicReference<>("(unread)");
        runOnServer(context, server -> {
            ServerLevel level = server.getLevel(dim);
            if (level == null) {
                out.set("(no level " + dim.identifier() + ")");
                return;
            }
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            out.set(level.getBlockState(pos).getBlock().toString());
        });
        return out.get();
    }

    /** Count generated nether portals in a box, on the SERVER thread. */
    private static int countTestPortals(
        ClientGameTestContext context, net.minecraft.world.phys.AABB box
    ) {
        AtomicReference<Integer> out = new AtomicReference<>(-1);
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            out.set(ow == null ? -1 : ow.getEntitiesOfClass(
                qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                box, p -> true).size());
        });
        return out.get();
    }

    /** Place the player for a seam shot, pinning the client so the server tp cannot rubber-band. */
    private static void seamStand(ClientGameTestContext context, double x, double y, double z) {
        runOnServer(context, server -> {
            CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(src,
                "tp @p " + x + " " + y + " " + z + " 180 0");
        });
        context.runOnClient(mc -> {
            mc.player.setPos(x, y, z);
            mc.player.xo = x;
            mc.player.yo = y;
            mc.player.zo = z;
            mc.player.setDeltaMovement(Vec3.ZERO);
        });
        context.waitTicks(5);
    }

    private static void emOutlineEvidenceLegs(
        ClientGameTestContext context, int px, int py, int pz, double planeZ
    ) {
        if (!screenshotsLeverOn()) {
            return;
        }
        // ---- EM-O-A: ordinary platform block, no portal anywhere near the ray ----
        // HIGH-CONTRAST TARGET: the platform is obsidian (near-black) — a 2px black outline
        // is unreadable on it. Swap the aimed block for white_concrete so the outline (if
        // present) is unmistakable; this is the EM-O-A gate's whole point (present => FIX-O
        // scope correct; absent on an ordinary target => a second, global mechanism).
        try {
            // EYE-LEVEL target 3 blocks SOUTH (pz+3, away from the north portal row so no
            // portal is ever on the pick ray) so the crosshair lands face-on, not grazing a
            // foreshortened floor block. white_concrete for the black-outline contrast.
            runCommands(context, List.of(
                "setblock " + px + " " + (py + 1) + " " + (pz + 3) + " minecraft:white_concrete"
            ));
            context.getInput().lookAt(new BlockPos(px, py + 1, pz + 3));
            context.waitTicks(5);
            maybeScreenshot(context, "em-o-a-ordinary-target-outline");
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(LOG + "EM-O-A FAILED (non-fatal)", t);
        }
        // ---- EM-O-B: through the cross-dim window at the nether bedrock roof behind it ----
        try {
            final double standX = px + 4.5; // portal B's center column
            final double standZ = planeZ + 3.0;
            // HIGH-CONTRAST nether target: pave the roof patch the window ray lands on with
            // white_concrete (netherrack is dark-red — same unreadable-black-outline problem).
            // The forceloaded nether region (-16..16) covers the dest B area (0.5,129.5,0.5);
            // the ray hits block-tops at y=128, so replace the y=127 layer.
            runCommands(context, List.of(
                inDim("minecraft:the_nether",
                    "fill -6 127 -6 6 127 6 minecraft:white_concrete")
            ));
            runOnServer(context, server -> {
                CommandSourceStack src = server.createCommandSourceStack().withSuppressedOutput();
                server.getCommands().performPrefixedCommand(src,
                    "tp @p " + standX + " " + py + " " + standZ + " 180 0");
            });
            // TitleCardCapture idiom: pin the client too so the server tp can't rubber-band.
            context.runOnClient(mc -> {
                mc.player.setPos(standX, py, standZ);
                mc.player.xo = standX;
                mc.player.yo = py;
                mc.player.zo = standZ;
                mc.player.setDeltaMovement(Vec3.ZERO);
            });
            context.waitTicks(5);
            // Aim at the WINDOW-BOTTOM point (x=standX, y=py+0.15, z=planeZ): entry maps to
            // nether y~128.1 with a steep downward slope — the ray hits the bedrock roof
            // (block tops at y=128.0) ~0.3 blocks past the plane.
            float pitchDeg = context.computeOnClient(mc -> {
                Vec3 eye = mc.player.getEyePosition();
                double dy = eye.y - (py + 0.15);
                double dz = eye.z - planeZ; // positive: eye is south of the plane
                return (float) Math.toDegrees(Math.atan2(dy, dz));
            });
            context.getInput().lookAt(180f, pitchDeg); // yaw 180 = north (-Z); pitch > 0 = down
            context.waitTicks(5);
            maybeScreenshot(context, "em-o-b-window-target-outline");
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(LOG + "EM-O-B FAILED (non-fatal)", t);
        }
    }

    /**
     * The EM-G creative-browse checkpoint (defect G evidence — vanilla tabs showed empty
     * slots + blank tab icons while the IP tab stayed intact): open the creative inventory
     * via {@code ClientGameTestContext.setScreen}, screenshot the default tab, click through
     * three vanilla tabs + the IP tab ({@code TestInput.setCursorPos} + click at vanilla's
     * own tab geometry; reflective {@code selectTab} fallback when a click misses — e.g. the
     * IP tab living on a Fabric pagination page), screenshot each, close. Invoked TWICE:
     * {@code "em-g-pre"} (before any portal is spawned) and {@code "em-g-post"} (after the
     * leg-7 portal views). Fail-soft throughout.
     */
    private static void maybeCreativeBrowse(ClientGameTestContext context, String phase) {
        if (!screenshotsLeverOn()) {
            return;
        }
        try {
            context.setScreen(() -> {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                // The vanilla open-site recipe (26.2 InventoryScreen:43-45).
                return new net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen(
                    mc.player, mc.player.connection.enabledFeatures(),
                    mc.options.operatorItemsTab().get());
            });
            context.waitTicks(5);
            // "-tab-initial", NOT "-tab-default": CreativeModeInventoryScreen.selectedTab is a
            // STATIC field and init() re-selects the previously-selected tab, so on the "em-g-post"
            // re-open this shot captures whatever tab "em-g-pre" left selected (the IP tab via the
            // reflective fallback), not the true default tab. Label it for what it actually is —
            // the screen's initial state on open (IS2 fold V6-1). The explicit per-tab shots below
            // still capture each tab deterministically.
            maybeScreenshot(context, phase + "-tab-initial");
            // Three vanilla tabs (the display-ordered head of CreativeModeTabs.tabs()) + IP.
            for (int i = 0; i < 3; i++) {
                emBrowseTab(context, phase, i);
            }
            emBrowseTab(context, phase, -1); // the IP tab (PeripheralModMain.TAB)
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(
                LOG + "EM-G '" + phase + "' browse FAILED (non-fatal)", t);
        } finally {
            try {
                context.setScreen(() -> null);
                context.waitTicks(2);
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(
                    LOG + "EM-G '" + phase + "' screen close FAILED (non-fatal)", t);
            }
        }
    }

    /**
     * EM-G-R3: reproduce the user's creative-inventory mangle by exercising the untested
     * trigger — a shader TOGGLE (iris destroyPipeline + recreate) mid-session, the leading
     * icon-atlas-poison suspect. Off→render→on→render, twice, then re-browse creative under
     * {@code phase}. iris-only (reflection; no-op + skip when iris absent); fail-soft.
     */
    private static void maybeShaderToggleThenBrowse(ClientGameTestContext context, String phase) {
        if (!screenshotsLeverOn()) {
            return;
        }
        try {
            if (!isIrisPackActive(context)) {
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "EM-G toggle skipped (no iris shaderpack active this run)");
                return;
            }
            context.runOnClient(mc -> mc.player.setYRot(180f)); // face the portal row
            for (int cycle = 0; cycle < 2; cycle++) {
                setShadersEnabled(context, false);
                context.waitTicks(40); // destroy + render frames shaders-OFF (our renderer live)
                setShadersEnabled(context, true);
                context.waitTicks(60); // recreate + settle, render frames shaders-ON
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "EM-G toggle cycle " + (cycle + 1) + " done (off→on)");
            }
            maybeCreativeBrowse(context, phase);
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(LOG + "EM-G toggle leg FAILED (non-fatal)", t);
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

    /** The browse target: index into {@code CreativeModeTabs.tabs()}, or -1 = the IP tab. */
    private static net.minecraft.world.item.CreativeModeTab emTab(int tabIndex) {
        return tabIndex < 0
            ? qouteall.imm_ptl.peripheral.PeripheralModMain.TAB
            : net.minecraft.world.item.CreativeModeTabs.tabs().get(tabIndex);
    }

    /**
     * Select one creative tab via a real cursor click ({@code TestInput.setCursorPos} +
     * {@code pressMouse}) at vanilla's own tab geometry (26.2
     * {@code CreativeModeInventoryScreen.getTabX/getTabY/checkTabClicked}: tab cell 26x32 at
     * {@code 27*column} / {@code row==TOP ? -32 : imageHeight}, relative to
     * {@code leftPos/topPos}); selection happens on mouseReleased, verified against the
     * screen's {@code selectedTab}, with a reflective {@code selectTab} fallback (26.2 ships
     * unobfuscated — mojmap names at runtime). Fail-soft.
     */
    private static void emBrowseTab(ClientGameTestContext context, String phase, int tabIndex) {
        try {
            String name = context.computeOnClient(mc ->
                emTab(tabIndex).getDisplayName().getString());
            String shotName = phase + "-tab-" + emSanitize(name);
            // Click point in RAW window pixels (TestInput.setCursorPos speaks raw window
            // coords; GUI coords scale by screenWidth/guiScaledWidth).
            double[] raw = context.computeOnClient(mc -> {
                // 26.2: the current screen moved off Minecraft onto Gui (mc.gui.screen()).
                net.minecraft.client.gui.screens.Screen scr = mc.gui.screen();
                if (!(scr
                    instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen)) {
                    return null;
                }
                net.minecraft.world.item.CreativeModeTab tab = emTab(tabIndex);
                Class<?> acs = net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class;
                java.lang.reflect.Field fLeft = acs.getDeclaredField("leftPos");
                java.lang.reflect.Field fTop = acs.getDeclaredField("topPos");
                java.lang.reflect.Field fW = acs.getDeclaredField("imageWidth");
                java.lang.reflect.Field fH = acs.getDeclaredField("imageHeight");
                fLeft.setAccessible(true);
                fTop.setAccessible(true);
                fW.setAccessible(true);
                fH.setAccessible(true);
                int leftPos = fLeft.getInt(scr);
                int topPos = fTop.getInt(scr);
                int imageWidth = fW.getInt(scr);
                int imageHeight = fH.getInt(scr);
                int tabX = 27 * tab.column();
                if (tab.isAlignedRight()) {
                    tabX = imageWidth - 27 * (7 - tab.column()) + 1;
                }
                int tabY = tab.row() == net.minecraft.world.item.CreativeModeTab.Row.TOP
                    ? -32 : imageHeight;
                double guiX = leftPos + tabX + 13.0; // tab cell center (26 wide, 32 tall)
                double guiY = topPos + tabY + 16.0;
                var w = mc.getWindow();
                return new double[]{
                    guiX * w.getScreenWidth() / (double) w.getGuiScaledWidth(),
                    guiY * w.getScreenHeight() / (double) w.getGuiScaledHeight()
                };
            });
            if (raw != null) {
                context.getInput().setCursorPos(raw[0], raw[1]);
                context.waitTicks(1);
                context.getInput().pressMouse(0); // GLFW_MOUSE_BUTTON_LEFT
                context.waitTicks(2);
            }
            boolean selected = context.computeOnClient(mc -> {
                Class<?> cls = net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen.class;
                java.lang.reflect.Field f = cls.getDeclaredField("selectedTab");
                f.setAccessible(true);
                return f.get(null) == emTab(tabIndex);
            });
            if (!selected) {
                SeamlessPortalsConstants.LOGGER.info(
                    LOG + "EM-G tab click missed for '{}' — reflective selectTab fallback", name);
                context.runOnClient(mc -> {
                    if (!(mc.gui.screen()
                        instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen screen)) {
                        return;
                    }
                    java.lang.reflect.Method m =
                        net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen.class
                            .getDeclaredMethod("selectTab",
                                net.minecraft.world.item.CreativeModeTab.class);
                    m.setAccessible(true);
                    m.invoke(screen, emTab(tabIndex));
                });
                context.waitTicks(2);
            }
            maybeScreenshot(context, shotName);
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(
                LOG + "EM-G tab " + tabIndex + " FAILED (non-fatal)", t);
        }
    }

    /** Screenshot-name-safe form of a tab display name. */
    private static String emSanitize(String s) {
        String out = s.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+)|(-+$)", "");
        return out.isEmpty() ? "tab" : out;
    }

    /** Spawn an item 2 blocks south of the portal plane, flying north through it. */
    private static UUID spawnThrownItem(ClientGameTestContext context, Vec3 portalOrigin) {
        AtomicReference<UUID> id = new AtomicReference<>();
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            ItemEntity item = new ItemEntity(
                ow, portalOrigin.x, portalOrigin.y - 0.3, portalOrigin.z + 2.0,
                new ItemStack(Items.APPLE), 0, 0.02, -0.7
            );
            ow.addFreshEntity(item);
            id.set(item.getUUID());
            SeamlessPortalsConstants.LOGGER.info(LOG + "item {} thrown at {}", item.getUUID(), item.position());
        });
        return id.get();
    }

    /**
     * Wait until the entity exists in {@code dim} within 8 blocks of {@code dest}
     * (arrival-time check — before post-arrival physics moves it), else throw with
     * a rich diagnostic.
     */
    private static void waitForArrival(
        ClientGameTestContext context, String leg,
        net.minecraft.resources.ResourceKey<Level> dim, UUID entityId, Vec3 dest, int timeoutTicks
    ) {
        try {
            context.waitFor(mc -> {
                MinecraftServer server = mc.getSingleplayerServer();
                if (server == null) return false;
                ServerLevel level = server.getLevel(dim);
                if (level == null) return false;
                Entity e = level.getEntity(entityId);
                return e != null && e.position().distanceTo(dest) < 8;
            }, timeoutTicks);
        } catch (Throwable t) {
            AtomicReference<String> diag = new AtomicReference<>("(server unavailable)");
            try {
                runOnServer(context, server -> {
                    StringBuilder sb = new StringBuilder();
                    for (ServerLevel level : server.getAllLevels()) {
                        Entity e = level.getEntity(entityId);
                        if (e != null) {
                            sb.append(level.dimension().identifier()).append(" @ ")
                                .append(e.position()).append("; ");
                        }
                    }
                    diag.set(sb.length() == 0 ? "entity not found in ANY dimension" : sb.toString());
                });
            } catch (Throwable ignored) {}
            throw new AssertionError(LOG + leg + " FAILED: no arrival in "
                + dim.identifier() + " within 8 of " + dest + " after " + timeoutTicks
                + " ticks. Entity now: " + diag.get(), t);
        }
    }

    /** Assert the entity no longer exists in {@code dim} (the source-side removal). */
    private static void assertGoneFrom(
        ClientGameTestContext context, String leg,
        net.minecraft.resources.ResourceKey<Level> dim, UUID entityId
    ) {
        AtomicReference<String> still = new AtomicReference<>();
        runOnServer(context, server -> {
            ServerLevel level = server.getLevel(dim);
            Entity e = level == null ? null : level.getEntity(entityId);
            if (e != null && !e.isRemoved()) {
                still.set(e + " @ " + e.position());
            }
        });
        if (still.get() != null) {
            throw new AssertionError(LOG + leg + " FAILED: source entity still present in "
                + dim.identifier() + ": " + still.get());
        }
    }

    private static String fill(int x1, int y1, int z1, int x2, int y2, int z2) {
        return "fill " + x1 + " " + y1 + " " + z1 + " "
            + x2 + " " + y2 + " " + z2 + " minecraft:obsidian";
    }

    private static String inDim(String dim, String command) {
        return "execute in " + dim + " run " + command;
    }

    /**
     * Wait for a generated {@code NetherPortalEntity} near {@code framePos} in {@code srcDim}
     * whose destination is {@code destDim} with dest X/Z inside the given windows — the
     * item-10 exactness net: a sign flip or floored-scaling error lands far outside the
     * window; the matcher's legitimate placement freedom stays well inside it.
     */
    private static void assertGeneratedPortal(
        ClientGameTestContext context, String leg,
        net.minecraft.resources.ResourceKey<Level> srcDim, Vec3 framePos,
        net.minecraft.resources.ResourceKey<Level> destDim,
        double minX, double maxX, double minZ, double maxZ, int timeoutTicks
    ) {
        AtomicReference<String> lastSeen = new AtomicReference<>("(no portal entity appeared)");
        try {
            context.waitFor(mc -> {
                MinecraftServer server = mc.getSingleplayerServer();
                if (server == null) return false;
                ServerLevel src = server.getLevel(srcDim);
                if (src == null) return false;
                var portals = src.getEntitiesOfClass(
                    qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity.class,
                    new net.minecraft.world.phys.AABB(
                        framePos.subtract(8, 8, 8), framePos.add(8, 8, 8)),
                    p -> true);
                for (var portal : portals) {
                    Vec3 dest = portal.getDestPos();
                    lastSeen.set(portal.getDestDim().identifier() + " @ " + dest);
                    if (portal.getDestDim().equals(destDim)
                        && dest.x >= minX && dest.x <= maxX
                        && dest.z >= minZ && dest.z <= maxZ) {
                        SeamlessPortalsConstants.LOGGER.info(
                            LOG + "{} PASS — generated portal dest {} within [{},{}]x[{},{}]",
                            leg, dest, minX, maxX, minZ, maxZ);
                        return true;
                    }
                }
                return false;
            }, timeoutTicks);
        } catch (Throwable t) {
            throw new AssertionError(LOG + leg + " FAILED: no generated NetherPortalEntity near "
                + framePos + " with dest in " + destDim.identifier() + " X[" + minX + "," + maxX
                + "] Z[" + minZ + "," + maxZ + "] within " + timeoutTicks
                + " ticks. Last portal seen: " + lastSeen.get()
                + " (a sign/scale error lands outside the window; timeout = the async"
                + " pipeline stalled — check FrameSearching/chunk loading)", t);
        }
    }

    /** Run on the server thread and WAIT for completion (rethrows assertion failures). */
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
