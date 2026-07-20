package qouteall.imm_ptl.core.compat.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
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
 * <p><b>C2-3b SYNC-ONLY GUARD (the live-round artifact fix; port-note §3.14a):</b> the predicate
 * is consulted ONLY on the render thread. Bytecode ground truth (all javap, 0.9.1):
 * <ul>
 *   <li>the WORKER-side caller ({@code OcclusionCuller.isWithinFrustum}, inside
 *       {@code CullTask.runTask}'s {@code findVisible} on the "Sodium Async Cull Thread") gates
 *       ONLY the LOCAL ({@code RayOcclusionSectionTree}) tree's marking
 *       ({@code visitNode} offsets 179-198: distance-gated {@code isWithinFrustum} →
 *       {@code blockLocalIncoming()}; the BFS enqueue and the WIDE/REGULAR marks are
 *       frustum-free — {@code CullType.isFrustumTested} is true for LOCAL alone). A predicate
 *       applied there is BAKED into a CROSS-FRAME-PERSISTENT structure
 *       ({@code RSM.cullResults[LOCAL]}, reused whenever {@code SectionTree.isValidFor} passes)
 *       — IP 0.6's redirect ran on the render thread inside the same-pass synchronous
 *       {@code RSM.update} walk and NEVER contaminated a persistent structure; baking it async
 *       was an implicit deviation, and it is exactly what produced the C2-3b recursive-round
 *       flicker (portal-state-baked trees collected on later frames + per-context tree clocks
 *       = the desynced source/window flicker; see the port-note mechanism record);</li>
 *   <li>the per-frame RENDER-THREAD terrain path does NOT route through this wrap at all:
 *       {@code SectionTree.traverse} → {@code TraversableTree.traverse} tests
 *       {@code Viewport.getBoxIntersectionDirect(FFFF)}/{@code isBoxVisibleDirect(FFFF)}
 *       (unhooked by design), and {@code renderOutOfGraph}'s fallback likewise;</li>
 *   <li>the surviving render-thread callers of {@code isBoxVisible(III)} (C2-3b lens
 *       CORRECTED — this is NOT the entity chain, which routes tree-presence-only through
 *       {@code SectionTree.isBoxVisible(DDDDDD, NotInTreePredicate)} frustum-free): the
 *       jar-wide callers of {@code SectionTree.isSectionVisible} are
 *       {@code RSM.isSectionImmediatePresentationCandidate} (updateWithResult, 7 sites) and
 *       {@code RSM.submitImportantSectionTasks} — the chunk-REBUILD presentation/scheduling
 *       path. The predicate there is per-call-fresh (correct) and worst-case defers a
 *       rebuild's presentation by a frame; it culls no drawn terrain.</li>
 * </ul>
 * Net contract (supersedes the §3.11 wording): the async cull trees are PORTAL-AGNOSTIC
 * supersets (bigger, never wrong — vanilla-sodium-identical), and the portal predicate's
 * sodium-TERRAIN participation is ZERO IN BOTH LEVER STATES — the design's stated floor
 * ("culling is pure perf with an acceptable floor", C2_DESIGN §0/§3.2). NAMED DEVIATION
 * (lens-corrected wording): this does NOT restore IP's full sync semantics — IP's per-frame
 * sync redirect actively portal-culled sodium terrain; ours now culls none via the predicate.
 * What it restores is IP's SAFETY property (the predicate is never staler than the frame).
 * The ledgered PERF RE-ENTRY: a render-thread-only hook on the traverse Direct shortcuts
 * ({@code isBoxVisibleDirect}/{@code getBoxIntersectionDirect}) — per-frame-fresh by
 * construction, the honest place to win the FPS back. The A/B lever's discriminator is now
 * ARTIFACT PRESENCE, not FPS. Candidate (b) (producer-side outer-func exclusion only) was
 * REJECTED: it would keep the INNER predicate baked into the portal contexts' own async trees,
 * leaving the recursive window's observed desynced flicker in place.
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

        // C2-3b SYNC-ONLY GUARD (class javadoc): never bake the portal predicate into the
        // async-built persistent cull trees — on the "Sodium Async Cull Thread" (the only
        // non-render-thread caller: OcclusionCuller.isWithinFrustum inside findVisible) pass
        // sodium's own verdict through unchanged. Render-thread callers keep IP's AND-compose.
        if (!RenderSystem.isOnRenderThread()) {
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
