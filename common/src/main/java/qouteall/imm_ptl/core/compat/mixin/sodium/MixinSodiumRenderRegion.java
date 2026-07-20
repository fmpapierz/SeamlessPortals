package qouteall.imm_ptl.core.compat.mixin.sodium;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.Helper;

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
 * <p>The class name carries {@code Sodium} for the compat plugin's substring gate.
 */
@Mixin(value = RenderRegion.class, remap = false)
public abstract class MixinSodiumRenderRegion {

    @Shadow
    @Final
    private ChunkRenderList renderList;

    @Unique
    private @Nullable ObjectArrayList<ChunkRenderList> ip_chunkRenderListsForPortalRendering = null;

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

        int index = PortalRendering.getPortalLayer() - 1;

        return Helper.arrayListComputeIfAbsent(
            this.ip_chunkRenderListsForPortalRendering,
            index,
            () -> new ChunkRenderList((RenderRegion) (Object) this)
        );
    }
}
