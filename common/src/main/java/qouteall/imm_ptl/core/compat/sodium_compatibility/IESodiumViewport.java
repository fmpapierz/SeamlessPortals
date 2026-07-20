package qouteall.imm_ptl.core.compat.sodium_compatibility;

import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;
import qouteall.q_misc_util.my_util.BoxPredicateF;

/**
 * C2-3 D2 — the viewport-carried culling snapshot duck (design {@code migration/C2_DESIGN.md}
 * §3.2), implemented by {@code MixinSodiumViewportExt} on Sodium 0.9.1's final
 * {@code net.caffeinemc.mods.sodium.client.render.viewport.Viewport}.
 *
 * <p>IP contract preserved (D2, design §5): Sodium's per-section frustum test is AND-ed with
 * IP's portal-aware predicate computed for exactly that pass's camera; cave culling follows
 * {@code PortalRendering.shouldEnableSodiumCaveCulling()}; shadow-pass inertness rides the
 * ported {@code FrustumCuller} gate set. The NAMED DEVIATION vs IP's render-thread static
 * ({@code SodiumInterface.frustumCuller}, written by IP file #6 / read by IP file #5): under
 * 0.9.1's async multi-visitor cull model the portal-derived decisions are SNAPSHOTTED on the
 * render thread at {@code setupTerrain} HEAD and CARRIED ON THE Viewport INSTANCE — one fresh
 * {@code Viewport} per pass ({@code ViewportProvider.sodium$createViewport()}), flowing BY
 * REFERENCE into the {@code CullTask} (census P4, STATICALLY SETTLED:
 * {@code AsyncRenderTask.<init>} does a direct {@code putfield} of setupTerrain's viewport,
 * no copy — no copy-site propagation needed). {@code ExecutorService.submit} inside
 * {@code RSM.scheduleAsyncWork} is the happens-before edge for the worker-thread readers.
 *
 * <p><b>Deliberately NOT hooked (design §3.2 / census correction #2)</b>:
 * {@code Viewport.isBoxVisibleLooser(III)} → {@code Frustum.testSectionExpanded(FFFF)} — the
 * {@code isWithinNearbySectionFrustum} path (census correction #2: it does NOT route through
 * {@code isBoxVisible(III)}/{@code testSection}). Hooking it with the un-expanded predicate
 * would OVER-CULL at aperture seams (the expanded margin exists precisely to keep nearby
 * sections); re-entry is perf-measured only, with expansion-adjusted AABBs (D-ledger watch
 * item "isBoxVisibleLooser unhooked").
 */
public interface IESodiumViewport {

    /**
     * The pass's portal-aware culling predicate, camera-relative float space —
     * {@code FrustumCuller}'s own {@code BoxPredicateF} (test returns TRUE = the box is
     * provably invisible → cull). Null = no portal culling this pass (culling lever off,
     * iris shadow pass, no cullable portal, or compat inactive) — consumers must fast-path
     * to sodium's own verdict.
     */
    @Nullable BoxPredicateF ip_getPortalCullPredicate();

    void ip_setPortalCullPredicate(@Nullable BoxPredicateF predicate);

    /**
     * Portal-pass cave-culling policy snapshot: non-null ONLY when
     * {@code PortalRendering.isRendering()} was true at the producer (the value =
     * {@code PortalRendering.shouldEnableSodiumCaveCulling()}). Null = main/non-portal pass —
     * the {@code findVisible} consumer leaves sodium's own {@code useOcclusionCulling}
     * untouched. AND-composed at the consumer ({@code original && override}) so the snapshot
     * can only tighten, never force-enable.
     */
    @Nullable Boolean ip_getUseOcclusionCullingOverride();

    void ip_setUseOcclusionCullingOverride(@Nullable Boolean override);

    /**
     * D2b — DORMANT (design §3.2 / stage C2-3 deliverables). IP file #4's box-portal
     * iteration-origin retarget ({@code getModifiedVisibleSectionIterationOrigin} seeding the
     * BFS start section) is a DEFERRED NAMED DEVIATION: its only upstream consumer is
     * {@code BoxPortalShape} behind {@code IPGlobal.boxPortalSpecialIteration} DEFAULT FALSE,
     * so default-config behavior is identical. This field is seeded null by every producer and
     * has NO consumer — it ledgers the re-entry surface (findVisible {@code getChunkCoord()}
     * redirect reading this origin + a per-viewport armed-once tolerant-frustum flag at the
     * now-private-static {@code isWithinFrustum} RETURN).
     */
    @Nullable SectionPos ip_getModifiedIterationOrigin();

    void ip_setModifiedIterationOrigin(@Nullable SectionPos origin);
}
