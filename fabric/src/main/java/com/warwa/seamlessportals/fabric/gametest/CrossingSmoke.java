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

            // RS (b) RAIL LEGS — rails CONNECTING across the seam (REDSTONE_B_SPEC.md §9, adapted).
            // After the seam-map gate on purpose: these consume the primitive it just proved, so a
            // failure here is a consumer bug, not seam arithmetic. Both are lever-aware: with (b) on
            // they prove the connection; under -PdisableSeamShadow they must prove the INVERSION.
            rsRailLegTopologyB(context);
            rsRailLegTopologyA(context, py);

            // RS SEAM-CLIP GATE (renderer) — the suite's first PIXEL gate: the seam block's far
            // half must stop drawing from an out-of-window side view (fix ON) and must reappear
            // under -PdisableSeamClip (inversion). After the rail legs: it moves the player.
            rsSeamClipGate(context, px, py, pz);

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
     * {@code -PdisableSeamClip} — far GOLD (the defect reproduced on demand) + near gold +
     * counters 0. Full-suite note: the player is in the nether by this point — the leg records
     * their whereabouts and restores them in the {@code finally}, along with the staging, the
     * mirrored far half, the portal and {@code hideGui}.
     */
    private static void rsSeamClipGate(ClientGameTestContext context, int px, int py, int pz) {
        final String tag = LOG + "[RS-SEAM-CLIP] ";
        // Master lever: with the whole passthrough stack disabled there is no seam registry, no
        // binding and nothing for a clip to gate — the fixture-validity check would (correctly)
        // refuse to run. The master-lever row proves stock-IP restoration; this leg's own
        // inversion row is -PdisableSeamClip, which keeps the stack alive.
        if (AperturePassthroughLever.DISABLED) {
            SeamlessPortalsConstants.LOGGER.info(tag + "SKIPPED — master lever"
                + " (-PdisableAperturePassthrough) disables the seam stack this gate rides on;"
                + " the clip's own inversion row is -PdisableSeamClip");
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
            });
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
                    throw new AssertionError(tag + "INVERSION FAILED — the lever is set but the"
                        + " mechanism still ran (cellsDrawn=" + cellsDrawn + " cellsExcluded="
                        + cellsExcluded + ")");
                }
                SeamlessPortalsConstants.LOGGER.info(tag + "INVERSION PASS — whole cube visible"
                    + " from the side under -PdisableSeamClip, mechanism fully idle");
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
