package qouteall.imm_ptl.core.compat.voxy_compatibility;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.render.SecondaryWorldRenderCore;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Voxy (the LOD renderer, Fabric-only, requires Sodium) compat — the presence facade, in the
 * {@code SodiumInterface} invoker shape. Design + ledger: {@code migration/VOXY_COMPAT.md}.
 *
 * <p><b>What Voxy does that collides with portal views.</b> Voxy keeps ONE {@code VoxyRenderSystem}
 * per {@code LevelRenderer} (a mixin field), created at {@code LevelExtractor.allChanged} HEAD and
 * keyed on the level's {@code WorldIdentifier}; every one of its hooks (the sodium cull stream, the
 * CUTOUT-pass LOD draw, the fog override, the mesh-upload ingest, the fade cancel) resolves that
 * system through {@code Minecraft.getInstance().levelRenderer} AT CALL TIME — the same call-time
 * resolution class as sodium's {@code checkRenderer} (memory {@code c2-lessons} #1).
 *
 * <p><b>Tier 1 (always):</b> a Voxy hook never runs in a non-render secondary context (secondary
 * world creation, {@code withSwitchedWorld}), a render system is never created inside such a
 * context (it is marked PENDING on that renderer and created at the first safe call instead), and
 * the main renderer always carries one.
 *
 * <p><b>Tier 2 ({@link #isPortalLodsEnabled()}, the shipped default):</b> LODs inside portal
 * windows. Inside a portal pass the renderer driving the pass ({@code mc.levelRenderer}, the dest
 * dimension's secondary renderer, or the main one for a same-dimension portal) exposes its render
 * system, so Voxy's hooks run for that pass exactly as they do for the main pass: the dest
 * sodium cull fills the visible-section stream, the dest CUTOUT pass draws the dest LODs, the
 * dest fog is pushed out. Three things make that correct (the Voxy-side mixins):
 * <ul>
 *   <li>a secondary renderer's system is created lazily in its FIRST portal pass (then
 *       {@code mc.level} IS that dimension, which Voxy's constructor reads for the Y bounds), and
 *       kept across crossings (the promoted and the demoted renderer both keep theirs — no
 *       shutdown + creation hitch at a crossing any more);</li>
 *   <li>each (portal, layer) gets its own Voxy viewport (temporal occlusion state), and a
 *       same-dimension pass never re-centres the main system's render-distance tracker;</li>
 *   <li>Voxy's final composite is bound to the aperture: its scratch framebuffer carries the
 *       main target's depth-STENCIL texture and the blit runs under the portal's stencil
 *       limitation; afterwards the limitation is re-armed for the rest of the dest pass.</li>
 * </ul>
 * Off (config {@code voxyLodsInPortalViews}, the {@code -Dseamlessportals.disableVoxyPortalLods}
 * lever, or a shaderpack active — the shaders-ON renderer composites differently) every portal
 * pass hides the system again (tier 1: no LODs in windows). The cost of tier 2 is one Voxy
 * render system per dimension looked at through a portal (its model atlas and node buffers;
 * the geometry buffer is a sparse reservation on NVIDIA).
 *
 * <p><b>Classloading discipline</b> (the sodium/iris precedent): the base {@link Invoker} references
 * NO Voxy type; {@link OnVoxyPresent} is classloaded only by
 * {@code RenderCompatGating.detectAndGateRenderCompat} on a voxy-present install.
 */
@Environment(EnvType.CLIENT)
public class VoxyInterface {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static class Invoker {
        public boolean isVoxyPresent() {
            return false;
        }

        /**
         * The promote/demote cutover hook ({@code ClientWorldLoader.promoteAndDemoteOnPlayerDimensionChange}
         * tail). No-op when Voxy is absent.
         */
        public void onPlayerDimensionChangePromoted(
            LevelRenderer promotedRenderer, ClientLevel toWorld, LevelRenderer demotedRenderer
        ) {
        }

        /** Diagnostics only (the smoke test's assertions). */
        public boolean hasRenderSystem(LevelRenderer renderer) {
            return false;
        }

        /** Diagnostics only: Voxy's own F3 debug lines for that renderer's system (empty if none). */
        public java.util.List<String> debugInfo(LevelRenderer renderer) {
            return java.util.List.of();
        }

        /** The deferred creation (pass guard); no-op when Voxy is absent. */
        public void createRendererNow(LevelRenderer renderer) {
        }

        /**
         * May this renderer's pending system be created right now? Voxy builds the system's
         * Iris pipeline against Iris's CURRENT pipeline, so under a shaderpack the creation must
         * wait until that is the renderer's own (inside its {@code LevelRenderer.render}, after
         * Iris's {@code preparePipeline}) — a dest renderer's first cull hook runs BEFORE that,
         * and a between-frames reload of the main renderer (a render-distance change) runs while
         * the LAST portal pass's dest pipeline is still current; creating in either bound the new
         * system to the other pipeline's data ("Pipeline data already bound", upon which Voxy
         * switches the shaders off; both observed 2026-10-09). Shaders off: always true.
         */
        public boolean canCreateNow(LevelRenderer renderer) {
            return true;
        }
    }

    public static Invoker invoker = new Invoker();

    /**
     * TRUE while {@link OnVoxyPresent#createRendererNow} runs: the pass guard lets that one
     * {@code voxy$createRenderer} through even inside a portal pass (the lazy dest creation).
     * Render-thread state only.
     */
    public static boolean creatingNow = false;

    /**
     * Is this the player's MAIN renderer — the one the global {@code Minecraft.levelExtractor}
     * drives? Identity, not context: the promote re-points that extractor's field, and inside a
     * bracket {@code mc.levelRenderer} is the dest renderer while the main extractor still names
     * the main one.
     */
    public static boolean isMainRenderer(LevelRenderer renderer) {
        Minecraft mc = Minecraft.getInstance();
        if (renderer == null || mc.levelExtractor == null) {
            return false;
        }
        return ((com.warwa.seamlessportals.mixin.client.LevelExtractorAccessor) (Object) mc.levelExtractor)
            .seamlessportals$getLevelRenderer() == renderer;
    }

    /**
     * TRUE whenever the current call is about a SECONDARY (non-main) renderer or runs inside a
     * portal pass: secondary world creation ({@code createSecondaryClientWorld} setLevel →
     * allChanged), a {@code ClientWorldLoader.withSwitchedWorld} bracket (the reload cascade's
     * per-dim allChanged, secondary ticking), a portal pass ({@code RendererUsingStencil.doRenderPortal}
     * push/pop + every iris renderer), and the dest extract bracket. Render-thread state only.
     * This is the CREATION gate: Voxy's own creation call is deferred in every one of these.
     */
    public static boolean isSecondaryContext() {
        return ClientWorldLoader.getIsCreatingClientWorld()
            || ClientWorldLoader.getIsWorldSwitched()
            || PortalRendering.isRendering()
            || SecondaryWorldRenderCore.isDestExtracting;
    }

    /** Tier 2 switch: config and the A/B lever. Shaders off and on alike (tier 3, 2026-10-09). */
    public static boolean isPortalLodsEnabled() {
        if (Boolean.getBoolean("seamlessportals.disableVoxyPortalLods")) {
            return false;
        }
        IPConfig config = IPConfig.getConfig();
        return config == null || config.voxyLodsInPortalViews;
    }

    /** A portal pass in which the dest LODs render (the Voxy-side mixins key on this). */
    public static boolean isPortalLodPass() {
        return PortalRendering.isRendering() && isPortalLodsEnabled();
    }

    /**
     * A dest-LOD portal pass under the STENCIL renderer (shaders off): the dest draws straight
     * into the main target bounded by the aperture's stencil value, so Voxy's composite must be
     * stencil-tested and the limitation re-armed after its draw. Under a shaderpack the dest
     * renders full-screen through its own Iris pipeline and the aperture is applied afterwards
     * by the stamp copy ({@code IrisCompatPaste.stampPortalArea}) — no stencil mask exists there,
     * and enabling an EQUAL test would reject everything.
     */
    public static boolean isStencilBoundedLodPass() {
        return isPortalLodPass() && !IrisInterface.invoker.isShaders();
    }

    /**
     * A portal pass whose dest renderer IS the main renderer (a same-dimension portal): the
     * main system renders it through a separate viewport and must not re-centre its tracker.
     */
    public static boolean isSameDimLodPass() {
        return isPortalLodPass() && isMainRenderer(Minecraft.getInstance().levelRenderer);
    }

    /**
     * The VISIBILITY gate for {@code voxy$getRenderSystem}: should this renderer answer
     * {@code null} right now? Inside a portal pass the renderer driving the pass answers
     * normally when tier 2 is on, and every renderer hides otherwise; outside a pass, the
     * non-render secondary contexts hide.
     */
    public static boolean shouldHideRenderSystem(LevelRenderer self) {
        if (PortalRendering.isRendering()) {
            return !(isPortalLodsEnabled() && self == Minecraft.getInstance().levelRenderer);
        }
        return ClientWorldLoader.getIsCreatingClientWorld()
            || ClientWorldLoader.getIsWorldSwitched()
            || SecondaryWorldRenderCore.isDestExtracting;
    }

    /**
     * Voxy-present invoker. Touches Voxy's merged {@code IVoxyRenderSystemHolder} surface on
     * {@code LevelRenderer} (the public interface Voxy's own {@code MixinLevelExtractor} drives) —
     * no reflection, no private state.
     */
    public static class OnVoxyPresent extends Invoker {

        @Override
        public boolean isVoxyPresent() {
            return true;
        }

        @Override
        public void onPlayerDimensionChangePromoted(
            LevelRenderer promotedRenderer, ClientLevel toWorld, LevelRenderer demotedRenderer
        ) {
            if (promotedRenderer == demotedRenderer) {
                return;
            }
            var demoted = (me.cortex.voxy.client.core.IVoxyRenderSystemHolder) demotedRenderer;
            var promoted = (me.cortex.voxy.client.core.IVoxyRenderSystemHolder) promotedRenderer;
            if (!isPortalLodsEnabled()) {
                // Tier 1: exactly one system, on the main renderer. Demoted FIRST: shutdown gives
                // the geometry buffer back to Voxy's reuse cache, so the promoted system's creation
                // reuses it instead of allocating a second multi-GB buffer.
                try {
                    demoted.voxy$shutdownRenderer();
                } catch (Throwable t) {
                    LOGGER.error("[Voxy compat] shutting down the demoted renderer's LOD system failed", t);
                }
            }
            // Tier 2: the demoted renderer keeps its system (it is the dest renderer for the
            // portal behind the player now), and the promoted one usually already has one from
            // the portal view that led here — no hitch at the crossing.
            try {
                // Mirrors Voxy's own MixinLevelExtractor.setLevel → allChanged sequence for the
                // promoted renderer (the promote deliberately skips allChanged — mesh-preserving).
                promoted.voxy$setWorld(toWorld);
                if (promoted.voxy$getRenderSystem() == null) {
                    if (canCreateNow(promotedRenderer)) {
                        createRendererNow(promotedRenderer);
                    } else {
                        // Under a shaderpack the promote runs in the tick, with another (or no)
                        // pipeline current: Voxy's creation call goes through the pass guard,
                        // which marks it pending and performs it at the promoted renderer's next
                        // own render (its first visible call that may create).
                        promoted.voxy$createRenderer();
                    }
                }
                LOGGER.info("[Voxy compat] LOD render system moved to the promoted {} renderer (present={}, demoted keeps its own={})",
                    toWorld.dimension().identifier(), promoted.voxy$getRenderSystem() != null,
                    demoted.voxy$getRenderSystem() != null);
            } catch (Throwable t) {
                // A creation failure must never break the crossing — the player just loses LODs
                // until the next allChanged (F3+A / resource reload) recreates it.
                LOGGER.error("[Voxy compat] creating the promoted renderer's LOD system failed; "
                    + "no LODs until the next renderer reload", t);
            }
        }

        @Override
        public boolean hasRenderSystem(LevelRenderer renderer) {
            return renderer != null
                && ((me.cortex.voxy.client.core.IVoxyRenderSystemHolder) renderer).voxy$getRenderSystem() != null;
        }

        @Override
        public boolean canCreateNow(LevelRenderer renderer) {
            if (!IrisInterface.invoker.isShaders()) {
                return true;
            }
            Object own = IrisInterface.invoker.getPipeline(renderer);
            return own != null && own == IrisInterface.invoker.getCurrentPipeline();
        }

        @Override
        public void createRendererNow(LevelRenderer renderer) {
            var holder = (me.cortex.voxy.client.core.IVoxyRenderSystemHolder) renderer;
            boolean saved = creatingNow;
            creatingNow = true;
            try {
                if (holder.voxy$getRenderSystem() == null) {
                    holder.voxy$createRenderer();
                    Minecraft mc = Minecraft.getInstance();
                    LOGGER.info("[Voxy compat] LOD render system created for the {} renderer{} (present={})",
                        mc.level == null ? "?" : mc.level.dimension().identifier(),
                        PortalRendering.isRendering() ? " inside its first portal pass" : "",
                        holder.voxy$getRenderSystem() != null);
                }
            } catch (Throwable t) {
                LOGGER.error("[Voxy compat] LOD render system creation failed; no LODs for that "
                    + "renderer until the next renderer reload", t);
            } finally {
                creatingNow = saved;
            }
        }

        @Override
        public java.util.List<String> debugInfo(LevelRenderer renderer) {
            if (renderer == null) {
                return java.util.List.of();
            }
            var system = ((me.cortex.voxy.client.core.IVoxyRenderSystemHolder) renderer).voxy$getRenderSystem();
            if (system == null) {
                return java.util.List.of();
            }
            java.util.List<String> lines = new java.util.ArrayList<>();
            try {
                system.addDebugInfo(lines);
            } catch (Throwable t) {
                lines.add("debugInfo failed: " + t);
            }
            return lines;
        }
    }
}
