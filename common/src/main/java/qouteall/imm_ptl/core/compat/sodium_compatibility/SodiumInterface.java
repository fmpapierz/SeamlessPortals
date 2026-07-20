package qouteall.imm_ptl.core.compat.sodium_compatibility;

import com.mojang.logging.LogUtils;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.mixin.sodium.IESodiumWorldRenderer;
import qouteall.imm_ptl.core.render.FrustumCuller;

import java.util.HashSet;
import java.util.Set;

@Environment(EnvType.CLIENT)
public class SodiumInterface {

    @Nullable
    public static FrustumCuller frustumCuller = null;

    public static class Invoker {
        public boolean isSodiumPresent() {
            return false;
        }

        public Object createNewContext(int renderDistance) {
            return null;
        }

        public void switchContextWithCurrentWorldRenderer(Object context) {

        }

        public void markSpriteActive(TextureAtlasSprite sprite) {

        }

        public void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ) {

        }

        public void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ) {

        }

        /**
         * C2-1 D1 registry invalidation hook — called from
         * {@code ClientWorldLoader.disposeWorldRenderer} (the per-dim dispose seam) so persistent
         * portal contexts whose trees were built against that dimension's RSM die with it.
         * No-op unless {@code OnSodiumPresent} is installed.
         */
        public void onWorldRendererDisposed(ResourceKey<Level> dimension) {

        }
    }

    public static Invoker invoker = new Invoker();

    public static class OnSodiumPresent extends Invoker {

        private static final Logger LOGGER = LogUtils.getLogger();

        /**
         * BLOCKER-1b one-shot ledger: dimensions for which the null-RSM degrade
         * (see {@link #switchContextWithCurrentWorldRenderer}) has already been logged.
         * Render thread only.
         */
        private static final Set<ResourceKey<Level>> NULL_RSM_LOGGED_DIMS = new HashSet<>();

        @Override
        public boolean isSodiumPresent() {
            return true;
        }

        /**
         * C2-1 D1: acquire-from-registry (design §3.1.3) behind the UNCHANGED facade signature —
         * the {@code MyGameRenderer} call sites (:344-345 / :424) are untouched. For a rendering
         * portal this returns the PERSISTENT per-(portal UUID, layer) context whose SectionTrees /
         * render lists persist across passes (TREE-PERSISTENCE WITH SYNCHRONOUS CONSUMPTION — the
         * §4.1 mitigation (b) model: every pass's CullTask is blocking-consumed before the
         * swap-out, results persist via the cullResults content-swap); non-portal callers get IP's
         * per-pass-fresh cold context. Cold registered contexts arm ForceMainThreadRebuild
         * (deliverable 6) inside the registry.
         */
        @Override
        public Object createNewContext(int renderDistance) {
            return SodiumContextRegistry.acquire(renderDistance);
        }

        /**
         * C2-1 D1 swap driver. IP's protocol preserved exactly (depth doc §0 "Two-way
         * context-swap driver"): resolve the CURRENT {@code mc.levelRenderer}'s SWR (the implicit
         * handle — at the :345 swap-in the :298 repoint has already made this the DEST renderer;
         * handle asymmetry preserved per design §0.2), {@code scheduleTerrainUpdate()}, swap,
         * {@code scheduleTerrainUpdate()} again. The swap payload is WIDENED (D1): first the
         * RSM-side set + content-swaps run inside {@code ip_swapContext}
         * ({@code MixinSodiumRenderSectionManager}) — which also performs the §4.1 race
         * mitigation (b) consume-before-swap and the swap-in validate (CORRECTION-4: validate
         * BEFORE any exchange, so a throw leaves no half-swap) — then the SWR-side camera-cache
         * five are swapped via the {@code IESodiumWorldRenderer} accessors (census :202-209 —
         * else every portal pass thrashes camera-changed detection). Symmetric: calling this
         * twice restores everything.
         *
         * <p>The scheduleTerrainUpdate() bracket (→ {@code RSM.markGraphDirty()}, javap-proven
         * delegation) marks BOTH sides dirty: the pre-swap call dirties the outgoing context
         * (it re-culls when restored), the post-swap call dirties the swapped-in context (it
         * schedules its own cull this pass) — IP's own arrangement, load-bearing for the
         * needsGraphUpdate swap semantics.
         */
        @Override
        public void switchContextWithCurrentWorldRenderer(Object context) {
            SodiumRenderingContext ctx = (SodiumRenderingContext) context;

            SodiumWorldRenderer swr =
                ((LevelRendererExtension) Minecraft.getInstance().levelRenderer).sodium$getWorldRenderer();
            IESodiumWorldRenderer ieSwr = (IESodiumWorldRenderer) swr;

            // ===== BLOCKER-1b (verify lens A): defensive null-RSM guard ======================
            // An SWR whose setLevel was never routed to it (the BLOCKER-1 wrong-routing class,
            // now fixed at the ClientWorldLoader setLevel brackets) has a null
            // renderSectionManager. This is a MID-FRAME render path: throwing here would abort
            // the whole frame from inside the portal bracket, so degrade VISIBLY instead —
            // one-shot-per-dimension LOGGER.error (the log is the loudness; honest degrade,
            // crash-freedom over fail-loud deliberately) and SKIP the swap entirely. Skipping is
            // symmetric: both bracket calls (:345 swap-in / :424 swap-out) resolve the SAME
            // renderer inside one pass, so a null seen at swap-in is null at swap-out too and
            // both halves skip identically (no half-swap).
            RenderSectionManager renderSectionManager = ieSwr.ip_getRenderSectionManager();
            if (renderSectionManager == null) {
                ClientLevel level = Minecraft.getInstance().level;
                ResourceKey<Level> dim = level == null ? null : level.dimension();
                if (NULL_RSM_LOGGED_DIMS.add(dim)) {
                    LOGGER.error(
                        "[imm_ptl sodium compat] SodiumWorldRenderer has a NULL "
                            + "RenderSectionManager while swapping context for dimension {} — "
                            + "sodium never received setLevel for this renderer; SKIPPING the "
                            + "context swap (portal view degrades, no crash). "
                            + "Report this — the ClientWorldLoader setLevel repoint bracket "
                            + "should make this unreachable.",
                        dim == null ? "<null level>" : dim.identifier()
                    );
                }
                return;
            }

            swr.scheduleTerrainUpdate();

            // ===== RSM-side widened swap (reference set + the two content-swapped finals) =====
            // CORRECTION-4 (verify lens A): this runs BEFORE the SWR-five swap below. ip_swapContext
            // contains the swap-in validate — with the old order (SWR five first) a validate throw
            // left an asymmetric half-swap (SWR camera cache exchanged, RSM side not). The two
            // halves touch disjoint state (ip_swapContext never reads the SWR camera cache; the
            // five-swap never touches the RSM), so they are order-independent — validate-first
            // makes a throw leave everything untouched. The scheduleTerrainUpdate() bracket
            // positions stay IP-exact (one before any exchange, one after all of it).
            ((IESodiumRenderSectionManager) renderSectionManager).ip_swapContext(ctx);

            // ===== SWR camera-cache five — symmetric tmp swap (D1, port-note §2) =====
            Vector3d tmpLastCameraPos = ieSwr.ip_getLastCameraPos();
            ieSwr.ip_setLastCameraPos(ctx.lastCameraPos);
            ctx.lastCameraPos = tmpLastCameraPos;

            double tmpLastCameraPitch = ieSwr.ip_getLastCameraPitch();
            ieSwr.ip_setLastCameraPitch(ctx.lastCameraPitch);
            ctx.lastCameraPitch = tmpLastCameraPitch;

            double tmpLastCameraYaw = ieSwr.ip_getLastCameraYaw();
            ieSwr.ip_setLastCameraYaw(ctx.lastCameraYaw);
            ctx.lastCameraYaw = tmpLastCameraYaw;

            FogParameters tmpLastFogParameters = ieSwr.ip_getLastFogParameters();
            ieSwr.ip_setLastFogParameters(ctx.lastFogParameters);
            ctx.lastFogParameters = tmpLastFogParameters;

            Matrix4f tmpCullMatrix = ieSwr.ip_getCullMatrix();
            ieSwr.ip_setCullMatrix(ctx.cullMatrix);
            ctx.cullMatrix = tmpCullMatrix;

            swr.scheduleTerrainUpdate();
        }

        /**
         * D6: {@code net.caffeinemc.mods.sodium.api.texture.SpriteUtil.INSTANCE} — the supported
         * API surface (javap: {@code public interface SpriteUtil { public static final SpriteUtil
         * INSTANCE; void markSpriteActive(TextureAtlasSprite); }}), replacing the internal
         * client-package static IP bound against 0.6.0.
         */
        @Override
        public void markSpriteActive(TextureAtlasSprite sprite) {
            SpriteUtil.INSTANCE.markSpriteActive(sprite);
        }

        // D11-SEAM (contingency, NOT active — design §0.3 resolved-conflict 10 / D-ledger D11):
        // the A5/A6 chunk-tracker feed below is what makes Sodium mesh ImmPtlClientChunkMap-held
        // chunks at all (call sites: ImmPtlClientChunkMap.java:221 load / :136 unload). Today it
        // rides the invoker installation (sodium present + gate/lever). IF the C2 baseline round
        // shows a BLANK/unmeshed main world with sodium present flag-ON and the gate OFF, the
        // feed becomes an UNCONDITIONAL-when-sodium-present carve-out from the experimental gate
        // (pure correctness plumbing, same family as the D3 unconditional-worldgen seam). The
        // re-gating spot is NOT here — it is the sodium-present-but-inactive branch of
        // SeamlessPortalsClientFabric.detectAndGateRenderCompat (grep "D11-SEAM" there), which
        // would install a minimal tracker-feed-only invoker so these two methods run while
        // everything else stays no-op.
        @Override
        public void onClientChunkLoaded(ClientLevel world, int chunkX, int chunkZ) {
            ChunkTrackerHolder.get(world)
                .onChunkStatusAdded(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        }

        @Override
        public void onClientChunkUnloaded(ClientLevel world, int chunkX, int chunkZ) {
            ChunkTrackerHolder.get(world)
                .onChunkStatusRemoved(chunkX, chunkZ, ChunkStatus.FLAG_HAS_BLOCK_DATA);
        }

        @Override
        public void onWorldRendererDisposed(ResourceKey<Level> dimension) {
            SodiumContextRegistry.invalidateForDimension(dimension);
        }
    }

}
