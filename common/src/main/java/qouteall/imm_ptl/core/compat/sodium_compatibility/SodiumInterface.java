package qouteall.imm_ptl.core.compat.sodium_compatibility;

import com.mojang.logging.LogUtils;
import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkStatus;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.ViewportProvider;
import net.caffeinemc.mods.sodium.client.util.FlawlessFrames;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.SodiumChunkSection;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
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

        /**
         * C2-1c F1 fix — drive sodium's dest-pass terrain setup (cull → render lists) from
         * {@code SecondaryWorldRenderCore.renderDestWorld} Step 9, inside the swap bracket.
         * ACTIVE-invoker-only: this base is a no-op, and {@code FeedOnlyOnSodiumPresent} inherits
         * it — the feed-only/base states keep the C2-1b yielded-empty dest passes.
         * Vanilla-types-only signature (facade discipline); the sodium types live in the
         * {@code OnSodiumPresent} body.
         */
        public void ip_driveDestTerrainSetup(
            Camera destCamera, Frustum destFrustum, Matrix4f destCullMatrix,
            FogData destFogData, boolean smartCull
        ) {

        }

        /**
         * C2-1c F2 fix — arm the dest pass's {@code ChunkSectionsToRender} the way sodium's own
         * {@code LevelRendererMixin.getRenderState} WrapOperation does, so
         * {@code renderGroup} delegates to {@code SWR.drawChunkLayer} instead of falling through
         * to the vanilla body over the dummy's empty map. Returns TRUE when armed — the caller's
         * canDraw gate must bypass its {@code maxIndicesRequired() > 0} test then (the dummy
         * reports -1). Base/feed-only: false, nothing armed.
         */
        public boolean ip_armDestChunkRenders(
            ChunkSectionsToRender destChunks, Matrix4f destDrawProjection, Matrix4f destViewMatrix,
            Vec3 destCameraPos, FogData destFogData
        ) {
            return false;
        }

        /**
         * C2-1c — called in {@code renderDestWorld}'s outermost finally (throw-safe) after every
         * dest pass's draws. Resets the shared-SWR {@code UniformBufferManager} once-per-frame
         * latch the dest drive/draws consumed — see the {@code OnSodiumPresent} body for the
         * shared-SWR corruption this prevents. Base/feed-only: no-op.
         */
        public void ip_onDestTerrainDrawsFinished() {

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

        // D11-LANDED (2026-07-19 C2-1b; design §0.3 resolved-conflict 10 / D-ledger D11): the
        // baseline round (gate OFF, 2026-07-19) ANSWERED P2 — the main world was BLANK (only
        // sky/outlines/particles) because ImmPtlClientChunkMap replaces the client chunk cache
        // and Sodium's own load hook never fires while this feed is invoker-dead. The A5/A6
        // chunk-tracker feed below (call sites: ImmPtlClientChunkMap.java:221 load / :136
        // unload) is therefore PRESENCE-GATED, carved out of the experimental gate: the
        // sodium-present-but-INACTIVE branch of
        // SeamlessPortalsClientFabric.detectAndGateRenderCompat installs
        // {@link FeedOnlyOnSodiumPresent} (these two bodies only, isSodiumPresent() stays
        // FALSE), and the ACTIVE branch installs this full class — so the feed runs whenever
        // sodium is present, regardless of gate/lever (pure correctness plumbing, same family
        // as the D3 unconditional-worldgen seam).
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

        /**
         * C2-1c F1 — THE DEST TERRAIN-SETUP DRIVE. Why sodium's own hook cannot fire for dest
         * passes (javap 0.9.1 {@code LevelExtractorMixin.cullTerrain}):
         * {@code @Inject(method="extract", at=@At("INVOKE",
         * target="Lnet/minecraft/client/renderer/SectionOcclusionGraph;consumeFrustumUpdate()Z"))}
         * — that anchor sits in the {@code else if (camera.getCapturedFrustum() == null)} branch
         * of {@code LevelExtractor.extract} (mc262-ref :125-135), and renderDestWorld Step 3.5
         * SETS a captured frustum on the dest camera (the designed applyFrustum/SPIKE-R1 skip),
         * so the hook is structurally skipped every dest pass and the D1-swapped context never
         * had a cull scheduled. CAPTURED-FRUSTUM DISCIPLINE: we drive EXPLICITLY and the frustum
         * STAYS captured — un-capturing would re-enter vanilla's applyFrustum semantics
         * (the reversed-Z hang class) and change the vanilla-path extract behavior.
         *
         * <p>The body replicates the hook 1:1 (javap-quoted, bytecode offsets 0-67):
         * <ol>
         *   <li>{@code Viewport viewport = ((ViewportProvider) frustum).sodium$createViewport()}
         *       — sodium's FrustumMixin implements ViewportProvider on the VANILLA Frustum
         *       (SimpleFrustum(intersection) + Vector3d(camX,camY,camZ)); our destFrustum was
         *       {@code prepare()}d with the dest camera pos at Step 3.4, so the viewport carries
         *       the dest camera origin;</li>
         *   <li>{@code updateChunksImmediately = FlawlessFrames.isActive()} — resolved HERE so
         *       the C2-1 cold-context arming (MixinSodiumFlawlessFrames +
         *       SodiumContextRegistry's n=1 ForceMainThreadRebuild) is picked up identically;</li>
         *   <li>{@code useOcclusionCulling = levelRenderState.cameraRenderState.smartCull} — the
         *       caller passes destCameraState.smartCull (filled by Camera.extractRenderState at
         *       Step 4, mc262-ref Camera.java:122-124);</li>
         *   <li>FogParameters built from the dest FogData with sodium's own capture mapping
         *       (javap FogRendererMixin.sodium$storeFogParameters: color.x/y/z/w,
         *       environmentalStart/End, renderDistanceStart/End via the 8-float ctor whose
         *       cullDistance = isNaN(envEnd) ? renderEnd : min(renderEnd, envEnd)) — built
         *       DIRECTLY rather than read from the FogStorage duck so the drive is immune to
         *       Step-6 ordering; renderDistanceEnd carries WorldRenderInfo.getRenderDistance, so
         *       sodium's getSearchDistance sees IP's graduated dest radius;</li>
         *   <li>cullMatrix — the hook reads vanilla Frustum.matrix via sodium's FrustumAccessor
         *       (a REGISTERED MIXIN class — illegal for us to classload), so the caller passes
         *       the byte-identical reconstruction {@code new Matrix4f(cullProjection)
         *       .mul(destViewMatrix)} (vanilla Frustum.calculateFrustum:
         *       {@code projection.mul(modelView, this.matrix)}). It is only setupTerrain's
         *       camera-delta basis (equals(1e-4) + set), never used to cull.</li>
         * </ol>
         *
         * <p>SWAP-BRACKET INTERPLAY (all javap-walked): the SWR resolves via the repointed
         * {@code mc.levelRenderer} (MyGameRenderer :298), same handle as the swap driver; the
         * SWR camera-cache five are the swapped-in context's, so setupTerrain's camera-delta
         * detection is per-context (cold context: lastFogParameters=NONE forces
         * notifyChangedCamera on the first pass). The drive's setupTerrain →
         * prepareRenderTrees schedules the async cull ({@code !isOutOfGraph(cameraChunk) &&
         * (cameraChanged || needsGraphUpdate)} — needsGraphUpdate is TRUE every swapped-in pass:
         * the driver's post-swap scheduleTerrainUpdate() → markGraphDirty), onto the context's
         * null pendingTask (consumed-before-every-swap invariant). EXPECTED FIRST FRAMES (cold
         * context): finalizeRenderLists takes the SYNC {@code renderOutOfGraph} path
         * (content-swapped ACTC previousPosition=null → getShouldRenderSync TRUE; and
         * prepareRender set needsRenderListUpdate via cameraChanged) — a same-pass FRUSTUM-ONLY
         * render list draws THIS pass (P10 fallback); the scheduled CullTask is
         * blocking-consumed at swap-out (mitigation b) and its occlusion trees persist in the
         * context (cullResults content-swap) for the next pass's findBestTree. If the cold
         * camera chunk is not yet in renderSections (isOutOfGraph), no cull is scheduled this
         * pass — retried next pass; the out-of-graph sync path still draws.
         *
         * <p>Shared-state safety (same-dim / A→B→A): prepareRender's chunkRenderer.rotate() is a
         * javap-proven NO-OP on the GL backend (GLDrawContext.rotate: single return; VK
         * backends rotate — an unvalidated corner riding the D4 VK posture decision), and
         * multi-invocation per frame is sodium's own in-design behavior (the FlawlessFrames
         * drain loop calls prepareRender per iteration). uniformBufferManager.prepareFrame()
         * resets the once-per-frame GlobalUniforms latch — restored after the pass by
         * {@link #ip_onDestTerrainDrawsFinished} (the shared-SWR corruption guard).
         * setupTerrain's HEAD reload() (options RD drift vs the UN-swapped SWR.renderDistance)
         * replaces the RSM mid-bracket — the C2-1 swapPartnerRsm guard was built for exactly
         * this seam. Entity.setViewScale at the tail recomputes the same options-derived value
         * every call (idempotent). Step 6's dest setupFog also overwrites sodium's FogStorage
         * duck with dest fog as a side effect (FogRendererMixin injects at setupFog RETURN) —
         * benign: the duck's only consumers (main cullTerrain / main getRenderState) run before
         * the portal slot each frame, and the next frame's main setupFog re-stores main fog.
         */
        @Override
        public void ip_driveDestTerrainSetup(
            Camera destCamera, Frustum destFrustum, Matrix4f destCullMatrix,
            FogData destFogData, boolean smartCull
        ) {
            SodiumWorldRenderer swr =
                ((LevelRendererExtension) Minecraft.getInstance().levelRenderer)
                    .sodium$getWorldRenderer();
            if (((IESodiumWorldRenderer) swr).ip_getRenderSectionManager() == null) {
                // BLOCKER-1b degrade family: the swap driver already skipped the context swap
                // for this renderer and logged one-shot — driving setupTerrain would NPE on the
                // null RSM. Skip symmetrically (the pass yields empty, no crash).
                return;
            }
            Viewport viewport = ((ViewportProvider) destFrustum).sodium$createViewport();
            boolean updateChunksImmediately = FlawlessFrames.isActive();
            FogParameters fogParameters = new FogParameters(
                destFogData.color.x, destFogData.color.y, destFogData.color.z,
                destFogData.color.w,
                destFogData.environmentalStart, destFogData.environmentalEnd,
                destFogData.renderDistanceStart, destFogData.renderDistanceEnd
            );
            swr.setupTerrain(
                destCamera, viewport, fogParameters,
                smartCull, updateChunksImmediately, destCullMatrix
            );
        }

        /**
         * C2-1c F2 — THE ARM. javap 0.9.1: sodium {@code @Overwrite}s
         * {@code LevelRenderer.prepareChunkRenders} to return an UN-ARMED dummy
         * ChunkSectionsToRender (shared empty STATIC_MAP, maxIndices=-1, no buffers); arming
         * happens only in sodium's {@code @WrapOperation} at the vanilla
         * {@code LevelRenderer.render} call site ({@code getRenderState}), which the
         * decomposition never runs. This replicates that WrapOperation's arm
         * (bytecode offsets 23-112) — with ONE deliberate omission, named below:
         * <ol>
         *   <li>{@code new ChunkRenderMatrices(projection, modelView)} — sodium takes the
         *       projection its GameRendererMixin captured at the vanilla projection-buffer
         *       write and the matrix passed to prepareChunkRenders; our dest equivalents are
         *       destDrawProjection (the Step-7 installed draw projection — base*bob*spin, the
         *       exact matrix the dest draws rasterize with) and destViewMatrix (what Step 10
         *       passes to prepareChunkRenders). Defensive copies: the record is retained on the
         *       armed instance; sodium passes live references, but copies are semantically
         *       identical here (the locals are never mutated after) and immune to reuse;</li>
         *   <li>{@code ((SodiumChunkSection) chunks).sodium$setRendering(renderer, matrices,
         *       x, y, z)} with the camera position (sodium reads
         *       levelRenderState.cameraRenderState.pos; destCameraPos IS that value — Step 4's
         *       extractRenderState filled destCameraState.pos from the same camera);</li>
         *   <li>{@code renderer.updateFogColor(fogColor)} — merges the render-time fog color
         *       into SWR.lastFogParameters (the swapped-in context's field). Our
         *       destFogData.color is the same color the drive's FogParameters already carry
         *       (and the one published to FogRendererContext), so this is value-identical —
         *       kept for 1:1 arm fidelity.</li>
         * </ol>
         * The armed instance's {@code renderGroup} HEAD-inject then cancels into
         * {@code SWR.drawChunkLayer}: OPAQUE → SOLID+CUTOUT, TRANSLUCENT → TRANSLUCENT
         * (javap drawChunkLayer) — reading the swapped-in context's renderLists +
         * lastFogParameters, through the D10 interim clip bracket at ShaderChunkRenderer.begin.
         *
         * <p>THE DELIBERATE OMISSION (vB NOTE-2 fold): sodium's WrapOperation ALSO stores the
         * ChunkRenderMatrices on the mixin'd LevelRenderer itself ({@code putfield matrices},
         * getRenderState offset 46 — backing {@code LevelRendererExtension.sodium$getMatrices}).
         * This arm does NOT replicate that putfield, for two reasons: (1) jar-proven dead read
         * surface — a full-jar grep of sodium 0.9.1 finds NO sodium-internal consumer of
         * {@code sodium$getMatrices} (the interface declares it and LevelRendererMixin implements
         * it; the only extension method sodium itself invokes is {@code sodium$getWorldRenderer}
         * — SodiumWorldRenderer + LevelExtractorMixin, javap-walked); (2) on a SHARED-renderer
         * frame (same-dim portal / A→B→A nesting) the putfield would clobber the MAIN renderer's
         * stored matrices with dest matrices — state sodium set for the main pass, gratuitously
         * corrupted for zero consumer benefit. Cross-reference: the once-per-frame-latch fix
         * ({@link #ip_onDestTerrainDrawsFinished}) likewise keeps shared-renderer frames sound
         * only because the D1 five-swap restores {@code SWR.lastFogParameters} at swap-out —
         * the {@code updateFogColor} merge in step 3 above lands on the swapped-in context's
         * field and leaves the main pass's fog untouched after the bracket. IRIS REVISIT
         * (C2-4): iris ships its own mixins over sodium — if the iris wiring round finds an
         * iris consumer of {@code sodium$getMatrices}, revisit this omission.
         */
        @Override
        public boolean ip_armDestChunkRenders(
            ChunkSectionsToRender destChunks, Matrix4f destDrawProjection, Matrix4f destViewMatrix,
            Vec3 destCameraPos, FogData destFogData
        ) {
            SodiumWorldRenderer swr =
                ((LevelRendererExtension) Minecraft.getInstance().levelRenderer)
                    .sodium$getWorldRenderer();
            if (((IESodiumWorldRenderer) swr).ip_getRenderSectionManager() == null) {
                // Same degrade family as the drive: nothing was culled, arm nothing.
                return false;
            }
            ChunkRenderMatrices matrices = new ChunkRenderMatrices(
                new Matrix4f(destDrawProjection), new Matrix4f(destViewMatrix)
            );
            ((SodiumChunkSection) (Object) destChunks).sodium$setRendering(
                swr, matrices, destCameraPos.x, destCameraPos.y, destCameraPos.z
            );
            swr.updateFogColor(destFogData.color);
            return true;
        }

        /**
         * C2-1c — the shared-SWR GlobalUniforms latch restore. javap 0.9.1:
         * {@code UniformBufferManager.update} early-returns when {@code hasUpdatedThisFrame}
         * (offsets 0-7) and otherwise writes a fresh DynamicUniformStorage slice; the latch is
         * reset ONLY by {@code prepareFrame()}. The dest drive's setupTerrain called
         * prepareFrame and the dest draws latched the manager onto the DEST slice — on a
         * SHARED-SWR frame (same-dim portal, or A→B→A nesting) the main pass's later
         * {@code renderGroup(TRANSLUCENT) → renderLayer → update} would latch-skip and bind the
         * DEST matrices/fog slice under the main translucent terrain (the decisive same-dim
         * live check would fail). Resetting the latch here makes every later renderLayer
         * update() WRITE a fresh slice from its own (correct) arguments —
         * DynamicUniformStorage's multi-slice-per-frame model, one bounded extra write per
         * consumer. Runs in renderDestWorld's outermost finally (throw-safe; nested passes
         * self-heal recursively — every bracket exit leaves the latch reset). mc.levelRenderer
         * is still the DEST renderer there (the shell restores it after the invoke), so this
         * resolves the same SWR the drive touched; cross-dim (per-dim SWR) the reset is a
         * harmless no-op-equivalent (that SWR's next drive/frame calls prepareFrame anyway).
         */
        @Override
        public void ip_onDestTerrainDrawsFinished() {
            SodiumWorldRenderer swr =
                ((LevelRendererExtension) Minecraft.getInstance().levelRenderer)
                    .sodium$getWorldRenderer();
            UniformBufferManager ubm =
                ((IESodiumWorldRenderer) swr).ip_getUniformBufferManager();
            if (ubm != null) {
                ubm.prepareFrame();
            }
        }
    }

    /**
     * D11-LANDED (C2-1b): the PRESENCE-gated tracker-feed-only invoker, installed by
     * {@code SeamlessPortalsClientFabric.detectAndGateRenderCompat} when sodium is PRESENT but
     * the experimental compat is NOT active (gate+lever off, or iris forcing sodium inactive).
     *
     * <p>Round-1 proof (2026-07-19, gate OFF): without this feed the main world is BLANK under
     * sodium flag-ON — {@code ImmPtlClientChunkMap} replaces the vanilla client chunk cache, so
     * Sodium's own {@code ClientChunkCacheMixin} load hook never fires and sodium meshes
     * NOTHING. The A5/A6 feed is pure correctness plumbing (D3-unconditional-seam family) and
     * must not depend on the experimental gate.
     *
     * <p>ONLY {@code onClientChunkLoaded}/{@code onClientChunkUnloaded} are overridden (the
     * exact {@link OnSodiumPresent} bodies). {@code isSodiumPresent()} stays FALSE so every
     * render-path consumer (TerrainSetupOverride yield, MixinSectionRenderDispatcher
     * pool-split, FrustumCuller outer-cull, D10 bracket) still sees the un-levered world, and
     * {@code createNewContext}/{@code switchContextWithCurrentWorldRenderer} stay no-ops (the
     * MyGameRenderer :344/:424 bracket calls them unconditionally — e.g. via the
     * config-gated CrossPortalViewRendering or the GUI-portal debug API, both reachable with
     * portal views forced off — and must not run the real swap while the compat is inactive).
     *
     * <p>Lazy-classload discipline preserved: this class touches sodium types
     * ({@code ChunkTrackerHolder}/{@code ChunkStatus}), so it is instantiated ONLY inside the
     * sodium-present branch.
     */
    public static class FeedOnlyOnSodiumPresent extends Invoker {

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
    }

}
