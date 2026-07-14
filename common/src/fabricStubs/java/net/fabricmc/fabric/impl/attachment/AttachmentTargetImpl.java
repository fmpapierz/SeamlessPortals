// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. PlayerChunkLoading.onSendPacket (an @IPVanillaCopy of Fabric API's
// own ChunkDataSenderMixin, which IP cancels + re-implements) casts a LevelChunk to this Fabric
// IMPL interface and calls fabric_computeInitialSyncChanges(player, changes::add). Resolved by the
// REAL fabric-api attachment module at S13 (compileOnly stub here). :common compile classpath only.
// Removed at S20.
package net.fabricmc.fabric.impl.attachment;

import net.fabricmc.fabric.impl.attachment.sync.AttachmentChange;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

public interface AttachmentTargetImpl {
    void fabric_computeInitialSyncChanges(ServerPlayer player, Consumer<AttachmentChange> changeOutput);
}
