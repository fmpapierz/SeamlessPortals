package qouteall.imm_ptl.core.compat.mixin.sodium;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.Helper;

import java.util.HashMap;
import java.util.Map;

/**
 * C2-1 #3 — per-portal-layer {@code ChunkRenderList} isolation on {@code RenderRegion}
 * (IP depth doc file #3, design §3.1.4). Ships UNCONDITIONALLY for same-dim portals per the C2-0
 * verify correction (port-note {@code migration/port-notes/C2-sodium-iris.md} §4.2): the reset
 * trigger is a value-INEQUALITY test at collection time
 * ({@code VisibleChunkCollector.visit: getLastVisibleFrame() != frame → reset(frame)}) — it fires
 * under ANY frame arrangement (the D1 frame swap does NOT neutralise it) and the renderOutOfGraph
 * sync fallback fires it too. P5 measures the artifact; it no longer decides ship/no-ship.
 *
 * <p>IP quote (file #3): "When rendering the world in portal (to-same-world portal), the frame
 * counter increases, then in SortedRenderLists.Builder#add(RenderSection) it will reset the
 * ChunkRenderList, which makes upcoming transparent block rendering in outer world to break. So
 * use separate ChunkRenderList for each portal rendering layer."
 *
 * <p>javap evidence ({@code sodium-mc26.2-0.9.1-fabric.jar}): target method
 * {@code public ChunkRenderList getRenderList()} EXISTS; shadowed field
 * {@code private final ChunkRenderList renderList}; the ctor
 * {@code public ChunkRenderList(RenderRegion)} EXISTS-IDENTICAL. Layer consistency across a
 * pass's extract+submit holds because {@code isRendering()/getPortalLayer()} are stable inside
 * the MyGameRenderer bracket (design V1), and the draw path iterates the SortedRenderLists
 * SNAPSHOT (census P12) — the per-layer hazard is confined to the collection phase this
 * overwrite serves.
 *
 * <p><b>C2-1 SAME-DIM FLASH FIX (2026-07-21) — THE MISSING HALF: per-portal-layer
 * {@code MultiDrawBatch} isolation.</b> The list isolation above fixed list MEMBERSHIP, but the
 * shaders-off/on same-dim flicker (SOURCE terrain beyond the standing chunk vanishes on pan/move,
 * only with a same-dim portal in view) is a distinct, deeper leak on the SAME shared region: the
 * per-region DRAW-COMMAND cache {@code RenderRegion.cachedBatches}
 * ({@code Map<TerrainRenderPass,MultiDrawBatch>}, javap field #41, keyed by PASS ONLY), gated by
 * {@code MultiDrawBatch.isFilled}. It is NOT in the D1 swap set, and the per-layer
 * {@link ChunkRenderList} above is built {@code new ChunkRenderList((RenderRegion)(Object)this)} on
 * the SAME region, so {@code getRegion().getCachedBatch(pass)} resolves to the SAME shared batch.
 * Bytecode chain (all one shared same-dim RSM): {@code DefaultChunkRenderer.render} phase-1
 * {@code getCachedBatch(pass)} @120, {@code if (!batch.isFilled)} @128-133 fills from the PASSED
 * list @136-151 and sets {@code isFilled=true} @0-2; phase-2 re-fetches the SAME batch @419-425 and
 * {@code batch.draw()} @481. The same-dim portal terrain draw ({@code ip_armDestChunkRenders} →
 * {@code SWR.drawChunkLayer} → this same {@code DefaultChunkRenderer.render}) fills the shared batch
 * with its NARROWER through-portal subset and sets {@code isFilled=true}; the later MAIN draw finds
 * {@code isFilled==true}, SKIPS its refill, and draws the portal subset — every section main-visible
 * in the region but outside the dest frustum is absent from the reused batch and is NOT emitted =
 * TRUE VANISH (its mesh is still resident; it drew last frame). {@code isFilled} is reset ONLY by
 * {@code MultiDrawBatch.clear()} via {@code clearAllCachedBatches}/{@code clearCachedBatchFor}, and
 * none of those run between the portal draw and the later main draw — hence the persistent vanish.
 *
 * <p><b>Fix (this class):</b> give each portal recursion LAYER its own per-region
 * {@code MultiDrawBatch}, indexed by {@code getPortalLayer()-1} exactly like the list above, via a
 * {@code getCachedBatch} HEAD-cancellable redirect. The MAIN draw runs with
 * {@code isRendering()==false} and gets the region-own batch, which the portal never touched, so it
 * refills from the correct main list. ORDER-INDEPENDENT: the portal batch and main batch are simply
 * never the same instance — no reliance on who-draws-first (the concern all three panel judges
 * flagged) and no reliance on the main draw self-healing. The three invalidation hooks
 * ({@code clearAllCachedBatches}/{@code clearCachedBatchFor}/{@code delete}) keep the per-layer
 * batches a strict SUPERSET of sodium's own invalidation (never stale) and leak-free (the abstract
 * {@code MultiDrawBatch.delete()} frees native memory). Cross-dim is a no-op-equivalent (its
 * separate per-dim regions never alias the main RSM). Lever-gated
 * ({@link IPGlobal#isPortalBatchIsolationActive()}), DEFAULT ON, behaviorally identical to vanilla
 * sodium when off (the woven getCachedBatch HEAD-guard still runs but self-skips to the vanilla body,
 * returning the region-own batch; hooks 2-4 no-op on the null field — identical return values + state).
 *
 * <p>The class name carries {@code Sodium} for the compat plugin's substring gate.
 */
@Mixin(value = RenderRegion.class, remap = false)
public abstract class MixinSodiumRenderRegion
    implements qouteall.imm_ptl.core.compat.sodium_compatibility.IPPortalLayerBatchClearable {

    @Shadow
    @Final
    private ChunkRenderList renderList;

    @Unique
    private @Nullable ObjectArrayList<ChunkRenderList> ip_chunkRenderListsForPortalRendering = null;

    /**
     * C2-1 SAME-DIM FLASH FIX — per-portal-layer draw-command batch cache, indexed by
     * {@code getPortalLayer()-1} exactly like {@link #ip_chunkRenderListsForPortalRendering}. Each
     * layer owns a {@code Map<TerrainRenderPass,MultiDrawBatch>} mirroring the region-own
     * {@code cachedBatches}. Entries may be null (the helper pads the list) — every consumer guards.
     */
    @Unique
    private @Nullable ObjectArrayList<Map<TerrainRenderPass, MultiDrawBatch>>
        ip_cachedBatchesForPortalRendering = null;

    /**
     * @author qouteall (IP file #3, 1:1 mechanism; C2-1 retarget to Sodium 0.9.1)
     * @reason each portal recursion layer must own a distinct ChunkRenderList, otherwise the
     * frame-counter reset fired by a portal pass's collection over SHARED regions blows away the
     * outer world's list before its transparent pass consumes it (same-dim portals). A plain
     * inject cannot express "return a different object per layer" — full replacement, IP's own
     * form. Vanilla behavior is bit-identical outside portal rendering (first branch).
     */
    @Overwrite
    public ChunkRenderList getRenderList() {
        if (!PortalRendering.isRendering()) {
            return this.renderList;
        }

        if (this.ip_chunkRenderListsForPortalRendering == null) {
            this.ip_chunkRenderListsForPortalRendering = new ObjectArrayList<>();
        }

        // IS5-W FIX 2: the layer key now splits by shadow-vs-camera scope (PortalScopeKey —
        // even slot = camera, odd = shadow), restoring iris's two-scope separation that this
        // overwrite bypasses (it never consults iris's swapped region fields). Lever-off returns
        // exactly the pre-fix layer-1. The outer !isRendering() gate above is UNCHANGED.
        int index = qouteall.imm_ptl.core.compat.sodium_compatibility.PortalScopeKey.index();

        ChunkRenderList layerList = Helper.arrayListComputeIfAbsent(
            this.ip_chunkRenderListsForPortalRendering,
            index,
            () -> new ChunkRenderList((RenderRegion) (Object) this)
        );
        // IS5-W shadow-alias probe feed (LOG-ONLY; static-final lever guard, dead when off). The
        // theory-restart adjudicated THIS overwrite as the scope collapse: it returns the SAME
        // layer list to iris's dest SHADOW scope and the dest CAMERA scope. The probe records
        // which scope touched which list object (identity + lastVisibleFrame at emit).
        if (com.warwa.seamlessportals.render.ShadowAliasProbe.ENABLED) {
            com.warwa.seamlessportals.render.ShadowAliasProbe.onLayerListAccess(this, layerList, index);
        }
        return layerList;
    }

    // ===== C2-1 SAME-DIM FLASH FIX — per-portal-layer MultiDrawBatch isolation (4 hooks) =========

    /**
     * HOOK 1 (the linchpin) — redirect {@code getCachedBatch} to a per-portal-LAYER batch while a
     * portal is rendering, so the portal terrain draw never fills the shared region-own batch that
     * the main draw reuses. Outside portal rendering (main path) the injection self-skips and the
     * vanilla body returns the region-own batch (behaviorally identical to vanilla sodium). The
     * lazily-created layer batch replicates vanilla {@code getCachedBatch}'s size formula EXACTLY
     * (javap offsets 20-29: {@code ModelQuadFacing.COUNT * 256 + 1}).
     */
    @Inject(method = "getCachedBatch", at = @At("HEAD"), cancellable = true)
    private void ip_getCachedBatch(TerrainRenderPass pass, CallbackInfoReturnable<MultiDrawBatch> cir) {
        if (!IPGlobal.isPortalBatchIsolationActive() || !PortalRendering.isRendering()) {
            return; // vanilla body runs -> region-own (main) batch
        }

        if (this.ip_cachedBatchesForPortalRendering == null) {
            this.ip_cachedBatchesForPortalRendering = new ObjectArrayList<>();
        }

        // IS5-W FIX 2: scope-split key (see getRenderList). The outer lever+isRendering gate
        // above is UNCHANGED (the asymmetric-gates discipline — the helper adds ONLY the scope bit).
        int index = qouteall.imm_ptl.core.compat.sodium_compatibility.PortalScopeKey.index();
        Map<TerrainRenderPass, MultiDrawBatch> layerMap = Helper.arrayListComputeIfAbsent(
            this.ip_cachedBatchesForPortalRendering, index, HashMap::new
        );

        MultiDrawBatch batch = layerMap.get(pass);
        boolean created = batch == null;
        if (created) {
            batch = MultiDrawBatch.newBatch(ModelQuadFacing.COUNT * 256 + 1);
            layerMap.put(pass, batch);
        }

        // IS5-W shadow-alias probe feed (LOG-ONLY; static-final lever guard, dead when off). The
        // theory-restart adjudicated THIS hand-out as the batch half of the scope collapse: both
        // the dest SHADOW and dest CAMERA scopes receive the SAME layer batch, isFilled-gated. The
        // probe tags fills {passSerial, scope} and classifies reuses (cross-scope = malignant).
        // isFilled is read BEFORE the consumer's fill decision — the on-entry state.
        if (com.warwa.seamlessportals.render.ShadowAliasProbe.ENABLED) {
            com.warwa.seamlessportals.render.ShadowAliasProbe.onLayerBatchAccess(
                batch, created, batch.isFilled, index);
        }

        IPGlobal.portalBatchIsolationRedirectCount++;
        cir.setReturnValue(batch);
    }

    /**
     * HOOK 2 — mirror sodium's own {@code clearAllCachedBatches} invalidation onto the per-layer
     * batches (RETURN so the region-own clear runs first). Keeps the per-layer batches a strict
     * SUPERSET of sodium's invalidation: a batch is never staler than the region-own one.
     */
    @Inject(method = "clearAllCachedBatches", at = @At("RETURN"))
    private void ip_clearAllCachedBatches(CallbackInfo ci) {
        if (this.ip_cachedBatchesForPortalRendering == null) {
            return;
        }
        for (Map<TerrainRenderPass, MultiDrawBatch> layerMap : this.ip_cachedBatchesForPortalRendering) {
            if (layerMap != null) {
                for (MultiDrawBatch batch : layerMap.values()) {
                    batch.clear();
                }
            }
        }
    }

    /**
     * HOOK 3 — mirror sodium's per-pass {@code clearCachedBatchFor} invalidation (fired on section
     * rebuild / region buffer resize) onto the per-layer batches, so a portal-view batch can never
     * draw stale storage pointers after that pass's geometry moved.
     */
    @Inject(method = "clearCachedBatchFor", at = @At("RETURN"))
    private void ip_clearCachedBatchFor(TerrainRenderPass pass, CallbackInfo ci) {
        if (this.ip_cachedBatchesForPortalRendering == null) {
            return;
        }
        for (Map<TerrainRenderPass, MultiDrawBatch> layerMap : this.ip_cachedBatchesForPortalRendering) {
            if (layerMap != null) {
                MultiDrawBatch batch = layerMap.get(pass);
                if (batch != null) {
                    batch.clear();
                }
            }
        }
    }

    /**
     * IS5-W FIX 3 (leg d) — the duck body: clear every per-layer batch after a REAL mesh upload.
     * Called by {@code MixinSodiumRenderRegionManager_PortalBatchClear} at the private
     * {@code uploadResults(RenderRegion,...)} RETURN, because under iris the vanilla
     * {@code clearAllCachedBatches} call HOOK 2 mirrors is {@code @Redirect}ed away
     * ({@code iris$forceClearAllBatches}) and HOOK 2 never fires on uploads — leaving layer
     * batches holding stale draw commands after region storage re-uploads. First-statement
     * null early-out keeps the non-portal path cost-identical; the shared FIX-2 lever gates it
     * (lever-off = exactly the pre-fix behavior). Clears only — HOOK 4 owns the delete.
     */
    @Override
    public void ip_clearPortalLayerBatches() {
        if (this.ip_cachedBatchesForPortalRendering == null) {
            return;
        }
        if (!IPGlobal.isShadowScopeIsolationActive()) {
            return;
        }
        for (Map<TerrainRenderPass, MultiDrawBatch> layerMap : this.ip_cachedBatchesForPortalRendering) {
            if (layerMap != null) {
                for (MultiDrawBatch batch : layerMap.values()) {
                    batch.clear();
                }
            }
        }
    }

    /**
     * HOOK 4 — leak-freedom. The abstract {@code MultiDrawBatch.delete()} frees native (GL/VK) memory;
     * vanilla {@code delete()} frees only the region-own map, so the per-layer batches must be freed
     * here (region lifetime) and the list dropped. RETURN so the region's own teardown runs first.
     */
    @Inject(method = "delete", at = @At("RETURN"))
    private void ip_delete(CallbackInfo ci) {
        if (this.ip_cachedBatchesForPortalRendering == null) {
            return;
        }
        for (Map<TerrainRenderPass, MultiDrawBatch> layerMap : this.ip_cachedBatchesForPortalRendering) {
            if (layerMap != null) {
                for (MultiDrawBatch batch : layerMap.values()) {
                    batch.delete();
                }
            }
        }
        this.ip_cachedBatchesForPortalRendering = null;
    }
}
