package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.PrimitiveTopology;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.q_misc_util.Helper;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * IS1 — THE PASTE FAMILY (iris shaders-ON engagement;
 * {@code migration/IRIS_SHADERS_ON_DESIGN.md} §2.3 / §1 IS1 deliverable 3, deviations D19/D20;
 * block-era recipe carrier = {@code migration/FBO_PRECEDENT_MINING.md} §3 + §8-7).
 *
 * <p>Two shipped pipelines + their draw drivers, consumed ONLY by {@code IrisCompatOn262Renderer}
 * (plus lever-selected diagnostic stamp siblings — see {@code selectStampPipeline}):
 * <ul>
 *   <li><b>{@code portalAreaSample}</b> (D20 — IP's {@code PORTAL_DRAW_FB_IN_AREA} re-expressed):
 *       draws the PORTAL-SHAPED view-area mesh (the {@link ViewAreaRenderer} geometry route,
 *       incl. the S14.36 near-plane clip) INTO THE DEFERRED BUFFER, fragment-sampling the MAIN
 *       target's color at the fragment's own screen coordinate ({@code texelFetch} at
 *       {@code gl_FragCoord} — the 1:1 screen-space UV law, mining §3-8: the dest content was
 *       rendered full-screen with the same projection, so it already sits at the correct
 *       pixels), DEPTH-TESTED against the deferred buffer's SNAPSHOT depth.
 *       <b>Depth compare = {@code GREATER_THAN_OR_EQUAL}, depth WRITE ON (R5 reversed-Z):</b> on
 *       26.2 reversed-Z, CLOSER-to-camera = LARGER depth value, so the portal surface passes
 *       exactly where it is in front of (or coincident with, hence GEQUAL not GREATER) the
 *       snapshotted scene = occlusion correct against the pre-portal frame.
 *       <b>#13 FIX (2026-07-21) — the write was RESTORED to match IP's original stamp</b>
 *       ({@code MyRenderHelper.drawPortalAreaWithFramebuffer} did {@code _depthMask(true)}): the
 *       port kept the reversed-Z-flipped GEQUAL test but had DROPPED the write, so with TWO
 *       non-recursive portals (nearest-first stamp order) the NEAR portal left no depth footprint
 *       and the FAR portal's later stamp GEQUAL-passed over it — the "second portal paints on top
 *       of the first" bug. Writing the near portal's plane depth into the deferred buffer now
 *       GEQUAL-rejects the far stamp exactly where it sits behind = IP parity. IS5-HAND note:
 *       the vertex-shader depth cap quantizes sub-10cm fragments to exactly 0.5, so two portal
 *       surfaces BOTH inside the camera's 10cm shell at the same pixel tie (0.5 >= 0.5) and the
 *       later stamp wins — a degenerate mid-crossing edge, accepted without a live repro. The deferred
 *       buffer's depth is discarded at the depth-test-OFF blit-back and re-cleared + re-snapshotted
 *       each frame in {@code IrisCompatOn262Renderer.onBeforeHandRendering}
 *       ({@code clearColorAndDepthTextures} then {@code copyDepthFrom(mainRT)}), so the write has
 *       NO effect beyond inter-portal occlusion. A frame-edge
 *       halo in the live round is the pre-registered wrong-compare-direction discriminator
 *       (design §1 IS1).</li>
 *   <li><b>{@code portalStraightCopy}</b>: the full-screen STRAIGHT-COPY pass used for both
 *       snapshot color (main→deferred) and blit-back (deferred→main). Exists because
 *       {@code RenderTarget.blitAndBlendToTexture} is ALPHA-BLEND source-over (the settled OQ5,
 *       port-note §1-E) — a compat frame must copy EXACTLY.</li>
 * </ul>
 *
 * <p><b>Mod-namespace shader assets</b> ({@code seamlessportals:core/...} — copies of vanilla
 * screenquad/blit_screen + the stamp shaders): P-PASTE belt-and-suspenders. Substitution
 * immunity is already structural (object-identity keying + {@code shouldOverrideShaders} +
 * no-"sodium"-substring — port-note §1-C, three grounds), the mod-namespace assets make it
 * unconditional. <b>RULE (port-note §1-C): never put the substring "sodium" anywhere in our
 * pipeline location namespace.</b>
 *
 * <p><b>The four block-era paste fixes</b> (mining §3/§8-7), applied to every pass here:
 * (1) 6-arg {@code createRenderPass} with an EXPLICIT full {@code RenderArea} on texture views
 * (the 5-arg auto-scissor clipped ultrawide; never a captured FBO id); (2) depth state
 * {@code Optional.empty()} = fully DISABLED on the full-screen copies (the ALWAYS_PASS trap:
 * tried block-era, kept the test enabled and still gated GEQUAL); (3) the sampler is bound VIA
 * THE RENDER PASS, NEAREST + clamp-to-edge (raw glBindTexture does not affect pass bindings);
 * (4) blend OFF — pipeline-declared (no blend function) + the {@code GlStateManager} backstops
 * around each pass ({@code applyPipelineState} short-circuits when {@code lastPipeline} is
 * unchanged, so pipeline-declared state may not be re-applied).
 *
 * <p>Frame-transient GPU buffers (stamp mesh vertices + the combined-matrix UBO) ride the
 * {@link SecondaryWorldRenderCore#registerFrameTransientUbo} ledger (closed at the
 * GameRenderer.render TAIL — the S14.30 discipline; never per-call {@code close()}).
 *
 * <p>S20 note: zero {@code com.warwa} types (design §5) — the geometry route is reached through
 * {@link ViewAreaRenderer#buildPortalViewAreaMesh} (qouteall-side).
 */
@Environment(EnvType.CLIENT)
public class IrisCompatPaste {

    private static RenderPipeline PORTAL_AREA_SAMPLE;
    /** IS5-MB attribution sibling: identical but depth WRITE off. Selected only by the lever. */
    private static RenderPipeline PORTAL_AREA_SAMPLE_NO_DEPTH_WRITE;
    /** IS5-SEAM §2d sibling: identical but the depth state is fully DISABLED (Optional.empty()).
     *  Selected only by -Dseamlessportals.disableStampDepthTest. */
    private static RenderPipeline PORTAL_AREA_SAMPLE_NO_DEPTH_TEST;
    /** IS5-SEAM solid-paint sibling: fragment outputs vColor, ignoring the sample (the multiply
     *  tint is blind on black content). Selected only by -Dseamlessportals.debugStampSolid. */
    private static RenderPipeline PORTAL_AREA_SOLID;
    /** IS5-SEAM solid + depth fully disabled — the two levers composed. */
    private static RenderPipeline PORTAL_AREA_SOLID_NO_DEPTH_TEST;
    /** IS5-STAMP-EAT sibling: identical to the shipped stamp but depth compare LESS_THAN_OR_EQUAL.
     *  The survival table (2026-07-28) caught the stamp overpainting the hand between the anchor
     *  and the blit-back; the hand pass's measured convention is small-is-near/LEQUAL, so the
     *  shipped GEQUAL (a reversed-Z assumption) lets the aperture beat everything NEARER than
     *  it — including the hand. Selected only by -PstampLequal. */
    private static RenderPipeline PORTAL_AREA_SAMPLE_LEQUAL;

    /** IS5-STAMP-EAT: true only while the stamp's drawIndexed is executing — lets the encoder
     *  probe dump the state the STAMP draw actually runs under (the pass-boundary reads proved
     *  blind; only trySetup-time reads are ground truth). */
    public static volatile boolean STAMP_DRAWING = false;

    /** IS5-RC: one line per session naming the stamp pipeline actually bound (see stampPortalArea). */
    private static boolean stampPipelineReported = false;
    private static RenderPipeline PORTAL_STRAIGHT_COPY;

    /**
     * IS5-HAND — the stamp VERTEX shader, selected once at registration: the default
     * {@code core/portal_area_sample} carries the NDC-z 0.5 depth cap (the seam hand-slicing
     * fix — derivation in the .vsh and the IPGlobal lever comment);
     * {@code -PdisableStampHandDepthCap} swaps in the verbatim pre-cap
     * {@code core/portal_area_sample_nocap} for EVERY stamp pipeline (shipped + diagnostic
     * siblings — the axes stay comparable). Reported on the IS5-RC STAMP PIPELINE line.
     */
    private static Identifier stampVertexShaderId() {
        // IS5-XCUT (2026-08-01): the floor moved to the FRAGMENT stage, so the DEFAULT vertex
        // shader is now the floor-free "nocap" one. Only the -PdisableXcutFragFloor reproduction
        // leg goes back to the per-vertex floor, and only when the hand-cap lever is not ALSO
        // asking for no floor at all. Applying both would floor twice.
        return Identifier.fromNamespaceAndPath("seamlessportals",
            vertexFloorActive() ? "core/portal_area_sample" : "core/portal_area_sample_nocap");
    }

    /** True only on the -PdisableXcutFragFloor reproduction leg: the OLD per-vertex floor. */
    private static boolean vertexFloorActive() {
        return qouteall.imm_ptl.core.IPGlobal.XCUT_FRAG_FLOOR_DISABLED_LEVER
            && !qouteall.imm_ptl.core.IPGlobal.STAMP_HAND_DEPTH_CAP_DISABLED_LEVER;
    }

    /** True in the shipped default: the floor is applied PER FRAGMENT. */
    private static boolean fragmentFloorActive() {
        return !qouteall.imm_ptl.core.IPGlobal.XCUT_FRAG_FLOOR_DISABLED_LEVER
            && !qouteall.imm_ptl.core.IPGlobal.STAMP_HAND_DEPTH_CAP_DISABLED_LEVER;
    }

    /**
     * IS5-XCUT — the stamp FRAGMENT shader, selected once at registration. The floor now lives
     * here (per-fragment) instead of in the vertex shader; see portal_area_sample_floor.fsh for
     * the derivation, the units, and the three-leg attribution.
     *
     * <p>THE THREE COHERENT STATES, so both pre-existing levers keep their meaning:
     * <ul>
     *   <li><b>default</b> — vsh=nocap + fsh=*_floor: floor applied PER FRAGMENT (the fix);</li>
     *   <li><b>-PdisableXcutFragFloor</b> — vsh=capped + fsh=plain: the OLD per-vertex floor,
     *       byte-identical to the behaviour that produced the swept cut (the A/B repro);</li>
     *   <li><b>-PdisableStampHandDepthCap</b> — vsh=nocap + fsh=plain: NO floor anywhere, which
     *       is what that lever has always meant (the hand-arc A/B). It wins over the row above,
     *       so the two levers together still give "no floor" rather than a contradiction.</li>
     * </ul>
     * Every stamp pipeline — shipped and diagnostic — goes through here, so the axes stay
     * comparable: a solid leg must differ from a sample leg by the fragment OUTPUT alone, never by
     * which depth boundary is in force.
     *
     * @param basePath {@code core/portal_area_sample} or {@code core/portal_area_solid}
     */
    private static Identifier stampFragmentShaderId(String basePath) {
        return Identifier.fromNamespaceAndPath("seamlessportals",
            fragmentFloorActive() ? basePath + "_floor" : basePath);
    }

    static {
        try {
            // The proven mod idiom for hand-built pipelines (PortalRenderTypes static init):
            // register through vanilla's private RenderPipelines.register. CORRECTED 2026-07-27
            // (IS5-SEAM panel, bytecode-verified): register() is a bare PIPELINES_BY_LOCATION.put —
            // it neither validates nor compiles. Compilation is LAZY (first draw via
            // getOrCompilePipeline; a GLSL failure returns INVALID_PROGRAM without throwing) plus
            // EAGER on every ShaderManager reload, which hard-fails the whole reload if ANY
            // registered pipeline is invalid — i.e. registering makes a shader load-bearing for
            // every F3+T/pack change. What registration buys is exactly that reload-recompile
            // membership. NOTE (P-PASTE, port-note §1-C): registration does NOT enter iris's
            // substitution key set — that map is populated from ~59 explicit vanilla
            // RenderPipelines.* singletons, keyed by OBJECT IDENTITY.
            Method registerMethod = RenderPipelines.class.getDeclaredMethod("register", RenderPipeline.class);
            registerMethod.setAccessible(true);

            // D20 — the portal-shaped stamp. POSITION_COLOR mesh (the ViewAreaRenderer route;
            // vertex color is WHITE = identity in the fragment multiply), transformed by ONE
            // combined clip matrix (projection * modelView, column-form M·v) uploaded as the
            // Projection UBO — no DynamicTransforms dependency. Reversed-Z GEQUAL + depth WRITE
            // (the #13 multi-portal-occlusion fix — class javadoc); blend off (no blend function
            // declared); cull off (the aperture mesh is visible from both sides, matching the
            // query/aperture draws).
            RenderPipeline portalAreaSample = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("seamlessportals", "pipeline/portal_area_sample"))
                .withVertexShader(stampVertexShaderId()) // IS5-HAND: capped by default, lever-swapped
                .withFragmentShader(stampFragmentShaderId("core/portal_area_sample"))
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
                .withCull(false)
                .build();
            PORTAL_AREA_SAMPLE = (RenderPipeline) registerMethod.invoke(null, portalAreaSample);

            // IS5-MB DIAGNOSTIC SIBLING — identical except depth WRITE is off. Purpose: attribute the
            // Motion-Blur portal-window blur. MEASURED so far: every uniform input to composite4's
            // velocity is exactly zero (|cam-prev|=0.000, matrix maxAbsDiff=0.00000), so the pass is a
            // mathematical passthrough at the PASS level — yet the blur scales with
            // MOTION_BLURRING_STRENGTH, which means velocity is nonzero PER PIXEL. Velocity is computed
            // from `z = texture2D(depthtex1, texCoord)`, and the stamp writes DEST depth into the
            // window region of the MAIN depth buffer (the #13 two-portal depth fix restored that
            // write). Main-chain composite4 then unprojects dest depth with MAIN matrices => garbage
            // viewPos => large velocity for WINDOW PIXELS ONLY, while main-view pixels stay sharp.
            // Uniform-level probing cannot see a per-pixel defect, which is why every probe read zero.
            // Turning this write off should make the blur vanish (at the cost of re-opening the #13
            // two-portal depth artifact) — that is the attribution, not a fix.
            RenderPipeline portalAreaSampleNoDepthWrite = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(
                    "seamlessportals", "pipeline/portal_area_sample_nodepthwrite"))
                .withVertexShader(stampVertexShaderId()) // IS5-HAND: capped by default, lever-swapped
                .withFragmentShader(stampFragmentShaderId("core/portal_area_sample"))
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                .withCull(false)
                .build();
            PORTAL_AREA_SAMPLE_NO_DEPTH_WRITE =
                (RenderPipeline) registerMethod.invoke(null, portalAreaSampleNoDepthWrite);

            // The straight copy — a clone of the PROVEN portalCompositeBlit shape (screenquad
            // full-screen triangle + blit_screen sample; GLOBALS + IN_SAMPLER;
            // Optional.empty() depth = _disableDepthTest — the working state, NOT ALWAYS_PASS),
            // with MOD-NAMESPACE shader assets (verbatim copies of vanilla screenquad/blit_screen).
            RenderPipeline straightCopy = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("seamlessportals", "pipeline/portal_straight_copy"))
                .withVertexShader(Identifier.fromNamespaceAndPath("seamlessportals", "core/screenquad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("seamlessportals", "core/blit_screen"))
                .withBindGroupLayout(BindGroupLayouts.GLOBALS)
                .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(Optional.empty())
                .withCull(false)
                .build();
            PORTAL_STRAIGHT_COPY = (RenderPipeline) registerMethod.invoke(null, straightCopy);

            Helper.log("[IrisCompatPaste] paste pipelines created (portal_area_sample + portal_straight_copy)");
        } catch (Throwable t) {
            // Loud, never a crash (the D7 loud-not-silent family). Fable-fold CORRECTION
            // (port-note §2.5): the draw helpers null-check and no-op, AND the compat renderer's
            // workhorse checks arePipelinesReady() and skips the whole snapshot/loop/blit pass —
            // the live-round symptom is therefore "portals render nothing" (window unchanged),
            // discriminated by this log line. (Without the workhorse gate the per-portal nested
            // renders would still run and clobber the main target — whole-screen dest world —
            // while every copy silently no-opped: a wrongly-discriminated corruption mode.)
            Helper.err("[IrisCompatPaste] FAILED to create paste pipelines — compat paste will no-op");
            t.printStackTrace();
        }

        // IS5-SEAM DIAGNOSTIC SIBLINGS — registration moved OUT of <clinit> (2026-07-28): the
        // siblings are COMPILE-validated, and <clinit> can run during resource loading, before
        // the shader assets exist — which failed the LEQUAL sibling's gate and VOIDed its leg
        // for a timing reason that looked like a shader defect. They now register lazily at the
        // first stamp (render thread, resources loaded, immediately before first use) via
        // ensureSiblingsRegistered(). The shipped pipelines above stay in <clinit>: they are
        // registered-not-compiled there and compile lazily at first draw, as before.
    }

    /** Lazy, once-only sibling registration at the first stamp (see the <clinit> note). */
    private static boolean siblingsRegistrationAttempted = false;

    private static void ensureSiblingsRegistered() {
        if (siblingsRegistrationAttempted) {
            return;
        }
        siblingsRegistrationAttempted = true;
        registerSeamDiagnosticSiblings();
    }

    /**
     * IS5-SEAM (2026-07-27, panel-hardened) — build + VALIDATE + register the lever-selected
     * diagnostic stamp siblings.
     *
     * <p><b>Lever-gated:</b> a sibling is created ONLY when the lever that can select it is set
     * ({@code -PdisableStampDepthTest} / {@code -PdebugStampSolid}). A default (no-lever) run
     * therefore carries ZERO new pipelines — which matters because registration is permanent and
     * {@code ShaderManager.apply} eagerly precompiles EVERY registered pipeline on EVERY resource
     * reload (F3+T / pack change) and hard-fails the reload if any is invalid. Unconditional
     * registration would have made the diagnostic-only shader load-bearing for every session.
     *
     * <p><b>Compile-validated before registration (the panel's HIGH):</b>
     * {@code RenderPipelines.register} is a bare map-put — it neither compiles nor validates, so a
     * registration try/catch is dead against shader defects, and a broken {@code .fsh} would
     * otherwise surface only at first draw (silently SKIPPED when {@code GlRenderPass.VALIDATION}
     * is off, a throw when on) AFTER the once-only report had already printed the sibling as
     * usable — the defective-instrument class this project keeps paying for. So each sibling is
     * run through {@code GpuDevice.precompilePipeline(pipeline).isValid()} FIRST; an invalid one
     * is NEVER registered (no reload blast radius) and its field stays null, which the selection's
     * existing degradation turns into a loud VOID on the IS5-RC line. This runs at first compat
     * use, on the render thread, resources loaded — the same moment the shipped pipelines lazily
     * compile today.
     */
    private static void registerSeamDiagnosticSiblings() {
        boolean wantNoTest = qouteall.imm_ptl.core.IPGlobal.STAMP_DEPTH_TEST_DISABLED_LEVER;
        boolean wantSolid = qouteall.imm_ptl.core.IPGlobal.debugStampSolid;
        boolean wantLequal = qouteall.imm_ptl.core.IPGlobal.STAMP_LEQUAL_LEVER;
        if (!wantNoTest && !wantSolid && !wantLequal) {
            return; // default runs: zero new pipelines, zero new reload surface
        }
        try {
            Method registerMethod = RenderPipelines.class.getDeclaredMethod("register", RenderPipeline.class);
            registerMethod.setAccessible(true);

            if (wantLequal) {
                // IS5-STAMP-EAT: the shipped stamp with the compare flipped to LEQUAL.
                PORTAL_AREA_SAMPLE_LEQUAL = buildValidateRegister(
                    registerMethod, "portal_area_sample_lequal", "core/portal_area_sample",
                    new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true), "-PstampLequal");
            }

            if (wantNoTest) {
                // §2d: the depth state fully DISABLED (Optional.empty() — the proven
                // PORTAL_STRAIGHT_COPY shape; GL disables depth WRITES along with the test).
                PORTAL_AREA_SAMPLE_NO_DEPTH_TEST = buildValidateRegister(
                    registerMethod, "portal_area_sample_nodepthtest", "core/portal_area_sample",
                    null, "-PdisableStampDepthTest");
            }
            if (wantSolid) {
                // Solid-paint discriminator: fragment = vColor, sample ignored
                // (portal_area_solid.fsh — the multiply tint is blind on black content, so
                // coverage needed a content-free paint). Both depth variants are built whenever
                // the solid lever is set; SOLID+NO-DEPTH-TEST is only SELECTED with both levers.
                PORTAL_AREA_SOLID = buildValidateRegister(
                    registerMethod, "portal_area_solid", "core/portal_area_solid",
                    new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true), "-PdebugStampSolid");
                PORTAL_AREA_SOLID_NO_DEPTH_TEST = buildValidateRegister(
                    registerMethod, "portal_area_solid_nodepthtest", "core/portal_area_solid",
                    null, "-PdebugStampSolid -PdisableStampDepthTest");
            }
        } catch (Throwable t) {
            // Never let a diagnostic escape into the <clinit> (ExceptionInInitializerError would
            // kill the whole compat path over a probe). Null fields => VOID at selection time.
            Helper.err("[IrisCompatPaste] IS5-SEAM diagnostic sibling registration failed —"
                + " the affected lever legs are VOID (see selection report)");
            t.printStackTrace();
        }
    }

    /**
     * Build one diagnostic sibling, prove it COMPILES ({@code precompilePipeline(...).isValid()}),
     * and only then register it. Returns null — loudly — on any failure; the caller's field stays
     * null and {@code selectStampPipeline} degrades to the shipped default with a VOID warning.
     * {@code depthOrNull == null} means the depth state is fully disabled ({@code Optional.empty()}).
     */
    private static RenderPipeline buildValidateRegister(
        Method registerMethod, String pipelinePath, String fragmentShaderPath,
        DepthStencilState depthOrNull, String leverLabel
    ) {
        try {
            var builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("seamlessportals", "pipeline/" + pipelinePath))
                .withVertexShader(stampVertexShaderId()) // IS5-HAND: same axis as the shipped pair
                .withFragmentShader(stampFragmentShaderId(fragmentShaderPath))
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false);
            if (depthOrNull != null) {
                builder = builder.withDepthStencilState(depthOrNull);
            } else {
                builder = builder.withDepthStencilState(Optional.empty());
            }
            RenderPipeline built = builder.build();
            // THE VALIDITY GATE: compile NOW, through the device's live shader source. register()
            // alone proves nothing (map-put), and an invalid registered pipeline would also
            // hard-fail every later resource reload.
            if (!RenderSystem.getDevice().precompilePipeline(built).isValid()) {
                Helper.err("[IrisCompatPaste] IS5-SEAM sibling '" + pipelinePath + "' FAILED TO"
                    + " COMPILE — every " + leverLabel + " leg is VOID until this is fixed"
                    + " (pipeline NOT registered; selection will degrade with a VOID warning)");
                return null;
            }
            return (RenderPipeline) registerMethod.invoke(null, built);
        } catch (Throwable t) {
            Helper.err("[IrisCompatPaste] IS5-SEAM sibling '" + pipelinePath + "' failed to"
                + " build/register — every " + leverLabel + " leg is VOID until this is fixed");
            t.printStackTrace();
            return null;
        }
    }

    /**
     * Fable-fold CORRECTION (port-note §2.5): both paste pipelines created successfully.
     * {@code IrisCompatOn262Renderer.onBeforeHandRendering} gates the entire compat pass on
     * this — see the static-init catch above for the failure-mode rationale.
     */
    public static boolean arePipelinesReady() {
        return PORTAL_AREA_SAMPLE != null && PORTAL_STRAIGHT_COPY != null;
    }

    /**
     * The full-screen STRAIGHT copy {@code from.color → to.color} (§2.3). Depth is NOT copied
     * here — the depth snapshot is {@code RenderTarget.copyDepthFrom} at the caller (replace
     * semantics; both targets must own depth textures and the destination must already be
     * resized to the source — the OQ5 resize-before-copyDepthFrom ordering lives at the caller).
     */
    public static void drawStraightCopy(RenderTarget from, RenderTarget to) {
        if (PORTAL_STRAIGHT_COPY == null
            || from.getColorTextureView() == null
            || to.getColorTextureView() == null
            || to.getDepthTextureView() == null) {
            return;
        }
        // Fix (4) backstops — applyPipelineState short-circuits on unchanged lastPipeline.
        GlStateManager._disableBlend(0);
        GlStateManager._disableDepthTest();
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "seamlessportals_compat_straight_copy",
            to.getColorTextureView(),
            Optional.empty(),
            to.getDepthTextureView(),
            OptionalDouble.empty(),
            new RenderPass.RenderArea(0, 0, to.width, to.height) // fix (1): explicit full area
        )) {
            pass.setPipeline(PORTAL_STRAIGHT_COPY);
            RenderSystem.bindDefaultUniforms(pass);
            pass.bindTexture(
                "InSampler", from.getColorTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST) // fix (3)
            );
            pass.draw(3, 1, 0, 0);
        } finally {
            GlStateManager._enableDepthTest();
            GlStateManager._enableBlend(0);
        }
    }

    /**
     * D20 — the portal-shaped stamp: draw the portal's view-area mesh into {@code deferred},
     * sampling {@code sampleSource} (the main target holding the just-rendered dest world) at
     * screen coords, depth-tested against {@code deferred}'s snapshot depth (class javadoc).
     * {@code modelView}/{@code projection} = the pass matrices the held IP source passed to
     * {@code drawPortalAreaWithFramebuffer} (the compat renderer's {@code passingModelView} +
     * {@code getCurrentProjectionMatrix()}).
     */
    public static void stampPortalArea(
        Portal portal,
        RenderTarget sampleSource,
        RenderTarget deferred,
        Matrix4f modelView,
        Matrix4f projection
    ) {
        if (PORTAL_AREA_SAMPLE == null
            || sampleSource.getColorTextureView() == null
            || deferred.getColorTextureView() == null
            || deferred.getDepthTextureView() == null) {
            return;
        }
        // The IDENTICAL geometry route as every aperture draw (ViewAreaRenderer, incl. the
        // S14.36 near-plane clip): camera-relative POSITION_COLOR triangles, color = WHITE
        // (identity in the fragment multiply — the copy stays exact).
        // IS5-G GHOST DISCRIMINATOR: under -Dseamlessportals.debugTintStamp the color becomes
        // channel-killing MAGENTA {1,0,1} — every stamped pixel loses its green channel, so a
        // live run settles whether the "phantom colored-blocks" wave IS this stamp's paint
        // (ghost turns magenta) or another carrier (window magenta, ghost full-color).
        try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(
            256 * DefaultVertexFormat.POSITION_COLOR.getVertexSize()
        )) {
            MeshData mesh = ViewAreaRenderer.buildPortalViewAreaMesh(
                qouteall.imm_ptl.core.IPGlobal.debugTintStamp
                    ? new Vec3(1.0, 0.0, 1.0) : new Vec3(1.0, 1.0, 1.0),
                portal,
                CHelper.getCurrentCameraPos(), RenderStates.getPartialTick(),
                modelView, byteBuffer
            );
            // IS5-SEAM: the aperture mesh can come back null because EVERY triangle failed the
            // S14.36 near-plane clip (ViewAreaRenderer:313 keeps only viewZ < -EPS). Skipping the
            // stamp then leaves the window region holding whatever the deferred buffer had — pure
            // black — which is the flash the user sees when crossing the seam slowly. Measured
            // live: -PdebugTintStamp turned the whole window magenta EXCEPT that band — which
            // (INFERENCE CORRECTED 2026-07-27) proves no NON-BLACK fragment survives there, NOT
            // that the stamp misses it: the tint is a MULTIPLY (portal_area_sample.fsh) and is
            // blind on black sampled content; the solid/no-depth-test levers split the remaining
            // branches (handoff §2c'/§2d). It reproduces at pre-session 082d533, so it predates
            // the motion-blur work and was simply masked by the smear.
            //
            // THE GUARD IS LOAD-BEARING. A null mesh ALSO occurs whenever the portal is behind the
            // camera or off to the side, and full-screen stamping there would paint the destination
            // world over the entire screen with no portal in sight — far worse than the flash. So
            // the fallback fires only when the camera is genuinely inside the aperture footprint:
            // getDistanceToNearestPointInPortal measures to the portal SHAPE, not its plane, so a
            // portal behind you reads large while a camera in the doorway reads ~0.
            // IS5-SEAM CENSUS. Report EVERY outcome, near the aperture, at 1 Hz — not just the branch
            // a hypothesis predicts. The previous round logged only "mesh came back null while inside
            // the footprint"; that never fired, the black flash persisted, and the log could not say
            // whether the mesh was null-but-outside or non-null-and-partially-clipped, which need
            // DIFFERENT fixes. Logging only the expected branch is how a refutation ends up carrying
            // no information.
            double distToAperture = Double.MAX_VALUE;
            try {
                distToAperture =
                    portal.getDistanceToNearestPointInPortal(CHelper.getCurrentCameraPos());
            }
            catch (Throwable ignored) {
                // treated as "not inside" — the safe direction
            }
            seamCensus(distToAperture, mesh == null);

            if (mesh == null) {
                // MEASURED NEVER TO HAPPEN on the frames that show the seam flash: meshNull=false on
                // 41/41 census rows across two runs. A full-screen fallback was built here on the
                // hypothesis that it did, and removed again when the census refuted it — do not
                // rebuild it without evidence that this branch actually fires.
                return; // every triangle near-plane-clipped away — nothing to stamp
            }
            try (mesh) {
                MeshData.DrawState drawState = mesh.drawState();
                int indexCount = drawState.indexCount();
                // Frame-transient buffers (S14.30 ledger — closed at the render TAIL, never
                // per-call; draws are issued synchronously so the ledger can never free a
                // referenced name early).
                GpuBufferSlice vertexSlice = SecondaryWorldRenderCore.registerFrameTransientUbo(
                    RenderSystem.getDevice().createBuffer(
                        () -> "seamlessportals_stamp_mesh",
                        GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer()
                    )
                );
                RenderSystem.AutoStorageIndexBuffer indices =
                    RenderSystem.getSequentialBuffer(drawState.primitiveTopology());
                GpuBuffer indexBuffer = indices.getBuffer(indexCount);

                // ONE combined clip transform: projection * modelView (JOML column-form M·v —
                // the D4.4 order; clip = P * MV * pos), uploaded as the Projection UBO — the
                // stamp's vertex shader reads ONLY ProjMat (no DynamicTransforms dependency).
                GpuBufferSlice combinedSlice;
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    java.nio.ByteBuffer buf = Std140Builder.onStack(stack, 64)
                        .putMat4f(new Matrix4f(projection).mul(modelView)).get();
                    combinedSlice = SecondaryWorldRenderCore.registerFrameTransientUbo(
                        RenderSystem.getDevice().createBuffer(
                            () -> "seamlessportals_stamp_proj",
                            GpuBuffer.USAGE_UNIFORM, buf
                        )
                    );
                }

                // Fix (4) backstop: blend OFF for the stamp (depth state is pipeline-declared
                // GEQUAL with depth WRITE — write=true since the #13 two-portal fix restored
                // IP's _depthMask(true); between two stamps a whole nested render() ran, so the
                // lastPipeline short-circuit cannot skip the stamp's own depth state).
                GlStateManager._disableBlend(0);
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "seamlessportals_portal_area_stamp",
                    deferred.getColorTextureView(),
                    Optional.empty(),
                    deferred.getDepthTextureView(),
                    OptionalDouble.empty(),
                    new RenderPass.RenderArea(0, 0, deferred.width, deferred.height) // fix (1)
                )) {
                    // IS5-SEAM / IS5-MB pipeline selection — two orthogonal diagnostic axes
                    // (selectStampPipeline javadoc has the full matrix and precedence). DEFAULT
                    // keeps today's depth-writing sampling pipeline.
                    //
                    // IS5-RC SELF-REPORT (once-only). An earlier stamp-depth A/B was voided because
                    // this selection had THREE silent no-op paths and reported none of them: the -P
                    // row might never have reached the JVM; a requested sibling might have failed to
                    // register, silently degrading to the base pipeline; or the stamp might not have
                    // run at all. The run-config block covers the first. This line covers the rest by
                    // naming the pipeline ACTUALLY bound at the point of effect, every lever input,
                    // and every sibling's registration state. A leg whose log lacks this line stamped
                    // nothing and measured nothing; a leg whose line carries a VOID warning must be
                    // re-run, not adjudicated.
                    StampSelection sel = selectStampPipeline();
                    if (!stampPipelineReported) {
                        stampPipelineReported = true;
                        Helper.LOGGER.info(
                            "[Seamless Portals] IS5-RC STAMP PIPELINE (once-only): bound={} ;"
                                + " vsh={} (capped = the IS5-HAND NDC-z 0.5 depth cap; nocap = the"
                                + " -PdisableStampHandDepthCap reproduction shader) ;"
                                + " levers: disableStampDepthTest={} disableStampDepthWrite={}"
                                + " debugStampSolid={} debugTintStamp={} ; siblings USABLE"
                                + " (noDepthTest/solid/solidNoDepthTest are lever-gated +"
                                + " COMPILE-VALIDATED, false = not requested OR failed — see any"
                                + " [IrisCompatPaste] err lines; noDepthWrite ships unconditionally"
                                + " from the static block, same shader as the default):"
                                + " noDepthTest={} noDepthWrite={} solid={} solidNoDepthTest={}{}",
                            sel.name(),
                            qouteall.imm_ptl.core.IPGlobal.STAMP_HAND_DEPTH_CAP_DISABLED_LEVER
                                ? "NOCAP" : "capped",
                            qouteall.imm_ptl.core.IPGlobal.STAMP_DEPTH_TEST_DISABLED_LEVER,
                            qouteall.imm_ptl.core.IPGlobal.STAMP_DEPTH_WRITE_DISABLED_LEVER,
                            qouteall.imm_ptl.core.IPGlobal.debugStampSolid,
                            qouteall.imm_ptl.core.IPGlobal.debugTintStamp,
                            PORTAL_AREA_SAMPLE_NO_DEPTH_TEST != null,
                            PORTAL_AREA_SAMPLE_NO_DEPTH_WRITE != null,
                            PORTAL_AREA_SOLID != null,
                            PORTAL_AREA_SOLID_NO_DEPTH_TEST != null,
                            sel.warnings().isEmpty() ? "" : (" ; " + sel.warnings())
                        );
                    }
                    pass.setPipeline(sel.pipeline());
                    pass.setUniform("Projection", combinedSlice);
                    pass.bindTexture(
                        "InSampler", sampleSource.getColorTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST) // fix (3)
                    );
                    pass.setVertexBuffer(0, vertexSlice);
                    pass.setIndexBuffer(indexBuffer, indices.type());
                    STAMP_DRAWING = true;
                    try {
                        pass.drawIndexed(indexCount, 1, 0, 0, 0);
                    }
                    finally {
                        STAMP_DRAWING = false;
                    }
                    // IS5-STAMP-EXEC (lever-gated -Dseamlessportals.stampExecProbe, DEFAULT
                    // OFF): read the driver's ACTUAL program/vsh-source/depth state right after
                    // the draw applied it — the ground-truth audit of the stage-C cap anomaly
                    // (declared GEQUAL+capped cannot produce the measured 0.98→0.65 writes).
                    com.warwa.seamlessportals.render.StampExecStateProbe
                        .afterStampDraw(sel.name());
                } finally {
                    GlStateManager._enableBlend(0);
                    // IS5-COV (lever-gated -Dseamlessportals.stampCoverageProbe, DEFAULT OFF):
                    // the occluder-ring coverage measurement. Deliberately HERE, in the
                    // try-with-resources' finally: the RenderPass is already CLOSED at this point
                    // (a resource is released before its finally runs), so the readback sees the
                    // committed result rather than racing an open pass. Where the stamp PASSED it
                    // also wrote depth; where it FAILED — the ring, the only thing this probe is
                    // asking about — the depth is untouched and is exactly the value the test
                    // rejected on. Refuses to measure unless the solid+tint levers are set.
                    com.warwa.seamlessportals.render.StampCoverageProbe.afterStamp(deferred);
                }
            }
        }
    }

    /** The stamp pipeline actually selected, its display name, and any degradation warnings. */
    private record StampSelection(RenderPipeline pipeline, String name, String warnings) {}

    /**
     * IS5-SEAM / IS5-MB — the ONE place the stamp pipeline is chosen; also consumed by the census
     * so every 1 Hz row names the pipeline in force. Two orthogonal diagnostic axes:
     * <ul>
     *   <li><b>PAINT</b>: SAMPLE (screen-space copy of the main target — the shipped stamp) vs
     *       SOLID ({@code -PdebugStampSolid}: fragment paints vColor and ignores the sample — the
     *       multiply tint is blind on black content, so coverage needed a content-free paint).</li>
     *   <li><b>DEPTH</b>: DEFAULT (GEQUAL vs the snapshot depth, WRITE on — the #13 shape) vs
     *       NO-WRITE ({@code -PdisableStampDepthWrite}: GEQUAL kept, write off — the IS5-MB
     *       attribution lever) vs NO-TEST ({@code -PdisableStampDepthTest}: depth state fully
     *       disabled — the §2d seam discriminator). GL disables depth WRITES together with the
     *       test, so NO-TEST strictly contains NO-WRITE and takes precedence when both are set.</li>
     * </ul>
     * Siblings are lever-gated AND compile-validated at registration
     * ({@link #registerSeamDiagnosticSiblings}): a null sibling here means its lever is off (then
     * it is never requested) or its build/COMPILE failed (then the request degrades). A
     * requested-but-null sibling binds the SHIPPED DEFAULT instead (never a different diagnostic —
     * the operator must get either exactly what was asked for or the known baseline) and stamps a
     * VOID warning into the once-only IS5-RC line: the leg still renders, but must be re-run, not
     * adjudicated. {@code PORTAL_AREA_SAMPLE} is non-null on every path that reaches this method
     * ({@code stampPortalArea} early-returns otherwise).
     */
    private static StampSelection selectStampPipeline() {
        ensureSiblingsRegistered(); // resources are loaded by the first stamp (see <clinit>)
        boolean wantSolid = qouteall.imm_ptl.core.IPGlobal.debugStampSolid;
        boolean wantNoTest = qouteall.imm_ptl.core.IPGlobal.STAMP_DEPTH_TEST_DISABLED_LEVER;
        boolean wantNoWrite = qouteall.imm_ptl.core.IPGlobal.STAMP_DEPTH_WRITE_DISABLED_LEVER;
        StringBuilder w = new StringBuilder();
        if (wantNoTest && wantNoWrite) {
            w.append("Both depth levers set: NO-DEPTH-TEST wins (GL disables depth writes together"
                + " with the test, so it strictly contains NO-WRITE).");
        }
        RenderPipeline intended;
        String name;
        if (wantSolid && wantNoTest) {
            intended = PORTAL_AREA_SOLID_NO_DEPTH_TEST;
            name = "SOLID+NO-DEPTH-TEST";
        }
        else if (wantSolid && wantNoWrite) {
            // SOLID+NO-WRITE is deliberately not built: solid legs adjudicate coverage, not the
            // #13 write. Keep the write ON and say so.
            intended = PORTAL_AREA_SOLID;
            name = "SOLID";
            w.append(" SOLID+NO-WRITE is not built: depth write stays ON this leg — do NOT"
                + " adjudicate a depth-write A/B from it.");
        }
        else if (wantSolid) {
            intended = PORTAL_AREA_SOLID;
            name = "SOLID";
        }
        else if (wantNoTest) {
            intended = PORTAL_AREA_SAMPLE_NO_DEPTH_TEST;
            name = "SAMPLE+NO-DEPTH-TEST";
        }
        else if (wantNoWrite) {
            intended = PORTAL_AREA_SAMPLE_NO_DEPTH_WRITE;
            name = "SAMPLE+NO-DEPTH-WRITE";
        }
        else if (qouteall.imm_ptl.core.IPGlobal.STAMP_LEQUAL_LEVER) {
            // IS5-STAMP-EAT candidate fix: LEQUAL compare — under the measured small-is-near
            // convention the aperture then loses to everything NEARER than it (the hand) while
            // still beating the far scene it must replace.
            intended = PORTAL_AREA_SAMPLE_LEQUAL;
            name = "SAMPLE+LEQUAL";
        }
        else {
            intended = PORTAL_AREA_SAMPLE;
            name = "SAMPLE(default)";
        }
        if (intended == null) {
            w.append(" THE REQUESTED PIPELINE (").append(name).append(") FAILED TO REGISTER —"
                + " binding the shipped default instead; THIS LEG IS VOID.");
            intended = PORTAL_AREA_SAMPLE;
            name = "SAMPLE(forced-fallback)";
        }
        return new StampSelection(intended, name, w.toString().trim());
    }

    /** IS5-SEAM: how close to the aperture SHAPE the camera must be for the degenerate-frame
     *  full-screen fallback to fire. Sized to "the camera is in the doorway", not "the portal is
     *  nearby": at 0.5 blocks a portal you have walked past reads far larger and is left alone. */
    private static final double DEGENERATE_APERTURE_DIST = 0.5;

    private static int seamNullMeshCount = 0;
    private static int seamNullOutsideCount = 0;
    private static int seamFullScreenCount = 0;
    private static boolean seamReported = false;

    /**
     * THE MEASUREMENT THIS FIX OWES. Everything above is consistent with the magenta A/B and with
     * ViewAreaRenderer's clip, but "the mesh comes back NULL on crossing frames" was inferred, not
     * observed — and the alternative (the mesh survives but is partially clipped, leaving a band) has
     * a different fix. This line settles it on the next run: if it appears while crossing, the null
     * case is real and the full-screen fallback is the right answer. If the black band persists and
     * this line NEVER appears, the mesh was non-null and partially clipped, and the fix must instead
     * extend coverage rather than replace it.
     */
    private static long seamCensusNanos = 0L;

    /**
     * One line per second whenever the camera is within {@link #SEAM_CENSUS_DIST} of the aperture —
     * i.e. exactly the frames that show the black flash — naming what the near-plane clip did.
     *
     * <p>Read it like this. {@code null=true} means every triangle was dropped and the stamp is
     * skipped: the full-screen fallback is then the right fix and its own line will follow.
     * {@code null=false} with {@code clipped>0} means the mesh SURVIVED but was cut, so the stamp is
     * covering only part of the aperture and the black band is the remainder — a different defect,
     * fixed by extending coverage, not by replacing it. {@code null=false, clipped=0, dropped=0} means
     * the clip is innocent entirely and the black comes from somewhere else, at which point the
     * magenta A/B needs re-reading.
     */
    private static void seamCensus(double distToAperture, boolean meshNull) {
        if (distToAperture > SEAM_CENSUS_DIST) {
            return;
        }
        // IS5-REC — LAYER 0 ONLY. This probe answers a question about the OUTER seam window, is
        // ALWAYS ON (no lever), and its single 1 Hz slot carries neither a portal id nor a layer.
        // Under recursion the NESTED stamp reaches it FIRST — the nested dispatch runs inside
        // renderPortalContent, which precedes the outer stamp — so a nested portal would take the
        // second's slot and its row would be read as the outer window's. Worse, distToAperture is
        // computed from the LIVE camera, which at a nested layer is the DEST camera, so for a
        // reverse pair the mirrored distance passes the proximity gate and the row looks perfectly
        // plausible. An always-on probe that silently answers about a different portal is exactly
        // the failure mode this repo has paid for repeatedly, so it is gated rather than enriched;
        // enriching the line with layer + portal id is the better long-term fix and is left to the
        // seam arc that owns this instrument.
        if (qouteall.imm_ptl.core.render.context_management.PortalRendering.getPortalLayer() != 0) {
            return;
        }
        long now = System.nanoTime();
        if (now - seamCensusNanos < 1_000_000_000L) {
            return;
        }
        seamCensusNanos = now;
        Helper.LOGGER.info(
            "[Seamless Portals] IS5-SEAM census (1Hz, camera within {} of the aperture):"
                + " distToAperture={} meshNull={} stamp={} | near-plane clip: kept={} clipped={}"
                + " dropped={}"
                + " — meshNull=true => the stamp is SKIPPED (full-screen fallback applies);"
                + " meshNull=false with clipped>0 => the stamp covers only PART of the aperture and"
                + " the black band is the rest; kept>0 clipped=0 dropped=0 => the clip is innocent.",
            SEAM_CENSUS_DIST, String.format("%.4f", distToAperture), meshNull,
            selectStampPipeline().name(),
            ViewAreaRenderer.clipTrisKept, ViewAreaRenderer.clipTrisClipped,
            ViewAreaRenderer.clipTrisDropped);
    }

    /** How near the aperture the camera must be for the census to speak. Wide enough to cover the
     *  approach and the crossing, narrow enough that ordinary play does not log. */
    private static final double SEAM_CENSUS_DIST = 3.0;

    private static void reportSeamOnce(double dist) {
        if (seamReported) {
            return;
        }
        seamReported = true;
        Helper.LOGGER.info(
            "[Seamless Portals] IS5-SEAM (once-only): the aperture mesh came back NULL while the"
                + " camera was INSIDE the portal footprint (distance {} < {}). Every triangle failed"
                + " the S14.36 near-plane clip, so without this fallback the stamp would be skipped"
                + " and the window region would show the cleared buffer — the pure-black seam flash."
                + " Stamping FULL-SCREEN instead. If you are reading this while the seam flash is"
                + " GONE, the null-mesh hypothesis is confirmed.",
            String.format("%.4f", dist), DEGENERATE_APERTURE_DIST);
    }

    /** IS5-SEAM counters, read by the 1 Hz probe so the fallback's frequency is visible. */
    public static String seamCounters() {
        return "nullMesh=" + seamNullMeshCount + " (inside=" + seamFullScreenCount
            + " outside=" + seamNullOutsideCount + ")";
    }

    private IrisCompatPaste() {}
}
