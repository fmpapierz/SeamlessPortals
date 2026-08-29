// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. PlayerChunkLoading.onSendPacket collects a List<AttachmentChange>
// then hands it to AttachmentSync.trySync(changes, player) (S13-B P2 re-derivation). In real
// fabric-data-attachment-api 2.2.16 this is a record(targetInfo,type,value); the held IP code
// references it only as a type parameter (never constructs it / reads fields), so a minimal shell
// is faithful. Resolved by the REAL fabric-api attachment module at :fabric. :common compile
// classpath only. Removed at S20.
package net.fabricmc.fabric.impl.attachment.sync;

public class AttachmentChange {
}
