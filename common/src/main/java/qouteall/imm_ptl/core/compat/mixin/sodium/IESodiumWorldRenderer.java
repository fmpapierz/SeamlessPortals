package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin on {@code SodiumWorldRenderer} (IP depth doc file #1 + the C2-1 D1 extension).
 *
 * <p>{@code renderSectionManager} is IP's original accessor (javap EXISTS-IDENTICAL on 0.9.1).
 *
 * <p>The five camera-cache accessor pairs are the C2-1 D1 SWR-side swap set (port-note
 * {@code migration/port-notes/C2-sodium-iris.md} §2 "REFERENCE-SWAP on SodiumWorldRenderer";
 * census :202-209): without swapping these, every portal pass sees a "camera moved" delta vs the
 * outer camera at {@code setupTerrain} and spuriously fires
 * {@code notifyChangedCamera()/invalidateRenderLists()} for BOTH views. javap
 * ({@code sodium-mc26.2-0.9.1-fabric.jar}, sha 14f3388…) — all five are private NON-final:
 * {@code private org.joml.Vector3d lastCameraPos; private double lastCameraPitch;
 * private double lastCameraYaw;
 * private net.caffeinemc.mods.sodium.client.util.FogParameters lastFogParameters;
 * private org.joml.Matrix4f cullMatrix;}.
 *
 * <p>Deliberately ABSENT: {@code SWR.renderDistance} — swapping the SWR-side render distance
 * triggers a FULL {@code reload()} whenever it differs from
 * {@code Options.getEffectiveRenderDistance()} at setupTerrain HEAD (javap offsets 21-39) —
 * per-pass reload thrash (port-note §4.3 guard). Only {@code RSM.renderDistance} is swapped.
 *
 * <p>The swap driver is {@code OnSodiumPresent.switchContextWithCurrentWorldRenderer}. The class
 * name carries {@code Sodium} for the compat plugin's substring gate.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface IESodiumWorldRenderer {
    @Accessor("renderSectionManager")
    RenderSectionManager ip_getRenderSectionManager();

    @Accessor("lastCameraPos")
    Vector3d ip_getLastCameraPos();

    @Accessor("lastCameraPos")
    void ip_setLastCameraPos(Vector3d lastCameraPos);

    @Accessor("lastCameraPitch")
    double ip_getLastCameraPitch();

    @Accessor("lastCameraPitch")
    void ip_setLastCameraPitch(double lastCameraPitch);

    @Accessor("lastCameraYaw")
    double ip_getLastCameraYaw();

    @Accessor("lastCameraYaw")
    void ip_setLastCameraYaw(double lastCameraYaw);

    @Accessor("lastFogParameters")
    FogParameters ip_getLastFogParameters();

    @Accessor("lastFogParameters")
    void ip_setLastFogParameters(FogParameters lastFogParameters);

    @Accessor("cullMatrix")
    Matrix4f ip_getCullMatrix();

    @Accessor("cullMatrix")
    void ip_setCullMatrix(Matrix4f cullMatrix);

    /**
     * C2-1c (the dest-draw wiring): read-only reach to the NEVER-SWAP per-SWR
     * {@code UniformBufferManager} — javap 0.9.1:
     * {@code private net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager
     * uniformBufferManager} (non-final). Needed by
     * {@code OnSodiumPresent.ip_onDestTerrainDrawsFinished} to reset the
     * {@code hasUpdatedThisFrame} once-per-frame latch after a dest pass wrote its own
     * GlobalUniforms slice — without the reset, on a SHARED-SWR (same-dim / A→B→A nested) frame
     * the main pass's later {@code renderLayer → UniformBufferManager.update} would latch-skip
     * (javap update offsets 0-7: early-return on the flag) and bind the DEST pass's slice —
     * main translucent terrain drawn with dest matrices/fog.
     */
    @Accessor("uniformBufferManager")
    net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager
    ip_getUniformBufferManager();
}
