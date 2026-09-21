package qouteall.imm_ptl.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.apache.commons.lang3.Validate;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * 26.3 (dedicated servers on the loaders that do NOT strip {@code @Environment(CLIENT)}: NeoForge, MinecraftForge) — the
 * body of {@link ScaleUtils#onClientPlayerTeleported}, moved here VERBATIM.
 *
 * <p>{@code ScaleUtils} is COMMON: servers call it for entity collision near portals
 * ({@code CollisionHelper.getStretchedBoundingBox} → {@code getScale}), reach checks
 * ({@code BlockManipulationServer.canPlayerReachPos} → {@code computeBlockReachScale}) and entity teleports
 * ({@code ServerTeleportationManager.teleportRegularEntity} → {@code onServerEntityTeleported}). The JVM verifies every
 * method body of a class when it links it, and this one body holds {@code doScalingForEntity(player, portal)} with a
 * {@code LocalPlayer}-typed local where {@code Entity} is declared — a proof the verifier can only make by LOADING
 * {@code LocalPlayer}, which a dedicated server's dist cleaner refuses. Upstream IP gets away with it because Fabric
 * Loader physically deletes the {@code @Environment(CLIENT)} method on servers; nothing deletes it here. Found by the
 * static link audit run for the first Forge dedicated-server check (an ASM-based verifier simulation over
 * every project class), before any server path reached it. NF-PARITY rule 1; same split pattern as
 * {@code ImmPtlNetworkingClient} / {@code GlobalPortalStorageClient}.
 */
public final class ScaleUtilsClient {

    private ScaleUtilsClient() {}

    static void onClientPlayerTeleported(Portal portal) {
        if (portal.hasScaling() && portal.isTeleportChangesScale()) {
            Minecraft client = Minecraft.getInstance();

            LocalPlayer player = client.player;

            Validate.notNull(player, "Player is null");

            ScaleUtils.doScalingForEntity(player, portal);

            IECamera camera = (IECamera) client.gameRenderer.mainCamera();
            camera.ip_setCameraY(
                ((float) (camera.ip_getCameraY() * portal.getScaling())),
                ((float) (camera.ip_getLastCameraY() * portal.getScaling()))
            );
        }
    }
}
