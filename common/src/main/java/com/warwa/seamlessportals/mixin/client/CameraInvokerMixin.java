package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Mixin accessor for Camera's protected/private members.
 *
 * Needed for Phase 2 context-switch rendering to position the virtual
 * destination camera at the transformed portal location.
 *
 * Target methods (verified from Camera.java):
 *   protected void setPosition(Vec3 position)          — line 347
 *   protected void setRotation(float yRot, float xRot) — line 333
 *   private Frustum cullFrustum                        — line 65
 *   private boolean initialized                        — line 49
 */
@Mixin(Camera.class)
public interface CameraInvokerMixin {

    // Camera.setPosition(Vec3) — protected, line 347
    @Invoker("setPosition")
    void seamlessportals$invokeSetPosition(Vec3 position);

    // Camera.setRotation(float yRot, float xRot) — protected, line 333
    @Invoker("setRotation")
    void seamlessportals$invokeSetRotation(float yRot, float xRot);

    // Camera.cullFrustum — private Frustum, line 65
    @Accessor("cullFrustum")
    void seamlessportals$setCullFrustum(Frustum frustum);

    // Camera.capturedFrustum — private @Nullable Frustum, Camera.java:66.
    // Setting it makes Camera.getCapturedFrustum() non-null, which (verified
    // LevelExtractor.extract :125) SKIPS the per-frame applyFrustum() →
    // SectionOcclusionGraph BFS (the freeze pinned by the thread dump), and
    // (Camera :121 → SectionOcclusionGraph.update :148) skips the async graph
    // full-update too. cullFrustum is derived from it (Camera :197). The portal
    // view supplies its own frustum + its own visibleSections, so vanilla's
    // occlusion-graph cull is both unnecessary and pathologically expensive on
    // the sparse secondary level — this captures our frustum to bypass it.
    @Accessor("capturedFrustum")
    void seamlessportals$setCapturedFrustum(Frustum frustum);

    // Camera.initialized — private boolean, line 49
    @Accessor("initialized")
    void seamlessportals$setInitialized(boolean initialized);
}
