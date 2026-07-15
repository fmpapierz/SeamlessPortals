package qouteall.imm_ptl.core.render.context_management;

import com.mojang.blaze3d.buffers.GpuBuffer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.q_misc_util.Helper;

import java.util.ArrayList;

// S11-A port disposition: NEW (IP-add, current-mod-render §5). VERBATIM 1:1 from IP EXCEPT the
// cached GPU geometry handle retype: com.mojang.blaze3d.vertex.VertexBuffer (GONE on 26.2 — the
// immediate/MultiBufferSource model was removed, MIGRATION_API_MAP headline / render-core G8) ->
// com.mojang.blaze3d.buffers.GpuBuffer (abstract AutoCloseable, GpuBuffer.java:11,43). The field's
// role is unchanged: a per-context, close()-able cloud-geometry buffer that dispose() releases.
// CUTOVER FLAG (S12 cloud mixin): 26.2 owns cloud geometry in CloudRenderer via a MappableRingBuffer
// (CloudRenderer.java:58-59, utb/ubo). Reconciling CloudContext's per-dimension multi-cache against
// CloudRenderer's single ring buffer is a cloud-rendering-mixin concern that lands with U10 (S12);
// this port keeps the state-holder shape intact and inert. Held/inert until S13.
/**
 * {@link net.minecraft.client.renderer.CloudRenderer} (26.2; was
 * {@code WorldRenderer#renderClouds} on the IP 1.21.3 baseline).
 */
public class CloudContext {

    //keys
    public int lastCloudsBlockX = 0;
    public int lastCloudsBlockY = 0;
    public int lastCloudsBlockZ = 0;
    public ResourceKey<Level> dimension = null;
    public Vec3 cloudColor;

    // 26.2: was VertexBuffer cloudsBuffer (GONE). See header note.
    public GpuBuffer cloudsBuffer = null;

    public static final ArrayList<CloudContext> contexts = new ArrayList<>();

    public static void init() {
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(CloudContext::cleanup);
        ClientWorldLoader.CLIENT_DIMENSION_DYNAMIC_REMOVE_EVENT.register(dim -> cleanup());
    }

    public CloudContext() {

    }

    private static void cleanup() {
        for (CloudContext context : contexts) {
            context.dispose();
        }
        contexts.clear();
    }

    public void dispose() {
        if (cloudsBuffer != null) {
            cloudsBuffer.close();
            cloudsBuffer = null;
        }
    }

    @Nullable
    public static CloudContext findAndTakeContext(
        int lastCloudsBlockX, int lastCloudsBlockY, int lastCloudsBlockZ,
        ResourceKey<Level> dimension, Vec3 cloudColor
    ) {
        int i = Helper.indexOf(contexts, c ->
            c.lastCloudsBlockX == lastCloudsBlockX &&
                c.lastCloudsBlockY == lastCloudsBlockY &&
                c.lastCloudsBlockZ == lastCloudsBlockZ &&
                c.dimension == dimension &&
                c.cloudColor.distanceToSqr(cloudColor) < 2.0E-4D
        );

        if (i == -1) {
            return null;
        }

        CloudContext result = contexts.get(i);
        contexts.remove(i);

        return result;
    }

    public static void appendContext(CloudContext context) {
        contexts.add(context);

        if (contexts.size() > 15) {
            contexts.remove(0).dispose();
        }
    }
}
