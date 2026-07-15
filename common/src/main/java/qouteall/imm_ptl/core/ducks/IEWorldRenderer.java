package qouteall.imm_ptl.core.ducks;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

public interface IEWorldRenderer {
    EntityRenderDispatcher ip_getEntityRenderDispatcher();

    ViewArea ip_getBuiltChunkStorage();

    // ip_myRenderEntity (the private LevelRenderer.renderEntity duck) is RETIRED at S11-C (render-core G3):
    // the R3 cross-portal projection now uses the public EntityRenderDispatcher.extractEntity + submit path
    // (design S11-R3-clip-bracketing.md §4). Its MultiBufferSource parameter type is GONE on 26.2 (the
    // whole immediate-mode BufferSource model was removed), so keeping it stranded 2 permanent probe
    // errors; nothing references it. Deleted here.

    RenderBuffers ip_getRenderBuffers();
    
    void ip_setRenderBuffers(RenderBuffers arg);
    
    Frustum portal_getFrustum();
    
    void portal_setFrustum(Frustum arg);
    
    void portal_fullyDispose();
    
    void portal_setChunkInfoList(ObjectArrayList<SectionRenderDispatcher.RenderSection> arg);
    
    ObjectArrayList<SectionRenderDispatcher.RenderSection> portal_getChunkInfoList();
}
