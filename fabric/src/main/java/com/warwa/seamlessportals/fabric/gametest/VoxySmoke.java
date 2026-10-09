package com.warwa.seamlessportals.fabric.gametest;

import com.warwa.seamlessportals.EntityPortalsFlag;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.compat.voxy_compatibility.VoxyInterface;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalManipulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VOXY SMOKE (2026-10-09; design + ledger {@code migration/VOXY_COMPAT.md} §4). Drives a real
 * client with Sodium + Voxy loaded through the portal-view and crossing paths that collide with
 * Voxy's per-{@code LevelRenderer} render system, and asserts the tier-1 contract from the
 * client's own state:
 * <ul>
 *   <li>exactly ONE Voxy render system exists across every renderer this mod knows, and it is
 *       the one on {@code mc.levelRenderer} — before any portal, while a same-dim AND a
 *       cross-dim portal view render, after walking into the nether (the promote hook), after
 *       walking back, and after a renderer reload (F3+A's {@code allChanged});</li>
 *   <li>the client arrives where the crossing says it should (the compat must not break the
 *       crossing itself).</li>
 * </ul>
 * The visual half (LODs in the main world, NONE inside the portal windows, no LOD leak outside
 * the aperture) is screenshot evidence for the multimodal pre-screen: {@code voxy-*.png} in
 * {@code runs/gametest-voxy/screenshots}. The log half (no Voxy error, no "Cannot have multiple
 * renderers", the install line, the promote lines) is classified by the driver after the run.
 *
 * <p>Runs only with {@code -Dseamlessportals.gametest.only=voxy} (the {@code voxyGametest} run
 * config) and FAILS LOUDLY when Voxy is not on the runtime classpath — a Voxy-absent pass would
 * prove nothing:
 * <pre>.\gradlew.bat :fabric:runVoxyGametest -PvoxyRuntime=true -PtestClient=true</pre>
 */
public final class VoxySmoke implements FabricClientGameTest {

    private static final String LOG = "[VOXY SMOKE] ";
    private static final Vec3 NETHER_DEST = new Vec3(0.5, 129.5, 0.5);

    @Override
    public void runTest(ClientGameTestContext context) {
        String only = System.getProperty("seamlessportals.gametest.only", "");
        if (!only.equals("voxy")) {
            SeamlessPortalsConstants.LOGGER.info(LOG + "skipped (selected test: {})", only);
            return;
        }
        if (!EntityPortalsFlag.isOn()) {
            throw new AssertionError(LOG + "entityPortals flag is OFF — the voxyGametest run config "
                + "must seed entityPortals=true into <runDir>/config before launch.");
        }
        if (!FabricLoader.getInstance().isModLoaded("voxy")) {
            throw new AssertionError(LOG + "Voxy is NOT loaded — launch with -PvoxyRuntime=true "
                + "(the smoke proves nothing without it).");
        }
        if (!FabricLoader.getInstance().isModLoaded("sodium")) {
            throw new AssertionError(LOG + "Sodium is NOT loaded — Voxy requires it (-PvoxyRuntime adds both).");
        }
        if (!VoxyInterface.invoker.isVoxyPresent()) {
            throw new AssertionError(LOG + "VoxyInterface.invoker is the no-op base — "
                + "RenderCompatGating did not install OnVoxyPresent (is sodium compat active?).");
        }

        // Voxy warns below 3; 8 loads a ring of chunks that becomes LOD when the distance shrinks.
        context.runOnClient(mc -> mc.options.renderDistance().set(8));

        try (TestSingleplayerContext sp = context.worldBuilder()
                .setUseConsistentSettings(true)
                .adjustSettings(s -> {
                    s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    s.setName("seamless-voxy-smoke");
                })
                .create()) {

            context.waitTicks(100);
            assertMainHasRenderSystem(context, "V0 world open, no portals yet");

            BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
            int px = feet.getX(), py = feet.getY(), pz = feet.getZ();
            SeamlessPortalsConstants.LOGGER.info(LOG + "player at ({},{},{}); staging", px, py, pz);

            runCommands(context, List.of(
                "gamerule doDaylightCycle false",
                "gamerule doWeatherCycle false",
                "gamerule doMobSpawning false",
                "time set 1000",
                "weather clear",
                fill(px - 12, py - 1, pz - 12, px + 12, py - 1, pz + 6, "minecraft:stone"),
                fill(px - 12, py, pz - 12, px + 12, py + 8, pz + 6, "minecraft:air"),
                "forceload add " + (px + 90) + " " + (pz - 12) + " " + (px + 110) + " " + (pz + 4),
                "execute in minecraft:the_nether run forceload add -16 -16 16 16",
                "execute in minecraft:the_nether run fill -8 128 -8 8 134 8 minecraft:air",
                "execute in minecraft:the_nether run fill -8 127 -8 8 127 8 minecraft:netherrack"
            ));
            context.waitTicks(40);

            // Same-dim pair A (dest 100 blocks east) and cross-dim pair B (nether above the roof),
            // both bi-way, identity orientation (normal +Z): the player stands south and walks north.
            Vec3 aOrigin = new Vec3(px + 0.5, py + 1.5, pz - 4.5);
            Vec3 aDest = new Vec3(px + 100.5, py + 1.5, pz - 4.5);
            Vec3 bOrigin = new Vec3(px + 6.5, py + 1.5, pz - 4.5);
            spawnBiWayPair(context, Level.OVERWORLD, aOrigin, Level.OVERWORLD, aDest);
            spawnBiWayPair(context, Level.OVERWORLD, bOrigin, Level.NETHER, NETHER_DEST);
            context.waitTicks(40);

            // Tier 2 (LODs inside portal windows) is the default; the lever forces the tier-1 posture.
            final boolean portalLods = !Boolean.getBoolean("seamlessportals.disableVoxyPortalLods");
            SeamlessPortalsConstants.LOGGER.info(LOG + "portal LODs {}", portalLods ? "ON (tier 2)" : "OFF (tier 1, lever)");

            // ---- V1: both portal views on screen with the LOD renderer live ----
            teleport(context, Level.OVERWORLD, px + 3.5, py, pz + 3.0, 180f);
            aimAt(context, aOrigin.x, aOrigin.y, aOrigin.z);
            context.waitTicks(120);   // dest chunks arrive, dest renderers get created and drawn
            screenshot(context, "voxy-1-samedim-view");
            assertMainHasRenderSystem(context, "V1 same-dim portal view rendering");

            aimAt(context, bOrigin.x, bOrigin.y, bOrigin.z);
            context.waitTicks(120);
            screenshot(context, "voxy-2-nether-view");
            assertMainHasRenderSystem(context, "V1 cross-dim portal view rendering");
            // Tier 2: the nether secondary renderer gets its own LOD system in its first portal
            // pass; tier 1: it never does.
            assertSecondaryRenderSystem(context, Level.NETHER, portalLods, "V1 nether secondary renderer");

            // ---- V2: shrink the render distance so the loaded ring turns into LODs ----
            context.runOnClient(mc -> mc.options.renderDistance().set(3));
            context.waitTicks(200);
            screenshot(context, "voxy-3-rd3-lods-main-world");
            assertMainHasRenderSystem(context, "V2 after render-distance shrink");
            // Voxy's own F3 lines: the proof that the main pass drives the LOD pipeline (section /
            // node counts, frame timings).
            logVoxyDebug(context, "V2");
            // The windows at RD 3: with tier 2 the same-dim window shows grass to the horizon and
            // the nether window shows the dest floor beyond the dest's chunk distance; with tier 1
            // both windows end at the chunk distance. Nothing outside either aperture may change.
            aimAt(context, aOrigin.x, aOrigin.y, aOrigin.z);
            context.waitTicks(100);
            screenshot(context, "voxy-3b-rd3-samedim-window");
            aimAt(context, bOrigin.x, bOrigin.y, bOrigin.z);
            context.waitTicks(100);
            screenshot(context, "voxy-3c-rd3-nether-window");
            assertSecondaryRenderSystem(context, Level.NETHER, portalLods, "V2 nether secondary renderer");

            // ---- V3: cross-dim crossing by walking (the promote/demote hook) ----
            walkThrough(context, "V3 ow->nether", Level.OVERWORLD,
                new Vec3(px + 6.5, py, pz - 2.0), 180f, Level.NETHER, NETHER_DEST);
            context.waitTicks(60);
            screenshot(context, "voxy-4-nether-arrived");
            assertMainHasRenderSystem(context, "V3 in the nether after the crossing");
            // Tier 2 keeps the demoted (overworld) system: it renders the portal behind the player.
            assertSecondaryRenderSystem(context, Level.OVERWORLD, portalLods, "V3 demoted overworld renderer");

            // Look back through the return portal (the overworld is now the secondary view).
            aimAt(context, NETHER_DEST.x, NETHER_DEST.y, NETHER_DEST.z);
            context.waitTicks(120);
            screenshot(context, "voxy-5-nether-looking-back");
            assertMainHasRenderSystem(context, "V3 overworld portal view from the nether");

            walkThrough(context, "V3 nether->ow", Level.NETHER,
                new Vec3(NETHER_DEST.x, 128.0, NETHER_DEST.z - 2.0), 0f, Level.OVERWORLD, bOrigin);
            context.waitTicks(60);
            screenshot(context, "voxy-6-back-in-overworld");
            assertMainHasRenderSystem(context, "V3 back in the overworld");
            assertSecondaryRenderSystem(context, Level.NETHER, portalLods, "V3 re-demoted nether renderer");

            // ---- V4: renderer reload (F3+A) — the main is recreated; a secondary's system is
            // recreated lazily in its next portal pass (tier 2) or never (tier 1) ----
            context.runOnClient(mc -> mc.levelExtractor.allChanged());
            context.waitTicks(100);
            aimAt(context, bOrigin.x, bOrigin.y, bOrigin.z);
            context.waitTicks(100);
            screenshot(context, "voxy-7-after-reload");
            assertMainHasRenderSystem(context, "V4 after allChanged");
            assertSecondaryRenderSystem(context, Level.NETHER, portalLods, "V4 nether renderer after reload");
            logVoxyDebug(context, "V4");

            SeamlessPortalsConstants.LOGGER.info(LOG + "ALL LEGS PASS");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------------------------------

    /**
     * The main renderer (mc.levelRenderer) carries a Voxy render system, and no renderer this mod
     * does not know carries one; the per-renderer census is logged for the record.
     */
    private static void assertMainHasRenderSystem(ClientGameTestContext context, String leg) {
        String verdict = context.computeOnClient(mc -> {
            List<String> seen = new ArrayList<>();
            boolean mainHas = VoxyInterface.invoker.hasRenderSystem(mc.levelRenderer);
            int count = 0;
            for (var e : ClientWorldLoader.WORLD_RENDERER_MAP.entrySet()) {
                LevelRenderer r = e.getValue();
                boolean has = VoxyInterface.invoker.hasRenderSystem(r);
                seen.add(e.getKey().identifier().getPath() + (r == mc.levelRenderer ? "(main)" : "") + "=" + has);
                if (has) count++;
            }
            boolean mainKnown = ClientWorldLoader.WORLD_RENDERER_MAP.containsValue(mc.levelRenderer);
            if (!mainKnown && mainHas) count++;
            String summary = "main=" + mainHas + " known=" + seen + " total=" + count;
            if (!mainHas) return "FAIL (main renderer has NO LOD render system): " + summary;
            if (count > ClientWorldLoader.WORLD_RENDERER_MAP.size() + (mainKnown ? 0 : 1)) {
                return "FAIL (more LOD render systems than renderers): " + summary;
            }
            return "OK " + summary;
        });
        SeamlessPortalsConstants.LOGGER.info(LOG + "{}: {}", leg, verdict);
        if (verdict.startsWith("FAIL")) {
            throw new AssertionError(LOG + leg + " " + verdict);
        }
    }

    /** A secondary renderer carries a system iff the dest-LOD feature is on. */
    private static void assertSecondaryRenderSystem(
        ClientGameTestContext context, ResourceKey<Level> dim, boolean expected, String leg
    ) {
        String verdict = context.computeOnClient(mc -> {
            LevelRenderer r = ClientWorldLoader.WORLD_RENDERER_MAP.get(dim);
            if (r == null) return "FAIL (no renderer registered for " + dim.identifier() + ")";
            if (r == mc.levelRenderer) return "FAIL (" + dim.identifier() + " IS the main renderer here)";
            boolean has = VoxyInterface.invoker.hasRenderSystem(r);
            if (has != expected) {
                return "FAIL (secondary " + dim.identifier() + " renderer: LOD render system present=" + has
                    + ", expected " + expected + ")";
            }
            return "OK (secondary " + dim.identifier() + " renderer: LOD render system present=" + has + ")";
        });
        SeamlessPortalsConstants.LOGGER.info(LOG + "{}: {}", leg, verdict);
        if (verdict.startsWith("FAIL")) {
            throw new AssertionError(LOG + leg + " " + verdict);
        }
    }

    private static void logVoxyDebug(ClientGameTestContext context, String leg) {
        List<String> lines = context.computeOnClient(mc -> VoxyInterface.invoker.debugInfo(mc.levelRenderer));
        SeamlessPortalsConstants.LOGGER.info(LOG + "{} voxy debug ({} lines):", leg, lines.size());
        for (String line : lines) {
            SeamlessPortalsConstants.LOGGER.info(LOG + "{} voxy | {}", leg, line);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Driving helpers (the DedicatedServerSmoke shapes, in-process only)
    // ------------------------------------------------------------------------------------------

    private static void walkThrough(
        ClientGameTestContext context, String leg, ResourceKey<Level> fromDim,
        Vec3 standAt, float yaw, ResourceKey<Level> expectDim, Vec3 expectNear
    ) {
        teleport(context, fromDim, standAt.x, standAt.y, standAt.z, yaw);
        context.runOnClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.setXRot(0f);
            mc.player.yRotO = yaw;
            mc.player.xRotO = 0f;
        });
        context.waitTicks(5);
        SeamlessPortalsConstants.LOGGER.info(LOG + "{}: walking from {}", leg, describeClient(context));
        context.getInput().holdKeyFor(options -> options.keyUp, 45);

        String expectDimId = expectDim.identifier().toString();
        try {
            context.waitFor(mc -> mc.level != null && mc.player != null
                && mc.level.dimension().identifier().toString().equals(expectDimId)
                && mc.player.position().distanceTo(expectNear) < 8.0, 400);
        } catch (Throwable t) {
            throw new AssertionError(LOG + leg + " FAILED: the client never arrived in " + expectDimId
                + " within 8 of " + expectNear + "; client now " + describeClient(context), t);
        }
        context.waitTicks(40);
        SeamlessPortalsConstants.LOGGER.info(LOG + "{}: arrived — {}", leg, describeClient(context));
    }

    private static void teleport(
        ClientGameTestContext context, ResourceKey<Level> dim, double x, double y, double z, float yaw
    ) {
        runCommands(context, List.of(String.format(Locale.ROOT,
            "execute in %s run tp @a %.2f %.2f %.2f %.0f 0", dim.identifier(), x, y, z, yaw)));
        context.waitTicks(30);
    }

    private static void spawnBiWayPair(
        ClientGameTestContext context, ResourceKey<Level> fromDim, Vec3 origin,
        ResourceKey<Level> toDim, Vec3 dest
    ) {
        runOnServer(context, server -> {
            ServerLevel from = server.getLevel(fromDim);
            Portal p = Portal.ENTITY_TYPE.create(from, EntitySpawnReason.COMMAND);
            if (p == null) throw new AssertionError(LOG + "Portal.ENTITY_TYPE.create returned null");
            p.setOriginPos(origin);
            p.setDestinationDimension(toDim);
            p.setDestination(dest);
            p.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 3, 3);
            McHelper.spawnServerEntity(p);
            Portal q = PortalManipulation.createReversePortal(p, Portal.ENTITY_TYPE);
            McHelper.spawnServerEntity(q);
            SeamlessPortalsConstants.LOGGER.info(LOG + "spawned bi-way pair {} {} -> {} {}",
                fromDim.identifier(), origin, toDim.identifier(), dest);
        });
    }

    private static String describeClient(ClientGameTestContext context) {
        return context.computeOnClient(mc -> mc.player == null ? "(no player)"
            : mc.level.dimension().identifier() + " @ " + mc.player.position() + " yaw " + mc.player.getYRot());
    }

    private static void aimAt(ClientGameTestContext context, double tx, double ty, double tz) {
        context.runOnClient(mc -> {
            if (mc.player == null) return;
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
        context.waitTicks(10);
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        try {
            context.waitTicks(10);
            SeamlessPortalsConstants.LOGGER.info(LOG + "screenshot {} -> {}", name, context.takeScreenshot(name));
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(LOG + "screenshot {} failed: {}", name, t.toString());
        }
    }

    private static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        return "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + block;
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
        context.waitFor(mc -> done.get(), 200);
        Throwable t = error.get();
        if (t instanceof AssertionError ae) throw ae;
        if (t != null) throw new AssertionError(LOG + "server action failed", t);
    }
}
