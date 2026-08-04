package com.warwa.seamlessportals.render;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.mixin.client.LevelRendererAccessorMixin;
import com.warwa.seamlessportals.mixin.client.ViewAreaInvokerMixin;
import com.warwa.seamlessportals.passthrough.AperturePassthroughLever;
import com.warwa.seamlessportals.passthrough.SeamIndexHolder;
import com.warwa.seamlessportals.passthrough.SeamMap;
import com.warwa.seamlessportals.passthrough.SeamOccupancy;
import com.warwa.seamlessportals.passthrough.SeamRegistry;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import qouteall.imm_ptl.core.render.ImmPtlViewArea;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE SEAM CLIP — cut a seam block at its portal plane in EVERY view, so the far half never draws
 * in the dimension that does not own it and the mirrored copy supplies it through the window.
 * Design + adversarial-panel record: {@code migration/SEAM_CLIP_DESIGN.md} (v2).
 *
 * <p>Three arms, all gated by {@link #active()}:
 * <ol>
 *   <li><b>Mesh exclusion</b> — qualifying seam cells read as AIR during section compile
 *       ({@code RenderRegionCacheSeamClipMixin} snapshots the cell set onto the region on the main
 *       thread; {@code RenderSectionRegionSeamClipMixin} answers AIR from it). The AIR report also
 *       un-culls neighbour faces against the cell, which is what makes the FRAMELESS side view
 *       correct (rays land on real drawn faces instead of tunnelling through culled-face gaps).</li>
 *   <li><b>The dynamic draw</b> — those cells re-tessellate against the LIVE level each pass and
 *       draw immediately ({@link PortalRenderTypes#drawMesh}), bracketed with a per-cell-plane
 *       {@code gl_ClipDistance} snapshot on the single com.warwa {@link FrontClipping} store that
 *       {@code GlCommandEncoderClipMixin} uploads per draw. Kept half = the CAMERA side of the
 *       plane, per pass — view-dependent by design (from behind, the back half draws and the
 *       back-side window supplies the front).</li>
 *   <li><b>Lifecycle dirtying</b> — client-side bind/unbind queues the covering sections (plus
 *       block-neighbour sections — the un-cull changes their meshes too) for a direct
 *       {@code compileAsync}, SameDimRemesh's coord-pinned recipe but with SEPARATE accounting
 *       (sharing its COMPILED set would have made the RS-DELIVERY arm-3 verdict un-failable —
 *       panel finding).</li>
 * </ol>
 *
 * <p><b>What is deliberately NOT clipped</b> (design §6): fluids (the region's
 * {@code getFluidState} bypasses {@code getBlockState}, so a waterlogged seam block keeps its
 * water), block-entity blocks (excluding them would drop the BE from the compile's collection),
 * DISJOINT (boundary-phase) cells (nothing straddles), query-only bindings (no mirror exists —
 * clipping would saw a block in half with no supplier), and any cell whose bindings all face away
 * from the camera (no window could supply the removed half).
 */
public final class SeamClipRenderer {

    private SeamClipRenderer() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_RECENT = 512;
    /** Same value as {@code qouteall...FrontClipping.ADJUSTMENT} — the seam epsilon family. */
    private static final double ADJUSTMENT = qouteall.imm_ptl.core.render.FrontClipping.ADJUSTMENT;
    private static final float SCALE_DETECT_EPSILON = 1.0e-3f;

    // =============================================================================================
    // Gate
    // =============================================================================================

    private static boolean sodiumGateLogged = false;

    /**
     * Whether the seam clip runs at all. OFF under the fix lever, the passthrough master lever,
     * the block-era renderer (flag-OFF draws through a different pipeline and would exclude cells
     * it never dynamically draws), and under sodium — the compat layer has no meshing hook, so the
     * exclusion arm cannot apply there and the dynamic draw alone would double-draw
     * (design §2 "Sodium").
     */
    public static boolean active() {
        if (AperturePassthroughLever.DISABLE_SEAM_CLIP || AperturePassthroughLever.DISABLED) {
            return false;
        }
        if (!com.warwa.seamlessportals.config.SeamlessPortalsConfig.isEntityPortals()) {
            return false;
        }
        if (SodiumInterface.invoker.isSodiumPresent()) {
            if (!sodiumGateLogged) {
                sodiumGateLogged = true;
                LOGGER.info("[SEAM CLIP] sodium present — seam clip self-gated OFF"
                    + " (no meshing hook; see SEAM_CLIP_DESIGN.md §2)");
            }
            return false;
        }
        return true;
    }

    // =============================================================================================
    // Predicate
    // =============================================================================================

    /** The STATE half of the clip predicate (design §1). Reads the LIVE level; main thread only. */
    private static boolean stateQualifies(BlockState state) {
        return !state.isAir()
            && state.getBlock() != PortalPlaceholderBlock.instance
            && state.getRenderShape() == RenderShape.MODEL
            && !state.hasBlockEntity();
    }

    /**
     * The BINDING half: a COINCIDENT, mirror-admitted binding — the mirror maintains a counterpart
     * for exactly these, so the clip's scope agrees with the mirror's by construction (panel
     * finding: clipping a query-only cell saws a block in half with no supplier anywhere).
     */
    @Nullable
    private static SeamRegistry.SeamBinding clipBinding(SeamRegistry.SeamCell cell) {
        for (SeamRegistry.SeamBinding b : cell.bindings()) {
            if (b.phase() == SeamMap.SeamPhase.COINCIDENT && b.isMirrorable()) {
                return b;
            }
        }
        return null;
    }

    /**
     * Whether any clip-eligible binding has the camera on its front side — i.e. a window facing
     * the camera can exist to supply the removed half. A single-faced portal seen from behind
     * fails this and draws UNCLIPPED (portals never render from behind, so nothing would ever
     * fill the hole — panel finding 3).
     */
    private static boolean cameraSideWindowPossible(
        SeamRegistry.SeamCell cell, Vec3 center, Vec3 camPos
    ) {
        for (SeamRegistry.SeamBinding b : cell.bindings()) {
            if (b.phase() != SeamMap.SeamPhase.COINCIDENT || !b.isMirrorable()) {
                continue;
            }
            Direction f = b.srcFacing();
            double d = f.getStepX() * (camPos.x - center.x)
                + f.getStepY() * (camPos.y - center.y)
                + f.getStepZ() * (camPos.z - center.z);
            if (d > 0) {
                return true;
            }
        }
        return false;
    }

    // =============================================================================================
    // Arm 1 — mesh exclusion (called by RenderRegionCacheSeamClipMixin, main thread)
    // =============================================================================================

    /**
     * Qualifying cells within the region's full 3×3×3 section bounds, or null when none. The FULL
     * bounds matter: a NEIGHBOUR section's compile queries this region for face culling against a
     * border seam cell, and must see AIR there too or its faces stay culled (the un-cull is what
     * closes the frameless x-ray — design §0/§1a).
     */
    @Nullable
    public static LongOpenHashSet computeRegionExclusions(ClientLevel level, long sectionNode) {
        if (!active()) {
            return null;
        }
        SeamIndexHolder holder = (SeamIndexHolder) level;
        if (holder.seamlessportals$sectionsWithSeams().isEmpty()) {
            return null;
        }
        int cx = SectionPos.x(sectionNode);
        int cy = SectionPos.y(sectionNode);
        int cz = SectionPos.z(sectionNode);
        LongOpenHashSet out = null;
        for (Long2ObjectMap.Entry<SeamRegistry.SeamCell> e
            : holder.seamlessportals$seamCells().long2ObjectEntrySet()) {
            BlockPos pos = BlockPos.of(e.getLongKey());
            if (Math.abs(SectionPos.blockToSectionCoord(pos.getX()) - cx) > 1
                || Math.abs(SectionPos.blockToSectionCoord(pos.getY()) - cy) > 1
                || Math.abs(SectionPos.blockToSectionCoord(pos.getZ()) - cz) > 1) {
                continue;
            }
            if (clipBinding(e.getValue()) == null) {
                continue;
            }
            if (!stateQualifies(level.getBlockState(pos))) {
                continue;
            }
            if (out == null) {
                out = new LongOpenHashSet(4);
            }
            out.add(e.getLongKey());
        }
        if (out != null) {
            cellsExcluded += out.size();
        }
        return out;
    }

    // =============================================================================================
    // Arm 2 — the dynamic draw
    // =============================================================================================

    /** One cell scheduled for this pass's dynamic draw. */
    private record CellDraw(BlockPos pos, BlockState state) {}

    /** Grouping key: the geometric plane a bracketed group is clipped by (axis-aligned). */
    private record PlaneKey(Direction keptDir, int planeCoordHalf) {}

    /**
     * MAIN pass draw site — registered at {@code LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN}
     * (after opaque terrain + entity phases, before translucent terrain and the portal driver, so
     * near halves are depth-buffered before the stencil pass computes window visibility).
     *
     * <p>The {@code isRendering()} guard is MANDATORY (panel finding): the Fabric event is woven
     * into the LevelRenderer CLASS and fires inside the full-pipeline twin's nested
     * {@code destRenderer.render()} with {@code mc.level} swapped to the dest world.
     */
    public static void onMainPassBeforeTranslucentTerrain() {
        if (!active()) {
            return;
        }
        if (PortalRendering.isRendering()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.levelRenderer == null) {
            return;
        }
        Matrix4f modelView = new Matrix4f(
            mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.viewRotationMatrix);
        drawForPass(mc.level, CHelper.getCurrentCameraPos(), modelView, null);
    }

    /**
     * DEST pass draw site — called from {@code SecondaryWorldRenderCore.renderDestWorld} right
     * after the 10.6 opaque-terrain draw, inside the armed inner clip (10.5, −ADJUSTMENT) and the
     * live stencil. {@code mc.level} IS the dest level at that point (the shell swap), and
     * {@code CHelper.getCurrentCameraPos()} is the pass's virtual camera — the same reads the
     * inner-clip arm itself uses. Runs once per portal layer (nested passes re-enter renderDestWorld).
     *
     * <p>NO twin call in {@code renderDestWorldFullPipeline} — see SEAM_CLIP_DESIGN.md §3: no
     * post-opaque-terrain insertion point exists there, M4 disarms the live store mid-pass, and
     * the FROZEN {@code FullPipelineClipState} override would silently defeat an own-plane
     * bracket. That path is iris ⇒ sodium ⇒ already self-gated OFF.
     */
    public static void onDestPassAfterOpaqueTerrain(Matrix4f destViewMatrix) {
        if (!active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Plane activePlane = PortalRendering.isRendering()
            ? PortalRendering.getActiveClippingPlane() : null;
        drawForPass(mc.level, CHelper.getCurrentCameraPos(), new Matrix4f(destViewMatrix), activePlane);
    }

    /**
     * Enumerate this pass's qualifying cells, split them into an AMBIENT group (drawn under
     * whatever plane the pass has armed — keep-all on the main pass, the inner clip on a dest
     * pass) and per-plane BRACKETED groups, then tessellate + draw each.
     *
     * <p>Dest-pass plane rules (design §3, panel-corrected): a cell on the ACTIVE plane draws
     * ambient (the inner clip performs exactly the far-half cut); an unrelated cell brackets its
     * OWN plane only when its whole AABB lies on the active plane's kept side — anything
     * straddling or camera-side draws ambient, because abandoning the inner clip for such a cell
     * paints foreground junk into the depth-cleared window (panel finding 5).
     */
    private static void drawForPass(
        ClientLevel level, Vec3 camPos, Matrix4f modelView, @Nullable Plane activePlane
    ) {
        SeamIndexHolder holder = (SeamIndexHolder) level;
        if (holder.seamlessportals$sectionsWithSeams().isEmpty()) {
            return;
        }
        List<CellDraw> ambient = null;
        Map<PlaneKey, List<CellDraw>> bracketed = null;

        for (Long2ObjectMap.Entry<SeamRegistry.SeamCell> e
            : holder.seamlessportals$seamCells().long2ObjectEntrySet()) {
            SeamRegistry.SeamBinding binding = clipBinding(e.getValue());
            if (binding == null) {
                continue;
            }
            BlockPos pos = BlockPos.of(e.getLongKey());
            BlockState state = level.getBlockState(pos);
            if (!stateQualifies(state)) {
                continue;
            }
            // Every qualifying cell MUST draw (it is absent from the mesh); the only question is
            // which plane brackets it. A cell that draws "unclipped" still draws — under the
            // pass's ambient plane.
            Vec3 center = Vec3.atCenterOf(pos);
            Direction f = binding.srcFacing();

            // ★ THE KEPT HALF COMES FROM THE OBJECT, NOT THE CAMERA — FRACTIONAL_DESIGN.md §2a.0.
            //
            // This line used to be `keptDir = side >= 0 ? f : f.getOpposite()`, i.e. keep whichever
            // half the camera is on. That is the walk-around swap the user declined on 2026-07-27,
            // and once collision started reading the recorded owner half the two disagreed outright:
            // you could walk into a half you could still see, and the block appeared to jump sides
            // as you circled the portal while its collision stayed put.
            //
            // Occupancy is recorded at placement from the crosshair hit point, so it is
            // view-INDEPENDENT by construction. A cell with no recorded owner keeps today's
            // behaviour (drawn whole) rather than guessing a side, matching keptShape exactly.
            byte owned = SeamOccupancy.occupancyOf(level, pos);
            // ★ TWO OCCUPANTS PER CELL (user live round 8). A cell may hold the vanilla blockstate
            // (the PRIMARY, clipped to its owned half) AND a side-table SECONDARY of any type,
            // clipped to the other half. Each occupant is its own draw with its own plane; the
            // secondary's state never touches the chunk, so this dynamic path is the ONLY thing
            // that renders it — which works because seam cells are mesh-excluded and drawn here
            // every frame from live state.
            SeamOccupancy.Secondary sec = SeamOccupancy.secondaryOf(level, pos);
            boolean single =
                owned == SeamOccupancy.HALF_POSITIVE || owned == SeamOccupancy.HALF_NEGATIVE;
            net.minecraft.world.level.block.state.BlockState[] oStates;
            Direction[] oDirs;    // null dir = drawn whole (no owner recorded / legacy BOTH)
            if (single) {
                Direction primaryDir = Direction.get(
                    owned == SeamOccupancy.HALF_POSITIVE
                        ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE,
                    f.getAxis());
                if (sec != null && stateQualifies(sec.state())) {
                    Direction secDir = Direction.get(
                        sec.half() == SeamOccupancy.HALF_POSITIVE
                            ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE,
                        f.getAxis());
                    oStates = new net.minecraft.world.level.block.state.BlockState[]{
                        state, sec.state()};
                    oDirs = new Direction[]{primaryDir, secDir};
                } else {
                    oStates = new net.minecraft.world.level.block.state.BlockState[]{state};
                    oDirs = new Direction[]{primaryDir};
                }
            } else {
                oStates = new net.minecraft.world.level.block.state.BlockState[]{state};
                oDirs = new Direction[]{null};
            }
            // ★ BOTH CAMERA-DERIVED GUARDS ARE GONE — user live round 6, and FRACTIONAL_DESIGN.md
            // §1.2 predicted exactly this once the cut stopped being camera-derived:
            //
            // - the NEAR-PLANE guard (|side| >= ADJUSTMENT) existed because the old camera-side
            //   flip was degenerate when the camera sat on the plane. It made the cell draw WHOLE
            //   for the crossing frame — measured live as "a block half flashes for a millisecond
            //   while crossing the seam": the empty half, drawn for one frame. The owned-half plane
            //   is fixed; nothing degenerates at any camera distance; the guard was pure defect.
            // - cameraSideWindowPossible existed so a single-faced portal seen from behind would
            //   not lose a half that no window could supply. Under the owner-half model the far
            //   half is GENUINELY ABSENT — there is nothing to supply — so the cut is correct from
            //   every viewpoint, windows or none, and the whole-block fallback was one more way to
            //   flash the empty half.
            if (activePlane != null && !onPlane(center, f, activePlane)
                && whollyOnClippedSide(pos, activePlane)) {
                // Unrelated cell in a dest pass, wholly on the CLIPPED (camera) side: every
                // fragment of every occupant would be discarded — skip the whole cell.
                continue;
            }
            boolean offPlaneStraddling = activePlane != null && !onPlane(center, f, activePlane)
                && !whollyOnKeptSide(pos, activePlane);

            for (int oi = 0; oi < oStates.length; oi++) {
                net.minecraft.world.level.block.state.BlockState oState = oStates[oi];
                Direction keptDir = oDirs[oi] == null ? f : oDirs[oi];
                boolean clipThis = oDirs[oi] != null;
                if (offPlaneStraddling) {
                    // Straddling: draw ambient (never abandon the inner clip — panel finding 5).
                    clipThis = false;
                }
                if (activePlane != null && onPlane(center, f, activePlane)) {
                // ★ ON-PLANE CELL IN A DEST PASS — THE WINDOW VIEW, and the phantom-half fix
                // (user live round 5, 2026-08-02). The old rule drew the cell's WHOLE cube under the
                // ambient inner clip: "show the beyond-plane half through the window". Correct under
                // the old model, where the far cell held a whole mirrored block — but under the
                // owner-half model the beyond-plane region can be the EMPTY half. Measured exactly
                // as the user described from both directions: from dest side A the window showed a
                // half block carved out of source side B (whose material is all in side A), and the
                // mirror image from source side B — a half block that "disappears when you teleport
                // through", because the in-world state was right and only this draw was wrong.
                //
                // NO second clip plane is needed: on a COINCIDENT seam the cut plane IS the portal
                // plane, so the owned half is either wholly on the window's kept side (draw ambient
                // — the inner clip then trims exactly at the owned boundary) or wholly on the
                // clipped side (there is nothing beyond the plane to show — draw NOTHING). One
                // plane evaluation at the owned half's centre decides; kept side is positive, per
                // whollyOnKeptSide/cornersExtreme.
                    if (oDirs[oi] != null) {
                        // Per-OCCUPANT window test: this occupant draws through the window only if
                        // ITS half lies beyond the plane. With two occupants exactly one qualifies
                        // (their halves are complements), so each side of the window shows the
                        // object whose material genuinely is there.
                        Vec3 halfCenter = center.add(
                            keptDir.getStepX() * 0.25, keptDir.getStepY() * 0.25,
                            keptDir.getStepZ() * 0.25);
                        Vec3 n = activePlane.normal();
                        Vec3 pp = activePlane.pos();
                        double ownedSide = n.x * (halfCenter.x - pp.x)
                            + n.y * (halfCenter.y - pp.y) + n.z * (halfCenter.z - pp.z);
                        if (ownedSide < 0) {
                            continue;   // this occupant is entirely on the camera side
                        }
                    }
                    clipThis = false;   // the active link's own cells: ambient inner clip cuts
                }
                CellDraw draw = new CellDraw(pos, oState);
                if (clipThis) {
                    // planeCoordHalf: the plane passes through the cell centre; its coordinate
                    // along the axis is (blockCoord + 0.5), stored ×2 to stay integral.
                    int coordHalf = 2 * componentAlong(pos, keptDir.getAxis()) + 1;
                    if (bracketed == null) {
                        bracketed = new LinkedHashMap<>();
                    }
                    bracketed.computeIfAbsent(new PlaneKey(keptDir, coordHalf),
                            k -> new ArrayList<>())
                        .add(draw);
                } else {
                    if (ambient == null) {
                        ambient = new ArrayList<>();
                    }
                    ambient.add(draw);
                }
            }
        }
        if (ambient == null && bracketed == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        boolean destPass = activePlane != null;
        // Install the pass model-view for prepare()'s DynamicTransforms snapshot — the ambient
        // stack can be IDENTITY inside a dest pass (MyGameRenderer's identity bracket), the same
        // reason ViewAreaRenderer installs its matrices around its draw.
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        mvStack.set(modelView);
        try {
            if (ambient != null) {
                drawGroup(mc, level, camPos, ambient, null, destPass);
            }
            if (bracketed != null) {
                for (Map.Entry<PlaneKey, List<CellDraw>> g : bracketed.entrySet()) {
                    FrontClipping.Snapshot plane =
                        outerPlaneSnapshot(g.getKey(), camPos, modelView);
                    drawGroup(mc, level, camPos, g.getValue(), plane, destPass);
                }
            }
        } finally {
            mvStack.popMatrix();
        }
    }

    /** Does this cell's plane coincide with the pass's active clipping plane? */
    private static boolean onPlane(Vec3 center, Direction facing, Plane activePlane) {
        Vec3 n = activePlane.normal();
        double align = Math.abs(n.x * facing.getStepX() + n.y * facing.getStepY()
            + n.z * facing.getStepZ());
        if (align < 1.0 - 1.0e-3) {
            return false;
        }
        Vec3 p = activePlane.pos();
        double dist = Math.abs(n.x * (center.x - p.x) + n.y
            * (center.y - p.y) + n.z * (center.z - p.z));
        return dist < ADJUSTMENT * 2;
    }

    /** All 8 corners of the cell's unit AABB on the active plane's kept (content) side? */
    private static boolean whollyOnKeptSide(BlockPos pos, Plane activePlane) {
        return cornersExtreme(pos, activePlane) > 0;
    }

    /** All 8 corners on the CLIPPED (camera) side — the draw would be fully discarded? */
    private static boolean whollyOnClippedSide(BlockPos pos, Plane activePlane) {
        return cornersExtreme(pos, activePlane) < 0;
    }

    /** +1: all corners strictly kept-side; -1: all strictly clipped-side; 0: straddling. */
    private static int cornersExtreme(BlockPos pos, Plane activePlane) {
        Vec3 n = activePlane.normal();
        Vec3 p = activePlane.pos();
        boolean anyKept = false, anyClipped = false;
        for (int i = 0; i < 8; i++) {
            double x = pos.getX() + ((i & 1) != 0 ? 1 : 0);
            double y = pos.getY() + ((i & 2) != 0 ? 1 : 0);
            double z = pos.getZ() + ((i & 4) != 0 ? 1 : 0);
            double d = n.x * (x - p.x) + n.y * (y - p.y) + n.z * (z - p.z);
            if (d > 0) {
                anyKept = true;
            } else {
                anyClipped = true;
            }
        }
        if (anyKept && anyClipped) {
            return 0;
        }
        return anyKept ? 1 : -1;
    }

    private static int componentAlong(BlockPos pos, Direction.Axis axis) {
        return switch (axis) {
            case X -> pos.getX();
            case Y -> pos.getY();
            case Z -> pos.getZ();
        };
    }

    /**
     * The outer-clip view-space plane for one bracketed group. Kept half-space (world):
     * {@code n·(P − planePoint) − ADJUSTMENT ≥ 0} with n = {@code keptDir} (points INTO the owned
     * half). Before-model-view (camera-relative) constant:
     * {@code c = n·(cam − planePoint) − ADJUSTMENT}; view-space normal by the COLUMN-FORM rotate
     * {@code M·n} (anti-fix guard: never mulTranspose — S11-A), with the S13-L inverse-transpose
     * fallback under a scaled model-view.
     *
     * <p>★ THE EPSILON FLIPPED SIGN with the owner-half model — user live round 6. The original
     * {@code +ADJUSTMENT} pushed the cut 0.01 PAST the plane so the two passes' halves would
     * overlap instead of leaving a slit — correct when the far region always held the mirrored
     * half's material, which covered the overhang. Under the owner-half model the far region can be
     * genuinely EMPTY, and that 0.01 of block skin poking into it is visible from the empty side as
     * "a tiny border sliver of the block right on the seam" (the user's exact words). Pulling the
     * cut back 0.01 INSIDE the owned half removes the sliver, and no slit opens in the window view:
     * the dest pass's inner clip arms at {@code −ADJUSTMENT} (content kept from plane−0.01 onward),
     * so the window's crossing half meets this cut at exactly plane−0.01.
     */
    private static FrontClipping.Snapshot outerPlaneSnapshot(
        PlaneKey key, Vec3 camPos, Matrix4f modelView
    ) {
        double nx = key.keptDir().getStepX();
        double ny = key.keptDir().getStepY();
        double nz = key.keptDir().getStepZ();
        double planeCoord = key.planeCoordHalf() / 2.0;
        // planePoint: any point on the plane; take the plane coordinate along the axis and the
        // camera's own transverse coordinates (transverse components drop out of the dot product).
        double px = key.keptDir().getAxis() == Direction.Axis.X ? planeCoord : camPos.x;
        double py = key.keptDir().getAxis() == Direction.Axis.Y ? planeCoord : camPos.y;
        double pz = key.keptDir().getAxis() == Direction.Axis.Z ? planeCoord : camPos.z;
        double c = nx * (camPos.x - px) + ny * (camPos.y - py) + nz * (camPos.z - pz) - ADJUSTMENT;

        Matrix3f linear = new Matrix3f(modelView);
        float det = linear.determinant();
        Vector3f nView;
        if (!Float.isFinite(det) || Math.abs(det - 1.0f) <= SCALE_DETECT_EPSILON) {
            Vector4f v = new Vector4f((float) nx, (float) ny, (float) nz, 0f).mul(modelView);
            nView = new Vector3f(v.x, v.y, v.z);
        } else {
            nView = linear.invert().transpose().transform(new Vector3f((float) nx, (float) ny, (float) nz));
        }
        return new FrontClipping.Snapshot(nView.x, nView.y, nView.z, (float) c, true);
    }

    /**
     * Tessellate one group against the LIVE level (real neighbours → the section path's face
     * culling, AO and light) and draw it. {@code plane == null} draws under the pass's ambient
     * clip state; otherwise the group brackets the com.warwa store around its draws — the
     * per-draw uploader reads the store at each {@code trySetup}, so the swap scopes exactly
     * these draws (the PerEntityClipBracket pattern).
     */
    private static void drawGroup(
        Minecraft mc, ClientLevel level, Vec3 camPos, List<CellDraw> cells,
        @Nullable FrontClipping.Snapshot plane, boolean destPass
    ) {
        EnumMap<ChunkSectionLayer, BufferBuilder> builders = new EnumMap<>(ChunkSectionLayer.class);
        List<ByteBufferBuilder> byteBuffers = new ArrayList<>(3);
        // ★ CULL=FALSE (user live round 14: "whatever face of the seam block that is touching the
        // face of the adjacent seam block on other side, it gets clipped/transparent"). Face
        // culling here runs against the REAL level, where a neighbouring seam block reads as a
        // FULL solid cube — so the shared face between two adjacent seam blocks was culled on both
        // sides. But each neighbour is only HALF there, and when their owned halves are opposite
        // (two rows passing side by side), the culled face's exposed part is a hole straight
        // through the block. Tessellating cull-less draws every face of the cut block; the extra
        // faces against genuinely solid neighbours are hidden by the depth buffer, and seam cells
        // are few (moving-piston cost class, per the design doc).
        ModelBlockRenderer renderer = new ModelBlockRenderer(
            mc.options.ambientOcclusion().get(), false, mc.getBlockColors());
        boolean cutoutLeaves = mc.options.cutoutLeaves().get();
        var modelSet = mc.getModelManager().getBlockStateModelSet();
        try {
            for (CellDraw cd : cells) {
                float ox = (float) (cd.pos().getX() - camPos.x);
                float oy = (float) (cd.pos().getY() - camPos.y);
                float oz = (float) (cd.pos().getZ() - camPos.z);
                boolean forceOpaque = ModelBlockRenderer.forceOpaque(cutoutLeaves, cd.state());
                BlockQuadOutput out = (x, y, z, quad, instance) -> {
                    ChunkSectionLayer layer = forceOpaque
                        ? ChunkSectionLayer.SOLID : quad.materialInfo().layer();
                    BufferBuilder b = builders.get(layer);
                    if (b == null) {
                        ByteBufferBuilder bytes = new ByteBufferBuilder(4096);
                        byteBuffers.add(bytes);
                        b = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
                        builders.put(layer, b);
                    }
                    b.putBlockBakedQuad(x, y, z, quad, instance);
                };
                renderer.tesselateBlock(out, ox, oy, oz, level, cd.pos(), cd.state(),
                    modelSet.get(cd.state()), cd.state().getSeed(cd.pos()));
                cellsDrawn++;
                if (destPass) {
                    destCellsDrawn++;
                }
            }

            FrontClipping.Snapshot prev = plane != null ? FrontClipping.capture() : null;
            if (plane != null) {
                FrontClipping.restore(plane);
                if (destPass) {
                    ownPlaneDraws++;
                }
            }
            try {
                for (Map.Entry<ChunkSectionLayer, BufferBuilder> b : builders.entrySet()) {
                    MeshData mesh = b.getValue().build();
                    if (mesh != null) {
                        PortalRenderTypes.drawMesh(renderTypeFor(b.getKey()), mesh);
                        drawsIssued++;
                    }
                }
            } finally {
                if (prev != null) {
                    FrontClipping.restore(prev);
                }
            }
        } finally {
            for (ByteBufferBuilder bytes : byteBuffers) {
                bytes.close();
            }
        }
        maybeReport();
    }

    private static RenderType renderTypeFor(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> RenderTypes.solidMovingBlock();
            case CUTOUT -> RenderTypes.cutoutMovingBlock();
            case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
        };
    }

    // =============================================================================================
    // Arm 3 — lifecycle dirtying (client bind/unbind → direct recompile, OWN accounting)
    // =============================================================================================

    /** Sections queued for recompile, per dimension. Client tick thread only (== render thread). */
    private static final Map<ResourceKey<Level>, LongOpenHashSet> PENDING_SECTIONS = new HashMap<>();

    /**
     * Called from {@code AperturePassthroughInit} on every CLIENT-side bind, rebind and dispose.
     * Queues the sections covering the portal's seam cells AND their block neighbours (the
     * un-cull changes neighbour meshes too, when a cell sits on a section border).
     *
     * <p>Known gap (design §6): a portal that MOVES queues only its NEW cells — the old cells'
     * sections recompile on their next natural dirtying. Teardown is fine (dispose fires with
     * geometry intact).
     */
    public static void onClientPortalIndexChanged(Portal portal) {
        if (!active()) {
            return;
        }
        Level level = portal.level();
        if (level == null || !level.isClientSide()) {
            return;
        }
        LongOpenHashSet set = PENDING_SECTIONS.computeIfAbsent(
            level.dimension(), k -> new LongOpenHashSet());
        for (Vec3 column : SeamMap.enumerateColumns(portal)) {
            BlockPos cell = SeamMap.seamCell(portal, column);
            set.add(SectionPos.asLong(cell));
            for (Direction d : Direction.values()) {
                set.add(SectionPos.asLong(cell.relative(d)));
            }
        }
    }

    /** POST_CLIENT_TICK flush — after the world tick, never mid-extract, never mid-render. */
    public static void onEndClientTick(Minecraft mc) {
        if (PENDING_SECTIONS.isEmpty()) {
            return;
        }
        if (mc == null || mc.level == null) {
            PENDING_SECTIONS.clear();
            return;
        }
        try {
            flushPending(mc);
        } catch (Throwable t) {
            PENDING_SECTIONS.clear();
            LOGGER.warn("[SEAM CLIP] recompile flush failed (seam meshes may lag a bind)", t);
        }
    }

    private static void flushPending(Minecraft mc) {
        var it = PENDING_SECTIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ResourceKey<Level>, LongOpenHashSet> e = it.next();
            ClientLevel level = null;
            LevelRenderer renderer = null;
            if (mc.level.dimension().equals(e.getKey())) {
                level = mc.level;
                renderer = mc.levelRenderer;
            } else if (ClientWorldLoader.getIsInitialized()) {
                // Remote dims: resolve WITHOUT creating — a bound portal implies its level exists.
                // The initialization guard matters during world close/reopen: a dispose can queue
                // remote entries right as the loader tears down, and getClientWorlds() Validates
                // isInitialized (observed as a caught IllegalArgumentException in the
                // seamDeliveryTest run). Uninitialized ⇒ the worlds are going away; drop silently.
                for (ClientLevel candidate : ClientWorldLoader.getClientWorlds()) {
                    if (candidate.dimension().equals(e.getKey())) {
                        level = candidate;
                        renderer = ClientWorldLoader.getWorldRenderer(e.getKey());
                        break;
                    }
                }
            }
            if (level != null && renderer != null) {
                RenderRegionCache cache = new RenderRegionCache();
                for (long node : e.getValue()) {
                    scheduleRecompile(level, renderer, node, cache);
                }
            }
            it.remove();
        }
    }

    /**
     * SameDimRemesh's coord-pinned direct-compile recipe (preset lookup → {@code ImmPtlViewArea}
     * fallback → {@code compileAsync}), with SeamClip-OWN counters. Only ALREADY-COMPILED sections
     * are recompiled: a fresh section's first compile snapshots the already-updated index anyway.
     * The direct compile is also what bounds the far-through-window staleness the tracker route
     * cannot serve (defect-A family — marks out there are never consumed).
     */
    private static void scheduleRecompile(
        ClientLevel level, LevelRenderer renderer, long node, RenderRegionCache cache
    ) {
        ViewArea viewArea = ((LevelRendererAccessorMixin) renderer).seamlessportals$getViewArea();
        if (viewArea == null) {
            return;   // sodium owns terrain; feature is self-gated off there anyway
        }
        SectionRenderDispatcher.RenderSection section =
            ((ViewAreaInvokerMixin) (Object) viewArea).seamlessportals$invokeGetRenderSection(node);
        if (section == null && viewArea instanceof ImmPtlViewArea immPtl) {
            section = immPtl.provideBuiltChunkByChunkPos(
                SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
            // provideBuiltChunkByChunkPos clamps Y into the world's section range — a node outside
            // it comes back as a different section; compiling that would rebuild the wrong slice.
            if (section != null && section.getSectionNode() != node) {
                return;
            }
        }
        if (section == null) {
            return;
        }
        if (section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED) {
            return;
        }
        section.setFadeDuration(0L);
        section.setWasPreviouslyEmpty(false);
        section.compileAsync(cache.createRegion(level, node));
        recompilesScheduled++;
    }

    // =============================================================================================
    // Outcome tracking (the gate's wait) + counters
    // =============================================================================================

    /**
     * Every {@code setSectionMesh} node, epoch-resettable — the gate's "the section holding the
     * staged cell recompiled AFTER the placement tick" wait. Deliberately NOT SameDimRemesh's
     * COMPILED set (panel finding 2: sharing it would mask the RS-DELIVERY arm-3 verdict).
     */
    private static final LongOpenHashSet RECENT_MESHES = new LongOpenHashSet();

    /** Called from {@code RenderSectionMeshReplacedMixin} for every finished section compile. */
    public static void noteMeshReplaced(long sectionNode) {
        synchronized (RECENT_MESHES) {
            if (RECENT_MESHES.size() >= MAX_RECENT) {
                RECENT_MESHES.clear();
            }
            RECENT_MESHES.add(sectionNode);
        }
    }

    /** Gate epoch reset: forget mesh replacements seen so far. */
    public static void resetMeshTracking() {
        synchronized (RECENT_MESHES) {
            RECENT_MESHES.clear();
        }
    }

    /** Has the section holding this block position had its mesh replaced since the last reset? */
    public static boolean meshReplacedAt(int x, int y, int z) {
        long node = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
        synchronized (RECENT_MESHES) {
            return RECENT_MESHES.contains(node);
        }
    }

    private static long cellsExcluded = 0;
    private static long cellsDrawn = 0;
    private static long destCellsDrawn = 0;
    private static long drawsIssued = 0;
    private static long ownPlaneDraws = 0;
    private static long recompilesScheduled = 0;

    public static long cellsExcludedCount() {
        return cellsExcluded;
    }

    public static long cellsDrawnCount() {
        return cellsDrawn;
    }

    public static String counters() {
        return "cellsExcluded=" + cellsExcluded + " cellsDrawn=" + cellsDrawn
            + " destCellsDrawn=" + destCellsDrawn + " drawsIssued=" + drawsIssued
            + " ownPlaneDraws=" + ownPlaneDraws + " recompilesScheduled=" + recompilesScheduled;
    }

    private static long lastReportMs = 0;

    private static void maybeReport() {
        if (!AperturePassthroughLever.SEAM_CLIP_PROBE) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReportMs >= 1000) {
            lastReportMs = now;
            LOGGER.info("[SEAM CLIP] {}", counters());
        }
    }
}
