package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamDeliveryProbe;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * DELIVERY PROBE STAGES 3b AND 4 — the last two places a mirrored write can vanish server-side.
 *
 * <p><b>3b.</b> {@code ChunkHolder.blockChanged} (REF {@code :123-127}) opens with
 * {@code LevelChunk chunk = this.getTickingChunk(); if (chunk == null) return false;}. A chunk that
 * is loaded but not at {@code FullChunkStatus.BLOCK_TICKING} — which is exactly what a chunk held
 * open only for a portal view can be — drops the change here with no log, no exception, and a
 * perfectly successful {@code setBlock} behind it.
 *
 * <p><b>4.</b> {@code broadcastChanges} (REF {@code :174-216}) takes recipients from
 * {@code playerProvider.getPlayers}, which IP redirects to
 * {@code ImmPtlChunkTracking.getPlayersViewingChunk} ({@code MixinChunkHolder.redirectGetPlayers}).
 * An empty list means the packet is never built. <b>The count is recorded even when it is zero</b>,
 * because zero is the answer worth having and vanilla only calls {@code broadcast} when the list is
 * non-empty — instrumenting the send alone would leave the interesting case invisible.
 *
 * <p>Coexists with IP's {@code MixinChunkHolder}: that one uses {@code @ModifyVariable} on
 * {@code broadcast} and {@code @Redirect} on {@code broadcastChanges}' {@code getPlayers} call. These
 * are {@code @Inject}s at {@code HEAD} on {@code blockChanged} and {@code broadcastChanges}, so no
 * injection point is shared.
 */
@Mixin(ChunkHolder.class)
public abstract class ChunkHolderDeliveryProbeMixin {

    @Shadow @Final private LevelHeightAccessor levelHeightAccessor;

    @Shadow public abstract @org.jetbrains.annotations.Nullable LevelChunk getTickingChunk();

    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void seamlessportals$noteDeliveryHolderAccept(
        BlockPos pos, CallbackInfoReturnable<Boolean> cir
    ) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return;
        }
        if (!(this.levelHeightAccessor instanceof Level level)) {
            return;
        }
        SeamDeliveryProbe.noteHolderAccept(level, pos, this.getTickingChunk() != null);
    }

    @Shadow @Final private @org.jetbrains.annotations.Nullable ShortSet[] changedBlocksPerSection;

    /**
     * Recorded at HEAD, before vanilla's own {@code hasChangesToBroadcast} branch, so a chunk that
     * reaches this method with nothing pending is still distinguishable from one that never reaches
     * it at all.
     *
     * <p><b>Per CELL, not per chunk.</b> The exact cell is checked against
     * {@code changedBlocksPerSection} — the set vanilla is about to encode — because a chunk-granular
     * answer to a cell-granular question is a false positive by construction, and the first build of
     * this probe produced exactly that: an unrelated change elsewhere in the chunk read as "this
     * write was broadcast". The recipient list is asked for by the same route the target uses.
     */
    @Inject(method = "broadcastChanges", at = @At("HEAD"))
    private void seamlessportals$noteDeliveryBroadcast(LevelChunk chunk, CallbackInfo ci) {
        if (!AperturePassthroughLever.SEAM_DELIVERY_PROBE) {
            return;
        }
        try {
            Level level = chunk.getLevel();
            int cx = chunk.getPos().x();
            int cz = chunk.getPos().z();
            List<BlockPos> traced = SeamDeliveryProbe.tracedPositionsInChunk(level, cx, cz);
            if (traced.isEmpty()) {
                return;
            }
            List<ServerPlayer> viewers = qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking
                .getPlayersViewingChunk(level.dimension(), cx, cz, false);
            for (BlockPos pos : traced) {
                int sectionIndex = this.levelHeightAccessor.getSectionIndex(pos.getY());
                ShortSet pending = sectionIndex >= 0 && sectionIndex < this.changedBlocksPerSection.length
                    ? this.changedBlocksPerSection[sectionIndex] : null;
                boolean queued = pending != null
                    && pending.contains(SectionPos.sectionRelativePos(pos));
                SeamDeliveryProbe.noteBroadcast(level, pos, queued, viewers.size(),
                    "(ImmPtlChunkTracking; tickingChunk=" + (this.getTickingChunk() != null) + ")");
            }
        }
        catch (Throwable ignored) {
            // A diagnostic must never take down chunk broadcasting.
        }
    }
}
