package com.warwa.seamlessportals.fabric.gametest;

import com.warwa.seamlessportals.EntityPortalsFlag;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.mixin.LivingEntityHurtAccessor;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.commands.CommandSourceStack;
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
