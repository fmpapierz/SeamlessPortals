package qouteall.imm_ptl.core.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap;
import qouteall.imm_ptl.core.ducks.IERenderSection;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;
import qouteall.imm_ptl.core.miscellaneous.GcMonitor;
import qouteall.q_misc_util.Helper;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * IP's unbounded, presets-cached render-section grid, rebuilt as a 26.2 {@link ViewArea}
 * SUBCLASS (R4; SPIKE-R4 CONFIRMED). Retires the mod's pinned-bounded deviation (and its
 * latent >71-chunk multi-portal collision bug) by owning an UNBOUNDED coord-pinned store
 * ({@link #columnMap} + {@link #presets}) instead of vanilla's fixed {@code RotatingSectionStorage}.
 *
 * <p><b>Install (S12):</b> a mixin {@code @Redirect} of the {@code new ViewArea(...)} construction
 * inside {@code LevelRenderer.invalidateCompiledGeometry} constructs this subclass instead, gated on
 * the GLOBAL {@code entityPortals} flag (NEVER on {@code mc.levelRenderer} identity — SPIKE-R4 §4-S1:
 * the mod swaps that field per portal-render frame). See {@code fragments/S11B-viewarea.md}.
 *
 * <p><b>26.2 reconcile (render-core G25/C22-C29, SPIKE-R4 §3):</b> 26.2's {@code ViewArea} wraps a
 * {@code private final RotatingSectionStorage} with no {@code sections}/{@code level}/grid-size
 * fields, no {@code createSections}, and no {@code setDirty} (dirty tracking externalised to
 * {@code SectionUpdateTracker}). The subclass keeps the super store ALIVE purely so the non-overridden
 * geometry getters ({@code size}/{@code minSectionY}/{@code getViewDistance}/{@code getCameraSectionPos}
 * …) advertise the bounded, stable answers the {@code SectionOcclusionGraph} octree sizing needs
 * (SPIKE-R4 caution 1), while OVERRIDING every accessor that actually resolves a section
 * ({@link #repositionCamera}, {@link #getRenderSectionAt}, {@link #getRenderSection}, {@link #releaseAllBuffers})
 * to read the mod-owned unbounded store. IP's coord-pinned columns are preserved exactly (SPIKE-R4
 * caution 2 — the OPPOSITE of 26.2's slot-stable/coord-mutable {@code repositionCenter}).
 */
@Environment(EnvType.CLIENT)
public class ImmPtlViewArea extends ViewArea {

    public static class Column {
        public long mark = 0;
        public RenderSection[] sections;

        public Column(RenderSection[] sections) {
            this.sections = sections;
        }
    }

    public static class Preset {
        public final RenderSection[] data;
        public long lastActiveTime = 0;

        public Preset(
            RenderSection[] data
        ) {
            this.data = data;
        }
    }

    private final SectionRenderDispatcher factory;
    private final Long2ObjectOpenHashMap<Column> columnMap = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Preset> presets = new Long2ObjectOpenHashMap<>();
    private Preset currentPreset = null;

    // 26.2: these were inherited protected ViewArea fields in 1.21.3 (GONE — G25). The subclass owns
    // them: `sections` is the CURRENT preset array (swapped by repositionCamera), `level` is the world
    // (26.2 ViewArea has no level field), the grid sizes derive from renderDistance + world height.
    private final Level level;
    private RenderSection[] sections;
    private final int sectionGridSizeX;
    private final int sectionGridSizeY;
    private final int sectionGridSizeZ;

    public final int minSectionY;
    public final int endSectionY;

    private boolean isAlive = true;

    public static void init() {
        ImmPtlClientChunkMap.clientChunkUnloadSignal.connect(section -> {
            ResourceKey<Level> dimension = section.getLevel().dimension();

            LevelRenderer worldRenderer = ClientWorldLoader.WORLD_RENDERER_MAP.get(dimension);

            if (worldRenderer != null) {
                ViewArea viewArea = ((IEWorldRenderer) worldRenderer).ip_getBuiltChunkStorage();
                if (viewArea instanceof ImmPtlViewArea immPtlViewArea) {
                    immPtlViewArea.onChunkUnload(section.getPos().x(), section.getPos().z());
                }
            }
        });

        IPGlobal.POST_CLIENT_TICK_EVENT.register(() -> {
            if (ClientWorldLoader.getIsInitialized()) {
                for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
                    LevelRenderer worldRenderer =
                        ClientWorldLoader.getWorldRenderer(world.dimension());
                    ViewArea viewArea = ((IEWorldRenderer) worldRenderer).ip_getBuiltChunkStorage();
                    if (viewArea instanceof ImmPtlViewArea immPtlViewArea) {
                        immPtlViewArea.tick();
                    }
                }
            }
        });
    }

    /**
     * 26.2 ctor (SPIKE-R4 §3): the super ctor moved from 1.21.3's {@code (dispatcher, Level, int r,
     * LevelRenderer)} to the 7-arg {@code (dispatcher, minY, maxY, minSectionY, maxSectionY,
     * renderDistance, occlusionGraph)} with no Level/LevelRenderer param. The install redirect (S12)
     * captures the {@code ClientLevel} from {@code invalidateCompiledGeometry}'s own parameter and
     * threads it in as the trailing {@code world} arg. The main-thread {@code isSameThread} assert
     * lives in the super ctor.
     */
    public ImmPtlViewArea(
        SectionRenderDispatcher sectionBuilder,
        int minY,
        int maxY,
        int minSectionY,
        int maxSectionY,
        int renderDistance,
        SectionOcclusionGraph occlusionGraph,
        Level world
    ) {
        super(sectionBuilder, minY, maxY, minSectionY, maxSectionY, renderDistance, occlusionGraph);
        factory = sectionBuilder;
        this.level = world;

        // 1.21.3 read these off ViewArea; 26.2 has no grid fields (G25) — derive them identically:
        // the RotatingSectionStorage sizes X/Z = radius*2+1 and Y = maxSectionY-minSectionY+1.
        this.sectionGridSizeX = renderDistance * 2 + 1;
        this.sectionGridSizeZ = renderDistance * 2 + 1;
        this.sectionGridSizeY = McHelper.getYSectionNumber(world);

        // Verbatim IP: cacheSize is computed and (as in 1.21.3) discarded — kept for zero-deviation.
        int cacheSize = this.sectionGridSizeX * this.sectionGridSizeY * this.sectionGridSizeZ;
        if (IPGlobal.cacheGlBuffer) {
            cacheSize = cacheSize / 10;
        }

        this.minSectionY = McHelper.getMinSectionY(world);
        this.endSectionY = McHelper.getMaxSectionYExclusive(world);

        // 26.2: `createSections` (1.21.3 override) is GONE (G25). Its body — allocate the empty
        // current-preset array — folds into the ctor. repositionCamera replaces it each frame.
        int num = this.sectionGridSizeX * this.sectionGridSizeY * this.sectionGridSizeZ;
        this.sections = new RenderSection[num];
    }

    @Override
    public void releaseAllBuffers() {
        Set<RenderSection> allActiveBuiltChunks = getAllActiveBuiltChunks();
        allActiveBuiltChunks.forEach(
            RenderSection::reset  // 26.2: RenderSection.releaseBuffers() -> reset() (C27)
        );
        columnMap.clear();
        presets.clear();

        isAlive = false;
    }

    /**
     * It will only be called during vanilla outer world rendering
     * Won't be called in portal rendering
     * In {@link SectionOcclusionGraph#initializeQueueForFullUpdate(Camera, Queue)} it reads the RenderChunks in another thread.
     *
     * <p>26.2 (C22): the signature moved from {@code repositionCamera(double, double)} to
     * {@code repositionCamera(SectionPos)} returning boolean. The {@code SectionPos} already IS the
     * camera chunk, so IP's {@code Mth.floor(playerX) >> 4} is dropped. After swapping the preset,
     * {@code super.repositionCamera} is invoked so the super {@code RotatingSectionStorage}'s
     * {@code centerSectionPos} + occlusion invalidation stay coherent (they feed the octree sizing
     * getters this subclass does NOT override) — a required 26.2 reconcile with no sign surface.
     */
    @Override
    public boolean repositionCamera(SectionPos cameraSectionPos) {
        Profiler.get().push("built_section_storage");

        int cameraChunkX = cameraSectionPos.x();
        int cameraChunkZ = cameraSectionPos.z();

        // 26.2: ChunkPos is a record; asLong(int,int)->pack(int,int) and the instance toLong() is
        // gone. pack + the surviving getX(long)/getZ(long) are the matched pack/unpack family.
        Preset preset = presets.computeIfAbsent(
            ChunkPos.pack(cameraChunkX, cameraChunkZ),
            whatever -> {
                return createPresetByChunkPos(cameraChunkX, cameraChunkZ);
            }
        );
        preset.lastActiveTime = System.nanoTime();

        this.sections = preset.data;
        this.currentPreset = preset;

        boolean moved = super.repositionCamera(cameraSectionPos);

        Profiler.get().pop();
        return moved;
    }

    // 26.2: ViewArea.setDirty(int,int,int,boolean) is GONE (C24 — dirty tracking externalised to
    // SectionUpdateTracker, driven per-dim by LevelExtractor). IP's override is removed; the mod
    // already routes dirty through LevelExtractor. provideBuiltChunkByChunkPos is retained verbatim
    // (its only 1.21.3 caller was setDirty; kept for zero-deviation — it is a public helper).

    public RenderSection provideBuiltChunkByChunkPos(int cx, int cy, int cz) {
        Column column = provideColumn(ChunkPos.pack(cx, cz));
        int offsetChunkY = Mth.clamp(
            cy - McHelper.getMinSectionY(level), 0, McHelper.getYSectionNumber(level) - 1
        );
        return column.sections[offsetChunkY];
    }

    /**
     * {@link ViewArea#repositionCamera(SectionPos)}
     */
    private Preset createPresetByChunkPos(int sectionX, int sectionZ) {
        RenderSection[] sections1 =
            new RenderSection[this.sectionGridSizeX * this.sectionGridSizeY * this.sectionGridSizeZ];

        for (int cx = 0; cx < this.sectionGridSizeX; ++cx) {
            int xBlockSize = this.sectionGridSizeX * 16;
            int xStart = (sectionX << 4) - xBlockSize / 2;
            int px = xStart + Math.floorMod(cx * 16 - xStart, xBlockSize);

            for (int cz = 0; cz < this.sectionGridSizeZ; ++cz) {
                int zBlockSize = this.sectionGridSizeZ * 16;
                int zStart = (sectionZ << 4) - zBlockSize / 2;
                int pz = zStart + Math.floorMod(cz * 16 - zStart, zBlockSize);

                Validate.isTrue(px % 16 == 0);
                Validate.isTrue(pz % 16 == 0);

                Column column = provideColumn(ChunkPos.pack(px >> 4, pz >> 4));

                for (int offsetCy = 0; offsetCy < this.sectionGridSizeY; ++offsetCy) {
                    int index = this.getChunkIndex(cx, offsetCy, cz);
                    sections1[index] = column.sections[offsetCy];
                }
            }
        }

        return new Preset(sections1);
    }

    /**
     * {@link ViewArea#repositionCamera(SectionPos)}
     */
    private void foreachPresetCoveredChunkPoses(
        int centerChunkX, int centerChunkZ,
        LongConsumer func
    ) {
        RenderSection[] sections1 =
            new RenderSection[this.sectionGridSizeX * this.sectionGridSizeY * this.sectionGridSizeZ];

        for (int cx = 0; cx < this.sectionGridSizeX; ++cx) {
            int xBlockSize = this.sectionGridSizeX * 16;
            int xStart = (centerChunkX << 4) - xBlockSize / 2;
            int px = xStart + Math.floorMod(cx * 16 - xStart, xBlockSize);

            for (int cz = 0; cz < this.sectionGridSizeZ; ++cz) {
                int zBlockSize = this.sectionGridSizeZ * 16;
                int zStart = (centerChunkZ << 4) - zBlockSize / 2;
                int pz = zStart + Math.floorMod(cz * 16 - zStart, zBlockSize);

                Validate.isTrue(px % 16 == 0);
                Validate.isTrue(pz % 16 == 0);

                long sectionPos = ChunkPos.pack(px >> 4, pz >> 4);

                func.accept(sectionPos);
            }
        }
    }

    //copy because private
    private int getChunkIndex(int x, int y, int z) {
        return (z * this.sectionGridSizeY + y) * this.sectionGridSizeX + x;
    }

    public Column provideColumn(long sectionPos) {
        return columnMap.computeIfAbsent(sectionPos, this::createColumn);
    }

    private Column createColumn(long sectionPos) {
        RenderSection[] array = new RenderSection[sectionGridSizeY];

        int sectionX = ChunkPos.getX(sectionPos);
        int sectionZ = ChunkPos.getZ(sectionPos);

        int minY = McHelper.getMinY(level);

        for (int offsetCY = 0; offsetCY < sectionGridSizeY; offsetCY++) {
            // 26.2 (C26): RenderSection identity is a packed SectionPos long, not (x,y,z) block ints.
            // IP passed block coords (sectionX<<4, (offsetCY<<4)+minY, sectionZ<<4); the equivalent
            // packed node is SectionPos.asLong(sectionX, offsetCY + (minY>>4), sectionZ). These
            // RenderSections are coord-PINNED (never repositioned) — preserving IP's coord-stable
            // identity, the inverse of 26.2's slot-stable RotatingSectionStorage (SPIKE-R4 caution 2).
            int sectionYCoord = offsetCY + SectionPos.blockToSectionCoord(minY);
            RenderSection builtChunk = factory.new RenderSection(
                0, SectionPos.asLong(sectionX, sectionYCoord, sectionZ)
            );

            array[offsetCY] = builtChunk;
        }

        return new Column(array);
    }

    private void tick() {
        if (!isAlive) {
            return;
        }

        ClientLevel worldClient = Minecraft.getInstance().level;
        if (worldClient != null) {
            if (GcMonitor.isMemoryNotEnough()) {
                if (worldClient.getGameTime() % 3 == 0) {
                    purge();
                }
            }
            else {
                if (worldClient.getGameTime() % 213 == 66) {
                    purge();
                }
            }
        }
    }

    private void purge() {
        Profiler.get().push("my_built_section_storage_purge");

        long dropTime = Helper.secondToNano(GcMonitor.isMemoryNotEnough() ? 3 : 20);

        long currentTime = System.nanoTime();

        presets.long2ObjectEntrySet().removeIf(entry -> {
            Preset preset = entry.getValue();

            long centerChunkPos = entry.getLongKey();

            boolean shouldDropPreset = shouldDropPreset(dropTime, currentTime, preset);

            if (!shouldDropPreset) {
                foreachPresetCoveredChunkPoses(
                    ChunkPos.getX(centerChunkPos),
                    ChunkPos.getZ(centerChunkPos),
                    columnChunkPos -> {
                        Column column = columnMap.get(columnChunkPos);
                        column.mark = currentTime;
                    }
                );
            }

            return shouldDropPreset;
        });

        long timeThreshold = Helper.secondToNano(5);

        ArrayDeque<RenderSection> toDelete = new ArrayDeque<>();

        columnMap.long2ObjectEntrySet().removeIf(entry -> {
            Column column = entry.getValue();

            boolean shouldRemove = currentTime - column.mark > timeThreshold;
            if (shouldRemove) {
                toDelete.addAll(Arrays.asList(column.sections));
            }

            return shouldRemove;
        });

        if (!toDelete.isEmpty()) {
            IPGlobal.PRE_GAME_RENDER_TASK_LIST.addTask(() -> {
                if (toDelete.isEmpty()) {
                    return true;
                }

                int num = 0;
                while (!toDelete.isEmpty() && num < 100) {
                    RenderSection builtChunk = toDelete.poll();
                    builtChunk.reset();  // 26.2: releaseBuffers() -> reset() (C27)
                    num++;
                }

                return false;
            });
        }

        Profiler.get().pop();
    }

    private boolean shouldDropPreset(long dropTime, long currentTime, Preset preset) {
        if (preset.data == this.sections) {
            return false;
        }
        return currentTime - preset.lastActiveTime > dropTime;
    }

    private Set<RenderSection> getAllActiveBuiltChunks() {
        HashSet<RenderSection> result = new HashSet<>();

        presets.forEach((key, preset) -> {
            result.addAll(Arrays.asList(preset.data));
        });

        if (sections != null) {
            result.addAll(Arrays.asList(sections));
        }

        // if this.sections are all null, it will have a null
        result.remove(null);

        return result;
    }

    public int getManagedSectionNum() {
        return columnMap.size() * sectionGridSizeY;
    }

    public String getDebugString() {
        return String.format(
            "Built Section Storage Columns:%s",
            columnMap.size()
        );
    }

    public int getRadius() {
        return (sectionGridSizeX - 1) / 2;
    }

    public boolean isRegionActive(int cxStart, int czStart, int cxEnd, int czEnd) {
        for (int cx = cxStart; cx <= cxEnd; cx++) {
            for (int cz = czStart; cz <= czEnd; cz++) {
                if (columnMap.containsKey(ChunkPos.pack(cx, cz))) {
                    return true;
                }
            }
        }

        return false;
    }

    public void onChunkUnload(int sectionX, int sectionZ) {
        long sectionPos = ChunkPos.pack(sectionX, sectionZ);
        Column column = columnMap.get(sectionPos);
        if (column != null) {
            for (RenderSection builtChunk : column.sections) {
                ((IERenderSection) builtChunk).portal_fullyReset();
            }
        }
    }

    public RenderSection getSectionFromRawArray(
        BlockPos sectionOrigin, RenderSection[] sections
    ) {
        int i = Mth.floorDiv(sectionOrigin.getX(), 16);
        int j = Mth.floorDiv(sectionOrigin.getY() - McHelper.getMinY(level), 16);
        int k = Mth.floorDiv(sectionOrigin.getZ(), 16);
        if (j >= 0 && j < this.sectionGridSizeY) {
            i = Mth.positiveModulo(i, this.sectionGridSizeX);
            k = Mth.positiveModulo(k, this.sectionGridSizeZ);
            return sections[this.getChunkIndex(i, j, k)];
        }
        else {
            return null;
        }
    }

    // NOTE it may be accessed from another thread
    @Nullable
    @Override
    public RenderSection getRenderSectionAt(BlockPos pos) {
        int i = Mth.floorDiv(pos.getX(), 16);
        int j = Mth.floorDiv(pos.getY() - McHelper.getMinY(level), 16);
        int k = Mth.floorDiv(pos.getZ(), 16);
        if (j >= 0 && j < this.sectionGridSizeY) {
            i = Mth.positiveModulo(i, this.sectionGridSizeX);
            k = Mth.positiveModulo(k, this.sectionGridSizeZ);
            int sectionIndex = this.getChunkIndex(i, j, k);
            RenderSection result = this.sections[sectionIndex];

            if (result == null) {
                Helper.err("Null RenderChunk " + pos);
                return null;
            }

            ((IERenderSection) result).portal_setIndex(sectionIndex);
            return result;
        }
        else {
            return null;
        }
    }

    /**
     * 26.2 (C23 / SPIKE-R4 §3): {@code getRenderSection(long)} is the NEW hot accessor
     * (SectionOcclusionGraph + LevelRenderer resolve sections by packed node — 258k+ calls/run,
     * incl. off-thread). It did not exist in 1.21.3, so it has no verbatim IP counterpart; it applies
     * {@link #getRenderSectionAt}'s exact wrap-around read to the decoded section coords (no BlockPos
     * allocation on the hot path). Additive-required 26.2 override.
     */
    @Nullable
    @Override
    protected RenderSection getRenderSection(long sectionNode) {
        int sectionX = SectionPos.x(sectionNode);
        int sectionY = SectionPos.y(sectionNode);
        int sectionZ = SectionPos.z(sectionNode);
        int i = sectionX;
        int j = Mth.floorDiv((sectionY << 4) - McHelper.getMinY(level), 16);
        int k = sectionZ;
        if (j >= 0 && j < this.sectionGridSizeY) {
            i = Mth.positiveModulo(i, this.sectionGridSizeX);
            k = Mth.positiveModulo(k, this.sectionGridSizeZ);
            int sectionIndex = this.getChunkIndex(i, j, k);
            RenderSection result = this.sections[sectionIndex];

            if (result == null) {
                return null;
            }

            // §2.6 ROW-1 AIOOBE HARDENING (port-note IS-iris-shaders-on.md §2.6;
            // vanilla-parity occupant guard): our RenderSections
            // are coord-PINNED (createColumn), so a query OUTSIDE the current preset's window
            // wraps via positiveModulo to a live section at congruent-mod-W DIFFERENT coords.
            // Vanilla's RotatingSectionStorage.getValue guarantees in-window => exact node
            // match, else null (containsSection + repositionCenter congruence); transplant
            // exactly that guarantee here for every node-keyed consumer (compileSections
            // re-resolution, SOG BFS, ...). NOTE: getRenderSectionAt (BlockPos-keyed) shares
            // the wrap hazard — ledgered for the S20 audit, not changed here.
            if (result.getSectionNode() != sectionNode) {
                return null;
            }

            ((IERenderSection) result).portal_setIndex(sectionIndex);
            return result;
        }
        else {
            return null;
        }
    }

    @Nullable
    public RenderSection rawFetch(int cx, int cy, int cz, long timeMark) {
        if (cy < minSectionY || cy >= endSectionY) {
            return null;
        }

        long l = ChunkPos.pack(cx, cz);
        Column column = provideColumn(l);

        column.mark = timeMark;

        int yOffset = cy - minSectionY;

        return column.sections[yOffset];
    }

    @Nullable
    public RenderSection rawGet(int cx, int cy, int cz) {
        if (cy < minSectionY || cy >= endSectionY) {
            return null;
        }

        long l = ChunkPos.pack(cx, cz);
        Column column = columnMap.get(l);

        if (column == null) {
            return null;
        }

        int yOffset = cy - minSectionY;

        return column.sections[yOffset];
    }
}
