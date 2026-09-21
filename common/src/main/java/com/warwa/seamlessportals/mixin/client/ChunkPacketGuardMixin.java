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
 *
 * <p><b>26.3 PORT.</b> javap on the 26.3 merged jar: the private
 * {@code ClientPacketListener.updateLevelChunk} is GONE — vanilla inlined it, and the ONE remaining
 * {@code invokevirtual ClientChunkCache.replaceWithPacketData} now sits in
 * {@code handleLevelChunkWithLight} with the folded descriptor
 * {@code (IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;)Lnet/minecraft/world/level/chunk/LevelChunk;}
 * (was {@code (IILFriendlyByteBuf;LMap;LConsumer;)} in {@code updateLevelChunk}). Same single call,
 * same guard, new home — pinned {@code require = allow = 1}.
 */
@Mixin(ClientPacketListener.class)
public abstract class ChunkPacketGuardMixin {

    @Redirect(method = "handleLevelChunkWithLight",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;"
                + "replaceWithPacketData("
                + "IILnet/minecraft/network/protocol/game/ClientboundLevelChunkPacketData;"
                + ")Lnet/minecraft/world/level/chunk/LevelChunk;"),
        require = 1, allow = 1)
    private LevelChunk seamlessportals$guardChunkDecode(
            ClientChunkCache cache,
            int chunkX, int chunkZ, ClientboundLevelChunkPacketData chunkData) {
        try {
            return cache.replaceWithPacketData(chunkX, chunkZ, chunkData);
        } catch (IndexOutOfBoundsException e) {
            SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS GUARD] Dropped stale chunk packet [{}, {}] — "
                    + "section-count mismatch with this.level ({}). Server will resend.",
                chunkX, chunkZ, e.getMessage());
            // 26.2 marked the half-read buffer fully consumed here. 26.3 has no such buffer to mark:
            // the read buffer is no longer an argument — LevelChunk.replaceWithPacketData builds its own
            // throwaway one per call from chunkData.getReadBuffer() = new FriendlyByteBuf(
            // Unpooled.wrappedBuffer(byte[])) (mc263-ref LevelChunk.java:519,
            // ClientboundLevelChunkPacketData.java:106-108), so nothing downstream can see a half-read state.
            return null;
        }
    }
}
