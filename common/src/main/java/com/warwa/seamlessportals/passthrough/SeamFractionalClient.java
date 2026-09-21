package com.warwa.seamlessportals.passthrough;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 26.3 (Forge dedicated server) — the CLIENT-ONLY branch of {@link SeamFractional#tryTwoObjectPlacement}, moved here
 * VERBATIM. {@code SeamFractional} links on a dedicated server the moment the block-state caches bake (its callers are
 * the {@code BlockStateBaseFractionalMixin} shape hooks), and the JVM verifies every method body of a class at link
 * time. This branch holds {@code SeamOccupancy.setSecondary(farClient, ..)} with a {@code ClientLevel}-typed local where
 * {@code Level} is declared — an assignability proof the verifier can only make by LOADING {@code ClientLevel}.
 * Measured on {@code :forge:runServer}: {@code RuntimeDistCleaner: Attempted to load class
 * net/minecraft/client/multiplayer/ClientLevel for invalid dist DEDICATED_SERVER} under
 * {@code BlockStateBase.getCollisionShape} ← {@code Cache.<init>} ← {@code GameData$BlockCallbacks.onBake} →
 * "Failed to start the minecraft server". NF-PARITY rule 1 (the verifier load); NeoForge's dist cleaner enforces the
 * same. An {@code invokestatic} into this class is safe — it links at first EXECUTION, which only a client reaches.
 */
public final class SeamFractionalClient {

    private SeamFractionalClient() {}

    /** The {@code else if (level.isClientSide() && binding.destPos() != null)} body of tryTwoObjectPlacement. */
    static void predictCounterpartFragment(
        Level level, SeamRegistry.SeamBinding binding, BlockState state, byte emptyHalf, Direction.Axis axis,
        BlockPos target
    ) {
        // ★ PREDICT THE COUNTERPART FRAGMENT TOO (live round 10, "place mirror has a tiny
        // lag"): the primary path's counterpart is client-predicted (SeamMirrorClient), so
        // the gesture must predict as well or the far half pops in one round-trip later —
        // exactly the lag reported only when the other side is occupied. Same math as the
        // server install; the server's broadcast confirms/corrects moments later.
        Direction emptyDirC = Direction.get(
            emptyHalf == SeamOccupancy.HALF_POSITIVE
                ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE,
            axis);
        byte destHalfC = SeamOccupancy.halfOf(
            SeamRegistry.mapDir(binding, emptyDirC.getOpposite()));
        // peekWorld, not PortalWorldManager: the loader's per-dim world is the instance
        // every portal-view consumer reads (SeamOccupancyClient's proven resolution) — the
        // manager's store answered NULL cross-dim and the prediction silently died (the
        // round-13 probe line: "farClient=NULL (no prediction — the lag)").
        net.minecraft.client.multiplayer.ClientLevel farClient =
            level.dimension().equals(binding.destDim())
                ? (net.minecraft.client.multiplayer.ClientLevel) level
                : qouteall.imm_ptl.core.ClientWorldLoader.peekWorld(binding.destDim());
        if (farClient != null) {
            SeamOccupancy.setSecondary(farClient, binding.destPos(),
                new SeamOccupancy.Secondary(
                    state.rotate(binding.stateRotation()), destHalfC));
        }
        if (AperturePassthroughLever.SEAM_FRACTIONAL_PROBE) {
            SeamFractionalProbe.onSeamCell(target, "PREDICT-PLACE",
                "counterpart fragment prediction: farClient="
                    + (farClient == null ? "NULL (no prediction — the lag)"
                        : farClient.dimension().identifier().toString())
                    + " destPos=" + binding.destPos() + " destHalf=" + destHalfC);
        }
    }
}
