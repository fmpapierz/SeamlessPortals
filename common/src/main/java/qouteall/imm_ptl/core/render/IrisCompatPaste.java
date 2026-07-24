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
 * <p>Two pipelines + their draw drivers, consumed ONLY by {@code IrisCompatOn262Renderer}:
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
 *       GEQUAL-rejects the far stamp exactly where it sits behind = IP parity. The deferred
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
    private static RenderPipeline PORTAL_STRAIGHT_COPY;

    static {
        try {
            // The proven mod idiom for hand-built pipelines (PortalRenderTypes static init):
            // register through vanilla's private RenderPipelines.register so the device
            // validates/precompiles like any vanilla pipeline. NOTE (P-PASTE, port-note §1-C):
            // registration does NOT enter iris's substitution key set — that map is populated
            // from ~59 explicit vanilla RenderPipelines.* singletons, keyed by OBJECT IDENTITY.
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
                .withVertexShader(Identifier.fromNamespaceAndPath("seamlessportals", "core/portal_area_sample"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("seamlessportals", "core/portal_area_sample"))
                .withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
                .withCull(false)
                .build();
            PORTAL_AREA_SAMPLE = (RenderPipeline) registerMethod.invoke(null, portalAreaSample);

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
            if (mesh == null) {
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
                    pass.setPipeline(PORTAL_AREA_SAMPLE);
                    pass.setUniform("Projection", combinedSlice);
                    pass.bindTexture(
                        "InSampler", sampleSource.getColorTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST) // fix (3)
                    );
                    pass.setVertexBuffer(0, vertexSlice);
                    pass.setIndexBuffer(indexBuffer, indices.type());
                    pass.drawIndexed(indexCount, 1, 0, 0, 0);
                } finally {
                    GlStateManager._enableBlend(0);
                }
            }
        }
    }

    private IrisCompatPaste() {}
}
