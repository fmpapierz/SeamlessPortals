package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.render.PortalContextSwitch;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes entities render in the portal destination view.
 *
 * <p>{@code LevelExtractor.extractVisibleEntities} keeps an entity only if
 * {@code isEntityVisible} → {@code LevelRenderer.isSectionCompiledAndVisible(pos)}
 * is true (LevelExtractor.java:257). That method (LevelRenderer.java:897) returns
 * {@code renderSection.getVisibility(now) >= 0.3F}, and
 * {@code getVisibility(now) = (now - uploadedTime) / fadeDuration}
 * (SectionRenderDispatcher.java:221-223) — a cosmetic fade-in measured from when
 * the section's mesh was last UPLOADED.
 *
 * <p>The portal secondary renderer compiles/uploads its dest sections on demand
 * every portal frame and drives visibility through the mod's own direct frustum
 * cull ({@code populateVisibleSectionsByFrustum}), bypassing the occlusion graph.
 * So its sections' {@code uploadedTime} is always "just now" → {@code getVisibility
 * < 0.3} → {@code isSectionCompiledAndVisible} is false → EVERY dest entity is
 * culled (proven: log {@code DIAG-RENDER destLevel.entities=28
 * extracted.entityRenderStates=0}). Terrain still renders because the terrain
 * path does not consult {@code getVisibility}.
 *
 * <p>The fade gate is meaningless for the portal view (there is no occlusion-graph
 * fade-in to wait on — the direct cull already decided what is visible this frame),
 * so while {@link PortalContextSwitch#isRenderingPortal} we report sections as
 * visible and let the entity's own frustum/distance cull
 * ({@code EntityRenderDispatcher.shouldRender}, the other half of
 * {@code isEntityVisible}) decide. This only affects the secondary render: the
 * phase-1 portal render runs at {@code GameRenderer.renderLevel} HEAD with
 * {@code isRenderingPortal == true}; it is cleared before the main render.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererEntityVisibilityMixin {

    /**
     * ★ CART ROUND 2 DIAGNOSTIC (log-only): the crossing cart vanished from the MAIN pass with
     * zero probe lines — every module gate logs, so the cull is vanilla's own extraction term
     * ({@code shouldRender && (outsideBuildHeight || isSectionCompiledAndVisible)}). This logs
     * the MAIN-pass verdict of THIS gate for seam sections, so one live run attributes the
     * vanish to the section gate (compiled/fade) or exonerates it (leaving shouldRender).
     */
    @Inject(method = "isSectionCompiledAndVisible", at = @At("RETURN"))
    private void seamlessportals$mainPassSeamSectionVerdict(
            BlockPos blockPos, CallbackInfoReturnable<Boolean> cir) {
        if (!com.warwa.seamlessportals.passthrough.AperturePassthroughLever.SEAM_CART_PROBE
            || PortalContextSwitch.isRenderingPortal
            || qouteall.imm_ptl.core.render.SecondaryWorldRenderCore.isDestExtracting) {
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null
            || !com.warwa.seamlessportals.passthrough.SeamRegistry
                .sectionHasSeam(mc.level, blockPos)) {
            return;
        }
        com.warwa.seamlessportals.render.CartWindowProbe.onMainGate(
            blockPos, cir.getReturnValueZ());
    }

    @Inject(method = "isSectionCompiledAndVisible", at = @At("HEAD"), cancellable = true)
    private void seamlessportals$showEntitiesInPortalView(
            BlockPos blockPos, CallbackInfoReturnable<Boolean> cir) {
        // Block-era key (flag OFF) OR the flag-ON dest-extract bracket (S15): the ported IP
        // path's extracts run under SecondaryWorldRenderCore.isDestExtracting and need the same
        // fade-gate bypass this mixin has always given the block-era path — a portal pass's
        // sections are compiled/uploaded on demand, so the uploadedTime fade would cull entities
        // in fresh sections (cross-dim: brief post-crossing pops; same-dim loop-back: the
        // S15 entity pass). Layer-independent; main-pass extracts see neither flag.
        //
        // S15 verify fold (wf_b11fbd6f-f8a): bypass ONLY the fade term, PRESERVE vanilla's
        // compiled-section gate — IP's exact shape (MixinLevelRenderer.ip_isChunkCompiled:
        // rawGet + compiled != UNCOMPILED; a blanket forced-true would draw entities floating
        // against the fog fill where terrain has not compiled, which IP culls). The block-era
        // path is unaffected: its on-demand-compiled sections pass the compiled gate and only
        // ever lost entities to the fade term.
        if (PortalContextSwitch.isRenderingPortal
            || qouteall.imm_ptl.core.render.SecondaryWorldRenderCore.isDestExtracting) {
            // C2-1e (dest entities invisible under ACTIVE sodium — live-round finding
            // 2026-07-19; mechanism javap-proven): under Sodium the renderer's viewArea field
            // is sodium's IgnoringViewArea (installed by LevelRendererMixin.sodium$replace),
            // whose getRenderSectionAt(BlockPos) returns null UNCONDITIONALLY (0.9.1 javap:
            // aconst_null/areturn) — so the compiled-gate read below evaluated FALSE for every
            // BlockPos, isSectionCompiledAndVisible returned false, and
            // LevelExtractor.isEntityVisible (shouldRender && (isOutsideBuildHeight ||
            // isSectionCompiledAndVisible), mc262-ref :251-259) culled EVERY in-build-height
            // dest entity during the Step-5 dest extract. Fix = IP sodium-compat file #2's
            // exact semantics ported to this consumer ("The section visibility information
            // will be wrong if rendered a portal. Just cancel this optimization." — IP forced
            // its 0.6.0 isSectionVisible seam TRUE during portal frames; on 0.9.1 that one
            // seam split in two: sodium's own EntityRenderer.shouldRender wrap →
            // RSM.isBoxVisible is covered by the D5 neutralize in
            // MixinSodiumRenderSectionManager, and THIS vanilla extract gate is the other
            // half): force visible and let the entity's own frustum/distance cull
            // (EntityRenderDispatcher.shouldRender — the other conjunct of isEntityVisible,
            // running against the dest frustum) decide. Scope is TIGHTER than IP's
            // portalsRenderedThisFrame != 0 — isSodiumPresent() is true only for the ACTIVE
            // invoker (flag-ON + gate/lever + !iris; feed-only/base report false and keep the
            // yielded-empty envelope), and this branch is reached only inside dest extracts —
            // the main extract keeps sodium's honest isSectionReady culling. Deliberately NOT
            // a fall-through into sodium's @Overwrite (SWR.isSectionReady →
            // RSM.isSectionBuilt): that body is null-guard-free on renderSectionManager
            // (javap), so the BLOCKER-1b null-RSM degrade state would NPE mid-extract.
            if (qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface
                .invoker.isSodiumPresent()) {
                // §2b probe: count the C2-1e neutralize firing (nv in the [ENT-PROBE] line).
                if (qouteall.imm_ptl.core.render.EntityVisibilityProbe.ENABLED) {
                    qouteall.imm_ptl.core.render.EntityVisibilityProbe.neutralizeVanilla++;
                }
                cir.setReturnValue(true);
                return;
            }
            net.minecraft.client.renderer.ViewArea viewArea =
                ((LevelRendererAccessorMixin) this).seamlessportals$getViewArea();
            if (viewArea == null) {
                cir.setReturnValue(false);
                return;
            }

            // (e) DEFECT A — THE SAME-DIM WINDOW LOSES ITS ENTITIES AT ANY REAL DISTANCE.
            //
            // The compiled-section term above is right to keep; the LOOKUP it was using was not.
            // ViewArea.getRenderSectionAt(BlockPos) on ImmPtlViewArea (:496) applies
            // positiveModulo into the CURRENT PRESET array and has NO exact-node guard — the
            // hazard that file's own getRenderSection(long) already documents at :551-553
            // ("getRenderSectionAt (BlockPos-keyed) shares the wrap hazard — ledgered for the S20
            // audit, not changed here"). The preset is re-centred on the dest camera for CROSS-DIM
            // ONLY (SecondaryWorldRenderCore:663 gates repositionCamera on !sharedState, because
            // same-dim shares the main renderer's grid and moving it would corrupt the main
            // frame). So for a SAME-DIM destination outside ±renderDistance chunks of the player —
            // the user's 40 km pair — every query aliased onto an unrelated section near the
            // player, which is normally UNCOMPILED, and LevelExtractor.isEntityVisible culled
            // EVERY entity in the window. Window terrain was unaffected the whole time because
            // VisibleSectionDiscovery reads through ImmPtlViewArea.rawFetch, which is unbounded —
            // hence "terrain fine, cart gone", the user's exact 2026-07-28 report.
            //
            // Fix: resolve by EXACT coordinates through the unbounded coord-pinned column map.
            // That is IP's ip_isChunkCompiled shape, which THIS METHOD'S OWN comment above already
            // cites (rawGet + compiled != UNCOMPILED) — the code had simply drifted from it.
            // ⚠ NOT the same as adding the exact-node guard to getRenderSectionAt: that returns
            // null out of window, the gate answers false, and the entity stays culled. The read
            // has to reach the UNBOUNDED map, not merely be honest about the bounded one.
            boolean useExact =
                !com.warwa.seamlessportals.passthrough.AperturePassthroughLever
                    .DISABLE_DEST_ENTITY_SECTION_EXACT
                && viewArea instanceof qouteall.imm_ptl.core.render.ImmPtlViewArea;
            boolean probe = com.warwa.seamlessportals.render.CartWindowProbe.armed();

            net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection exact = null;
            net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection wrap = null;
            if ((useExact || probe)
                && viewArea instanceof qouteall.imm_ptl.core.render.ImmPtlViewArea ipViewArea) {
                exact = ipViewArea.rawGet(
                    net.minecraft.core.SectionPos.blockToSectionCoord(blockPos.getX()),
                    net.minecraft.core.SectionPos.blockToSectionCoord(blockPos.getY()),
                    net.minecraft.core.SectionPos.blockToSectionCoord(blockPos.getZ()));
            }
            if (!useExact || probe) {
                wrap = viewArea.getRenderSectionAt(blockPos);
            }

            net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection section =
                useExact ? exact : wrap;
            boolean verdict = section != null
                && section.getSectionMesh()
                    != net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED;

            if (probe) {
                com.warwa.seamlessportals.render.CartWindowProbe.onDestGate(
                    blockPos, wrap, exact,
                    wrap != null && wrap.getSectionMesh()
                        != net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED,
                    exact != null && exact.getSectionMesh()
                        != net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED,
                    useExact);
            }

            cir.setReturnValue(verdict);
        }
    }
}
