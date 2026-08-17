package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.warwa.seamlessportals.render.FrontClipping.Snapshot;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.Plane;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * S11-C (Slice A) — the R3 per-entity clip-bracketing SEAM (design round
 * {@code migration/port-notes/S11-R3-clip-bracketing.md} §3.1). The ported
 * {@link CrossPortalEntityRenderer} never branches on the delivery mechanism (D3: its IP body stays
 * flag-clean); this class owns BOTH candidate mechanisms and the S18 A/B switch, and realizes IP's
 * per-entity {@code endBatch()} clip-split semantics on the 26.2 submit render model (render-core
 * G2/G3/G23; there is no mid-batch flush on 26.2 — the draw-call boundary must come from the submit-side
 * data model).
 *
 * <h2>Mechanism A — {@code SUBMIT_ORDER_UNIFORM} (DEFAULT, design §1)</h2>
 * {@code SubmitNodeStorage.order(int)} partitions submits into per-order {@link SubmitNodeCollection}s
 * (26.2:SubmitNodeStorage.java:33-37), each owning its OWN {@link FeatureRenderPhase} objects
 * (26.2:SubmitNodeCollection.java:54-68). {@code PhaseSubmitGrouper} is built per phase object and
 * {@code PreparedFrame.executePhase} executes only that phase's groups
 * (26.2:FeatureRenderDispatcher.java:81,258-266) — so submits placed under a dedicated order execute as
 * their own draw call(s), the 26.2-native equivalent of IP's endBatch split. Each clipped entity gets a
 * fresh order; its order's phase objects are registered against the entity's view-space clip plane; the
 * S12 {@code executePhase} HEAD/RETURN mixin brackets each registered phase with
 * {@link com.warwa.seamlessportals.render.FrontClipping#capture()}/{@code restore(Snapshot)} so the
 * always-on {@code GlCommandEncoderClipMixin} uploads the entity's plane to that group's draws only.
 *
 * <h2>Mechanism B — {@code ISOLATED_STORAGE_BRACKET} (design §2)</h2>
 * Each clipped render is submitted into its OWN one-entity {@link SubmitNodeStorage} and drawn later via a
 * DEDICATED {@link FeatureRenderDispatcher} (mechanism B MUST construct its own — the main dispatcher's
 * single {@code PreparedFrame} throws "PreparedFrame already in use" if re-entered,
 * 26.2:FeatureRenderDispatcher.java:35,187-190), bracketed by the plane store. The draw fires from
 * {@link #drawBracketedEntitiesIfAny(SubmitNodeStorage)} at the S18-DECIDED per-pass call sites
 * (design §2.1.3 resolved): main pass via {@link #onMainPassBeforeTranslucentTerrain()} (Fabric
 * {@code BEFORE_TRANSLUCENT_TERRAIN} — IP's end-of-entity-rendering slot, before translucent terrain);
 * dest passes via direct calls after each {@code renderAllFeatures} in
 * {@code SecondaryWorldRenderCore.renderPortalEntities} / {@code renderPortalEntitiesSameDim}.
 *
 * <p><b>C4 rider (BINDING):</b> neither mechanism is hard-committed — both are always compiled and both
 * sets of hooks are registered (each inert when not selected), so the A/B flip is a no-restart switch read
 * live per frame from the {@code IPGlobal.crossPortalEntityClipMechanism} field via {@link #getMechanism()}.
 * That live-read field is the landed interim one-line switch (the rider's "documented one-line switch");
 * persisting it as an {@code IPConfig} in-game config-screen entry rides the S12 config/mixin wiring
 * (design §5).
 *
 * <p><b>SIGN NOTE (D4.4):</b> every plane→view-space conversion reuses the S11-B qouteall
 * {@link FrontClipping} bridge feed ({@code captureOuterClipping}/{@code captureInnerClipping}: column-form
 * {@code M·v}, {@code planeW = c}; the anti-"fix" guard against {@code mulTranspose} applies). The view
 * rotation R is sourced from {@link CameraRenderState#viewRotationMatrix} (the world→view rotation the
 * 26.2 model-view stack applies to camera-relative submit poses at draw, LevelRenderer.render:170-172);
 * the exact equivalence to the pushed {@code modelViewMatrix} + the scaling-portal edge are S18 runtime
 * checks (design §6, S11-B §3.1).
 *
 * <p>LIVE since S13 (the S12 anchor + executePhase mixins are registered in
 * seamlessportals-ip-client.mixins.json and fire per pass); Mechanism B's draw sites + the
 * ownRenderBuffers endFrame lifecycle landed at S18 — both mechanisms are now fully wired for the
 * C4 live A/B.
 */
@Environment(EnvType.CLIENT)
public class PerEntityClipBracket {

    public enum Mechanism {
        /** Default: per-draw clip uniform keyed off submit-order partitioning (design §1). */
        SUBMIT_ORDER_UNIFORM,
        /** Fallback: one-entity SubmitNodeStorage + renderAllFeatures bracketed by clip state (design §2). */
        ISOLATED_STORAGE_BRACKET
    }

    /** Live-read each frame from the {@code IPGlobal.crossPortalEntityClipMechanism} field — the S18 A/B
     *  switch (design §5). Persistence is LANDED: {@code IPConfig.crossPortalEntityClipMechanism}
     *  (IPConfig:51, null-guarded :179, synced to IPGlobal at :196) — flip via
     *  {@code config/immersive_portals.json} + restart, or the config GUI. */
    public static Mechanism getMechanism() {
        return IPGlobal.crossPortalEntityClipMechanism;
    }

    // ------------------------------------------------------------------------------------------------
    // Shared band constants (design §1.2.1). Each clipped entity claims its OWN band of consecutive submit
    // orders in the MAIN storage, well above every vanilla submit order. Vanilla entity/block layers use
    // order() for INTRA-entity layering (eyes/emissive at order(1), some inner layers at order(-1),
    // banner/shield masks up to ~size+1) — so one entity spans several small RELATIVE orders, and its
    // submits must all land in a collision-free absolute band [base + minRel, base + maxRel]. Starting the
    // band well above vanilla (~[-1, ~20]) and spacing bands by CLIP_ORDER_STRIDE guarantees no collision
    // with vanilla OR between clipped entities; clipped entities execute after all vanilla orders within
    // each pass (design §1.2.1/§1.3 draw-order perturbation, same class as IP's endBatch splits). See
    // OffsetStorage.
    private static final int INITIAL_CLIP_ORDER = 1000;
    private static final int CLIP_ORDER_STRIDE = 64;

    // CASE-2 projections with NO inner clip plane must draw UNCLIPPED — IP disabled clipping before the
    // projection loop (CrossPortalEntityRenderer :101) and the isRendering branch adds no clip (IP
    // :184-204). On 26.2 the ambient store during a dest pass is the persistent CASE-3 inner clip (NOT
    // disabled), so a null plane must register this EXPLICITLY DISABLED snapshot rather than "nothing"
    // (which would leave the projection under the dest inner clip — the OPPOSITE of IP). enabled=false +
    // the (0,0,0,1) no-op plane; com.warwa restore() honors enabled=false (disables GL_CLIP_DISTANCE0).
    // (Verifier-1 P1 fix.)
    private static final Snapshot DISABLED_CLIP = new Snapshot(0f, 0f, 0f, 1f, false);

    // Mechanism A: phase identity → the view-space plane the executePhase bracket pushes for that phase's
    // draws. GLOBAL and NOT destructively cleared per pass. A nested dest LevelRenderer pass is invoked
    // MID-main-framegraph (StencilPortalRenderer.renderOnePortal at AFTER_TRANSLUCENT_TERRAIN,
    // PortalContextSwitch:821), i.e. BETWEEN the outer main pass's submit and its late-phase execute; a
    // global clear at the nested pass's submitEntities-HEAD would WIPE the outer pass's not-yet-executed
    // registrations (Verifier-1 P2). Phase objects are storage-unique (each order owns distinct phase
    // instances, 26.2:SubmitNodeCollection.java:54-68), so the registry safely holds every live pass's
    // entries at once, keyed by identity; onFrameSubmitBegin removes ONLY the ending pass's own prior-frame
    // phases (tracked in PassState.registeredPhases).
    private static final IdentityHashMap<FeatureRenderPhase<?>, Snapshot> phaseRegistry =
        new IdentityHashMap<>();

    // Per-LevelRenderer-instance (per-storage) frame state, keyed by the pass's SubmitNodeStorage identity.
    // Scopes the per-frame reset so a nested dest pass never destroys the outer pass's live band counter /
    // registrations / deferred brackets (Verifier-1 P2). Bounded by the number of live LevelRenderer
    // instances (main + cached secondaries), which reuse their storage across frames
    // (26.2:LevelRenderer.java:107).
    private static final IdentityHashMap<SubmitNodeStorage, PassState> passStates = new IdentityHashMap<>();

    /** Per-pass (per-storage) frame-scoped seam state. */
    private static final class PassState {
        int nextClipOrder = INITIAL_CLIP_ORDER;
        // Mechanism A: exactly the global-registry phases THIS pass registered this frame, so the next
        // frame's HEAD reset removes only its own entries, leaving other live passes' entries intact.
        final List<FeatureRenderPhase<?>> registeredPhases = new ArrayList<>();
        // Mechanism B: this pass's deferred isolated-storage draws, drained at its own draw site.
        final List<BracketEntry> deferredBrackets = new ArrayList<>();
    }

    @Nullable
    private static FeatureRenderDispatcher ownDispatcher;

    // Lifecycle: registered into ClientWorldLoader's core-owned per-frame endFrame walk at allocation
    // (S18; memory gpu-buffer-leak-endframe / S11-A B5/B6 — see getOrCreateOwnDispatcher).
    @Nullable
    private static RenderBuffers ownRenderBuffers;

    /** One deferred isolated-storage draw: a filled one-entity storage + its (nullable) clip plane. */
    private static final class BracketEntry {
        final SubmitNodeStorage storage;
        @Nullable
        final Snapshot plane;

        BracketEntry(SubmitNodeStorage storage, @Nullable Snapshot plane) {
            this.storage = storage;
            this.plane = plane;
        }
    }

    private static PassState passStateFor(SubmitNodeStorage storage) {
        return passStates.computeIfAbsent(storage, s -> new PassState());
    }

    /**
     * Frame reset — called from the submitEntities-HEAD anchor (design §3.3 row 1). PER-STORAGE: resets
     * only THIS pass's band counter / deferred brackets and removes only THIS pass's prior-frame phase
     * registrations from the global registry, so a nested dest pass invoked mid-main-framegraph does not
     * wipe the outer pass's still-pending state (Verifier-1 P2).
     */
    public static void onFrameSubmitBegin(SubmitNodeStorage storage) {
        PassState st = passStateFor(storage);
        for (FeatureRenderPhase<?> phase : st.registeredPhases) {
            phaseRegistry.remove(phase);
        }
        st.registeredPhases.clear();
        st.deferredBrackets.clear();
        st.nextClipOrder = INITIAL_CLIP_ORDER;
    }

    /**
     * CASE 1: submit a collided main-pass entity with an OUTER clip plane (design §1.2.2 / §2.1.2). The
     * caller ({@link CrossPortalEntityRenderer#submitMainPassEntity}) has already resolved the last-wins
     * colliding portal.
     */
    public static void submitMainPassEntityClipped(
        EntityRenderDispatcher dispatcher, EntityRenderState state,
        CameraRenderState cam, double camX, double camY, double camZ,
        PoseStack poseStack, SubmitNodeStorage storage, Portal collidingPortal
    ) {
        // IP's setupOuterClipping math via the S11-B bridge, WITHOUT touching the live store (design §1.2.2).
        Snapshot outerPlane = FrontClipping.captureOuterClipping(collidingPortal, cam.viewRotationMatrix);

        if (getMechanism() == Mechanism.ISOLATED_STORAGE_BRACKET) {
            deferIsolatedBracket(storage, dispatcher, state, cam, camX, camY, camZ, poseStack, outerPlane);
            return;
        }

        // Mechanism A: the entity's own draw-order band + register the plane against every touched phase.
        // outerPlane == null (portal shape has no outer clipping) → unregistered → unclipped draw under the
        // main pass's ambient (disabled) store, matching IP's setupOuterClipping(null) → disableClipping.
        submitToOwnOrderBand(dispatcher, state, cam, camX, camY, camZ, poseStack, storage, outerPlane);
    }

    /**
     * CASE 2: submit one projected entity with an INNER clip plane (design §1.2.3 / §2.1.2). {@code
     * innerClipPlane == null} means the projection must draw UNCLIPPED — IP disabled clipping before the
     * projection loop (CrossPortalEntityRenderer :101) and the isRendering branch adds no clip (IP
     * :184-204; the rough flipped/reverse/isHidden checks stand in for a second culling plane) — so the
     * seam registers an EXPLICITLY DISABLED snapshot ({@link #DISABLED_CLIP}), NOT "nothing" (which on 26.2
     * would leave the projection under the dest pass's persistent CASE-3 inner clip, the opposite of IP;
     * Verifier-1 P1). Coordinates are the camera-substituted projection position: {@code state.{x,y,z} -
     * newCameraPos} (identical to IP feeding newCameraPos into ip_myRenderEntity).
     */
    public static void submitProjectedEntityClipped(
        EntityRenderDispatcher dispatcher, EntityRenderState state,
        CameraRenderState cam, Vec3 newCameraPos,
        PoseStack poseStack, SubmitNodeStorage storage, @Nullable Plane innerClipPlane,
        boolean seamBand
    ) {
        Snapshot innerPlane = innerClipPlane == null
            ? null
            : FrontClipping.captureInnerClipping(
                innerClipPlane, cam.viewRotationMatrix, seamBand);
        if (innerPlane == null) {
            // No inner plane (isRendering branch always; else branch when getInnerClipping()/useFrontClipping
            // yields none): IP draws the projection UNCLIPPED (CrossPortalEntityRenderer :101 disableClipping,
            // or :206/:210 setupInnerClipping(null) → disableClipping). Register an EXPLICITLY DISABLED
            // snapshot so the entity's band draws unclipped, NOT under the ambient dest inner clip
            // (Verifier-1 P1). Fed to Mechanism B's bracket below for the same reason.
            innerPlane = DISABLED_CLIP;
        }

        double x = state.x - newCameraPos.x;
        double y = state.y - newCameraPos.y;
        double z = state.z - newCameraPos.z;

        if (getMechanism() == Mechanism.ISOLATED_STORAGE_BRACKET) {
            deferIsolatedBracket(storage, dispatcher, state, cam, x, y, z, poseStack, innerPlane);
            return;
        }

        submitToOwnOrderBand(dispatcher, state, cam, x, y, z, poseStack, storage, innerPlane);
    }

    // ------------------------------------------------------------------------------------------------
    // Mechanism dispatch helpers.
    // ------------------------------------------------------------------------------------------------

    /**
     * Mechanism A: route one entity's submits — which may span several relative orders internally — into a
     * dedicated, collision-free absolute-order band of the MAIN storage (via {@link OffsetStorage}), then
     * register the (nullable) clip plane against exactly the collections the entity touched. Every submit
     * stays in its correct vanilla framegraph pass; the clip is scoped to the entity's own draw calls.
     */
    private static void submitToOwnOrderBand(
        EntityRenderDispatcher dispatcher, EntityRenderState state, CameraRenderState cam,
        double x, double y, double z, PoseStack poseStack, SubmitNodeStorage storage,
        @Nullable Snapshot plane
    ) {
        PassState st = passStateFor(storage);
        int base = st.nextClipOrder;
        st.nextClipOrder += CLIP_ORDER_STRIDE;
        OffsetStorage offset = new OffsetStorage(storage, base);
        dispatcher.submit(state, cam, x, y, z, poseStack, offset);
        if (plane != null) {
            for (SubmitNodeCollection collection : offset.touched) {
                registerPhases(collection, plane, st);
            }
        }
    }

    /**
     * Mechanism B: submit one entity into its OWN one-entity {@link SubmitNodeStorage} (which naturally
     * preserves its internal multi-order layering) and record it + its (nullable) plane against the pass's
     * {@link PassState} for the deferred isolated-storage draw in
     * {@link #drawBracketedEntitiesIfAny(SubmitNodeStorage)}. {@code passStorage} is the pass's MAIN storage
     * (the per-pass key), not the one-entity scratch storage.
     */
    private static void deferIsolatedBracket(
        SubmitNodeStorage passStorage,
        EntityRenderDispatcher dispatcher, EntityRenderState state, CameraRenderState cam,
        double x, double y, double z, PoseStack poseStack, @Nullable Snapshot plane
    ) {
        SubmitNodeStorage scratch = new SubmitNodeStorage();
        dispatcher.submit(state, cam, x, y, z, poseStack, scratch);
        passStateFor(passStorage).deferredBrackets.add(new BracketEntry(scratch, plane));
    }

    private static void registerPhases(SubmitNodeCollection collection, Snapshot snapshot, PassState st) {
        for (FeatureRenderPhase<?> phase : collection.allPhases()) {
            phaseRegistry.put(phase, snapshot);
            st.registeredPhases.add(phase);
        }
    }

    /**
     * A {@link SubmitNodeStorage} that OFFSETS every order into a target (main) storage's dedicated band:
     * {@code order(r)} → {@code main.order(base + r)}, and the inherited direct collector methods (which all
     * route through {@code this.order(0)}) → {@code main.order(base)}. This lands one entity — whose layers
     * submit across several relative orders (26.2 uses {@code order()} for intra-entity layering) — entirely
     * inside the main storage's framegraph passes under a collision-free absolute band, so Mechanism A's
     * clip plane registers against exactly the collections it touched while every submit stays in its
     * correct vanilla pass. Overriding {@code order} alone suffices: all of {@code SubmitNodeStorage}'s
     * collector methods delegate to {@code this.order(0)} (26.2:SubmitNodeStorage.java:40-148).
     */
    private static final class OffsetStorage extends SubmitNodeStorage {
        private final SubmitNodeStorage main;
        private final int base;
        private final List<SubmitNodeCollection> touched = new ArrayList<>();

        OffsetStorage(SubmitNodeStorage main, int base) {
            this.main = main;
            this.base = base;
        }

        @Override
        public SubmitNodeCollection order(int order) {
            SubmitNodeCollection collection = main.order(base + order);
            if (!touched.contains(collection)) {
                touched.add(collection);
            }
            return collection;
        }
    }

    /**
     * ★ ROUND 43 — a direct phase override for pass-level exemptions (the r42 outline regression):
     * the pass-wide clip re-arm in {@code SecondaryWorldRenderCore} correctly clips particle/entity
     * feature draws, but the block-selection outline lives ON the seam plane and fragment-fights
     * the clip boundary (user-reported dest-side flicker). The draw sites register the storage's
     * outline phases with a DISABLED snapshot around {@code renderAllFeatures}; the existing
     * executePhase bracket then swaps to keep-all for exactly those draws. Callers must
     * unregister in a finally (the registry is global and phase objects persist per storage).
     */
    public static void registerPhaseOverride(FeatureRenderPhase<?> phase, Snapshot snapshot) {
        phaseRegistry.put(phase, snapshot);
    }

    public static void unregisterPhaseOverride(FeatureRenderPhase<?> phase) {
        phaseRegistry.remove(phase);
    }

    /**
     * Mechanism A execute bracket (called by the S12 {@code executePhase} HEAD mixin, design §1.2.4). If
     * the phase is registered, captures the current store, pushes the registered plane, and returns the
     * captured previous snapshot for {@link #endPhase} to restore at RETURN. Returns {@code null} when the
     * phase is not registered (endPhase then no-ops) — the ambient store governs that phase's draws.
     */
    @Nullable
    public static Snapshot beginPhaseIfRegistered(FeatureRenderPhase<?> phase) {
        Snapshot registered = phaseRegistry.get(phase);
        if (registered == null) {
            return null;
        }
        Snapshot prev = com.warwa.seamlessportals.render.FrontClipping.capture();
        com.warwa.seamlessportals.render.FrontClipping.restore(registered);
        return prev;
    }

    /** Companion to {@link #beginPhaseIfRegistered} (S12 executePhase RETURN mixin). */
    public static void endPhase(@Nullable Snapshot prev) {
        if (prev != null) {
            com.warwa.seamlessportals.render.FrontClipping.restore(prev);
        }
    }

    /**
     * Mechanism B deferred bracket (design §2.1.2/§2.1.3), called from the S18-chosen per-pass draw-site
     * hook with the pass's MAIN storage. Draws each recorded one-entity storage for THAT pass through the
     * OWN dispatcher, bracketed by the plane store: capture → feed the entity's plane (if any) →
     * {@code renderAllFeatures} → restore. No-op (and never touches the own dispatcher) when the pass
     * recorded nothing — so when Mechanism A is active this is inert. Per-storage so a nested dest pass does
     * not consume the outer pass's brackets before the outer draw site runs (Verifier-1 P2).
     */
    public static void drawBracketedEntitiesIfAny(SubmitNodeStorage storage) {
        PassState st = passStates.get(storage);
        if (st == null || st.deferredBrackets.isEmpty()) {
            return;
        }
        // S18 throw fence (verify fold wf_a5599a71-df0, all three lenses): the S15 same-dim
        // discipline. Without it, one swallowed dest-side throw that wedges the own dispatcher's
        // PreparedFrame open (a prepareFrame throw AFTER begin() — renderAllFeatures'
        // try-with-resources only closes a frame prepareFrame RETURNED) converts into an
        // UNCAUGHT "PreparedFrame already in use" at the main-pass site every later frame —
        // a deterministic crash during the C4 live A/B. Three strikes dead-latch B for the
        // session (degrades to: bracketed entities missing — the pre-S18 state).
        if (bracketThrowCount >= 3) {
            st.deferredBrackets.clear();
            return;
        }
        try {
            FeatureRenderDispatcher dispatcher = getOrCreateOwnDispatcher();
            for (BracketEntry entry : st.deferredBrackets) {
                Snapshot prev = com.warwa.seamlessportals.render.FrontClipping.capture();
                try {
                    if (entry.plane != null) {
                        com.warwa.seamlessportals.render.FrontClipping.restore(entry.plane);
                    }
                    dispatcher.renderAllFeatures(entry.storage);
                } finally {
                    // ALWAYS restore — a skipped restore leaks the entry's plane (or a
                    // DISABLED_CLIP that DISARMS the dest pass's CASE-3 inner clip) into the
                    // ambient store for the rest of the frame (verify scenario A).
                    com.warwa.seamlessportals.render.FrontClipping.restore(prev);
                }
            }
        } catch (Throwable t) {
            bracketThrowCount++;
            // The wedged-PreparedFrame recovery: discard the dispatcher (its PreparedFrame may be
            // stuck open); the next attempt rebuilds it around the SAME registered ownRenderBuffers
            // (no re-registration, no buffer leak). One-shot log per session (render-thread
            // logging discipline).
            ownDispatcher = null;
            if (!bracketThrowLogged) {
                bracketThrowLogged = true;
                qouteall.q_misc_util.Helper.err(
                    "[PerEntityClipBracket] bracket draw swallowed (first per session, strike "
                        + bracketThrowCount + "/3): " + t);
                t.printStackTrace();
            }
        } finally {
            // Drop strands regardless of outcome (the S15 discipline) — a half-drawn list must
            // not be re-attempted by a later pass under a different camera.
            st.deferredBrackets.clear();
        }
    }

    // S18 throw fence state (see drawBracketedEntitiesIfAny). Session-scoped; reset at cleanup.
    private static int bracketThrowCount = 0;
    private static boolean bracketThrowLogged = false;

    private static FeatureRenderDispatcher getOrCreateOwnDispatcher() {
        if (ownDispatcher == null) {
            Minecraft mc = Minecraft.getInstance();
            // Dedicated RenderBuffers — mechanism B MUST NOT reuse the main dispatcher (its single
            // PreparedFrame is in use during the main pass; begin() throws "PreparedFrame already in use",
            // 26.2:FeatureRenderDispatcher.java:187-190). S18 lifecycle wiring: registered into
            // ClientWorldLoader's core-owned per-frame endFrame walk (endFrameOnSecondaryFeatureBuffers,
            // driven from GameRenderer.render TAIL flag-ON) — the exact S15 same-dim pipeline pattern
            // (memory gpu-buffer-leak-endframe; every mod-created RenderBuffers needs the per-frame
            // endFrame or its StagedVertexBuffer pools never fence-recycle).
            // On a throw-fence rebuild (ownDispatcher nulled, see drawBracketedEntitiesIfAny's
            // catch) the SAME already-registered buffers are reused — only the dispatcher (and its
            // possibly-wedged PreparedFrame) is discarded.
            if (ownRenderBuffers == null) {
                ownRenderBuffers = new RenderBuffers(0);
                qouteall.imm_ptl.core.ClientWorldLoader.registerCoreOwnedFeatureBuffers(ownRenderBuffers);
            }
            ownDispatcher = new FeatureRenderDispatcher(
                ownRenderBuffers,
                mc.getModelManager(),
                mc.getAtlasManager(),
                mc.font,
                mc.gameRenderer.gameRenderState()
            );
        }
        return ownDispatcher;
    }

    /**
     * S18 main-pass Mechanism B draw site (design §2.1.3 candidate (i), decided S18): called from the
     * Fabric {@code LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN} registration — inside the main-pass
     * framegraph lambda AFTER all entity feature phases (solid/translucent/outline) execute and BEFORE
     * translucent terrain draws. This is IP's exact slot (IP's per-entity endBatch draws + CASE-2
     * projections fired at end-of-entity-rendering, before translucent terrain), so water/glass in front
     * of a bracketed entity still tints it: the entity writes depth first and translucent terrain blends
     * over it. Drawing later (e.g. AFTER_TRANSLUCENT_TERRAIN) would depth-reject the entity behind
     * already-drawn translucent terrain — the entity would vanish through water. The model-view stack
     * still holds the view rotation there (pushed for the whole framegraph execute,
     * 26.2:LevelRenderer.java:170-172/:252), matching Mechanism A's phase-draw transform.
     *
     * <p>Dest (portal) passes do NOT run a framegraph — their draw sites are direct calls in
     * {@code SecondaryWorldRenderCore.renderPortalEntities} / {@code renderPortalEntitiesSameDim}
     * right after each pass's {@code renderAllFeatures}, inside the pushed dest view matrix and the
     * armed inner clip + stencil. The event never fires for them; the defensive guard below only
     * protects against a future re-entrant framegraph render.
     */
    public static void onMainPassBeforeTranslucentTerrain() {
        if (qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // 26.2 renders frames MID-PACKET: skip (never assert) on the transient mc.level mismatch frame.
        if (mc.level == null || mc.levelRenderer == null) {
            return;
        }
        SubmitNodeStorage storage =
            ((com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin) mc.levelRenderer)
                .seamlessportals$getSubmitNodeStorage();
        if (storage != null) {
            drawBracketedEntitiesIfAny(storage);
        }
    }

    /**
     * Evict one pass's seam state (its band counter, its deferred Mechanism-B brackets, and its
     * prior-frame phase registrations from the global registry). Called by the same-dim throw fence
     * when it REPLACES its {@code SubmitNodeStorage} (the replaced storage's PassState would otherwise
     * linger forever with orphaned brackets), and available for dimension-churn eviction of a discarded
     * secondary renderer's storage (design §1.2.5).
     */
    public static void evictPassState(SubmitNodeStorage storage) {
        PassState st = passStates.remove(storage);
        if (st != null) {
            for (FeatureRenderPhase<?> phase : st.registeredPhases) {
                phaseRegistry.remove(phase);
            }
        }
    }

    /**
     * Full seam reset at client-cleanup boundaries (world unload / dynamic dimension removal) —
     * clears BOTH identity maps together so no stale phase registration can clip a reused phase
     * object next session (phase identity is stable across frames: collections persist in the AVL
     * map once used, design §1.2.5). Registered alongside {@code collidedEntities.clear()} via
     * {@code CrossPortalEntityRenderer.cleanUp()}. No frame is in flight at these boundaries.
     */
    public static void onClientCleanup() {
        phaseRegistry.clear();
        passStates.clear();
        // Per-session throw-fence reset (the S15 same-dim discipline: a new world session gets a
        // fresh 3-strike budget; a wedged dispatcher from the old session is discarded for rebuild).
        bracketThrowCount = 0;
        bracketThrowLogged = false;
        ownDispatcher = null;
    }
}
