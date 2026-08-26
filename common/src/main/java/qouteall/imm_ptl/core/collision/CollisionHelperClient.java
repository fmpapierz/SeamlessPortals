package qouteall.imm_ptl.core.collision;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;

/**
 * NF-PARITY C3 dist-split (2026-08-25): the CLIENT half of {@link CollisionHelper},
 * physically split out (the measured E0 boot crash at {@code IPModMain.init:89} —
 * {@code updateClientCollidingStatus}'s {@code ClientLevel} loop force-loads client classes
 * when CollisionHelper LINKS on the dedicated server; {@code CollisionHelper.init()} is
 * server-side). Logic verbatim; the stagnate flags moved with their only writers/readers.
 */
public class CollisionHelperClient {

    private static boolean thisTickStagnate = false;
    private static boolean lastTickStagnate = false;

    public static void initClient() {
        IPGlobal.POST_CLIENT_TICK_EVENT.register(CollisionHelperClient::tickClient);
    }

    public static void tickClient() {
        updateClientCollidingStatus();

        updateClientStagnateStatus();
    }

    private static void updateClientCollidingStatus() {
        if (ClientWorldLoader.getIsInitialized()) {
            for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
                CollisionHelper.updateCollidingPortalForWorld(world, 0);
            }
        }
    }

    public static void informClientStagnant() {
        thisTickStagnate = true;
        CollisionHelper.limitedLogger.log("client movement stagnated");
    }

    private static void updateClientStagnateStatus() {
        if (thisTickStagnate && lastTickStagnate) {
            Minecraft.getInstance().gui.hud.setOverlayMessage(
                Component.translatable("imm_ptl.stagnate_movement"),
                false
            );
        }
        else if (!thisTickStagnate && lastTickStagnate) {
            Minecraft.getInstance().gui.hud.setOverlayMessage(
                Component.literal(""),
                false
            );
        }

        lastTickStagnate = thisTickStagnate;
        thisTickStagnate = false;
    }
}
