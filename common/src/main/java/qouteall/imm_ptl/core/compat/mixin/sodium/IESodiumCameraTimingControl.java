package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.AsyncCameraTimingControl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * C2-1 D1 — accessor for the CONTENT-swap of {@code RenderSectionManager}'s
 * {@code final AsyncCameraTimingControl cameraTimingControl} (port-note
 * {@code migration/port-notes/C2-sodium-iris.md} §2 content-swap set; census :179).
 *
 * <p>javap ({@code sodium-mc26.2-0.9.1-fabric.jar}): both fields are PRIVATE —
 * {@code private net.minecraft.world.phys.Vec3 previousPosition;} and
 * {@code private boolean isSyncRendering;} — hence this accessor mixin (an AT cannot reach a mod
 * class). {@code previousPosition} may legitimately be null: {@code getShouldRenderSync} null-seeds
 * it and returns true (javap offsets 5-18), which is the useful cold-context sync-render behavior.
 *
 * <p>The class name carries {@code Sodium} for the compat plugin's substring gate (the footgun
 * documented on {@code IPCompatMixinPlugin}).
 */
@Mixin(value = AsyncCameraTimingControl.class, remap = false)
public interface IESodiumCameraTimingControl {

    @Accessor("previousPosition")
    Vec3 ip_getPreviousPosition();

    @Accessor("previousPosition")
    void ip_setPreviousPosition(Vec3 previousPosition);

    @Accessor("isSyncRendering")
    boolean ip_getIsSyncRendering();

    @Accessor("isSyncRendering")
    void ip_setIsSyncRendering(boolean isSyncRendering);
}
