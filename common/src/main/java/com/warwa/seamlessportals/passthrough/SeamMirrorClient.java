package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.ducks.IEClientWorld;

/**
 * SAME-FRAME MIRRORING — the client-side prediction of a mirrored write.
 *
 * <h2>The problem this solves, measured rather than assumed</h2>
 * The server mirror is already inline and same-tick: {@code SeamMirror.onSeamCellChanged} calls
 * {@code applyToDestination} directly, with no deferral. So both halves are written in one server
 * tick, and yet the user sees the near half appear instantly and the far half a moment later. The
 * asymmetry is entirely client-side: the player's OWN block is predicted locally by
 * {@code MultiPlayerGameMode}, while the mirrored half cannot appear until the server's block-update
 * packet arrives. This predicts the mirrored half too, so both appear in the same frame — which is
 * what "looks exactly like vanilla block placement" means.
 *
 * <h2>Why prediction here is riskier than vanilla's, and how that is contained</h2>
 * Vanilla predicts the cell the player clicked. This predicts a DIFFERENT cell, possibly in another
 * dimension, and the server may refuse the whole placement (refuse-on-conflict). A wrong or
 * unreverted prediction is therefore a ghost block in a place the player may not be looking at.
 * Three rules contain it:
 *
 * <ol>
 *   <li><b>Every predicted cell is retained BEFORE it is written.</b>
 *       {@code BlockStatePredictionHandler.retainKnownServerState} records the cell's pre-write state
 *       against the current sequence number. When the server's ack arrives,
 *       {@code endPredictionsUpTo} either replaces it with the server's real value (if the server
 *       agreed and sent an update) or restores the retained state (if it did not). <b>Either way the
 *       cell is resolved</b> — there is no timer, no bookkeeping of our own, and no path that leaves
 *       a prediction outstanding. IP already fans the ack out to every {@code ClientLevel}, so this
 *       works for a cross-dimension destination too.</li>
 *   <li><b>Predict only when the destination is genuinely resolvable.</b> If the destination
 *       {@code ClientLevel} is not already loaded, or its chunk is absent, or the binding is not
 *       mirrorable, this declines and the existing server-driven behaviour stands. A block that
 *       appears one tick late is a far smaller defect than one that appears instantly in the wrong
 *       cell and then jumps.</li>
 *   <li><b>Only while the client is actually predicting.</b> {@code isPredicting()} is true only
 *       inside a player action. Without that gate every incoming server block update would re-fire
 *       this and write speculative state with nothing tying it to an ack.</li>
 * </ol>
 *
 * <h2>Two details that look optional and are not</h2>
 * <ul>
 *   <li><b>{@code UPDATE_SKIP_ON_PLACE}</b>, matching the server write. Without it the predicted copy
 *       runs its own {@code onPlace} against the DESTINATION's neighbours, reproducing client-side
 *       the "straight rail AND curved rail at once" divergence the server write suppresses.</li>
 *   <li><b>A client-side {@code applying} guard.</b> For a SAME-dimension portal the predicted write
 *       lands in the very level that is driving this, and would re-enter immediately.</li>
 * </ul>
 */
public final class SeamMirrorClient {

    private SeamMirrorClient() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Re-entrancy guard — same-dimension mirroring writes into the level that is driving us. */
    private static boolean applying = false;

    private static long predicted = 0L;
    private static long declinedNoDestLevel = 0L;
    private static long declinedNotPredicting = 0L;

    /**
     * Called from the block-write driver for a CLIENT level whose cell is bound to a seam.
     *
     * @param level    the client level that changed
     * @param pos      the changed cell
     * @param newState the state now at {@code pos}
     */
    public static void onSeamCellChanged(ClientLevel level, BlockPos pos, BlockState newState) {
        if (AperturePassthroughLever.DISABLED
            || AperturePassthroughLever.DISABLE_SEAM_MIRROR
            || AperturePassthroughLever.DISABLE_SEAM_PREDICTION) {
            return;
        }
        if (applying) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) {
                return;
            }
            BlockStatePredictionHandler handler =
                ((IEClientWorld) level).ip_getBlockStatePredictionHandler();
            // THE GATE. isPredicting() is true only inside a player action being predicted, which is
            // exactly the set of writes that should mirror — and it is also what guarantees an ack is
            // coming to resolve whatever we write below.
            if (handler == null || !handler.isPredicting()) {
                declinedNotPredicting++;
                return;
            }
            // Same policy as the server: player writes only, exact seams only. Asked here too rather
            // than assumed, so the client can never predict something the server will decline.
            SeamWriteSource source = SeamWriteContext.sourceFor(pos);
            if (!SeamMirrorPolicy.mirrors(source)) {
                return;
            }
            SeamRegistry.SeamCell cell = SeamRegistry.lookup(level, pos);
            if (cell == null) {
                return;
            }

            BlockPos lastDest = null;
            ResourceKey<Level> lastDim = null;
            for (SeamRegistry.SeamBinding binding : cell.bindings()) {
                if (!binding.isMirrorable()) {
                    continue;
                }
                // CLUSTER DEDUPE, as on the server: an obsidian frame yields four portal entities and
                // a cell can carry two bindings resolving to the same destination.
                if (binding.destPos().equals(lastDest) && binding.destDim().equals(lastDim)) {
                    continue;
                }
                lastDest = binding.destPos();
                lastDim = binding.destDim();

                ClientLevel dest = loadedClientLevel(binding.destDim());
                if (dest == null) {
                    declinedNoDestLevel++;
                    continue;   // rule 2 — decline rather than guess
                }
                BlockPos destPos = binding.destPos();
                if (!dest.hasChunkAt(destPos)) {
                    declinedNoDestLevel++;
                    continue;
                }
                predictOne(dest, destPos, newState.rotate(binding.stateRotation()), mc);
            }
        }
        catch (Throwable t) {
            LOGGER.warn("[RS-SEAM-PREDICT] client mirror prediction failed at {} — the server write"
                + " still applies, it will just arrive a tick later", pos, t);
        }
    }

    private static void predictOne(
        ClientLevel dest, BlockPos destPos, BlockState state, Minecraft mc
    ) {
        BlockStatePredictionHandler destHandler =
            ((IEClientWorld) dest).ip_getBlockStatePredictionHandler();
        if (destHandler == null) {
            declinedNoDestLevel++;
            return;
        }
        applying = true;
        try {
            // RETAIN FIRST, ALWAYS. This is what makes the prediction self-correcting: the cell's
            // current state is filed against the CURRENT sequence number, and the ack resolves it
            // whichever way the server went. Note we deliberately do NOT call startPredicting() on
            // this handler — that would bump its sequence past the one the outbound packet carries,
            // and endPredictionsUpTo would then skip the entry and never revert it.
            destHandler.retainKnownServerState(destPos, dest.getBlockState(destPos), mc.player);
            dest.setBlock(destPos, state, Block.UPDATE_ALL | Block.UPDATE_SKIP_ON_PLACE);
            predicted++;
        }
        finally {
            applying = false;
        }
    }

    /**
     * The already-loaded client level for a dimension, or null.
     *
     * <p><b>Deliberately not {@code ClientWorldLoader.getWorld} or {@code getOptionalWorld}</b> —
     * both CREATE a secondary client world on demand (the "optional" one delegates straight to the
     * other for any dimension the server has, despite its name). Creating a whole client world from
     * inside a block-placement hook is not something a prediction should do, and a destination whose
     * world does not exist yet is precisely a case where declining is correct.
     */
    @Nullable
    private static ClientLevel loadedClientLevel(ResourceKey<Level> dim) {
        try {
            if (!ClientWorldLoader.getIsInitialized()) {
                return null;
            }
            for (ClientLevel candidate : ClientWorldLoader.getClientWorlds()) {
                if (candidate.dimension().equals(dim)) {
                    return candidate;
                }
            }
        }
        catch (Throwable ignored) {
            // Not initialised yet, or called off-thread — decline.
        }
        return null;
    }

    public static String counters() {
        return "predicted=" + predicted
            + " declinedNoDestLevel=" + declinedNoDestLevel
            + " declinedNotPredicting=" + declinedNotPredicting;
    }
}
