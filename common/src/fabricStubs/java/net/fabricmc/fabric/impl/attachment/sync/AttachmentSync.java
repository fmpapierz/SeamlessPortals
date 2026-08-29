// S13-B loader-seam compileOnly stub (entity-portal migration; port-note S13B-flip.md).
// NOT IP source, NOT shipped. fabric-data-attachment-api 2.2.16 moved the partition/send half of
// attachment sync off AttachmentChange onto AttachmentSync.trySync(List<AttachmentChange>,
// ServerPlayer) (chunk-loading.md row 53). PlayerChunkLoading.onSendPacket (an @IPVanillaCopy of
// Fabric's own 26.2 PlayerChunkSenderMixin) calls it. Resolved by the REAL fabric-api attachment
// module at :fabric. :common compile classpath only. Removed at S20.
package net.fabricmc.fabric.impl.attachment.sync;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class AttachmentSync {
    public static void trySync(List<AttachmentChange> changes, ServerPlayer player) {
    }
}
