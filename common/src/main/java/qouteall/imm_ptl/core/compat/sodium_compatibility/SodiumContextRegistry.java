package qouteall.imm_ptl.core.compat.sodium_compatibility;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.ExperimentalCompatGate;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.ForceMainThreadRebuild;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * C2-1 D1 — the persistent per-portal context lifetime registry (design
 * {@code migration/C2_DESIGN.md} §3.1.3). Acquire happens INSIDE
 * {@code OnSodiumPresent.createNewContext} — the invoker FACADE SIGNATURES ARE UNCHANGED and the
 * {@code MyGameRenderer} call sites (:344-345 create+swap-in, :424 swap-out) are untouched.
 *
 * <p>WHY persistent (NOTE-6 corrected model — TREE-PERSISTENCE WITH SYNCHRONOUS CONSUMPTION):
 * under the §4.1 race mitigation (b), NO CullTask ever survives a swap — {@code ip_swapContext}
 * blocking-consumes any in-flight task before every exchange, so each pass pays a bounded
 * blocking consume. What persists across passes is the cull OUTPUT: the SectionTrees (via the
 * {@code cullResults} content-swap) and render lists/render tree, so the portal's visible set
 * converges across passes instead of restarting cold (design §3.1.3). A per-pass-fresh context
 * would restart the cull from nothing every pass.
 *
 * <p>KEY: rendering-portal identity — {@code PortalRendering.getRenderingPortal()} entity UUID +
 * {@code PortalRendering.getPortalLayer()} recursion layer (both valid at the :344 call site:
 * {@code pushPortalLayer} runs in the renderer BEFORE {@code renderPortalContent} invokes the
 * MyGameRenderer bracket — RendererUsingStencil.java:321). Non-portal facade callers
 * ({@code CrossPortalViewRendering}, GUI-portal API — {@code isRendering()} false) get an
 * UNREGISTERED per-pass cold context: IP's original per-pass behavior, no registry churn.
 *
 * <p>INVALIDATION (design §3.1.3):
 * <ul>
 *   <li>dest {@code ClientLevel} unload / LevelRenderer replacement —
 *       {@code ClientWorldLoader.disposeWorldRenderer} (the per-dim dispose seam, reached by both
 *       {@code cleanUp()} and {@code disposeDimensionDynamically}) calls
 *       {@code SodiumInterface.invoker.onWorldRendererDisposed(dim)} →
 *       {@link #invalidateForDimension};</li>
 *   <li>render-distance mismatch at acquire — discard → cold restart;</li>
 *   <li>portal destination-dimension change at acquire — discard → cold restart;</li>
 *   <li>gate flip ({@link ExperimentalCompatGate} consult) — registry cleared; acquire degrades
 *       to unregistered per-pass cold contexts while the gate reads off.</li>
 * </ul>
 *
 * <p>LRU cap: {@code IPGlobal.maxPortalLayer} discipline — the recursion depth bound (default 5)
 * times a generous simultaneously-visible-portal budget. Eldest-accessed entries are evicted;
 * an evicted portal view simply cold-restarts on its next pass (correct, just briefly blank —
 * the same accepted degradation envelope as any cold context, design §3.1.5).
 *
 * <p>Thread safety: render thread only (all callers are inside the render bracket / client init /
 * client dispose paths). No synchronization needed or provided.
 *
 * <p>Classload discipline: this class references sodium types only through
 * {@link SodiumRenderingContext}; it is only ever class-loaded from {@code OnSodiumPresent}
 * (installed exclusively when sodium is present + gate/lever active), from the no-op-guarded
 * invoker dispose hook override, and from {@code MixinSodiumRenderSectionManager} (a sodium-class
 * mixin, applied only when sodium is present) — never on a sodium-absent runtime.
 */
@Environment(EnvType.CLIENT)
public class SodiumContextRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * BLOCKER-2 (verify lens A) — the global monotonic pass serial. At each
     * {@code MixinSodiumRenderSectionManager.ip_swapContext} exchange it ABSORBS both frame
     * values observable at the swap boundary (the swapped-in and the parked-outer frame), is
     * incremented once, and is assigned to the swapped-in side's {@code frame} (C2-1 vA2 note 2
     * + vB correction — strict absorb-then-increment, replacing the earlier pre-increment
     * max-merge). The serial is therefore >= every frame value ever seen at any swap boundary,
     * so no two swapped-in passes can EVER run the shared RSM with EQUAL frame counters (the
     * VisibleChunkCollector.visit reset/add skip — persistent holey aperture + cross-appended
     * render lists), including under FlawlessFrames-armed multi-increment passes.
     *
     * <p>Render-thread-only PLAIN field — deliberately no atomic/volatile: every
     * {@code ip_swapContext} caller sits inside the render bracket on the render thread (the same
     * single-thread discipline as the registry map above). An {@code int} rather than a
     * {@code long}: sodium's own {@code RenderSectionManager.frame} is an int advancing once per
     * pass, so the ~2^31 wrap horizon is the substrate's own (see the wrap comment at the use
     * site).
     */
    public static int GLOBAL_PASS_SERIAL = 0;

    /**
     * Cold-context freshness arming (C2-1 deliverable 6): frames of forced main-thread rebuild
     * when a NEW registered portal view appears. n=1 — ForceMainThreadRebuild's frame latch is
     * consumed at the NEXT RenderStates pre-render (the current frame's latch has already been
     * taken by the time a mid-frame acquire runs), and one full FlawlessFrames frame raises
     * sodium's build-drain cap to renderDistance iterations (census (e) mechanism 1) — a complete
     * synchronous build catch-up. The cold pass ITSELF converges same-pass regardless via the
     * blocking consumeCullTaskResults(true) + the ACTC cold sync-render path, so a larger n would
     * only add whole-scene sync-build frames (the IP note on ForceMainThreadRebuild:
     * "sometimes effective but not always" — bounded arming, not a guarantee). Unregistered
     * per-pass contexts (GUI portals etc.) deliberately do NOT arm — they are cold EVERY pass and
     * would keep FlawlessFrames permanently on.
     */
    private static final int COLD_CONTEXT_REBUILD_FRAMES = 1;

    private record Key(UUID portalId, int layer) {}

    /**
     * Access-ordered LRU. Cap = maxPortalLayer (recursion bound, IPGlobal default 5) × 16
     * simultaneously-tracked portal views per layer — far above any real scene, small enough that
     * retained SectionTrees/render-lists stay bounded.
     */
    private static final int MAX_ENTRIES = Math.max(16, IPGlobal.maxPortalLayer * 16);

    private static final LinkedHashMap<Key, SodiumRenderingContext> CONTEXTS =
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, SodiumRenderingContext> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

    /**
     * The acquire behind {@code OnSodiumPresent.createNewContext(renderDistance)}.
     */
    public static SodiumRenderingContext acquire(int renderDistance) {
        // Gate-flip consult (design §3.1.3 invalidation set): the invoker can only have been
        // installed with the gate/lever active at client init, but the gate boolean is a mutable
        // lever — if it was flipped off mid-session, drop all persistent state and degrade to
        // IP's per-pass-fresh behavior (still correct, just non-converging) instead of keeping
        // stale trees alive behind a disabled gate.
        if (!isGateActive()) {
            if (!CONTEXTS.isEmpty()) {
                LOGGER.info("[imm_ptl sodium compat] gate flipped off — dropping {} contexts",
                    CONTEXTS.size());
                CONTEXTS.clear();
            }
            return new SodiumRenderingContext(renderDistance);
        }

        if (!PortalRendering.isRendering()) {
            // Non-portal facade caller (GUI portal / cross-portal view) — unregistered per-pass
            // cold context, IP's original lifetime.
            return new SodiumRenderingContext(renderDistance);
        }

        Portal portal = PortalRendering.getRenderingPortal();
        Key key = new Key(portal.getUUID(), PortalRendering.getPortalLayer());
        ResourceKey<Level> destDim = portal.getDestDim();

        SodiumRenderingContext stored = CONTEXTS.get(key); // access-order touch
        if (stored != null) {
            if (stored.renderDistance != renderDistance) {
                // Render-distance mismatch (user changed the setting, or the portal's computed
                // render distance moved) — discard, cold restart (design §3.1.3).
                CONTEXTS.remove(key);
                stored = null;
            }
            else if (stored.dimension != destDim) {
                // Portal destination changed since this context's trees were built — they belong
                // to another dimension's RSM/world. Discard, cold restart.
                CONTEXTS.remove(key);
                stored = null;
            }
        }

        if (stored == null) {
            stored = new SodiumRenderingContext(renderDistance);
            stored.dimension = destDim;
            CONTEXTS.put(key, stored);
            // Cold-context freshness arming — see COLD_CONTEXT_REBUILD_FRAMES.
            ForceMainThreadRebuild.forceMainThreadRebuildFor(COLD_CONTEXT_REBUILD_FRAMES);
        }
        return stored;
    }

    /**
     * Dest ClientLevel unload / LevelRenderer replacement invalidation. Called (via the
     * {@code SodiumInterface.invoker.onWorldRendererDisposed} facade) from
     * {@code ClientWorldLoader.disposeWorldRenderer} — the single per-dim dispose seam, reached
     * by both the full {@code cleanUp()} walk and {@code disposeDimensionDynamically}. Every
     * context whose trees were built against that dimension's (about-to-die) RSM is dropped.
     */
    public static void invalidateForDimension(ResourceKey<Level> dimension) {
        Iterator<Map.Entry<Key, SodiumRenderingContext>> it = CONTEXTS.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().dimension == dimension) {
                it.remove();
            }
        }
    }

    private static boolean isGateActive() {
        return ExperimentalCompatGate.ENABLE_SODIUM_IRIS_COMPAT
            || Boolean.getBoolean("seamlessportals.experimentalSodiumCompat");
    }

    /** NOTE-5 hardening one-shot latch — see {@link #logFailedCullTaskConsumeOnce}. */
    private static boolean loggedFailedCullTaskConsume = false;

    /**
     * NOTE-5 hardening (verify lens A): one-shot error report for a FAILED in-flight CullTask
     * whose blocking consume threw during {@code ip_swapContext}'s consume-before-swap. Lives
     * here (a plain class — static initialisers provably run) rather than as mixin-class static
     * state, which Mixin does not reliably initialise in the merged target. Render thread only.
     */
    public static void logFailedCullTaskConsumeOnce(RuntimeException e) {
        if (!loggedFailedCullTaskConsume) {
            loggedFailedCullTaskConsume = true;
            LOGGER.error(
                "[imm_ptl sodium compat] consuming the in-flight CullTask failed before a "
                    + "context swap — dropping the task, closing the safe-read phase and "
                    + "continuing (one recoverable stale-cull frame). "
                    + "Further occurrences are not logged.",
                e
            );
        }
    }
}
