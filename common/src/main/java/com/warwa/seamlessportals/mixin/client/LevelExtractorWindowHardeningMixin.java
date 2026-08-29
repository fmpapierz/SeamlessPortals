package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.SecondaryWorldRenderCore;

/**
 * S17 pre-flip hardening (the two S14-ledgered items, port-note S14C-round7 "S17 hardening
 * items (rounds 1-3)"; flag-ON only — the block-era baseline stays byte-vanilla).
 *
 * <p><b>(a) CAPTURE-POINT WINDOW RESOLUTION.</b> Vanilla applies the loadedChunks delta
 * window order-dependently (addAll-then-removeAll); a chunk in BOTH sets nets to
 * REMOVED-while-loaded. Single-frame vanilla windows almost never pair, but the mod's
 * substrate makes pairs REAL: secondary extractors accumulate multi-frame windows (the
 * S14.42 invariant), and two ledgered residuals survived the pump family — the MAIN-dim
 * quantified-LOW unresolved application (forget+chunk same-pos inside one ≤1-tick window,
 * round-1 §0-4) and the sub-tick re-poison race (a reload draining between the promote-tick
 * hook and the first post-promote capture, round-7 §0c). Resolving added∩removed pairs
 * against LIVE chunk truth AT THE CAPTURE POINT — after the render state captures the set
 * REFS, before {@code flipUpdateTrackingSets} — closes BOTH in one move: the captured refs
 * ARE the pre-flip current sets, so in-place resolution feeds every consumer an order-free
 * window. No-op when no pair exists (the overwhelmingly common case); idempotent over the
 * promote-tick hook's earlier resolution.
 *
 * <p><b>(b) THE setLevel RE-ARM ASSERTION.</b> {@code setLevel} arms
 * {@code shouldResetLevelRenderData}; the first extract's reset-consume →
 * {@code SOG.waitAndReset(null)} CLEARS loadedChunks and the post-arm window is the ONLY
 * re-seeder. A future caller re-arming mid-life while KEEPING the same ClientLevel would
 * under-seed (the pump already drained the pre-re-arm adds) — unreachable today (armers:
 * world creation + disposal, both safe — round-7 §0b-4) but a silent-wipe landmine if a new
 * caller appears. One-shot loud assertion, never a crash.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorWindowHardeningMixin {

    @Shadow private ClientLevel level;

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private net.minecraft.client.renderer.state.level.LevelRenderState levelRenderState;

    private static boolean seamlessportals$reArmWarned = false;

    /**
     * ★ CART ROUND 3 DIAGNOSTIC (log-only, -PseamCartProbe): whether each seam-tracked cart made
     * it into this extract's {@code entityRenderStates}. The crossing cart's main-pass submit
     * went silent with the section gate VISIBLE and the entity still tracked — the remaining
     * candidates all live between extraction and submit, and this line splits them: extracted
     * but not submitted vs never extracted. javap-verified: {@code LevelExtractor.levelRenderState}
     * (private final), {@code extract(DeltaTracker, Camera, float)}.
     */
    @Inject(method = "extract", at = @At("RETURN"))
    private void seamlessportals$cartExtractPresence(CallbackInfo ci) {
        com.warwa.seamlessportals.render.CartWindowProbe.onMainExtract(
            this.level, this.levelRenderState);
    }

    /**
     * ★ CART ROUND 7 DIAGNOSTIC (log-only, -PseamCartProbe): the full extraction verdict for
     * near-seam carts, per extracting level — settles "main pass said false" vs "main pass never
     * asked" (round 6's id-keyed change map could not tell them apart). javap-verified:
     * {@code public boolean isEntityVisible(Entity, Frustum, double, double, double)}.
     */
    @Inject(method = "isEntityVisible", at = @At("RETURN"))
    private void seamlessportals$cartVisibilityVerdict(
        net.minecraft.world.entity.Entity entity,
        net.minecraft.client.renderer.culling.Frustum frustum,
        double camX, double camY, double camZ,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir
    ) {
        com.warwa.seamlessportals.render.CartWindowProbe.onIsEntityVisible(
            this.level, entity, frustum, camX, camY, camZ, cir.getReturnValueZ());
    }

    @Inject(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;flipUpdateTrackingSets()V"
        )
    )
    private void seamlessportals$resolveWindowAtCapture(CallbackInfo ci) {
        if (!com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) {
            return;
        }
        if (level != null) {
            // Resolves the CURRENT accumulating sets in place — the same object refs the
            // render state just captured (pre-flip), so the applied window is order-free.
            SecondaryWorldRenderCore.preResolvePromotedWindow(level);
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void seamlessportals$assertNoMidLifeReArm(ClientLevel newLevel, CallbackInfo ci) {
        if (!com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) {
            return;
        }
        if (newLevel != null && newLevel == this.level && !seamlessportals$reArmWarned) {
            seamlessportals$reArmWarned = true;
            qouteall.q_misc_util.Helper.err(
                "[LevelExtractor hardening] setLevel re-armed with the SAME ClientLevel — "
                    + "shouldResetLevelRenderData will clear loadedChunks and the accumulated "
                    + "window CANNOT fully re-seed it (the S14 round-7 §0b-4 landmine). "
                    + "The caller needs a wholesale re-seed.");
            new Throwable("re-arm call site").printStackTrace();
        }
    }
}
