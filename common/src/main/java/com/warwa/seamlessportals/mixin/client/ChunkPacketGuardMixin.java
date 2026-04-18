package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Guards {@code ClientPacketListener.updateLevelChunk} against
 * {@code IndexOutOfBoundsException} from stale chunk packets arriving after
 * a client-first seamless teleport.
 *
 * <p>Root cause: when a player teleports overworld↔nether, chunk packets
 * queued on the server for the source dim may still be in flight when the
 * client's {@code this.level} is already pointed at the destination dim.
 * The packet's encoded section count doesn't match {@code this.level}'s
 * section count (overworld 24 vs nether 16), so
 * {@code LevelChunk.replaceWithPacketData} reads past the buffer end and
 * throws {@code IndexOutOfBoundsException}. Vanilla treats that as fatal
 * and disconnects with "Network Protocol Error".
 *
 * <p>Fix: wrap the inner call with try/catch. Drop the bad packet and let
 * the server resend chunks when the player moves or the server decides to.
 * Temporary visual loss of that chunk; no disconnect.
 *
 * <p>Only catches {@code IndexOutOfBoundsException}. Other exceptions
 * (OOM, NPE, etc.) propagate and fail loudly.
 */
@Mixin(ClientPacketListener.class)
public abstract class ChunkPacketGuardMixin {

    @Redirect(method = "updateLevelChunk",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;"
                + "replaceWithPacketData("
                + "IILnet/minecraft/network/FriendlyByteBuf;"
                + "Ljava/util/Map;"
                + "Ljava/util/function/Consumer;"
                + ")Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk seamlessportals$guardChunkDecode(
            ClientChunkCache cache,
            int chunkX, int chunkZ, FriendlyByteBuf buf,
            Map<Heightmap.Types, long[]> heightmaps,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntitiesConsumer) {
        try {
            return cache.replaceWithPacketData(chunkX, chunkZ, buf, heightmaps, blockEntitiesConsumer);
        } catch (IndexOutOfBoundsException e) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS GUARD] Dropped stale chunk packet [{}, {}] — "
                    + "section-count mismatch with this.level ({}). Server will resend.",
                chunkX, chunkZ, e.getMessage());
            // Mark the buffer fully consumed so downstream packet processing
            // sees a clean state rather than a half-read buffer.
            try {
                ByteBuf b = buf;
                if (b.readerIndex() < b.writerIndex()) {
                    b.readerIndex(b.writerIndex());
                }
            } catch (Throwable ignored) {}
            return null;
        }
    }
}
