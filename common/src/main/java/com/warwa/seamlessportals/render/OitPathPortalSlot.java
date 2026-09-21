package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.config.SeamlessPortalsConfig;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

/**
 * 26.3 SUBSTRATE — the mod's main-pass timing slots on vanilla's NEW improved-transparency (OIT) path.
 *
 * <p><b>What changed.</b> On 26.2 there was ONE main-pass lambda for both transparency modes (mc262-ref
 * LevelRenderer.java:408-440: opaque terrain, solid features, translucent features, outline, translucent terrain — with
 * "Fabulous" only changing WHICH target the translucent draws landed in), so Fabric's
 * {@code BEFORE/AFTER_TRANSLUCENT_TERRAIN} and NeoForge's {@code AfterTranslucentBlocks} fired in BOTH modes and the
 * portal driver ran in both. 26.3 forks the frame (mc263-ref LevelRenderer.java:442-460): the "Solid" pass, then EITHER
 * {@code executeClassicTransparency} OR — with Video Settings -> Improved Transparency ON — {@code executeOit}
 * (:551-638), which never draws a translucent-terrain group into the main target at all (it accumulates every
 * translucent into the OIT targets per {@code OitStage} and composites once at the end). Neither loader has a
 * translucent-terrain seam there: Fabric API's {@code wrapRenderTranslucentTerrain} targets
 * {@code executeClassicTransparency} ONLY and {@code executeOit} gets nothing but AFTER_TRANSLUCENT_FEATURES at RETURN
 * (javap fabric-rendering-v1 27.0.14 LevelRendererMixin); NeoForge posts {@code AfterTranslucentBlocks} in
 * {@code executeClassicTransparency} ONLY and {@code OitTranslucent} per stage inside the stage's own pass (NF-patched
 * 26.3 LevelRenderer.java:636, :731). So with the option ON every one of the mod's four main-pass handlers went
 * silent: no portal window, no seam band, no clip bracket, no seam clip — zero errors.
 *
 * <p><b>Measured</b> (2026-09-20, crossing gametest, one variable): {@code improvedTransparency:false} ->
 * TP-XDIM census {@code f1=YES} on 39/39 sampled frames, dest renders ran, windows on screen;
 * {@code improvedTransparency:true} -> {@code f1=NO} on 39/39, {@code invoke=NOT-CALLED} on 39/39, the same camera
 * shows bare source world. That is the user's live report on 26.3 ("nothing rendered in portal window ... source
 * visible like there is nothing there"), whose options.txt carries {@code improvedTransparency:true} from 26.2.
 *
 * <p><b>The port.</b> The same four handlers, in the same order the loaders run them on the classic path — Fabric's
 * two BEFORE_TRANSLUCENT_TERRAIN registrations, then its two AFTER_TRANSLUCENT_TERRAIN registrations
 * ({@code SeamlessPortalsClientFabric}; the NeoForge twins are {@code MixinLevelRenderer_ClipBracketMainPassNeoForge}
 * and the {@code AfterTranslucentBlocks} listeners) — at the HEAD of {@code executeOit}. That is the classic slot's
 * position in every respect that the handlers depend on:
 * <ul>
 *   <li>AFTER opaque terrain + the solid entity phases ({@code executeSolid}, inside the "Solid" pass), so the near
 *       halves and bracketed entities are depth-buffered before the stencil pass computes window visibility;</li>
 *   <li>BEFORE any translucent of the frame reaches the main target (the single "OIT Composite" draw at the end), and
 *       before the OIT stages depth-test against the main depth — so they test against the portal-plane depth that
 *       {@code restoreDepthOfPortalViewArea} leaves in every window: source translucents behind a portal are rejected,
 *       those in front composite over the destination image. That is the picture 26.2's depth-sorted transparency
 *       chain produced from the same inputs;</li>
 *   <li>PASS-FREE, as on 26.2: the "Solid" pass is closed before {@code executeOit} is invoked (javap 26.3
 *       {@code lambda$addMainPass$0}: {@code RenderPass.close} @197, {@code executeOit} @242), so no
 *       {@link MainPassSplit} is needed here.</li>
 * </ul>
 * One common-side seam serves every loader ({@code executeOit(ChunkSectionsToRender, PreparedFrame)V} is identical in
 * the Fabric merged, NeoForge-patched and Forge 26.3 jars). It cannot double-drive: {@code executeOit} and
 * {@code executeClassicTransparency} are mutually exclusive per frame, and dest passes never reach either (no
 * framegraph runs there, and {@code MixinGameRenderState} forces improved transparency OFF while a portal view renders).
 *
 * <p>The handler bodies are the loaders' bodies VERBATIM (the NF-PARITY precedent: the NeoForge class already
 * byte-mirrors the Fabric driver rather than sharing it). Wired by
 * {@code com.warwa.seamlessportals.mixin.client.LevelRendererOitPortalSlotMixin}.
 */
public final class OitPathPortalSlot {

    private OitPathPortalSlot() {}

    /** {@code LevelRenderer.executeOit} HEAD — see the class note. */
    public static void onExecuteOitHead() {
        if (SeamlessPortalsConfig.isEntityPortals()) {
            runEntityPortalSlot();
        } else {
            runBlockEraSlot();
        }
    }

    /**
     * The AFTER_TRANSLUCENT_TERRAIN half alone (flag-ON: portal driver, then band painter; flag-OFF: the block-era
     * driver), for a loader with NO event at that slot. MinecraftForge 26.3-66.0.2 is one: its patched LevelRenderer
     * posts nothing inside {@code executeSolid} / {@code executeClassicTransparency} / {@code executeOit} (javap -c:
     * the only {@code net.minecraftforge} references in the class are {@code FramePassManager.insertForgePasses} in
     * {@code render} and the block-outline callback) and the jar has no {@code RenderLevelStageEvent} class at all —
     * its one level-render extension point, {@code AddFramePassEvent}, adds whole frame-graph passes AFTER vanilla's.
     * Driven on the classic fork by {@code LevelRendererForgeAfterTranslucentSlotMixin}; the BEFORE half rides
     * {@code MixinLevelRenderer_ClipBracketMainPassNeoForge}, which the mixin plugin also applies on Forge.
     */
    public static void onAfterTranslucentTerrainWithoutLoaderEvent() {
        if (SeamlessPortalsConfig.isEntityPortals()) {
            runEntityPortalDriver();
            qouteall.imm_ptl.core.render.SeamBandPainter.onAfterPortalPasses();
        } else {
            runBlockEraSlot();
        }
    }

    /** Flag-ON: Fabric's BEFORE_TRANSLUCENT_TERRAIN pair, then its AFTER_TRANSLUCENT_TERRAIN pair, registration order. */
    private static void runEntityPortalSlot() {
        // ===== S18: Mechanism-B main-pass draw site (first BEFORE_TRANSLUCENT_TERRAIN registration) =====
        qouteall.imm_ptl.core.render.PerEntityClipBracket.onMainPassBeforeTranslucentTerrain();
        // ===== SEAM CLIP main-pass draw site (second BEFORE_TRANSLUCENT_TERRAIN registration) =====
        com.warwa.seamlessportals.render.SeamClipRenderer.onMainPassBeforeTranslucentTerrain();

        // ===== WIRE 3: flag-ON render DISPATCH (first AFTER_TRANSLUCENT_TERRAIN registration) =====
        // Byte-mirrors SeamlessPortalsClientFabric's driver, including the census witness and the re-entrancy
        // guard (LOAD-BEARING there; defensive here — see the class note on why no nested render reaches this slot).
        runEntityPortalDriver();

        // ===== ENGINE STAGE 2b — the band painter's hook (second AFTER_TRANSLUCENT_TERRAIN registration): runs
        // AFTER every portal pass of the frame, EVERY frame, regardless of whether any pass executed. =====
        qouteall.imm_ptl.core.render.SeamBandPainter.onAfterPortalPasses();
    }

    private static void runEntityPortalDriver() {
        com.warwa.seamlessportals.render.TpXdimFrameCensus.noteF1Driver("flagON");
        if (PortalRendering.isRendering()
            || qouteall.imm_ptl.core.render.CrossPortalViewRendering
                .isRenderingCrossPortalView()
        ) {
            com.warwa.seamlessportals.render.TpXdimFrameCensus
                .noteF1Driver("skipped-reentrant");
            return;
        }
        Minecraft client = Minecraft.getInstance();
        // Defensive copy — FrontClipping/ViewAreaRenderer must not mutate the live
        // cameraRenderState matrix (same read + copy as the Fabric driver).
        Matrix4f modelView = new Matrix4f(
            client.gameRenderer.gameRenderState().levelRenderState
                .cameraRenderState.viewRotationMatrix);
        PortalRenderer.switchToCorrectRenderer();
        IPCGlobal.renderer.prepareRendering();
        IPCGlobal.renderer.onBeforeTranslucentRendering(modelView);
        IPCGlobal.renderer.finishRendering();
    }

    /** Flag-OFF: the block-era driver's single AFTER_TRANSLUCENT_TERRAIN registration. */
    private static void runBlockEraSlot() {
        com.warwa.seamlessportals.render.TpXdimFrameCensus.noteF1Driver("stencil");
        StencilPortalRenderer.renderPortals();
    }
}
