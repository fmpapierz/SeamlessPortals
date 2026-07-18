package qouteall.imm_ptl.core.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import qouteall.q_misc_util.Helper;

/**
 * S14.49 — the superflat BOUNDARY-SHADOW discriminator (`debug_dump_light_section` one-shot).
 *
 * <p>The observation (user, many-portal superflat round): a partial shadow band with two
 * receding legs on distant grass, apparently outlining the chunk square a distant portal's
 * loader streamed in; a block update there clears it. Suspected: sections MESHED before their
 * (neighbor) light arrived, with the follow-up light→dirty remesh signal lost — previously
 * masked by the phantom all-dirty remesh wave the S14.48 tracker-continuity fix removed.
 *
 * <p>Protocol: stand so the crosshair targets a SHADOWED block, run
 * {@code /imm_ptl_client_debug debug_dump_light_section_enable}, repeat once on a HEALTHY block
 * for contrast, hand over the log. One log write per invocation (lever-gated — cadence rules
 * respected). Reading: {@code sky=15/14} at the surface with {@code dirty=false} and a compiled
 * mesh = LIGHT DATA CORRECT + MESH STALE + NO PENDING REMESH — the lost-signal case (the fix
 * then goes to the light→setSectionDirty plumbing for portal-loaded chunks). {@code sky=0}
 * = the light DATA itself is wrong — the fix goes to the loader/packet path. {@code dirty=true}
 * = the remesh is pending but never draining — a compile-scheduling problem instead.
 */
@Environment(EnvType.CLIENT)
public class LightSectionDump {

    public static void dump() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.levelRenderer == null || mc.player == null) {
                Helper.log("[light-dump] no level");
                return;
            }
            BlockPos pos = mc.hitResult instanceof BlockHitResult bhr
                ? bhr.getBlockPos()
                : mc.player.blockPosition();
            SectionPos sec = SectionPos.of(pos);
            long sectionNode = sec.asLong();

            StringBuilder sb = new StringBuilder(1 << 10);
            sb.append("[light-dump] pos=").append(pos.toShortString())
                .append(" section=").append(sec.x()).append(',').append(sec.y()).append(',').append(sec.z())
                .append(" dim=").append(mc.level.dimension().identifier().getPath());

            // Light DATA (the engine's truth, independent of what the mesh baked).
            sb.append(" | skyHere=").append(mc.level.getBrightness(LightLayer.SKY, pos))
                .append(" skyAbove=").append(mc.level.getBrightness(LightLayer.SKY, pos.above()))
                .append(" blockHere=").append(mc.level.getBrightness(LightLayer.BLOCK, pos));
            // Horizontal neighbors' sky light above (smooth lighting samples across the seam).
            sb.append(" skyN4=[")
                .append(mc.level.getBrightness(LightLayer.SKY, pos.north().above())).append(',')
                .append(mc.level.getBrightness(LightLayer.SKY, pos.south().above())).append(',')
                .append(mc.level.getBrightness(LightLayer.SKY, pos.west().above())).append(',')
                .append(mc.level.getBrightness(LightLayer.SKY, pos.east().above())).append(']');

            // Chunk + light-column readiness (the hasAllNeighbors gate components).
            boolean chunkFull = mc.level.getChunk(pos.getX() >> 4, pos.getZ() >> 4,
                ChunkStatus.FULL, false) != null;
            sb.append(" | chunkFull=").append(chunkFull)
                .append(" lightOnInColumn=").append(mc.level.getLightEngine()
                    .lightOnInColumn(SectionPos.getZeroNode(sectionNode)));

            // Tracker state: does the CURRENT extractor still owe this section a remesh?
            var extractor = ((com.warwa.seamlessportals.mixin.client.ClientLevelExtractorAccessor) mc.level)
                .seamlessportals$getLevelExtractor();
            String trackerState = "no-extractor";
            if (extractor != null) {
                var tracker = ((com.warwa.seamlessportals.mixin.client.LevelExtractorAccessor) (Object) extractor)
                    .seamlessportals$getSectionUpdateTracker();
                if (tracker == null) {
                    trackerState = "no-tracker";
                }
                else {
                    var state = tracker.getDirtyState(sectionNode);
                    trackerState = state == null ? "outside-range"
                        : "dirty=" + ((com.warwa.seamlessportals.mixin.client.SectionDirtyStateAccessor) (Object) state)
                            .seamlessportals$isDirty()
                        + " fromPlayer=" + ((com.warwa.seamlessportals.mixin.client.SectionDirtyStateAccessor) (Object) state)
                            .seamlessportals$isDirtyFromPlayer();
                }
            }
            sb.append(" | tracker[").append(trackerState).append(']')
                .append(" extractorIsMain=").append(extractor == mc.levelExtractor);

            // Mesh state: what the renderer actually holds for this section.
            var viewArea = ((qouteall.imm_ptl.core.ducks.IEWorldRenderer) mc.levelRenderer)
                .ip_getBuiltChunkStorage();
            String meshState = "no-viewarea";
            if (viewArea != null) {
                var section = viewArea.getRenderSectionAt(pos);
                if (section == null) {
                    meshState = "no-section";
                }
                else {
                    var mesh = section.getSectionMesh();
                    // Verify-fold: UNCOMPILED and EMPTY are anonymous classes (getSimpleName()
                    // = "") — tag both explicitly so the log is never ambiguous.
                    meshState = mesh == net.minecraft.client.renderer.chunk.CompiledSectionMesh.UNCOMPILED
                        ? "UNCOMPILED"
                        : mesh == net.minecraft.client.renderer.chunk.CompiledSectionMesh.EMPTY
                            ? "EMPTY"
                            : mesh.getClass().getSimpleName();
                }
            }
            sb.append(" mesh=").append(meshState);

            // SOG membership (render-graph coverage of the chunk).
            var sog = mc.levelRenderer.sectionOcclusionGraph();
            sb.append(" sogHasChunk=").append(sog != null
                && ((com.warwa.seamlessportals.mixin.client.SectionOcclusionGraphAccessorMixin) (Object) sog)
                    .seamlessportals$getLoadedChunks()
                    .contains(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4)));

            Helper.log(sb.toString());
        }
        catch (Throwable t) {
            Helper.log("[light-dump] failed: " + t);
        }
    }
}
