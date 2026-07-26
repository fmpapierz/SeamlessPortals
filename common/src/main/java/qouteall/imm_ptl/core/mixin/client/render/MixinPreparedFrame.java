package qouteall.imm_ptl.core.mixin.client.render;

import com.warwa.seamlessportals.render.FrontClipping;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.PerEntityClipBracket;

/**
 * S12-A (Slice C) — R3 mixin 4 of 4: THE clip-delivery execute bracket (S11-R3-clip-bracketing.md
 * §1.2.4). Its javadoc used to call it "Mechanism-A", implying an alternative; since the S20 C4
 * loser cleanup there is only this. <b>Do not read the deleted Mechanism B's paperwork onto this
 * file</b> — it was proposed for deletion with B on the strength of a stale "always under
 * Mechanism B" line and is in fact A's ONLY clip-delivery bracket; deleting it makes straddling
 * entities draw unclipped (port-note §G.3).
 * HEAD/RETURN inject on the private
 * {@code FeatureRenderDispatcher.PreparedFrame.executePhase(FeatureRenderPhase, FeatureFrameContext)}
 * ({@code 26.2:.../feature/FeatureRenderDispatcher.java:258-266}) — the point at which one phase object's
 * groups are drawn. On 26.2 an {@code order()} band's submits land in per-order {@code SubmitNodeCollection}s
 * whose phase objects are storage-unique, so registering a clipped entity's plane against exactly those phase
 * objects makes this bracket scope the clip to precisely that entity's draw calls — the native equivalent of
 * IP's {@code endBatch} clip-split (design §1.1).
 *
 * <p><b>HEAD:</b> {@code prev = PerEntityClipBracket.beginPhaseIfRegistered(phase)} captures the ambient store
 * and pushes the registered plane onto the proven {@code com.warwa.seamlessportals.render.FrontClipping} store
 * (whose per-draw upload is the always-on {@code GlCommandEncoderClipMixin}); {@code null} when the phase is
 * unregistered (the common case) — {@code endPhase(null)} then no-ops, so with no collided entities
 * this mixin is two map-miss branches per phase per frame.
 * <b>RETURN:</b> {@code PerEntityClipBracket.endPhase(prev)} restores the ambient store.
 *
 * <p><b>Scoping (Verifier-1 P2).</b> {@code prev} is held in a {@code @Unique} field on the mixed
 * {@code PreparedFrame} instance. {@code executePhase} is called sequentially per pass (never re-entrant within
 * one {@code PreparedFrame}); a nested dest {@code LevelRenderer} pass uses a DIFFERENT
 * {@code FeatureRenderDispatcher}/{@code PreparedFrame} instance, so its bracket state is naturally isolated.
 * The RETURN restore fires on normal completion; the (exceptional) throw path is KNOWN-OPEN and accepted
 * (S18 port-note ledger): a throw inside {@code executePhase} skips the RETURN restore, but on the main
 * path nothing above swallows — the frame dies anyway (vanilla behavior for a feature-renderer throw), so
 * the leaked plane never draws. (The seam's own throw-fenced dispatcher path was Mechanism B's, and
 * went with it at S20.)
 *
 * <p>REGISTERED + LIVE (seamlessportals-ip-client.mixins.json "client" array) — brackets every
 * executePhase on every dispatcher instance (main + secondaries + the seam's own) since S13.
 */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public class MixinPreparedFrame {

    @Unique
    private FrontClipping.Snapshot seamlessportals$prevClip;

    @Inject(method = "executePhase", at = @At("HEAD"))
    private void seamlessportals$beginPhaseClip(
        FeatureRenderPhase<?> phase, FeatureFrameContext context, CallbackInfo ci
    ) {
        this.seamlessportals$prevClip = PerEntityClipBracket.beginPhaseIfRegistered(phase);
    }

    @Inject(method = "executePhase", at = @At("RETURN"))
    private void seamlessportals$endPhaseClip(
        FeatureRenderPhase<?> phase, FeatureFrameContext context, CallbackInfo ci
    ) {
        PerEntityClipBracket.endPhase(this.seamlessportals$prevClip);
        this.seamlessportals$prevClip = null;
    }
}
