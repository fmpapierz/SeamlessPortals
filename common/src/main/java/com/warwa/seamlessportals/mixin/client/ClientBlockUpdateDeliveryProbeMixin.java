package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamDeliveryProbe;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DELIVERY PROBE STAGE 5 — did the client receive the update, and into WHICH level did it apply it?
 *
 * <p>Both questions matter separately. "The packet arrived" and "it reached the {@code ClientLevel}
 * the portal view renders from" are different claims, and under IP's packet redirection
 * ({@code PacketRedirection}, and the per-dimension client levels this port keeps) they can come
 * apart. The handler's own {@code level} field is read at the moment of application, so what is
 * logged is the level vanilla is actually about to write to — not a level this probe went looking
 * for.
 *
 * <p>Matched to a server-side trace by POSITION ONLY, deliberately: telling the client which
 * dimension to expect would make the instrument unable to report the one answer worth having, that
 * the update landed in the wrong level. Position matching relies on the integrated server sharing
 * this JVM, which is true of the dev client this probe is for and nowhere else.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientBlockUpdateDeliveryProbeMixin {

    @Shadow private ClientLevel level;

    private String seamlessportals$dim() {
        ClientLevel l = this.level;
        return l == null ? "(null level)" : l.dimension().identifier().toString();
    }

    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void seamlessportals$noteDeliveryBlockUpdate(
        ClientboundBlockUpdatePacket packet, CallbackInfo ci
    ) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return;
        }
        if (SeamDeliveryProbe.isWatchedPosition(packet.getPos())) {
            SeamDeliveryProbe.noteClientReceived(
                packet.getPos(), seamlessportals$dim(), "ClientboundBlockUpdatePacket",
                String.valueOf(packet.getBlockState()));
        }
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"))
    private void seamlessportals$noteDeliverySectionUpdate(
        ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci
    ) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return;
        }
        String dim = seamlessportals$dim();
        packet.runUpdates((pos, state) -> {
            if (SeamDeliveryProbe.isWatchedPosition(pos)) {
                SeamDeliveryProbe.noteClientReceived(
                    pos.immutable(), dim, "ClientboundSectionBlocksUpdatePacket",
                    String.valueOf(state));
            }
        });
    }
}
