// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. PlayerChunkLoading.onSendPacket collects a List<AttachmentChange>
// then calls the static AttachmentChange.partitionAndSendPackets(changes, player). Resolved by the
// REAL fabric-api attachment module at S13. :common compile classpath only. Removed at S20.
package net.fabricmc.fabric.impl.attachment.sync;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class AttachmentChange {
    public static void partitionAndSendPackets(List<AttachmentChange> changes, ServerPlayer player) {
    }
}
