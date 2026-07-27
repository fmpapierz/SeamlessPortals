package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.passthrough.SeamWriteContext;
import com.warwa.seamlessportals.passthrough.SeamWriteSource;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * THE CLIENT-SIDE PLAYER-BREAK BRACKET — what makes a mirrored BREAK instant.
 *
 * <p><b>Why placing was already instant and breaking was not.</b> The place bracket sits on
 * {@code BlockItem.place}, which runs on BOTH sides — the client executes the same method while
 * predicting — so the client's predicted placement was already attributed to the player and mirrored
 * in the same frame. Breaking has no such shared method: the server bracket is on
 * {@code ServerPlayerGameMode.destroyBlock}, and the client's own predicted removal goes through
 * {@code MultiPlayerGameMode.destroyBlock} (REF {@code MultiPlayerGameMode.java:117}) instead. So on
 * the client a break reported {@code UNKNOWN}, {@code SeamMirrorPolicy} declined it, and the mirrored
 * half was left waiting for the server packet — exactly the lag the user still saw on breaks after
 * placement had become instant.
 *
 * <p>This is the client half of the same bracket, not a second policy: both sides now report
 * {@code PLAYER_BREAK} for the same gesture, and {@code SeamMirrorClient} predicts the far half's
 * removal the same way it predicts a placement — retained first, so the server's ack resolves it
 * whichever way the server went.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MixinMultiPlayerGameModeBreakSource {

    @Unique
    private Object[] seamlessportals$saved = null;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void seamlessportals$armClientBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (!SeamlessPortalsConfig.isEntityPortals()) {
            return;
        }
        seamlessportals$saved = SeamWriteContext.push(SeamWriteSource.PLAYER_BREAK, pos);
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void seamlessportals$disarmClientBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (seamlessportals$saved != null) {
            SeamWriteContext.pop(seamlessportals$saved);
            seamlessportals$saved = null;
        }
    }
}
