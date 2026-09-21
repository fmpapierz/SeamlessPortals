package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 26.3 (Forge dedicated server) — the CLIENT-ONLY bodies of two {@link AperturePassthroughInit} tick handlers, moved
 * here VERBATIM. {@code AperturePassthroughInit} is linked on a dedicated server (the loader entry points call
 * {@code init()} on both dists), and the JVM verifies EVERY method body of a class when it links it — lambda bodies
 * included, whether or not they ever run. Two bodies needed an assignability proof involving a client-only class:
 * <ul>
 *   <li>the (e) DEFECT-B ride sampler — {@code Entity player = mc.player} ({@code LocalPlayer} → {@code Entity});</li>
 *   <li>{@code clientSeamViewProbe} — {@code SeamOccupancy.secondaryOf(mc.level, pos)} / {@code occupancyOf(mc.level, ..)}
 *       ({@code ClientLevel} → {@code Level}).</li>
 * </ul>
 * Measured on the first {@code :forge:runServer} that got past Bootstrap: {@code RuntimeDistCleaner: Attempted to load
 * class net/minecraft/client/player/LocalPlayer for invalid dist DEDICATED_SERVER} at the {@code init()} call
 * ({@code SeamlessPortalsModForge.onRegisterRegistries}) — the class then never initialised, so the server ran without
 * the seam registry wiring. This is NF-PARITY rule 1 (the verifier load), which NeoForge's dist cleaner enforces the same
 * way; both handlers were added after the August NeoForge server measurements. An {@code invokestatic} into this class
 * is safe: the callee links at first EXECUTION, and both call sites sit on client-only tick signals.
 */
public final class AperturePassthroughInitClient {

    private AperturePassthroughInitClient() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The (e) DEFECT-B ride sampler body — see the registration comment in {@link AperturePassthroughInit#init()}. */
    static void rideProbeEndClientTick() {
        if (!SeamRideProbe.windowOpen()) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.world.entity.Entity player = mc == null ? null : mc.player;
        net.minecraft.world.entity.Entity vehicle = player == null ? null : player.getVehicle();
        int watched = SeamRideProbe.watchedVehicleId();
        SeamRideProbe.onEndClientTick(
            player, vehicle,
            mc == null || mc.level == null
                ? "null" : mc.level.dimension().identifier().toString(),
            mc != null && mc.level != null && watched >= 0
                && mc.level.getEntity(watched) != null);
    }

    private static long clientSeamViewProbeLast = 0;

    /** RS-XTALK round 3 — the 1 Hz client-view line; see the registration comment. Log-only. */
    static void clientSeamViewProbe() {
        if (!AperturePassthroughLever.SEAM_SIGNAL_PROBE) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - clientSeamViewProbeLast < 1_000_000_000L) {
            return;
        }
        clientSeamViewProbeLast = now;
        var cells = ((SeamIndexHolder) mc.level).seamlessportals$seamCells();
        if (cells.isEmpty()) {
            return;
        }
        var powered = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED;
        for (var e : cells.long2ObjectEntrySet()) {
            net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(e.getLongKey());
            var st = mc.level.getBlockState(pos);
            if (st.isAir()) {
                continue;
            }
            var sec = SeamOccupancy.secondaryOf(mc.level, pos);
            LOGGER.info("[RS-SIGNAL] client view: {} {} powered={} mask={} secondary={}{}",
                pos, st.getBlock(),
                st.hasProperty(powered) ? st.getValue(powered) : "n/a",
                SeamOccupancy.occupancyOf(mc.level, pos),
                sec != null,
                sec == null ? "" : (" secBlock=" + sec.state().getBlock() + " secPowered="
                    + (sec.state().hasProperty(powered) ? sec.state().getValue(powered) : "n/a")
                    + " secHalf=" + sec.half()));
        }
    }
}
