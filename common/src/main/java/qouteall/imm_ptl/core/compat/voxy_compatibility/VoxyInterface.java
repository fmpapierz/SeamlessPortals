package qouteall.imm_ptl.core.compat.voxy_compatibility;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
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
 * resolution class as sodium's {@code checkRenderer} (memory {@code c2-lessons} #1). Flag-ON this
 * mod (a) creates a {@code LevelRenderer} + {@code LevelExtractor} per secondary dimension and
 * {@code setLevel}s it (→ Voxy would build a FULL render system per dimension: a multi-GB geometry
 * buffer, worker threads, and for a same-identifier level it steals the engine's dirty callback),
 * (b) repoints {@code mc.levelRenderer} to the dest renderer during every portal pass (→ Voxy's
 * hooks would reset/refill the main pass's visible-section stream with dest sections, clobber the
 * main viewport's temporal occlusion state with the portal camera, draw dest LODs into the main
 * target with no stencil bound, and ingest dest-dimension sections into the main dimension's
 * LOD store), and (c) re-points the main extractor at a promoted renderer on a cross-dim crossing
 * WITHOUT {@code allChanged} (→ no render system on the new main renderer: no LODs after walking
 * through a nether portal).
 *
 * <p><b>The contract this facade keeps (tier 1, coexistence):</b> exactly the MAIN
 * {@code LevelRenderer} carries a Voxy render system, and no Voxy hook fires inside a portal
 * pass. Three pieces:
 * <ol>
 *   <li>{@code MixinVoxyLevelRenderer_PassGuard} (compat mixin set, substring-gated on "Voxy"):
 *       {@code voxy$createRenderer} is cancelled in any secondary context (secondary world
 *       creation, {@code withSwitchedWorld}, a portal pass) and {@code voxy$getRenderSystem}
 *       answers {@code null} inside a portal pass — one guard makes every Voxy hook inert there.</li>
 *   <li>{@link #onPlayerDimensionChangePromoted}: the promote/demote cutover moves the render
 *       system to the promoted renderer (shut the demoted one down FIRST so its geometry buffer
 *       returns to Voxy's reuse cache, then create on the promoted one) — the same cost vanilla
 *       Voxy pays on a respawn-driven dimension change.</li>
 *   <li>{@code ImmPtlClientChunkMap.voxy$cheekyGetChunk}: Voxy reads unloading chunks through
 *       vanilla's {@code ClientChunkCache.storage}, which IP's chunk map never fills — the override
 *       routes it to IP's own map so the unload-ingest keeps working.</li>
 * </ol>
 * Portal views therefore show NO LODs (tier 2 is a separate engagement — see the design doc for
 * why the per-secondary render system is not affordable and what a same-dim viewport would need).
 *
 * <p><b>Classloading discipline</b> (the sodium/iris precedent): the base {@link Invoker} references
 * NO Voxy type; {@link OnVoxyPresent} is classloaded only by
 * {@code RenderCompatGating.detectAndGateRenderCompat} on a voxy-present install.
 * {@link #isSecondaryContext()} is the ONE predicate both the mixin and the facade share.
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

        /** The deferred main-renderer creation (pass guard); no-op when Voxy is absent. */
        public void createRendererNow(LevelRenderer renderer) {
        }
    }

    public static Invoker invoker = new Invoker();

    /**
     * Set by the pass guard when Voxy asked to create the MAIN renderer's system inside a
     * secondary context (Iris creating a dest-dimension pipeline reloads the main extractor from
     * inside the dest bracket); consumed by the first {@code voxy$getRenderSystem} call outside
     * any bracket, or cleared by any creation that goes through. Render-thread state only.
     */
    public static boolean deferredMainCreate = false;

    /**
     * Is this the player's MAIN renderer — the one the global {@code Minecraft.levelExtractor}
     * drives? Identity, not context: the promote re-points that extractor's field, and inside a
     * bracket {@code mc.levelRenderer} is the dest renderer while the main extractor still names
     * the main one.
     */
    public static boolean isMainRenderer(LevelRenderer renderer) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
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
     */
    public static boolean isSecondaryContext() {
        return ClientWorldLoader.getIsCreatingClientWorld()
            || ClientWorldLoader.getIsWorldSwitched()
            || PortalRendering.isRendering()
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
            // The explicit creation below supersedes any pending deferral (a second creation on
            // a live system throws inside Voxy).
            deferredMainCreate = false;
            var demoted = (me.cortex.voxy.client.core.IVoxyRenderSystemHolder) demotedRenderer;
            var promoted = (me.cortex.voxy.client.core.IVoxyRenderSystemHolder) promotedRenderer;
            // Demoted FIRST: shutdown gives the geometry buffer back to Voxy's reuse cache, so the
            // promoted system's creation reuses it instead of allocating a second multi-GB buffer.
            try {
                demoted.voxy$shutdownRenderer();
            } catch (Throwable t) {
                LOGGER.error("[Voxy compat] shutting down the demoted renderer's LOD system failed", t);
            }
            try {
                // Mirrors Voxy's own MixinLevelExtractor.setLevel → allChanged sequence for the
                // promoted renderer (the promote deliberately skips allChanged — mesh-preserving).
                promoted.voxy$setWorld(toWorld);
                if (promoted.voxy$getRenderSystem() == null) {
                    promoted.voxy$createRenderer();
                }
                LOGGER.info("[Voxy compat] LOD render system moved to the promoted {} renderer (present={})",
                    toWorld.dimension().identifier(), promoted.voxy$getRenderSystem() != null);
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
        public void createRendererNow(LevelRenderer renderer) {
            var holder = (me.cortex.voxy.client.core.IVoxyRenderSystemHolder) renderer;
            try {
                if (holder.voxy$getRenderSystem() == null) {
                    holder.voxy$createRenderer();
                    LOGGER.info("[Voxy compat] deferred LOD render system creation ran for the main renderer (present={})",
                        holder.voxy$getRenderSystem() != null);
                }
            } catch (Throwable t) {
                LOGGER.error("[Voxy compat] deferred LOD render system creation failed; "
                    + "no LODs until the next renderer reload", t);
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
