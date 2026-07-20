package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumViewport;

/**
 * C2-3 D2 — the CAVE-CULLING CONSUMER: the cave-cull half of IP's
 * {@code MixinSodiumOcclusionCuller} (depth doc file #4, injector A — which read
 * {@code PortalRendering.shouldEnableSodiumCaveCulling()} DIRECTLY inside {@code findVisible};
 * sound on 0.6.0's render-thread-synchronous cull, wrong-thread/wrong-frame under 0.9.1's
 * async model) re-expressed as a snapshot read off the viewport duck (design §3.2).
 *
 * <p><b>Bind (javap, {@code sodium-mc26.2-0.9.1-fabric.jar}):</b>
 * {@code public void findVisible(OcclusionCuller$GraphOcclusionVisitor,
 * OcclusionCuller$GraphOcclusionVisitor, OcclusionCuller$VisibilityTestingVisitor, Viewport,
 * float, float, boolean, CancellationToken)} — the LONE boolean (useOcclusionCulling, arg
 * slot 7 of 8) makes the type-discriminated {@code @ModifyVariable(argsOnly=true)} unambiguous,
 * and the lone {@code Viewport} arg makes the {@code @Local} capture unambiguous.
 *
 * <p><b>Thread safety:</b> {@code findVisible}'s only jar caller is {@code CullTask.runTask}
 * (constant-pool scan over every class in the jar; the boolean is the CullTask's captured
 * {@code useOcclusionCulling} field, viewport its {@code protected final} by-reference
 * viewport) — i.e. this handler runs on the ASYNC CULL WORKER. It performs snapshot-only
 * reads: the duck field was published on the render thread before
 * {@code ExecutorService.submit} (the happens-before edge, census P4), is immutable after
 * publish, and no {@code PortalRendering}/{@code CHelper} state is touched here — that is the
 * entire point of D2.
 *
 * <p><b>Semantics:</b> override null (main/non-portal pass, or compat inactive) → sodium's
 * own value untouched; override non-null (portal pass) → AND-composed
 * ({@code original && override}), so IP's policy ({@code shouldEnableSodiumCaveCulling}:
 * cave-cull only within 5 blocks of a non-box portal — mis-culling things behind the dest is
 * the risk it bounds) can only TIGHTEN sodium's decision, never force occlusion culling on.
 * NOTE the deliberate main-pass deviation from IP file #4 (which forced the boolean to
 * {@code shouldEnableSodiumCaveCulling()}'s not-rendering FALSE for the outer world too):
 * the design's null-passthrough keeps sodium's own main-world occlusion culling — design
 * §3.2's specced consumer body, D2's per-pass-accuracy contract.
 *
 * <p>IP file #4's OTHER half (box-portal iteration-origin retarget + tolerant frustum) is
 * D2b DEFERRED-DORMANT — see {@link IESodiumViewport#ip_getModifiedIterationOrigin()}.
 *
 * <p>Registered in {@code seamlessportals-ip-compat.mixins.json}; the class name carries
 * {@code Sodium} for the plugin's substring gate.
 */
@Mixin(value = OcclusionCuller.class, remap = false)
public class MixinSodiumOcclusionCuller_CaveCull {

    @ModifyVariable(method = "findVisible", at = @At("HEAD"), argsOnly = true)
    private boolean ip_modifyUseOcclusionCulling(
        boolean original,
        @Local(argsOnly = true) Viewport viewport
    ) {
        @Nullable Boolean override =
            ((IESodiumViewport) (Object) viewport).ip_getUseOcclusionCullingOverride();
        return override != null ? (original && override) : original;
    }
}
