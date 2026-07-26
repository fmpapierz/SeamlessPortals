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

            // ---- Leg 1: same-dim item ----
            UUID itemA = spawnThrownItem(context, originA);
            waitForArrival(context, "leg 1 (same-dim item)", Level.OVERWORLD, itemA, destA, 200);
            SeamlessPortalsConstants.LOGGER.info(LOG + "leg 1 PASS — same-dim item arrived at {}", destA);

            // ---- Leg 2: cross-dim item (recreate path) ----
            UUID itemB = spawnThrownItem(context, originB);
            waitForArrival(context, "leg 2 (cross-dim item)", Level.NETHER, itemB, destB, 300);
            assertGoneFrom(context, "leg 2 (cross-dim item)", Level.OVERWORLD, itemB);
            SeamlessPortalsConstants.LOGGER.info(LOG + "leg 2 PASS — cross-dim item recreated in nether at {}", destB);

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
                    boolean found = cell.bindings().stream()
                        .anyMatch(b -> b.portalUuid().equals(p.getUUID())
                            && expectedDst.equals(b.destPos()));
                    if (!found) {
                        failure.set("registry binding at " + src + " for portal " + p.getId()
                            + " does not carry SeamMap's destination " + expectedDst
                            + " — registry and arithmetic disagree; bindings=" + cell.bindings());
                        return;
                    }
                    registryChecks++;
                }
            }
            if (registryChecks == 0) {
                failure.set("zero registry cross-checks ran — the step-2 assertion never executed");
                return;
            }

            report.set("examined " + examined + " mirrorable portal(s), "
                + involutions.get() + " involution check(s), "
                + registryChecks + " registry cross-check(s)" + sb);
        });

        String f = failure.get();
        if (f != null) {
            throw new AssertionError(LOG + "RS-A step-1 SEAM MAP GATE FAILED: " + f);
        }
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

            runCommands(context, List.of(
                "setblock " + fx + " " + cellY + " " + fz + " minecraft:rail"));
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

            // (2) breaking the source clears the mirrored counterpart (provenance)
            ow.setBlockAndUpdate(sourceCell, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
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
            ow.setBlockAndUpdate(sourceCell, net.minecraft.world.level.block.Blocks.RAIL.defaultBlockState());
            if (!dest.getBlockState(destPos).is(net.minecraft.world.level.block.Blocks.RAIL)) {
                failure.set("re-place did not re-mirror; cannot test the reverse direction");
                return;
            }
            dest.setBlockAndUpdate(destPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
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
            dest.setBlockAndUpdate(destPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
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
        AtomicReference<String> out = new AtomicReference<>("(unread)");
        runOnServer(context, server -> {
            ServerLevel ow = server.getLevel(Level.OVERWORLD);
            out.set(ow == null ? "(no overworld)" : ow.getBlockState(pos).getBlock().toString());
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
