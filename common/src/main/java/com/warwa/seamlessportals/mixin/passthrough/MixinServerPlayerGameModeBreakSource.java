package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.passthrough.SeamWriteContext;
import com.warwa.seamlessportals.passthrough.SeamWriteSource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * THE PLAYER-BREAK BRACKET.
 *
 * <p>{@code ServerPlayerGameMode.destroyBlock(BlockPos)} (REF
 * {@code ServerPlayerGameMode.java:260}) is the single funnel for every way a player removes a
 * block — creative destroy, insta-mine and normal mining all reach it through {@code destroyAndAck}
 * ({@code :171}, {@code :200}, {@code :223}, method at {@code :251}). The actual write is
 * {@code this.level.removeBlock(pos, false)} at {@code :278}, inside the bracket.
 *
 * <p><b>Why breaks are still mirrored when other non-placement writes are not.</b> "Break one half
 * breaks the other" is {@code REDSTONE_RECON.md} §0.7, a separate user rule that the 2026-07-26
 * player-only narrowing did not withdraw. It also cannot simply be dropped: an unmirrored break
 * leaves the far half standing with no counterpart, and the player then cannot replace their own
 * block because the seam is occupied — the exact "places and instantly disappears" defect already
 * recorded at {@code SeamMirror.java:288-295}.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class MixinServerPlayerGameModeBreakSource {

    @Unique
    private Object[] seamlessportals$saved = null;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void seamlessportals$armPlayerBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!SeamlessPortalsConfig.isEntityPortals()) {
            return;
        }
        seamlessportals$saved = SeamWriteContext.push(SeamWriteSource.PLAYER_BREAK, pos);
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void seamlessportals$disarmPlayerBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (seamlessportals$saved != null) {
            SeamWriteContext.pop(seamlessportals$saved);
            seamlessportals$saved = null;
        }
    }
}
