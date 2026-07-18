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

        try (TestSingleplayerContext sp = context.worldBuilder()
                .setUseConsistentSettings(true)
                .adjustSettings(s -> {
                    s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    s.setName("seamless-crossing-smoke");
                })
                .create()) {

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
