package com.warwa.seamlessportals.fabric.gametest;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Automated title-card capture for Seamless Portals.
 *
 * <p>Drives a real Minecraft client (no human input) through the Fabric
 * client-gametest API to produce a genuine, vanilla-rendered screenshot of
 * the mod's flagship effect: standing in the nether, looking back through a
 * portal at the live overworld beyond it.
 *
 * <p>Flow:
 * <ol>
 *   <li>Create a creative singleplayer world.</li>
 *   <li>Set bright clear-day weather, freeze the day/weather cycle, disable
 *       mob spawns — a clean stage.</li>
 *   <li>Build an obsidian frame in front of the player and fill it with
 *       {@code nether_portal} blocks (AXIS=X).</li>
 *   <li>Teleport the player into the portal — the mod's
 *       {@code NetherPortalBlock.entityInside} hook fires, establishing the
 *       link and seamlessly teleporting to the nether.</li>
 *   <li>Wait for the overworld chunks to stream into the portal-view
 *       secondary level (the mod's async fill).</li>
 *   <li>Frame the camera a few blocks back from the nether portal, looking
 *       straight at it, HUD hidden.</li>
 *   <li>Capture the screenshot.</li>
 * </ol>
 *
 * <p>The result lands under {@code fabric/runs/gametest/screenshots/}.
 *
 * <p>This entrypoint only runs when the client is launched with
 * {@code -Dfabric.client.gametest.modid=seamlessportals} (the
 * {@code clientGametest} Gradle run config). It is never invoked during
 * normal play.
 */
public class TitleCardCapture implements FabricClientGameTest {

    /** Z-offset from the player's feet where the portal plane is built. */
    private static final int PORTAL_Z = 3;

    /**
     * 26.2: {@code Options.hideGui} was removed — the HUD-hidden state moved to
     * {@code Hud.isHidden}, reached via {@code mc.gui.hud}. It exposes only a
     * {@code toggle()} and an {@code isHidden()} getter (no value setter), so we
     * toggle only when the current state differs from the desired one.
     */
    private static void seamlessportals$setHudHidden(net.minecraft.client.Minecraft mc, boolean hidden) {
        if (mc.gui.hud.isHidden() != hidden) {
            mc.gui.hud.toggle();
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        SeamlessPortalsConstants.LOGGER.info("[TITLE CARD] Capture starting…");

        // Moderate render distance → fuller terrain through the portal,
        // without overloading the gametest window.
        context.runOnClient(mc -> {
            mc.options.renderDistance().set(10);
            mc.options.bobView().set(false);
            seamlessportals$setHudHidden(mc, false);
        });

        try (TestSingleplayerContext sp = context.worldBuilder()
                .setUseConsistentSettings(true)
                .adjustSettings(s -> {
                    s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                    s.setName("seamless-title-card");
                })
                .create()) {

            // NOTE: deliberately NOT using waitForChunksRender() — under
            // Sodium + the portal mixins the "all chunks rendered" predicate
            // never settles, so it always times out. A fixed wait is enough
            // for a fresh creative spawn area.
            SeamlessPortalsConstants.LOGGER.info("[TITLE CARD] World created, settling…");
            context.waitTicks(80);

            // ---- Stage the overworld ----
            BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
            int px = feet.getX();
            int py = feet.getY();
            int pz = feet.getZ();
            int zf = pz + PORTAL_Z; // portal plane Z

            SeamlessPortalsConstants.LOGGER.info(
                "[TITLE CARD] Player at ({},{},{}); building portal plane at Z={}", px, py, pz, zf);

            List<String> setup = new ArrayList<>();
            setup.add("gamerule doDaylightCycle false");
            setup.add("gamerule doWeatherCycle false");
            setup.add("gamerule doMobSpawning false");
            setup.add("gamerule doFireTick false");
            setup.add("time set 1000");        // bright morning
            setup.add("weather clear");
            // Obsidian frame (interior X in [px, px+1], Y in [py, py+2]):
            setup.add(fill(px - 1, py - 1, zf, px + 2, py - 1, zf));          // base
            setup.add(fill(px - 1, py + 3, zf, px + 2, py + 3, zf));          // lintel
            setup.add(fill(px - 1, py, zf, px - 1, py + 2, zf));              // left column
            setup.add(fill(px + 2, py, zf, px + 2, py + 2, zf));             // right column
            // Portal interior:
            setup.add("fill " + (px) + " " + (py) + " " + zf + " "
                    + (px + 1) + " " + (py + 2) + " " + zf + " minecraft:nether_portal[axis=x]");
            runCommands(context, setup);
            context.waitTicks(40);

            // Diagnostic plate from the overworld side, looking at the portal
            // we just built (this is the currently-buggy pre-teleport view —
            // useful to confirm the portal exists and is framed).
            context.runOnClient(mc -> {
                // Look toward the portal (north, -Z) from a couple blocks south.
                mc.player.setYRot(180f);
                mc.player.setXRot(0f);
                seamlessportals$setHudHidden(mc, true);
            });
            context.waitTicks(10);
            context.takeScreenshot("seamless-portals-overworld-side");
            context.runOnClient(mc -> seamlessportals$setHudHidden(mc, false));

            // ---- Walk into the portal → seamless teleport to nether ----
            SeamlessPortalsConstants.LOGGER.info("[TITLE CARD] Stepping into portal…");
            runCommands(context, List.of(
                "tp @p " + (px + 0.5) + " " + py + " " + (zf + 0.5)));
            // Give the mod's entityInside hook time to fire + teleport.
            // Non-fatal: if it doesn't teleport we still capture diagnostics.
            boolean reachedNether = false;
            try {
                int waited = context.waitFor(mc ->
                    mc.player != null
                    && mc.player.level().dimension().equals(Level.NETHER), 400);
                reachedNether = true;
                SeamlessPortalsConstants.LOGGER.info(
                    "[TITLE CARD] Reached nether after {} ticks (dim={})",
                    waited, context.computeOnClient(mc -> mc.player.level().dimension().identifier().toString()));
            } catch (Throwable t) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[TITLE CARD] Did NOT reach nether (teleport never fired): {}", t.toString());
            }
            if (!reachedNether) {
                // Capture whatever we have, then bail cleanly.
                context.runOnClient(mc -> seamlessportals$setHudHidden(mc, true));
                context.waitTicks(5);
                context.takeScreenshot("seamless-portals-no-teleport");
                context.runOnClient(mc -> seamlessportals$setHudHidden(mc, false));
                SeamlessPortalsConstants.LOGGER.warn("[TITLE CARD] Aborting — see no-teleport plate.");
                return;
            }

            // ---- Let the overworld stream into the portal-view level ----
            // The mod fills the secondary ClientLevel asynchronously (~8s);
            // wait generously.
            context.waitTicks(220);

            // ---- Frame the camera on the nether portal ----
            frameCameraOnPortal(context);
            context.waitTicks(80);

            // Hide HUD for a clean plate.
            context.runOnClient(mc -> seamlessportals$setHudHidden(mc, true));
            context.waitTicks(5);

            Path shot = context.takeScreenshot("seamless-portals-title");
            SeamlessPortalsConstants.LOGGER.info("[TITLE CARD] Screenshot saved: {}", shot);

            // A couple of alternate frames in case the first composition is off.
            context.runOnClient(mc -> {
                if (mc.player != null) mc.player.setXRot(-5f);
            });
            context.waitTicks(10);
            context.takeScreenshot("seamless-portals-title-alt");

            context.runOnClient(mc -> seamlessportals$setHudHidden(mc, false));
        }

        SeamlessPortalsConstants.LOGGER.info("[TITLE CARD] Capture finished.");
    }

    /**
     * Search the client level around the player for nether_portal blocks,
     * compute their centroid, then place the camera a few blocks back along
     * the portal's normal looking straight at the centre.
     */
    private void frameCameraOnPortal(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            if (mc.player == null || mc.level == null) return;
            BlockPos around = mc.player.blockPosition();

            double sx = 0, sy = 0, sz = 0;
            int n = 0;
            boolean axisX = true;
            for (int dx = -8; dx <= 8; dx++) {
                for (int dy = -4; dy <= 8; dy++) {
                    for (int dz = -8; dz <= 8; dz++) {
                        BlockPos p = around.offset(dx, dy, dz);
                        var st = mc.level.getBlockState(p);
                        if (st.is(Blocks.NETHER_PORTAL)) {
                            sx += p.getX() + 0.5;
                            sy += p.getY() + 0.5;
                            sz += p.getZ() + 0.5;
                            n++;
                            try {
                                axisX = st.getValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS)
                                        == net.minecraft.core.Direction.Axis.X;
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            if (n == 0) {
                SeamlessPortalsConstants.LOGGER.warn(
                    "[TITLE CARD] No nether_portal blocks found near player — framing skipped");
                return;
            }

            double cx = sx / n, cy = sy / n, cz = sz / n;
            // Portal plane normal: AXIS=X → normal along Z; AXIS=Z → normal along X.
            double back = 3.4;     // distance from portal
            double camX, camZ;
            if (axisX) { camX = cx; camZ = cz + back; }
            else       { camX = cx + back; camZ = cz; }
            double camY = cy - 0.2; // eye roughly at portal centre

            // Aim straight at the centre.
            double ddx = cx - camX, ddy = cy - camY, ddz = cz - camZ;
            double horiz = Math.sqrt(ddx * ddx + ddz * ddz);
            float yaw = (float) (Math.toDegrees(Math.atan2(-ddx, ddz)));
            float pitch = (float) (-Math.toDegrees(Math.atan2(ddy, horiz)));

            SeamlessPortalsConstants.LOGGER.info(
                "[TITLE CARD] Portal centre ({},{},{}) axisX={} → cam ({},{},{}) yaw={} pitch={}",
                fmt(cx), fmt(cy), fmt(cz), axisX, fmt(camX), fmt(camY), fmt(camZ),
                fmt(yaw), fmt(pitch));

            // Move the server player too (so it doesn't yank the client back),
            // then pin the client camera exactly.
            MinecraftServer server = mc.getSingleplayerServer();
            if (server != null) {
                final double fx = camX, fy = camY, fz = camZ;
                final float fyaw = yaw, fpitch = pitch;
                server.execute(() -> {
                    CommandSourceStack src = server.createCommandSourceStack()
                            .withSuppressedOutput();
                    server.getCommands().performPrefixedCommand(src,
                        "tp @p " + fx + " " + fy + " " + fz + " " + fyaw + " " + fpitch);
                });
            }
            mc.player.setPos(camX, camY, camZ);
            mc.player.xo = camX; mc.player.yo = camY; mc.player.zo = camZ;
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.setDeltaMovement(0, 0, 0);
        });
    }

    private static String fill(int x1, int y1, int z1, int x2, int y2, int z2) {
        return "fill " + x1 + " " + y1 + " " + z1 + " "
                + x2 + " " + y2 + " " + z2 + " minecraft:obsidian";
    }

    private static void runCommands(ClientGameTestContext context, List<String> commands) {
        context.runOnClient(mc -> {
            MinecraftServer server = mc.getSingleplayerServer();
            if (server == null) {
                SeamlessPortalsConstants.LOGGER.error("[TITLE CARD] No singleplayer server!");
                return;
            }
            server.execute(() -> {
                CommandSourceStack src = server.createCommandSourceStack()
                        .withSuppressedOutput();
                for (String c : commands) {
                    try {
                        server.getCommands().performPrefixedCommand(src, c);
                    } catch (Exception e) {
                        SeamlessPortalsConstants.LOGGER.warn(
                            "[TITLE CARD] command failed: {} ({})", c, e.toString());
                    }
                }
            });
        });
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
