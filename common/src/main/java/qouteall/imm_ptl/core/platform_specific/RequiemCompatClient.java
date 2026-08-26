package qouteall.imm_ptl.core.platform_specific;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Mob;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import qouteall.q_misc_util.Helper;

/**
 * NF-PARITY C3 dist-split (2026-08-25): the CLIENT half of {@link RequiemCompat}, physically
 * split out. Verifying the old {@code RequiemCompat.onPlayerTeleportedClient} body needed the
 * {@code LocalPlayer -> Player} assignability proof, which force-loads the client-only
 * {@code LocalPlayer} when {@code RequiemCompat} LINKS — and RequiemCompat links on the
 * dedicated server ({@code onPlayerTeleportedServer} is called from
 * {@code ServerTeleportationManager}). NeoForge has no {@code @Environment} member stripping,
 * so client-typed bodies cannot share a class with server-linked members. Logic verbatim.
 */
public class RequiemCompatClient {

    public static void onPlayerTeleportedClient() {
        if (!RequiemCompat.getIsRequiemPresent()) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        Mob possessedEntity = RequiemCompat.getPossessedEntity(player);
        if (possessedEntity != null) {
            if (possessedEntity.level() != player.level()) {
                Helper.LOGGER.info("Move Requiem Possessed Entity at Client");
                ClientTeleportationManager.moveClientEntityAcrossDimension(
                    possessedEntity,
                    ((ClientLevel) player.level()),
                    player.position()
                );
            }
        }
    }
}
