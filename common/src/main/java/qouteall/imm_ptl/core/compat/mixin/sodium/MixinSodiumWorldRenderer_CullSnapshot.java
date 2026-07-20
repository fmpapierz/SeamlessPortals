package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumViewport;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.FrustumCuller;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * C2-3 D2 — the SNAPSHOT PRODUCER: the RETARGET of IP's {@code MixinSodiumWorldRenderer}
 * (depth doc file #6: {@code setupTerrain} HEAD, fresh {@code FrustumCuller} + {@code update}
 * per pass) onto Sodium 0.9.1's RESHAPED signature, with the store retargeted from IP's
 * render-thread static to the viewport-carried duck (design §3.2; the D2 named deviation).
 *
 * <p><b>Bind (javap, {@code sodium-mc26.2-0.9.1-fabric.jar} sha 14f3388…):</b>
 * {@code public void setupTerrain(net.minecraft.client.Camera,
 * net.caffeinemc.mods.sodium.client.render.viewport.Viewport,
 * net.caffeinemc.mods.sodium.client.util.FogParameters, boolean, boolean, org.joml.Matrix4f)}
 * — IP 1.21.3's 4-arg tail {@code (Camera, Viewport, boolean, boolean)} grew
 * {@code FogParameters} at position 3 and {@code Matrix4f} at position 6; the handler below
 * captures all six positionally. {@code Camera.position()} is the 26.2 name (javap'd from
 * setupTerrain's own bytecode: {@code invokevirtual Camera.position:()Lnet/…/Vec3;}).
 *
 * <p><b>Who drives setupTerrain</b> (both on the RENDER THREAD): sodium's
 * {@code LevelExtractorMixin.cullTerrain} for the main pass, and our
 * {@code SodiumInterface.OnSodiumPresent.ip_driveDestTerrainSetup} (C2-1c F1) for dest
 * passes — the latter INSIDE the MyGameRenderer bracket where the {@code PortalRendering}
 * globals ({@code isRendering}/{@code getRenderingPortal}/portal-layer stack) are valid for
 * exactly this pass, which is what makes the HEAD snapshot per-pass-camera-accurate.
 *
 * <p><b>Thread/publication model (design §3.2):</b> the snapshot is fully computed here
 * ({@code FrustumCuller.update} builds the predicate before we read it) and written to the
 * duck fields of THIS pass's fresh {@code Viewport} before setupTerrain's body runs. The sync
 * consumers ({@code SectionTree.isWithinFrustum} → {@code isBoxVisible(III)} during
 * render-list collection) are same-thread. The async consumer chain
 * ({@code RSM.scheduleAsyncWork} → {@code new CullTask(viewport …)} →
 * {@code submitTo(asyncCullExecutor)} = {@code executor.submit(this)}) reads the SAME viewport
 * instance on the worker ({@code AsyncRenderTask}'s {@code protected final Viewport viewport}
 * — direct putfield, census P4: BY REFERENCE, no copy), and {@code ExecutorService.submit} is
 * the happens-before edge — every duck write here is visible to the worker. The snapshot is
 * immutable after publish (no writer after this handler returns).
 *
 * <p><b>Gating:</b> woven only flag-ON + sodium-present (IPCompatMixinPlugin gates 2 and 1).
 * Within that, the body no-ops unless the ACTIVE invoker is installed
 * ({@code SodiumInterface.invoker.isSodiumPresent()} is TRUE only for
 * {@code OnSodiumPresent} — the {@code FeedOnlyOnSodiumPresent} un-levered row and the base
 * row return FALSE), because the un-levered state has no portal rendering and must stay
 * byte-identical (design §6.2); {@code FrustumCuller.update}'s own reads
 * ({@code PortalRendering}, {@code CHelper.getClientNearbyPortals}) belong to the flag-ON
 * active-compat world. IP's producer ran unconditionally — the invoker guard is the compat
 * gate discipline, not a behavior change (with compat inactive there is no portal pass and
 * IP's culler would compute null anyway).
 *
 * <p><b>The A/B lever (in-stage rollback):</b> {@code IPCGlobal.doUseAdvancedFrustumCulling}
 * is a plain public static boolean, runtime-toggled by the client debug commands
 * {@code advanced_frustum_culling_enable}/{@code _disable} (ClientDebugCommand :152-165).
 * {@code FrustumCuller.update} → {@code getCanDetermineInvisibleFunc} consults it INTERNALLY
 * (first gate, FrustumCuller :82) — so the snapshot INHERITS the gate at every producer call.
 * PRECISION (lens-A corrected): the lever disables the PREDICATE consumer ONLY — lever off →
 * predicate null → duck null → the testSection wrap fast-paths to sodium's exact verdict.
 * The cave-cull override below is LEVER-INDEPENDENT (IP file #4 never lever-gated it either)
 * and safe-direction-only: it AND-composes, so it can only DISABLE occlusion culling (render
 * MORE terrain) — the mis-cull artifact class the lever exists to roll back cannot come from
 * it. Live-round A/B FPS deltas therefore attribute to the predicate half. No re-weave
 * needed; the toggle takes effect on the next pass.
 *
 * <p><b>The legacy static:</b> {@code SodiumInterface.frustumCuller} is ALSO written each
 * pass — IP file #6 parity for debugging/inspection ONLY. It has NO consumer in this port
 * (grep-proven: the sole reference is its declaration; our consumer reads the duck), because
 * under the async model a static is wrong-thread/wrong-frame — that is the exact reason D2
 * exists. Do not add readers.
 *
 * <p>Registered in {@code seamlessportals-ip-compat.mixins.json}; the class name carries
 * {@code Sodium} for the plugin's substring gate.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer_CullSnapshot {

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void ip_onSetupTerrain(
        Camera camera, Viewport viewport, FogParameters fogParameters,
        boolean spectator, boolean updateChunksImmediately, Matrix4f cullMatrix,
        CallbackInfo ci
    ) {
        if (!SodiumInterface.invoker.isSodiumPresent()) {
            // FeedOnly / base invoker rows (compat present-but-inactive): stay no-op — the
            // un-levered baseline must be byte-identical (design §6.2).
            return;
        }

        // IP file #6 body, 1:1: fresh culler + update with this pass's camera position.
        FrustumCuller frustumCuller = new FrustumCuller();
        Vec3 cameraPos = camera.position();
        frustumCuller.update(cameraPos.x, cameraPos.y, cameraPos.z);

        IESodiumViewport viewportDuck = (IESodiumViewport) (Object) viewport;
        viewportDuck.ip_setPortalCullPredicate(frustumCuller.getCanDetermineInvisibleFunc());

        // Cave-culling policy snapshot: non-null ONLY for portal passes. The null/non-null
        // distinction is load-bearing — a main pass must NOT be forced to
        // shouldEnableSodiumCaveCulling()'s not-rendering FALSE (that would kill sodium's own
        // occlusion culling for the outer world); the findVisible consumer AND-composes.
        viewportDuck.ip_setUseOcclusionCullingOverride(
            PortalRendering.isRendering()
                ? PortalRendering.shouldEnableSodiumCaveCulling()
                : null
        );

        // D2b dormant field: explicitly seeded null (fresh viewports are null anyway; this
        // documents the producer as the single seeding site for the ledgered re-entry).
        viewportDuck.ip_setModifiedIterationOrigin(null);

        // Legacy IP static — parity/debug only, NO consumer (see class javadoc).
        SodiumInterface.frustumCuller = frustumCuller;
    }
}
