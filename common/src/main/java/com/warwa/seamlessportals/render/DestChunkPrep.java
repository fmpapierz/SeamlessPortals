package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4fc;

/**
 * 26.3 SUBSTRATE — the destination pass's terrain prepare, in the SAME flavour vanilla's own {@code render()} picks.
 *
 * <p><b>What changed.</b> 26.2 had ONE {@code LevelRenderer.prepareChunkRenders(Matrix4fc)}; the dest passes call it
 * directly because they never run the dest renderer's {@code render()}. 26.3 has TWO, chosen per frame inside
 * {@code render()} (mc263-ref LevelRenderer.java:266-272):
 * <pre>
 *   this.usingMultiDrawIndirectForTerrain = this.multiDrawIndirectAvailable &amp;&amp; this.levelRenderState.shouldUseMultiDrawIndirectForTerrain;
 *   chunkSectionsToRender = this.usingMultiDrawIndirectForTerrain
 *       ? this.prepareChunkRendersIndirect(terrainMatrix, ..)   // -&gt; DrawIndirect, per-section data = INSTANCED vertex buffer (slot 1)
 *       : this.prepareChunkRenders(terrainMatrix, ..);          // -&gt; DrawSeparate, per-section data = one UBO slice per draw
 * </pre>
 * and the two are NOT free to mix within a frame: both write the per-section data through ONE frame-global storage,
 * {@code RenderSystem.getDynamicUniforms()}'s {@code chunkSections}, which exists in exactly one flavour at a time —
 * asking for the other flavour CLOSES the current storage on the spot and starts a new one at capacity 2
 * (mc263-ref DynamicGpuData.java:90-101 {@code writeChunkSections}, :103-114 {@code writeChunkSectionsInstanced}).
 * Same-flavour writers coexist safely (distinct blocks; a mid-frame resize keeps the old buffer alive until
 * {@code reset()} at frame end — DynamicGpuDataStorageMapped.java:40-59, Minecraft.java:1342).
 *
 * <p><b>The defect this replaces.</b> The first 26.3 port called {@code prepareChunkRenders(view, true)}
 * unconditionally. On any device with multi-draw-indirect (the main view's flavour there is INSTANCED) every dest pass
 * therefore closed the main view's prepared per-section buffer mid-frame, and the next frame's main prepare closed the
 * dest's in turn. Measured 2026-09-20 (crossing gametest, portals in view): ~1730 "Resizing Chunk Sections Instanced,
 * capacity limit of 2" + ~1730 "... UBO, capacity limit of 2" lines per run, against 12 in the run where the dest pass
 * never ran and 10 in a whole 26.2 session. On the classic-transparency slot that was only churn — the main view's
 * last terrain draw (translucent terrain) precedes the portal driver. On the improved-transparency slot
 * ({@link OitPathPortalSlot}: the driver runs BEFORE the main view's OIT terrain stages) it is fatal as soon as the main
 * view has any translucent terrain — the user's live crash:
 * {@code IllegalStateException: Vertex buffer at slot 1 has been closed!} at
 * {@code FrontendRenderPass.setVertexBuffer} &lt;- {@code ChunkSectionsToRender$DrawIndirect.render:109} &lt;-
 * {@code renderOit} &lt;- {@code LevelRenderer.executeOit:579}. (The gametest world is superflat with no translucent
 * terrain, so {@code DrawIndirect.render} never reached its {@code setVertexBuffer(1, ..)} there — fixture blindness.)
 *
 * <p><b>The port.</b> Evaluate vanilla's selection exactly, for the renderer being prepared:
 * {@code multiDrawIndirectAvailable} off the renderer itself (Sodium 0.9.2 forces that field false per instance to stay
 * on the branch it overwrites — see the accessor's note) AND {@code Minecraft.multiDrawIndirect}, the value
 * {@code LevelExtractor} copies into {@code shouldUseMultiDrawIndirectForTerrain} every frame (mc263-ref
 * LevelExtractor.java:230; read at the source because a decomposed dest pass does not necessarily run that extract
 * line on the state object the dest renderer holds). Every LevelRenderer of a session is built against the same
 * device, so this is the main view's flavour too and the shared storage is never flipped.
 * {@code respectTranslucentOrder} stays TRUE for the reason recorded at the call sites (the dest pass always draws
 * translucent terrain classically). {@code maxIndicesRequired} — the dest passes' "is there geometry" gate — lives on
 * the base class and is flavour-independent; the per-layer draw COUNTS that two diagnostics read are per-flavour and
 * are answered by {@link #countDraws}.
 */
public final class DestChunkPrep {

    private DestChunkPrep() {}

    /** Vanilla's {@code render()} chunk-prep selection (mc263-ref LevelRenderer.java:266-272), for {@code renderer}. */
    public static boolean usesMultiDrawIndirect(LevelRenderer renderer) {
        return ((LevelRendererAccessorMixin) renderer).seamlessportals$isMultiDrawIndirectAvailable()
            && Minecraft.getInstance().multiDrawIndirect;
    }

    /** Prepare the dest pass's terrain in the flavour vanilla's own {@code render()} would use this frame. */
    public static ChunkSectionsToRender prepare(LevelRenderer destRenderer, Matrix4fc destViewMatrix) {
        // A/B lever (-PdisableDestChunkPrepFlavour): the first 26.3 port's unconditional non-indirect prepare — the
        // storage flip-flop + the OIT-slot crash, on demand. Folded static final: byte-inert at the shipped default.
        if (qouteall.imm_ptl.core.IPGlobal.DEST_CHUNK_PREP_FLAVOUR_DISABLED_LEVER) {
            return destRenderer.prepareChunkRenders(destViewMatrix, true);
        }
        if (usesMultiDrawIndirect(destRenderer)) {
            return destRenderer.prepareChunkRendersIndirect(destViewMatrix, true);
        }
        return destRenderer.prepareChunkRenders(destViewMatrix, true);
    }

    /**
     * Draw count of one layer, whichever flavour {@code chunks} is: {@code DrawSeparate} holds a flat
     * {@code layer -> draws} map, {@code DrawIndirect} a {@code layer -> indirect groups} map whose groups each carry
     * {@code drawCount} section draws (mc263-ref ChunkSectionsToRender.java:96-149, LevelRenderer.java:936-944). The two
     * sums are the same number for the same visible set — both walk {@code extractSectionDrawGroups}' draw lists.
     *
     * @return the count, or {@code -1} for a type this method does not know (a mod's replacement, e.g. sodium's)
     */
    public static int countDraws(ChunkSectionsToRender chunks, net.minecraft.client.renderer.chunk.ChunkSectionLayer layer) {
        if (chunks instanceof ChunkSectionsToRender.DrawSeparate separate) {
            var draws = separate.drawsPerLayer.get(layer);
            return draws == null ? 0 : draws.size();
        }
        if (chunks instanceof ChunkSectionsToRender.DrawIndirect indirect) {
            var groups = indirect.drawGroupsPerLayer.get(layer);
            if (groups == null) {
                return 0;
            }
            int n = 0;
            for (var group : groups) {
                n += group.drawCount();
            }
            return n;
        }
        return -1;
    }
}
