package com.warwa.seamlessportals.chunk;

import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.client.PortalWorldManager;
import com.warwa.seamlessportals.mixin.client.ClientPacketListenerAccessorMixin;
import com.warwa.seamlessportals.mixin.client.MinecraftAccessorMixin;
import com.warwa.seamlessportals.network.ModPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Phase 4c (IP {@code PacketRedirection} architecture): apply a REDIRECTED
 * VANILLA chunk packet to a destination dimension by running the packet's OWN
 * handler under a world-switch, so the engine's native chunk-load tracking
 * drives the dest renderer's occlusion graph — replacing the custom snapshot
 * feed ({@code RemoteChunkManager}/{@code drainPendingFeeds}) and the
 * O(all-sections) manual scan that hung the render thread.
 *
 * <p>Mirrors IP {@code ClientWorldLoader.withSwitchedWorld(dim, () ->
 * packet.handle(handler))}: the vanilla {@code ClientPacketListener
 * .handleLevelChunkWithLight} routes ALL of its work through {@code this.level}
 * (chunk via {@code replaceWithPacketData}, light via {@code queueLightUpdate}),
 * and the deferred light lambda calls {@code mc.levelRenderer
 * .onChunkReadyToRender} — the signal that drives the dest occlusion graph. So
 * we swap the listener's level + {@code mc.level} + {@code mc.levelRenderer} to
 * the dest for the duration of {@code handle(...)}. The deferred lambda runs
 * later (on the dest's {@code pollLightUpdates}); {@link ChunkLightLambdaGuardMixin}
 * re-establishes the same dest context at that time.
 *
 * <p>Runs on the client/main thread (the Fabric receiver dispatches via
 * {@code context.client().execute(...)}), as {@code handleLevelChunkWithLight}'s
 * {@code ensureRunningOnSameThread} requires.
 */
public final class RedirectedPacketApplier {

    private RedirectedPacketApplier() {}

    private static int appliedLogCount = 0;

    // T2: time-boxed apply queue. The network receiver enqueues redirected chunks
    // instead of applying each one immediately, and {@link #drainPending()} applies a
    // budgeted slice per client tick on the main thread. Without this, a burst of
    // pre-warm chunks ran the full synchronous handleLevelChunkWithLight back-to-back
    // in a single tick — the ~155ms render-thread freeze. The queue is FIFO so chunks
    // still apply in arrival order. Thread-safe: the netty receiver adds, the client
    // tick drains.
    private static final java.util.Queue<ModPayloads.RedirectedChunkPayload> PENDING =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Per-tick apply budget. Caps the render-thread cost of the redirected feed to a
     *  small slice (so a big incoming batch spreads over ticks instead of freezing),
     *  while still converging the dest to fully-present — IP's graduated pre-warm. */
    private static final long DRAIN_BUDGET_NS = 3_000_000L; // 3ms/tick

    /** Network-thread entry: queue a redirected chunk for budgeted application. */
    public static void enqueue(ModPayloads.RedirectedChunkPayload payload) {
        PENDING.add(payload);
    }

    /**
     * Per-drain ACK batch: (dimId → packed chunk positions) applied THIS drain,
     * flushed as one {@code RedirectedChunkAckPayload} per dim at the end of
     * {@link #drainPending}. The server records a redirected chunk as client-held
     * ONLY on this ack — never at send time — so payloads this class drops (the
     * active-dim guard below, apply failures, disconnect clears) can never become
     * false "held" claims that arm the crossing suppression (the nether-limbo /
     * OW-holes bug, 2026-07-06). Main-thread only.
     */
    private static final java.util.Map<String, java.util.List<Long>> ACK_BATCH = new java.util.HashMap<>();

    /** Main-thread (client tick): apply queued redirected chunks within the time budget. */
    public static void drainPending() {
        if (PENDING.isEmpty()) return;
        long start = System.nanoTime();
        do {
            ModPayloads.RedirectedChunkPayload p = PENDING.poll();
            if (p == null) break;
            applyChunk(p);
        } while (System.nanoTime() - start < DRAIN_BUDGET_NS);

        if (!ACK_BATCH.isEmpty()) {
            for (java.util.Map.Entry<String, java.util.List<Long>> e : ACK_BATCH.entrySet()) {
                com.warwa.seamlessportals.network.PlatformHelper.getInstance().sendToServer(
                    new ModPayloads.RedirectedChunkAckPayload(e.getKey(), e.getValue()));
            }
            ACK_BATCH.clear();
        }
    }

    /** Drop any queued chunks (e.g. on disconnect) so a stale dim's chunks never apply. */
    public static void clearPending() {
        PENDING.clear();
    }

    public static void applyChunk(ModPayloads.RedirectedChunkPayload p) {
        ResourceKey<Level> dim = parseDimensionKey(p.dimensionId());
        if (dim == null) return;
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) return;

        // Never redirect into the ACTIVE dimension — it already receives its own
        // vanilla chunk packets directly; a redirect would double-apply.
        if (mc.level != null && mc.level.dimension().equals(dim)) return;

        // Ensure the secondary level + renderer + extractor exist (created on the
        // first redirected chunk if the player hasn't looked through the portal).
        LevelRenderer destRenderer = PortalWorldManager.getOrCreateRenderer(dim);
        ClientLevel destLevel = PortalWorldManager.getLevel(dim);
        if (destLevel == null || destRenderer == null) return;

        ClientPacketListenerAccessorMixin lacc = (ClientPacketListenerAccessorMixin) listener;
        MinecraftAccessorMixin macc = (MinecraftAccessorMixin) mc;
        ClientLevel savedListenerLevel = lacc.seamlessportals$getLevel();
        ClientLevel savedMcLevel = mc.level;
        LevelRenderer savedRenderer = macc.seamlessportals$getLevelRenderer();

        lacc.seamlessportals$setLevel(destLevel);
        mc.level = destLevel;
        macc.seamlessportals$setLevelRenderer(destRenderer);
        try {
            p.innerPacket().handle(listener);
            // ACK only on VERIFIED apply — "handled without exception" is not
            // enough: a bounded vanilla ClientChunkCache (the default-config
            // secondary store) silently IGNORES out-of-range chunks (warn +
            // return null, no throw). Confirm the chunk is actually present in
            // the dest store before acking; anything else stays un-acked, the
            // server's inflight entry expires, and the chunk re-sends.
            if (destLevel.getChunkSource().hasChunk(
                    p.innerPacket().getX(), p.innerPacket().getZ())) {
                ACK_BATCH.computeIfAbsent(p.dimensionId(), k -> new java.util.ArrayList<>())
                    .add(net.minecraft.world.level.ChunkPos.pack(
                        p.innerPacket().getX(), p.innerPacket().getZ()));
            }
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.error(
                "[SEAMLESS 4C] redirected chunk apply failed for {}", dim.identifier(), t);
        } finally {
            lacc.seamlessportals$setLevel(savedListenerLevel);
            mc.level = savedMcLevel;
            macc.seamlessportals$setLevelRenderer(savedRenderer);
        }

        if (appliedLogCount < 5) {
            appliedLogCount++;
            SeamlessPortalsConstants.LOGGER.info(
                "[SEAMLESS 4C] applied redirected vanilla chunk for {} (#{}) — engine drives the dest graph",
                dim.identifier(), appliedLogCount);
        }
    }

    private static ResourceKey<Level> parseDimensionKey(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> Level.OVERWORLD;
            case "minecraft:the_nether" -> Level.NETHER;
            case "minecraft:the_end" -> Level.END;
            default -> null;
        };
    }
}
