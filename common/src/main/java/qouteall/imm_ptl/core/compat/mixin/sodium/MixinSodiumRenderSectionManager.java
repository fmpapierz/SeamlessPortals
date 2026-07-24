package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.async.CullTask;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.AsyncCameraTimingControl;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumRenderSectionManager;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumContextRegistry;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumRenderingContext;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.EnumMap;
import java.util.Map;

/**
 * C2-1 — the D1 widened context swap + the D5 entity-cull neutralize on Sodium 0.9.1's
 * {@code RenderSectionManager}. Re-expression of IP's {@code MixinSodiumRenderSectionManager}
 * (depth doc {@code migration/C2_IP_COMPAT_DEPTH.md} file #2) against the SectionTree/async-cull
 * model. Governing swap list: port-note {@code migration/port-notes/C2-sodium-iris.md} §2; every
 * {@code @Shadow} below is javap-proven against {@code sodium-mc26.2-0.9.1-fabric.jar}
 * (sha 14f3388…) — field names/types quoted in {@code migration/C2_0_CENSUS.md} section (c) and
 * re-verified this stage.
 *
 * <p>IP contract preserved (design §3.1.1): each rendered world view owns isolated Sodium
 * visible-section state; the outer world's state is untouched by portal passes; the swap is
 * SYMMETRIC (calling it twice restores the primary state exactly); the invoker facade and the
 * MyGameRenderer bracket (:344-345 swap-in … :424 swap-out in finally) are unchanged from IP.
 * The deviation (D1) is the payload's WIDTH and the context's LIFETIME (persistent registry).
 *
 * <p>Registered in {@code seamlessportals-ip-compat.mixins.json}; the class name carries
 * {@code Sodium} for the plugin's substring gate.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class MixinSodiumRenderSectionManager implements IESodiumRenderSectionManager {

    // NOTE-5 logging lives in SodiumContextRegistry.logFailedCullTaskConsumeOnce — NOT as a
    // static field here: Mixin does not reliably merge mixin-class static initialisers into the
    // target's <clinit>, so an initialised LOGGER/one-shot field could be null/unset at runtime
    // exactly on the degrade path. The registry is a plain class (initialisers provably run).

    // ===== reference-swap set (all javap-proven non-final; census (c) :91) =====================

    @Shadow
    private SortedRenderLists renderLists;

    @Shadow
    private SectionTree renderTree;

    @Shadow
    private CullTask pendingTask;

    @Shadow
    private DeferredTaskList taskLists;

    @Shadow
    private int frame;

    @Shadow
    private boolean needsRenderListUpdate;

    @Shadow
    private boolean cameraChanged;

    @Shadow
    private int cameraStableSince;

    @Shadow
    private Vector3dc cameraPosition;

    @Shadow
    private boolean needsGraphUpdate;

    /**
     * javap: {@code private final int renderDistance} — STILL final on 0.9.1 (census (c) :91),
     * so {@code @Mutable} strips final for the swap, exactly IP's own mechanism.
     */
    @Shadow
    @Final
    @Mutable
    private int renderDistance;

    // ===== the two FINAL members — content-swapped, never reference-swapped (census :177-179) ===

    /** javap: {@code private final Map<CullType, SectionTree> cullResults} (EnumMap at ctor). */
    @Shadow
    @Final
    private Map<CullType, SectionTree> cullResults;

    /** javap: {@code private final AsyncCameraTimingControl cameraTimingControl}. */
    @Shadow
    @Final
    private AsyncCameraTimingControl cameraTimingControl;

    /**
     * NOTE-5 hardening: {@code private final SectionStorage renderSections} (javap-proven; the
     * interface declares {@code startSafeReadPhase()/endSafeReadPhase()}). Needed only for the
     * failed-CullTask repair path — closing a safe-read phase stranded open by a
     * {@code consumeCullTaskResults} throw.
     */
    @Shadow
    @Final
    private SectionStorage renderSections;

    /**
     * javap-proven: {@code private void consumeCullTaskResults(boolean)} — with {@code true} it
     * skips the isDone() poll, calls {@code pendingTask.getResult()} (= future.get(), BLOCKS),
     * flushes the result into cullResults/taskLists, nulls pendingTask and calls
     * {@code renderSections.endSafeReadPhase()} (bytecode offsets 8-23 / 147-175). The target is
     * private, hence the shadow-with-dummy-body form (Java forbids {@code private abstract}).
     */
    @Shadow
    private void consumeCullTaskResults(boolean blocking) {
        throw new AssertionError("shadowed by mixin");
    }

    /**
     * The D1 widened SYMMETRIC three-way tmp swap. Swap-in and swap-out are the SAME operation
     * (IP's double-call restore protocol); the driver is
     * {@code OnSodiumPresent.switchContextWithCurrentWorldRenderer}, which also swaps the
     * SWR-side camera-cache five and brackets this call with {@code scheduleTerrainUpdate()}.
     */
    @Override
    public void ip_swapContext(SodiumRenderingContext context) {
        // ===== D1 race mitigation (b), port-note C2-sodium-iris §4.1 — consume-before-swap =====
        // QueuedSectionStorage's safe-read phase is a plain boolean (NO refcount) and
        // endSafeReadPhase flushes queued section mutations into the LIVE map. If the live
        // context's in-flight CullTask were swapped out while a portal pass schedules + consumes
        // its OWN CullTask on a shared (same-dim) RSM, the portal's endSafeReadPhase would flush
        // the shared SectionStorage WHILE the swapped-out task still traverses it — the exact
        // race the phase exists to prevent. So: BEFORE any swap, drain the in-flight task into
        // the LIVE context with the RSM's own blocking consume. consumeCullTaskResults(true)
        // getResult()-blocks, absorbs the result into the live cullResults/taskLists, nulls
        // pendingTask and closes the safe-read phase (javap offsets 147-175) — its 1:1
        // start/end pairing stays intact. Resulting invariant: NO CullTask is ever outstanding
        // while its context is swapped out (context.pendingTask is always null at rest).
        // Hardening candidate if the blocking wait shows frame cost in the live round: option
        // (a) — refcount the phase via a tiny QueuedSectionStorage mixin (ledgered, §4.1).
        //
        // NOTE-5 hardening (verify lens A): a FAILED CullTask makes getResult() rethrow out of
        // consumeCullTaskResults — uncaught, that would escape through the MyGameRenderer :424
        // finally, stranding BOTH contexts half-swapped plus an OPEN safe-read phase. Catch,
        // one-shot LOGGER.error, repair the live side, continue with the swap (the failed frame's
        // cull result is already lost either way; the driver's post-swap scheduleTerrainUpdate
        // re-culls both sides).
        //
        // The REPAIR (smallest sound one, bytecode-grounded): consumeCullTaskResults(true) can
        // throw at any point before the null/close tail (getResult, the checkcast, the
        // cullResults puts) — the repair is sound for all of them, since every such throw exits
        // BEFORE the null/close tail (offsets 147-175) runs: this.pendingTask is
        // still non-null and the safe-read phase is still OPEN (scheduleAsyncWork's pairing:
        // putfield pendingTask offset 54 → startSafeReadPhase offset 61; only the consume tail
        // closes it). So: null pendingTask
        // (drop the dead task) + renderSections.endSafeReadPhase() (the interface declares it;
        // QueuedSectionStorage.endSafeReadPhase is IDEMPOTENT — bytecode: isQueueing false →
        // jump to the final putfield-false, no flush — so no extra "was it open?" state is
        // needed; this IS the flush sodium itself would have run, at the same call depth).
        //
        // FORCED-CHOICE NOTE vs the verdict text: the verdict named context.resetRsmSideToCold()
        // as the repair, but the broken state lives on the LIVE RSM side (this.pendingTask + this
        // RSM's renderSections phase), which that context method cannot reach — the equivalent
        // live-side cold repair above is implemented instead; the incoming context is untouched
        // by the failure.
        //
        // RESIDUAL (ledgered): the queued section mutations flushed by this endSafeReadPhase were
        // deferred for a task that never delivered its tree — the live renderTree may reference
        // sections just removed until the re-cull lands (one recoverable stale frame, the same
        // envelope as sodium's own failed-frame behavior); and if sodium's async cull executor is
        // permanently broken, this logs once and every pass degrades to stale trees — visible,
        // not crashing.
        if (this.pendingTask != null) {
            try {
                this.consumeCullTaskResults(true);
            }
            catch (RuntimeException e) {
                SodiumContextRegistry.logFailedCullTaskConsumeOnce(e);
                this.pendingTask = null;
                this.renderSections.endSafeReadPhase();
            }
        }

        // ===== RSM-replacement guard (C2-1 self-review catch; see the swapPartnerRsm javadoc) ==
        // SWR.reload() (render-distance change at setupTerrain HEAD, javap offsets 21-39)
        // deleteRendererState()s + REPLACES the RenderSectionManager instance (javap initRenderer
        // offsets 1 / 68-84) — for dest dims that happens INSIDE the swap bracket, so a context
        // may arrive holding state displaced from a now-DEAD RSM. Exchanging that into this fresh
        // instance would install destroyed trees/lists AND a stale renderDistance (a hard
        // validate-crash on a user-reachable path: dragging the render-distance slider with a
        // portal on screen). Reset the RSM-side payload cold, adopting this instance's own
        // (already-correct) renderDistance; the SWR-side five stay valid (the SWR survives).
        if (context.swapPartnerRsm != null
            && context.swapPartnerRsm.get() != (Object) this
        ) {
            context.resetRsmSideToCold(this.renderDistance);
        }

        // ===== validate on swap-in (port-note §2) ==============================================
        Validate.isTrue(
            context.renderDistance != 0,
            "SodiumRenderingContext has renderDistance 0"
        );
        Validate.notNull(
            context.renderLists,
            "SodiumRenderingContext has null renderLists"
        );
        // Coherence check against the options-effective render distance. Evidence chain:
        // SWR.initRenderer seeds BOTH SWR.renderDistance and the RSM ctor arg from
        // Minecraft.options.getEffectiveRenderDistance() (javap initRenderer offsets 61-81), and
        // SWR.setupTerrain HEAD reloads the whole renderer whenever SWR.renderDistance drifts
        // from that call (javap offsets 21-39) — so the LIVE RSM.renderDistance always tracks
        // the effective distance. A stored context whose renderDistance mismatches is stale
        // (user changed render distance) and must have been discarded by SodiumContextRegistry
        // BEFORE reaching this swap — EXCEPT that IP's own facade legitimately requests a
        // non-effective portal render distance. CORRECTION-3 (verify lens A): the escape mirrors
        // PortalRenderer.getPortalRenderDistance's REAL per-pass branches (:337-351:
        // IPGlobal.reducedPortalRendering => mcRD/3; portal.getScale() > 2 may exceed mcRD) —
        // the previous RenderStates.renderedScalingPortal escape was SESSION-latched (set at
        // PortalRendering.java:101, its only reset commented out at RenderStates.java:209-214),
        // so one scaling-portal render would have disarmed the validate for the whole session.
        // PortalRendering.isRendering()/getRenderingPortal() are valid here: the swap driver runs
        // inside the portal-rendering bracket for exactly the passes that use a portal render
        // distance (non-portal facade callers pass the effective distance — no mismatch, the
        // escape is never consulted). Outside those IP-sanctioned paths, a mismatch is a hard
        // error — fail loud (the defaultRequire=1 honesty discipline).
        int effectiveRenderDistance =
            Minecraft.getInstance().options.getEffectiveRenderDistance();
        if (context.renderDistance != effectiveRenderDistance) {
            Validate.isTrue(
                IPGlobal.reducedPortalRendering
                    || (PortalRendering.isRendering()
                        && PortalRendering.getRenderingPortal().getScale() > 2),
                "SodiumRenderingContext renderDistance %d mismatches effective render distance %d"
                    + " outside the IP-sanctioned portal-render-distance paths",
                context.renderDistance, effectiveRenderDistance
            );
        }
        // Null renderTree / pendingTask / taskLists are LEGITIMATE (cold context — port-note §2).

        // ===== the symmetric three-way tmp swap over the reference set =========================
        SortedRenderLists tmpRenderLists = this.renderLists;
        this.renderLists = context.renderLists;
        context.renderLists = tmpRenderLists;

        SectionTree tmpRenderTree = this.renderTree;
        this.renderTree = context.renderTree;
        context.renderTree = tmpRenderTree;

        // pendingTask: the consume above guarantees the LIVE side is null here; the context side
        // is null by the swap-out invariant. Swapped anyway for structural symmetry.
        CullTask tmpPendingTask = this.pendingTask;
        this.pendingTask = context.pendingTask;
        context.pendingTask = tmpPendingTask;

        DeferredTaskList tmpTaskLists = this.taskLists;
        this.taskLists = context.taskLists;
        context.taskLists = tmpTaskLists;

        int tmpFrame = this.frame;
        this.frame = context.frame;
        context.frame = tmpFrame;

        boolean tmpNeedsRenderListUpdate = this.needsRenderListUpdate;
        this.needsRenderListUpdate = context.needsRenderListUpdate;
        context.needsRenderListUpdate = tmpNeedsRenderListUpdate;

        boolean tmpCameraChanged = this.cameraChanged;
        this.cameraChanged = context.cameraChanged;
        context.cameraChanged = tmpCameraChanged;

        int tmpCameraStableSince = this.cameraStableSince;
        this.cameraStableSince = context.cameraStableSince;
        context.cameraStableSince = tmpCameraStableSince;

        Vector3dc tmpCameraPosition = this.cameraPosition;
        this.cameraPosition = context.cameraPosition;
        context.cameraPosition = tmpCameraPosition;

        boolean tmpNeedsGraphUpdate = this.needsGraphUpdate;
        this.needsGraphUpdate = context.needsGraphUpdate;
        context.needsGraphUpdate = tmpNeedsGraphUpdate;

        int tmpRenderDistance = this.renderDistance;
        this.renderDistance = context.renderDistance;
        context.renderDistance = tmpRenderDistance;

        // ===== CONTENT swap for the two finals (census :177-179) ===============================
        // cullResults: displace the live entries into the outgoing context's holder, repopulate
        // the live map from the incoming holder. (SectionTree.isValidFor additionally rejects a
        // wrong-viewport tree at findBestTree — partial cross-context protection.)
        Map<CullType, SectionTree> displaced = new EnumMap<>(CullType.class);
        displaced.putAll(this.cullResults);
        this.cullResults.clear();
        this.cullResults.putAll(context.cullResultsContent);
        context.cullResultsContent.clear();
        context.cullResultsContent.putAll(displaced);

        // cameraTimingControl (final AsyncCameraTimingControl): both fields are private (javap:
        // private Vec3 previousPosition; private boolean isSyncRendering) → content-swapped via
        // the IESodiumCameraTimingControl accessor mixin.
        IESodiumCameraTimingControl timing =
            (IESodiumCameraTimingControl) this.cameraTimingControl;
        Vec3 tmpTimingPos = timing.ip_getPreviousPosition();
        boolean tmpTimingSync = timing.ip_getIsSyncRendering();
        timing.ip_setPreviousPosition(context.timingPreviousPosition);
        timing.ip_setIsSyncRendering(context.timingIsSyncRendering);
        context.timingPreviousPosition = tmpTimingPos;
        context.timingIsSyncRendering = tmpTimingSync;

        // ===== BLOCKER-2 (verify lens A): global monotonic pass serial =========================
        // Two same-layer contexts rendering into ONE dimension can arrive at the shared RSM with
        // EQUAL frame counters (each swapped in its own parked counter). VisibleChunkCollector
        // .visit (bytecode offsets 62→84) skips BOTH ChunkRenderList.reset(frame) AND
        // sortedRenderLists.add when the list's lastVisibleFrame already equals the current frame
        // — a persistent holey aperture with cross-appended render lists. Forcing the swapped-IN
        // side onto a GLOBALLY unique, strictly increasing serial makes every pass's frame
        // distinct, so the reset/add pair always runs.
        //
        // STRICT UNIQUENESS (C2-1 vA2 note 2 + vB correction — supersedes the earlier
        // ++serial/max-merge form, which let a frame value ABOVE the serial pass through
        // untracked, e.g. under FlawlessFrames-armed multi-increment passes, so two swap-ins
        // could still collide): the serial first ABSORBS both frame values observable at this
        // swap boundary — after the exchange, this.frame holds the swapped-in context's frame
        // and context.frame holds the parked outer frame — then increments once. The serial is
        // therefore >= every frame value ever seen at any swap boundary, so every swapped-in
        // frame STRICTLY exceeds all historical lastVisibleFrame stamps — "always distinct" is
        // a real guarantee, not a heuristic. SAFE because: (1) the consume-before-swap above
        // guarantees NO CullTask is outstanding at this moment, so no in-flight task carries a
        // frame stamp that this jump could invert; (2) within a context, frame only ever moves
        // FORWARD (the absorb-then-increment result strictly exceeds the pre-assignment
        // this.frame), which preserves the cameraStableSince <= task.getFrame() staleness
        // comparison and prepareRender's own increment semantics.
        //
        // Render-thread-only plain int (no atomic needed — every ip_swapContext caller is inside
        // the render bracket). INT WRAP: chosen over a long+clamp because sodium's own frame
        // field is an int incremented once per pass in prepareRender — the serial advances at the
        // same order of rate, so the ~2^31-pass wrap horizon (years of continuous play) is the
        // substrate's own; on wrap the worst case is one skipped render-list reset pass (the
        // pre-fix symptom, for one frame, self-healing next pass).
        SodiumContextRegistry.GLOBAL_PASS_SERIAL = Math.max(
            SodiumContextRegistry.GLOBAL_PASS_SERIAL,
            Math.max(this.frame, context.frame)
        ) + 1;
        this.frame = SodiumContextRegistry.GLOBAL_PASS_SERIAL;

        // The context now holds THIS instance's displaced state — record the pairing for the
        // RSM-replacement guard above.
        context.swapPartnerRsm = new java.lang.ref.WeakReference<>(
            (RenderSectionManager) (Object) this
        );
    }

    /**
     * D5 — the entity-cull neutralize, RETARGETED from IP's {@code isSectionVisible(III)} (GONE
     * on 0.9.1) to {@code isBoxVisible(DDDDDD)Z} (javap-proven present; the consumer chain is
     * {@code SWR.isEntityVisible → RSM.isBoxVisible(DDDDDD) → SectionTree.isBoxVisible}). Body
     * identical to IP file #2: after a portal is rendered this frame the cached section
     * visibility reflects the LAST world rendered, so entity culling against it would wrongly
     * vanish entities near portals — force visible. IP quote: "The section visibility
     * information will be wrong if rendered a portal. Just cancel this optimization."
     */
    @Inject(method = "isBoxVisible(DDDDDD)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void ip_onIsBoxVisible(
        double x1, double y1, double z1, double x2, double y2, double z2,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (RenderStates.portalsRenderedThisFrame != 0) {
            // §2b probe: count the D5 neutralize firing (nd5 in the [ENT-PROBE] line).
            if (qouteall.imm_ptl.core.render.EntityVisibilityProbe.ENABLED) {
                qouteall.imm_ptl.core.render.EntityVisibilityProbe.neutralizeD5++;
            }
            cir.setReturnValue(true);
        }
    }
}
