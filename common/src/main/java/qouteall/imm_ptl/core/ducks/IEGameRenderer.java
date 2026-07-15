package qouteall.imm_ptl.core.ducks;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.Lightmap;

// S11-A coupled reconcile: 26.2 removed net.minecraft.client.renderer.LightTexture (split into
// Lightmap + LightmapRenderStateExtractor, render-core G4). The lightmap retype was assigned to the
// S11 DimensionRenderHelper port by S04-ducks-roots-facade.md line 181 and the S10B ClientWorldLoader
// note (ClientWorldLoader.java:166). Retyping ip_setLightmapTextureManager's param LightTexture ->
// Lightmap clears the ducks-package compile error and lets DimensionRenderHelper.lightmapTexture
// (now a Lightmap) flow through RenderStates.onTotalRenderEnd. (The impl on GameRenderer + the fate of
// ip_getDoRenderHand / ip_setIsRenderingPanorama — whose backing fields are GONE on 26.2, G18 — is a
// render-mixin concern landing with U10 at S12.)
public interface IEGameRenderer {
    void ip_setLightmapTextureManager(Lightmap manager);
    
    boolean ip_getDoRenderHand();
    
    void ip_setCamera(Camera camera);
    
    void ip_setIsRenderingPanorama(boolean cond);
}
