package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * S12-B (render client-mixin half) — IP {@code MixinLevelRenderer_Optional}
 * ({@code IP:mixin/client/render/MixinLevelRenderer_Optional.java}), 26.2 DEFERRED-with-notes
 * (mixin-client.md §7 NEEDS-RETARGET). All four IP handlers target 1.21.3 render methods that are GONE on
 * 26.2, so none has a live anchor to compile against; each re-site is recorded for the S13 render-driver /
 * FrontClipping design pass. Held/UNREGISTERED; no-op today. (IP kept {@code priority = 1100} +
 * {@code require = 0} for Sodium tolerance; re-apply at S13 when the handlers land.)
 *
 * <ul>
 *   <li><b>① avoid translucent sort while rendering portal.</b> IP {@code @Redirect}
 *       {@code RenderType.translucent()} in {@code renderSectionLayer} → null. Both are GONE; the resort is
 *       {@code LevelRenderer.scheduleTranslucentSectionResort(Vec3)} ({@code :840}, called from
 *       {@code compileSections :643}) + {@code RenderSection.resortTransparency()} ({@code :285}). Re-site:
 *       HEAD-cancel {@code scheduleTranslucentSectionResort} while {@code PortalRendering.isRendering()}.</li>
 *   <li><b>② avoid moving the translucent-sort camera position.</b> IP {@code @Redirect}
 *       {@code SectionRenderDispatcher.setCamera(Vec3)} in {@code setupRender}. {@code setCamera} →
 *       {@code setCameraPosition(Vec3)} ({@code :110}); {@code setupRender} is GONE (called from
 *       {@code LevelRenderer.repositionCamera :311}). Re-site: {@code @Redirect setCameraPosition} in
 *       {@code repositionCamera}, skipping when the pass dim == the original player dim.</li>
 *   <li><b>③ upload the clipping uniform per render layer.</b> IP {@code @Inject} at
 *       {@code ShaderInstance.apply()} INVOKE in {@code renderSectionLayer} →
 *       {@code FrontClipping.updateClippingEquationUniformForCurrentShader(false)}. The whole
 *       {@code ShaderInstance}/{@code renderSectionLayer} model is GONE — this MERGES INTO THE FRONTCLIPPING
 *       REDESIGN (vanilla-terrain clip via a patched terrain pipeline or raw {@code GL_CLIP_DISTANCE0}
 *       around {@code renderGroup}; MixinRenderSystem_Clipping / MixinCompiledShader are the same design).</li>
 *   <li><b>④ correct the ViewArea update position.</b> IP {@code @Redirect}
 *       {@code LocalPlayer.getX/getY/getZ} in {@code setupRender} → {@code WorldRenderInfo.getCameraPos()}.
 *       {@code setupRender} is GONE; grid repositioning is {@code LevelRenderer.repositionCamera
 *       (CameraRenderState)} ({@code :304-312}) already keyed to {@code camera.pos} (the camera, not the
 *       player), so IP's player-getter redirect is structurally superseded — feed a portal-camera
 *       {@code CameraRenderState} (or cancel {@code repositionCamera}) during portal passes (api-map §7 ④).</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_Optional {
    // All four IP handlers DEFERRED — targets GONE on 26.2 (see class javadoc). Re-anchored at S13 /
    // FrontClipping design. This class is held-inert (no live injection) meanwhile.
}
