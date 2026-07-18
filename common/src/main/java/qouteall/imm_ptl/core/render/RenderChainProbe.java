package qouteall.imm_ptl.core.render;

import com.warwa.seamlessportals.mixin.client.ClientLevelExtractorAccessor;
import com.warwa.seamlessportals.mixin.client.LevelExtractorAccessor;
import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import com.warwa.seamlessportals.mixin.client.SectionOcclusionGraphAccessorMixin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S14.42 — the post-promote render-chain probe (NO-GUESSING instrumentation for the far-walk
 * terrain-wipe defect; S20-removal-ledgered).
 *
 * <p><b>The defect it exists to attribute:</b> after the player walks far from a portal (loaders
 * collapse), returns (portal view fine), and crosses — the promoted dim draws NO terrain (entities
 * only) until relog, DESPITE the warm promote logging. No log trace exists at the wipe moment; this
 * probe supplies the discriminating numbers.
 *
 * <p><b>Cadence discipline</b> (memory render-thread-logging-log4j-stall): auto-armed by every
 * promote for {@link #ARMED_FRAMES} frames but rate-limited to 1 line/second — the same budget as
 * the S14.21 resolver landing log. The {@code debug_dump_render_chain} switch fires ONE
 * unconditional line on demand (for the user to run WHILE wiped, hours after the promote window).
 *
 * <p><b>The one-line format discriminates the candidate classes:</b>
 * <ul>
 *   <li>{@code visSec} (visibleSections count) vs {@code rendered} (sections with renderable
 *       meshes): empty-visibleSections (SOG/frustum class) vs sections-without-meshes (compile
 *       class) vs both-fine (draw/identity class).</li>
 *   <li>{@code extOK/lrsOK/renOK} identity coherences: the extractor-orphan class (memory
 *       nether-block-freeze-orphaned-extractor) — mc.levelExtractor must BE the level's extractor,
 *       its LRS must BE the shared game LRS AND the renderer's, its renderer must BE
 *       mc.levelRenderer.</li>
 *   <li>{@code applyF} (applyFrustum firings since promote) + {@code needsF} (SOG
 *       needsFrustumUpdate): a never-firing or never-satisfied frustum update.</li>
 *   <li>{@code ldChunks} (client loaded chunk count): server/client streaming vs render loss.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class RenderChainProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger("ImmPtlRenderChainProbe");

    /** ~20s at 60fps; the defect window is the first seconds after a promote. */
    private static final int ARMED_FRAMES = 1200;

    public static int armedFrames = 0;
    /** One-shot: set by the debug_dump_render_chain switch; fires one line, self-clears. */
    public static boolean dumpOnce = false;
    /** applyFrustum firings on the MAIN extractor since the last promote (incremented by
     *  MixinLevelExtractor_TerrainSetupOverride). */
    public static int applyFrustumCount = 0;

    static long lastLogMs = 0;
    // S14.48 verify MAJOR fold: the flash-probe rcLog marker reads THIS — stamped ONLY when a
    // LOGGER write actually happens (lastLogMs is pure rate-limit pacing state and now changes at
    // arm without a write, which false-flagged the promote row — the exact row the zero-lag
    // capture reads).
    static long lastWriteMs = 0;

    /** Called by ClientWorldLoader at the end of every promote. */
    public static void armOnPromote() {
        armedFrames = ARMED_FRAMES;
        applyFrustumCount = 0;
        // S14.48: log from ~1s AFTER the promote, not on the promote frame — the frame is the
        // crossing's hottest (the zero-lag hunt) and a log4j write there costs real ms. The
        // diagnostic value shifts by <=1s; the dumpOnce lever is unaffected.
        lastLogMs = System.currentTimeMillis();
    }

    /** Called from MixinGameRenderer at renderLevel HEAD, every frame. */
    public static void onFrame() {
        boolean armed = armedFrames > 0;
        if (armed) {
            armedFrames--;
        }
        if (!armed && !dumpOnce) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!dumpOnce && now - lastLogMs < 1000) {
            return;
        }
        lastLogMs = now;
        lastWriteMs = now;
        boolean wasDump = dumpOnce;
        dumpOnce = false;
        try {
            LOGGER.info("{}{}", wasDump ? "[DUMP] " : "", collectLine());
        }
        catch (Throwable t) {
            // The probe must never take down the frame; one line, no rethrow.
            LOGGER.info("probe collection failed: {}", t.toString());
        }
    }

    private static String collectLine() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.levelRenderer == null || mc.levelExtractor == null) {
            return "no-level";
        }
        LevelExtractor mainExt = mc.levelExtractor;
        LevelExtractorAccessor extAcc = (LevelExtractorAccessor) (Object) mainExt;
        LevelRenderer mcRenderer = mc.levelRenderer;

        // Identity coherences (each 'true' is an invariant; any 'false' names the broken hand-off).
        LevelExtractor levelsExtractor =
            ((ClientLevelExtractorAccessor) mc.level).seamlessportals$getLevelExtractor();
        boolean extOK = levelsExtractor == mainExt;
        boolean renOK = extAcc.seamlessportals$getLevelRenderer() == mcRenderer;
        boolean lvlOK = extAcc.seamlessportals$getLevel() == mc.level;
        LevelRenderState extLRS = extAcc.seamlessportals$getLevelRenderState();
        LevelRenderState gameLRS = mc.gameRenderer.gameRenderState().levelRenderState;
        LevelRenderState renLRS =
            ((LevelRendererAccessorMixin) mcRenderer).seamlessportals$getLevelRenderState();
        boolean lrsOK = extLRS == gameLRS && gameLRS == renLRS;

        // The three-way terrain discriminator.
        int visSec = mcRenderer.visibleSections().size();
        int rendered = mainExt.countRenderedSections();

        // SOG / frustum-update state. sogLoaded << ldChunks = the S14.42 loadedChunks poison
        // signature (the far-walk wipe's root); with the pump+resolver fix they should track.
        SectionOcclusionGraph sog = mcRenderer.sectionOcclusionGraph();
        String needsF = "null-sog";
        int sogLoaded = -1;
        if (sog != null) {
            SectionOcclusionGraphAccessorMixin sogAcc =
                (SectionOcclusionGraphAccessorMixin) (Object) sog;
            needsF = String.valueOf(sogAcc.seamlessportals$getNeedsFrustumUpdate().get());
            sogLoaded = sogAcc.seamlessportals$getLoadedChunks().size();
        }

        boolean dispatcherNull = mcRenderer.sectionRenderDispatcher() == null;
        int ldChunks = mc.level.getChunkSource().getLoadedChunksCount();

        return "dim=" + mc.level.dimension().identifier()
            + " visSec=" + visSec
            + " rendered=" + rendered
            + " ldChunks=" + ldChunks
            + " sogLoaded=" + sogLoaded
            + " applyF=" + applyFrustumCount
            + " needsF=" + needsF
            + " extOK=" + extOK
            + " renOK=" + renOK
            + " lvlOK=" + lvlOK
            + " lrsOK=" + lrsOK
            + " dispNull=" + dispatcherNull
            + " tracker@" + Integer.toHexString(
                System.identityHashCode(extAcc.seamlessportals$getSectionUpdateTracker()))
            + " ext@" + Integer.toHexString(System.identityHashCode(mainExt))
            + " ren@" + Integer.toHexString(System.identityHashCode(mcRenderer));
    }
}
