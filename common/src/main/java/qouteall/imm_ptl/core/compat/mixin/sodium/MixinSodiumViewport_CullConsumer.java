package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumViewport;
import qouteall.q_misc_util.my_util.BoxPredicateF;

/**
 * C2-3 D2 — the SECTION-TEST CONSUMER: the RE-EXPRESSION of IP's {@code MixinSodiumViewport}
 * (depth doc file #5: {@code @Redirect} of {@code Frustum.testAab(FFFFFF)} inside
 * {@code isBoxVisible}, AND-ing IP's portal predicate onto sodium's own frustum verdict).
 * IP's redirect point is GONE on 0.9.1 — {@code isBoxVisible(III)} now calls
 * {@code Frustum.testSection(FFF)} — so the same contract re-lands here (design §2 file #5
 * verdict; D2).
 *
 * <p><b>Bind (javap, {@code sodium-mc26.2-0.9.1-fabric.jar}):</b>
 * {@code Viewport.isBoxVisible(int,int,int)} converts each int arg to a camera-relative float
 * ({@code (arg − CameraTransform.intX) − fracX}, bytecode offsets 0-58) and at offset 70 does
 * {@code invokeinterface Frustum.testSection:(FFF)Z} — the wrapped call. The three ints are
 * the section CENTER (census P6a, STATICALLY SETTLED: both callers pass
 * {@code section.getCenterX/Y/Z()}), so the three floats are the CAMERA-RELATIVE SECTION
 * CENTER, and {@code center±8} reconstructs the exact 16³ section AABB in the SAME
 * camera-relative float space {@code FrustumCuller.canDetermineInvisibleWithCameraCoord}
 * (the {@link BoxPredicateF} the producer snapshotted) already takes — conservative-exact,
 * NO interface re-derivation (design §3.2).
 *
 * <p><b>{@code @WrapOperation}, NOT {@code @Redirect}</b> (design §0.2 / §3.2): iris also
 * mixes into sodium's culling classes — a Redirect would hard-conflict; WrapOperation chains.
 *
 * <p><b>Callers of {@code isBoxVisible(III)} (jar-enumerated, constant-pool scan + javap):</b>
 * {@code OcclusionCuller.isWithinFrustum} (the terrain-BFS gate — WORKER thread, inside
 * {@code CullTask.runTask}'s {@code findVisible} traversal) and
 * {@code SectionTree.isWithinFrustum} (tree-based render-list collection — render thread).
 * Both read only this viewport's immutable duck snapshot: same-thread for the collector,
 * submit-happens-before for the worker (see the producer's javadoc). The
 * {@code isBoxVisibleLooser(III)} → {@code testSectionExpanded(FFFF)} nearby-section path is
 * DELIBERATELY unhooked (census correction #2; over-cull risk at aperture seams — see
 * {@link IESodiumViewport}).
 *
 * <p><b>Semantics (IP file #5 preserved):</b> sodium invisible → invisible (we never widen);
 * sodium visible + no predicate → visible (the null fast path — culling lever off / main pass
 * with no cullable portal / compat inactive, all zero-cost); sodium visible + predicate says
 * {@code canDetermineInvisible} → CULLED. Mis-cull is worse than no-cull: the predicate is
 * IP's own conservative portal frustum math ({@code Frustum4Planes} fully-outside /
 * fully-behind tests), unchanged.
 *
 * <p>Registered in {@code seamlessportals-ip-compat.mixins.json}; the class name carries
 * {@code Sodium} for the plugin's substring gate.
 */
@Mixin(value = Viewport.class, remap = false)
public class MixinSodiumViewport_CullConsumer {

    @WrapOperation(
        method = "isBoxVisible(III)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/viewport/frustum/Frustum;testSection(FFF)Z"
        )
    )
    private boolean ip_wrapTestSection(
        Frustum frustum, float centerX, float centerY, float centerZ,
        Operation<Boolean> original
    ) {
        boolean visible = original.call(frustum, centerX, centerY, centerZ);
        if (!visible) {
            return false;
        }

        @Nullable BoxPredicateF predicate =
            ((IESodiumViewport) (Object) this).ip_getPortalCullPredicate();
        if (predicate == null) {
            return true;
        }

        // C2-3 lens-B CORRECTION (supersedes the design §3.2 "center±8" spec): sodium's OWN
        // testSection verdict is padded to CHUNK_SECTION_PADDED_RADIUS (9.125f — 8 + the 1.125
        // margin it reserves for overhanging geometry: fences, fluids, extended models). IP's
        // 0.6 predicate consumed sodium's own tested floats BY CONSTRUCTION; feeding a tighter
        // ±8 box here could portal-cull a section whose overhang is legitimately visible at
        // aperture/frustum seams — the "mis-cull is worse than no-cull" class. Padded-radius
        // parity = strictly more conservative (culls less), margin-identical to what sodium
        // itself just tested. Camera-relative floats (P6a).
        float r = net.caffeinemc.mods.sodium.client.render.viewport.Viewport.CHUNK_SECTION_PADDED_RADIUS;
        return !predicate.test(
            centerX - r, centerY - r, centerZ - r,
            centerX + r, centerY + r, centerZ + r
        );
    }
}
