package com.warwa.seamlessportals.mixin.passthrough;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import com.warwa.seamlessportals.passthrough.SeamWriteContext;
import com.warwa.seamlessportals.passthrough.SeamWriteSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * THE PLAYER-PLACEMENT BRACKET — marks the one write a player actually asked for.
 *
 * <p>{@code BlockItem.place(BlockPlaceContext)} (REF {@code BlockItem.java:48}) is the narrowest
 * reliable bracket, and the alternatives are all wrong for concrete reasons:
 * <ul>
 *   <li><b>Not {@code ServerPlayerGameMode.useItemOn}</b> — too wide. It also covers
 *       {@code state.useItemOn} and {@code useWithoutItem}, i.e. opening a chest, flipping a lever,
 *       lighting a portal. Those write blocks too and none is "a player placed a block".</li>
 *   <li><b>Not {@code BlockItem.placeBlock}</b> — it IS overridden, and by exactly the items that
 *       matter: {@code BedItem.placeBlock} and {@code DoubleHighBlockItem.placeBlock} do not call
 *       {@code super}. A hook there never fires for a bed or a door.</li>
 *   <li><b>Not {@code MixinBlockItemCanPlace}</b>, the existing veto host — {@code canPlace} is not a
 *       bracket, and {@code StandingAndWallBlockItem}/{@code HangingSignItem} never reach the
 *       two-argument overload it hooks. (That is a real pre-existing gap in the VETO for wall-mounted
 *       items — the redstone torch among them — recorded in the handoff, not fixed here.)</li>
 * </ul>
 * {@code place} itself is overridden nowhere in vanilla, so every {@code BlockItem} funnels through
 * this one method.
 *
 * <p><b>The null-player guard is what excludes dispensers</b>, and it costs nothing: a dispenser
 * reaches the same {@code place} with a {@code DirectionalPlaceContext}, whose constructor hardcodes
 * a null player (REF {@code DirectionalPlaceContext.java:15}). So machines exclude themselves through
 * the same test that admits the player, with no block-source sniffing.
 *
 * <p>Buckets are outside this bracket by construction — {@code BucketItem} overrides {@code Item.use},
 * not {@code useOn}, so fluid placement never reaches {@code place}. That matches the current policy
 * (fluids are refused outright by the veto) and is the seam where fluid support will later attach.
 * Powder snow DOES arrive here, because {@code SolidBucketItem} is a {@code BlockItem}.
 */
@Mixin(BlockItem.class)
public abstract class MixinBlockItemPlaceSource {

    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
        at = @At("HEAD"))
    private void seamlessportals$armPlayerPlace(
        BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (!SeamlessPortalsConfig.isEntityPortals() || context.getPlayer() == null) {
            return;
        }
        // getClickedPos() is the cell the block actually lands in — already offset off the clicked
        // face when the clicked block is solid (REF BlockPlaceContext.java:47-49), which is exactly
        // the aperture cell for the "click the obsidian frame, block lands in the opening" gesture
        // this feature exists for.
        seamlessportals$saved = SeamWriteContext.push(
            SeamWriteSource.PLAYER_PLACE, context.getClickedPos());
    }

    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
        at = @At("RETURN"))
    private void seamlessportals$disarmPlayerPlace(
        BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (seamlessportals$saved != null) {
            SeamWriteContext.pop(seamlessportals$saved);
            seamlessportals$saved = null;
        }
    }

    /**
     * Saved outer state, restored on the way out. A per-instance field is safe here only because
     * {@code BlockItem} instances are effectively singletons per item AND this is server-thread
     * confined; the value is written and read within one synchronous {@code place} call.
     *
     * <p>RETURN rather than a try/finally: Mixin cannot wrap the target in one, and {@code place}
     * throwing would leave the source armed. {@code SeamWriteContext.sourceFor} is position-keyed, so
     * a stale arm can only ever mis-attribute a later write at the SAME position — and
     * {@code SeamWriteContext.reset} exists for the pathological case.
     */
    @org.spongepowered.asm.mixin.Unique
    private Object[] seamlessportals$saved = null;
}
