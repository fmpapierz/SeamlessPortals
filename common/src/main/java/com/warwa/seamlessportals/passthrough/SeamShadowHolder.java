package com.warwa.seamlessportals.passthrough;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Duck interface implemented onto {@code net.minecraft.world.level.block.RailState} by
 * {@code com.warwa.seamlessportals.mixin.passthrough.MixinRailStateSeam} — the per-instance carrier
 * of a {@link SeamShadow}.
 *
 * <p><b>Why per-instance and not a thread-local or a position rule.</b> {@code RailState}s are
 * constructed for ordinary local cells constantly ({@code BaseRailBlock.updateDir},
 * {@code RailBlock.updateState}, {@code DetectorRailBlock.updatePowerToConnected}). A position rule
 * would mistake a genuine local rail standing at a shadow coordinate — on an obsidian frame the
 * approach cells on BOTH sides are real, buildable cells — for a proxy of the far world. Only the
 * {@code getRail} handoff knows a particular instance was minted to represent a far rail, so the
 * shadow rides the instance it stamped.
 */
public interface SeamShadowHolder {

    BlockPos seamlessportals$pos();

    @Nullable
    SeamShadow seamlessportals$shadow();

    void seamlessportals$setShadow(SeamShadow shadow);
}
