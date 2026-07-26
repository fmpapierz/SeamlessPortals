package com.warwa.seamlessportals.fabric.gametest;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Automated title-card capture for Seamless Portals.
 *
 * <p>Drives a real Minecraft client (no human input) through the Fabric
 * client-gametest API to produce a genuine, vanilla-rendered screenshot of
 * the mod's flagship effect: standing in front of a portal, looking through
 * it at a live view of somewhere else.
 *
 * <p><b>S20 RE-SHOOT (increment 4).</b> Until S20 this capture drove the BLOCK-ERA flow — build an
 * obsidian frame, fill it with {@code nether_portal} blocks, walk in and let
 * {@code NetherPortalBlock.entityInside} link + teleport — and the {@code runClientGametest} run
 * config pinned its run dir to {@code entityPortals=false} to keep that flow alive after the S17
 * cutover, calling a flag-ON title card "a future re-shoot". This IS that re-shoot: the block era
 * and the flag are both deleted, so the capture now spawns a real IP {@code Portal} ENTITY the way
 * the 8-leg suite does ({@code CrossingSmoke.spawnTestPortal}) and photographs its window. No
 * teleport is needed for the shot — an entity portal shows its destination while you stand in
 * front of it, which is the whole point of the picture.
 *
 * <p>Flow:
 * <ol>
 *   <li>Create a creative singleplayer world.</li>
 *   <li>Set bright clear-day weather, freeze the day/weather cycle, disable
 *       mob spawns — a clean stage.</li>
 *   <li>Spawn a 3×3 portal entity a few blocks north of the player, pointing at a
 *       distant overworld destination, and frame it with an obsidian border so the
 *       aperture reads as a portal in the shot.</li>
 *   <li>Wait for the destination to stream into the portal-view secondary level
 *       (the mod's async fill).</li>
 *   <li>Frame the camera a few blocks back from the portal plane, at EYE height,
 *       looking straight at it, HUD hidden.</li>
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

    /** Z-offset north (−Z) of the player where the portal plane sits. */
    private static final int PORTAL_OFFSET_Z = 6;

    /** Portal aperture size (blocks), square. */
    private static final int PORTAL_SIZE = 3;

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
        // Test selection: with multiple fabric-client-gametest entrypoints the framework
        // runs ALL of them; each run config selects one via this property (the
        // clientGametest config sets "titlecard", crossingGametest sets "crossing").
        String only = System.getProperty("seamlessportals.gametest.only", "");
        if (!only.isEmpty() && !only.equals("titlecard")) {
            SeamlessPortalsConstants.LOGGER.info("[TITLE CARD] skipped (selected test: {})", only);
            return;
        }

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
            int zf = pz - PORTAL_OFFSET_Z; // portal plane Z, north of the player

            SeamlessPortalsConstants.LOGGER.info(
                "[TITLE CARD] Player at ({},{},{}); portal plane at Z={}", px, py, pz, zf);

            List<String> setup = new ArrayList<>();
            setup.add("gamerule doDaylightCycle false");
            setup.add("gamerule doWeatherCycle false");
            setup.add("gamerule doMobSpawning false");
            setup.add("gamerule doFireTick false");
            setup.add("time set 1000");        // bright morning
            setup.add("weather clear");
            // Obsidian border around the (empty) 3x3 aperture, so the portal window reads as a
            // portal rather than a floating rectangle. The aperture itself stays AIR — the portal
            // is an entity, not a block.
            setup.add(fill(px - 2, py - 1, zf, px + 2, py - 1, zf));   // base
            setup.add(fill(px - 2, py + 3, zf, px + 2, py + 3, zf));   // lintel
            setup.add(fill(px - 2, py, zf, px - 2, py + 2, zf));       // left column
            setup.add(fill(px + 2, py, zf, px + 2, py + 2, zf));       // right column
            runCommands(context, setup);
            context.waitTicks(40);

            // ---- Spawn the portal ENTITY (the CrossingSmoke.spawnTestPortal template) ----
            // Identity transform: axisW=+X, axisH=+Y → normal +Z, facing the player standing
            // south of the plane. Destination is a far-away overworld position, so the window
            // shows terrain the camera cannot also see directly.
            final double planeZ = zf + 0.5;
            final Vec3 origin = new Vec3(px + 0.5, py + 1.5, planeZ);
            final Vec3 dest = new Vec3(px + 400.5, py + 1.5, planeZ);
            runCommands(context, List.of(
                "forceload add " + (px + 384) + " " + (zf - 16) + " " + (px + 416) + " " + (zf + 16)));
            context.runOnClient(mc -> {
                MinecraftServer server = mc.getSingleplayerServer();
                if (server == null) {
                    SeamlessPortalsConstants.LOGGER.error("[TITLE CARD] No singleplayer server!");
                    return;
                }
                server.execute(() -> {
                    ServerLevel ow = server.getLevel(Level.OVERWORLD);
                    Portal portal = Portal.ENTITY_TYPE.create(ow, EntitySpawnReason.COMMAND);
                    if (portal == null) {
                        SeamlessPortalsConstants.LOGGER.error(
                            "[TITLE CARD] Portal.ENTITY_TYPE.create returned null");
                        return;
                    }
                    portal.setOriginPos(origin);
                    portal.setDestinationDimension(Level.OVERWORLD);
                    portal.setDestination(dest);
                    portal.setOrientationAndSize(
                        new Vec3(1, 0, 0), new Vec3(0, 1, 0), PORTAL_SIZE, PORTAL_SIZE);
                    McHelper.spawnServerEntity(portal);
                    SeamlessPortalsConstants.LOGGER.info(
                        "[TITLE CARD] portal spawned: {} -> overworld {}", origin, dest);
                });
            });

            // ---- Let the destination stream into the portal-view level ----
            // The mod fills the secondary ClientLevel asynchronously (~8s); wait generously.
            context.waitTicks(220);

            // ---- Frame the camera on the portal ----
            frameCameraOnPortal(context, origin);
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
     * Place the camera a few blocks back along the portal's normal (+Z), looking straight at the
     * portal centre.
     *
     * <p>The pre-S20 version searched for {@code nether_portal} BLOCKS to find its subject, which
     * no longer exists here — the portal is an entity whose origin we already know, so it is passed
     * in. It also carried an aiming bug worth naming, since this is the one artefact whose entire
     * purpose is composition: it positioned and aimed the camera from the player's FEET, while the
     * rendered view is from the EYES ({@code player.getEyeHeight()} above), so every plate was
     * framed ~1.6 blocks below where the maths said. Both the camera placement and the pitch
     * solution below work in EYE space and then convert back to a feet position for the teleport.
     */
    private void frameCameraOnPortal(ClientGameTestContext context, Vec3 portalCentre) {
        context.runOnClient(mc -> {
            if (mc.player == null || mc.level == null) return;

            double back = 3.4;                       // distance from the portal plane
            double eyeX = portalCentre.x;
            double eyeY = portalCentre.y;            // eye level with the portal centre
            double eyeZ = portalCentre.z + back;     // the portal's normal is +Z

            // Aim straight at the centre, from the EYE.
            double ddx = portalCentre.x - eyeX;
            double ddy = portalCentre.y - eyeY;
            double ddz = portalCentre.z - eyeZ;
            double horiz = Math.sqrt(ddx * ddx + ddz * ddz);
            float yaw = (float) (Math.toDegrees(Math.atan2(-ddx, ddz)));
            float pitch = (float) (-Math.toDegrees(Math.atan2(ddy, horiz)));

            // The teleport takes a FEET position; the framing above is in eye space.
            double feetY = eyeY - mc.player.getEyeHeight();

            SeamlessPortalsConstants.LOGGER.info(
                "[TITLE CARD] Portal centre ({},{},{}) → eye ({},{},{}) feetY={} yaw={} pitch={}",
                fmt(portalCentre.x), fmt(portalCentre.y), fmt(portalCentre.z),
                fmt(eyeX), fmt(eyeY), fmt(eyeZ), fmt(feetY), fmt(yaw), fmt(pitch));

            // Move the server player too (so it doesn't yank the client back),
            // then pin the client camera exactly.
            MinecraftServer server = mc.getSingleplayerServer();
            if (server != null) {
                final double fx = eyeX, fy = feetY, fz = eyeZ;
                final float fyaw = yaw, fpitch = pitch;
                server.execute(() -> {
                    CommandSourceStack src = server.createCommandSourceStack()
                            .withSuppressedOutput();
                    server.getCommands().performPrefixedCommand(src,
                        "tp @p " + fx + " " + fy + " " + fz + " " + fyaw + " " + fpitch);
                });
            }
            mc.player.setPos(eyeX, feetY, eyeZ);
            mc.player.xo = eyeX; mc.player.yo = feetY; mc.player.zo = eyeZ;
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
