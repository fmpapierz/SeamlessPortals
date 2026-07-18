package qouteall.imm_ptl.core.render.context_management;

import com.warwa.seamlessportals.mixin.client.GameRendererAccessorMixin;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.ducks.IECamera;

import java.util.function.Consumer;
import java.util.function.Supplier;

// S11-A port disposition: NEW (IP-add, current-mod-render §5), RE-EXPRESSED onto 26.2 fog + FLAGGED.
// API_RISKS R9 / render-sub G2-G3 — the deep divergence of this slice:
//   * IP checkpointed/swapped vanilla's SIX private fog statics (fogRed/Green/Blue, targetBiomeFog,
//     previousBiomeFog, biomeChangedTime) via MixinFogRenderer shadows + StaticFieldsSwappingManager.
//   * 26.2 FogRenderer is a fully-rewritten INSTANCE class (net.minecraft.client.renderer.fog) with
//     NO color/biome-interpolation statics (FogRenderer.java:1-236). The old per-dimension smoothing
//     state is now Camera.attributeProbe() (an EnvironmentAttributeProbe per Camera). So the swap
//     machinery has NOTHING left to swap for fog (R9 line 276: "compiles as-is"); the FogRendererContext
//     instance fields below are vestigial snapshots kept for structural fidelity.
//
// >>> CUTOVER_SPEC ITEM, DEFERRED TO S11-B (per mission): per-dimension FogRenderer / FogData buffer
//     OWNERSHIP. Fog is delivered as a GpuBufferSlice UBO backed by a SINGLE per-frame WORLD
//     ring-buffer slot (FogRenderer.java:55-83,167-202); writing dest-world fog into that slot
//     mid-frame (via updateBuffer) corrupts whichever passes execute later. Rendering a second world
//     with different fog needs its OWN buffer/instance (own FogRenderer or own MappableRingBuffer) —
//     UNKNOWN-NEEDS-DESIGN on exact ownership. NOTE: getFogColorOf below only calls setupFog (which
//     COMPUTES a fresh FogData, FogRenderer.java:167-186) and never updateBuffer, so it is already
//     ring-buffer-safe; the ownership decision governs the LIVE per-layer fog UBO in the driver core
//     (S12/S13), not this color-probe. <<<
// Held/inert until S13.
/**
 * {@link FogRenderer}
 * {@code MixinFogRenderer} (S12 client mixin) assigns the hooks below.
 */
@SuppressWarnings("SpellCheckingInspection")
public class FogRendererContext {
    // Vestigial on 26.2 (no fog statics left to mirror — R9). Retained for structural fidelity to IP's
    // context-record shape; the biome-smoothing they represented now lives in Camera.attributeProbe().
    public float red;
    public float green;
    public float blue;
    public int targetBiomeFog = -1;
    public int previousBiomeFog = -1;
    public long biomeChangedTime = -1L;

    // External hooks (IP pattern) assigned by the S12 MixinFogRenderer. On 26.2 there are no statics to
    // read/write, so copyContextFromObject/copyContextToObject degrade to no-ops and getCurrentFogColor
    // is superseded by reading FogData.color directly in getFogColorOf (kept for the mixin/swap
    // contract; candidate for pruning at S11-B).
    public static Consumer<FogRendererContext> copyContextFromObject;
    public static Consumer<FogRendererContext> copyContextToObject;
    public static Supplier<Vec3> getCurrentFogColor;

    // S13-I (first-photons black-seam fix): the live fog color of the world CURRENTLY being rendered.
    // Re-expresses IP's fog statics (fogRed/fogGreen/fogBlue) that getCurrentFogColor read on 1.21.3:
    // during a portal dest render IP had already set up the DEST fog, so getCurrentFogColor returned the
    // dest atmosphere color and RendererUsingStencil.replaceFrameBufferClearing (R5 Row 16) sealed the
    // portal opening with it — a SEAMLESS backdrop behind the dest sky/terrain. On 26.2 the fog statics
    // are GONE (R9), so the S12 MixinFogRenderer stubbed getCurrentFogColor to Vec3.ZERO; that made the
    // Row-16 fill BLACK, and the dest horizon seam (the thin band where dest sky meets dest terrain and
    // neither fully covers) showed that black at eye level — the reported symptom. The driver core
    // (SecondaryWorldRenderCore, right after it computes the per-layer FogData) now PUBLISHES the dest
    // fog color here, and MixinFogRenderer points getCurrentFogColor at getCurrentRenderedFogColor().
    // Render-thread-only in practice; volatile is defensive, never null (defaults to Vec3.ZERO before the
    // first dest render, which never consumes it — the fill only runs mid-dest-render, after a publish).
    private static volatile Vec3 currentRenderedFogColor = Vec3.ZERO;

    public static void setCurrentRenderedFogColor(Vec3 color) {
        currentRenderedFogColor = color;
    }

    public static Vec3 getCurrentRenderedFogColor() {
        return currentRenderedFogColor;
    }

    public static StaticFieldsSwappingManager<FogRendererContext> swappingManager;

    public static void init() {
        //load the class and apply mixin
        FogRenderer.class.hashCode();

        swappingManager = new StaticFieldsSwappingManager<>(
            copyContextFromObject, copyContextToObject, false,
            FogRendererContext::new
        );


    }

    public static void update() {
        swappingManager.setOuterDimension(RenderStates.originalPlayerDimension);
        swappingManager.resetChecks();
        if (ClientWorldLoader.getIsInitialized()) {
            ClientWorldLoader.getClientWorlds().forEach(world -> {
                ResourceKey<Level> dimension = world.dimension();
                swappingManager.contextMap.computeIfAbsent(
                    dimension,
                    k -> new StaticFieldsSwappingManager.ContextRecord<>(
                        dimension,
                        new FogRendererContext(),
                        dimension != RenderStates.originalPlayerDimension
                    )
                );
            });
        }
    }

    public static Vec3 getFogColorOf(
        ClientLevel destWorld, Vec3 pos
    ) {
        Minecraft client = Minecraft.getInstance();

        // 26.2: Minecraft.getProfiler() -> static Profiler.get() (render-sub G ; Hud.java:333 usage).
        Profiler.get().push("get_fog_color");

        ClientLevel oldWorld = client.level;

        ResourceKey<Level> newWorldKey = destWorld.dimension();

        swappingManager.contextMap.computeIfAbsent(
            newWorldKey,
            k -> new StaticFieldsSwappingManager.ContextRecord<>(
                k, new FogRendererContext(), true
            )
        );

        swappingManager.pushSwapping(newWorldKey);
        // Faithful to IP: swap the effective client level for the color derivation and restore it in
        // finally. On 26.2 setupFog takes the level explicitly (below), so this swap is not strictly
        // required by setupFog itself; kept for fidelity + the swap-machinery contract. Minecraft.level
        // is public-writable (MIGRATION_API_MAP S6).
        client.level = destWorld;

        Camera newCamera = new Camera();
        ((IECamera) newCamera).portal_setPos(pos);
        // 26.2: Minecraft.cameraEntity FIELD is gone; only the getCameraEntity() getter remains
        // (mc262-ref Minecraft.java:2648). IP 1.21.3 origin used the field (FogRendererContext.java:88).
        // Faithful field->getter translation (R-render category-c); NOT an access-widener case.
        ((IECamera) newCamera).portal_setFocusedEntity(client.getCameraEntity());

        try {
            // 26.2: static FogRenderer.setupColor(...) + the six fog statics are GONE (R9 / render-sub
            // G2). Fog is an instance FogRenderer producing a FogData whose .color is the fog color
            // (Vector4f). setupFog(...) only COMPUTES a fresh FogData (FogRenderer.java:167-186) — it
            // does NOT touch the WORLD ring buffer (that is updateBuffer(), :188), so this mid-frame
            // color probe is ring-buffer-safe. The FogRenderer instance is GameRenderer's private field,
            // reached via the mod's GameRendererAccessorMixin. Darken arg: getDarkenWorldAmount(pt) ->
            // GameRenderer.bossOverlayWorldDarkening(pt) (GameRenderer.java:653, the vanilla setupFog
            // call site at :630-639). DeltaTracker via Minecraft.getDeltaTracker() (:2693).
            FogRenderer fogRenderer =
                ((GameRendererAccessorMixin) client.gameRenderer).seamlessportals$getFogRenderer();

            FogData fogData = fogRenderer.setupFog(
                newCamera,
                client.options.getEffectiveRenderDistance(),
                client.getDeltaTracker(),
                client.gameRenderer.bossOverlayWorldDarkening(RenderStates.getPartialTick()),
                destWorld
            );

            return new Vec3(fogData.color.x, fogData.color.y, fogData.color.z);
        }
        finally {
            swappingManager.popSwapping();
            client.level = oldWorld;

            Profiler.get().pop();
        }
    }

    /**
     * S14.46 — the TELEPORT-FLASH fix (capture-proven, port-note S14C-round8): the 26.2 HALF of
     * IP's per-dimension fog swap. The block-era half below swaps this class's static-field
     * contexts (a 26.2 no-op — the fog statics are gone), but 26.2 moved ALL atmospheric
     * smoothing (FOG/SKY/CLOUD colors, fog distances) into the retained
     * {@code Camera.attributeProbe}, which the crossing never touched — the capture shows the
     * exact two-phase far-pixel flash: (1) the probe's internal level/position update only in
     * {@code Camera.tick}, so until the next client tick every sample still comes from the
     * SOURCE dim (2-3 frames of pure source fog/sky — "nether gets an OW flash", and the OW
     * return paints a black sky disc from nether's SKY_COLOR=0); (2) then partialTickLerp blends
     * source→dest over one more tick. Vanilla hides this behind its dimension loading screen;
     * seamless crossing exposes it. IP solved it with the per-dim context swap re-expressed
     * here: reset the probe and immediately re-tick it against the DEST world at the arrival
     * position — the next extract's lazily-created ValueProbes then sample lastValue=newValue=
     * dest (an instant snap, no lerp). NOTE on fidelity: restoring a SAVED per-dim probe (IP's
     * literal swap semantics) degenerates to this same snap for any absence > 2 ticks —
     * ValueProbe.tick evicts entries not read since the last tick — so the snap IS the faithful
     * 26.2 re-expression; only sub-2-tick double-crossings would differ, and they snap correctly
     * too (each crossing re-snaps to the then-current dim).
     */
    public static void onPlayerTeleport(ResourceKey<Level> from, ResourceKey<Level> to) {
        swappingManager.updateOuterDimensionAndChangeContext(to);

        Minecraft client = Minecraft.getInstance();
        // mc.level is already the dest world here (changePlayerDimension swaps it before this
        // call), and the player already stands at the arrival position.
        if (client.gameRenderer != null && client.level != null && client.player != null) {
            var probe = client.gameRenderer.mainCamera().attributeProbe();
            probe.reset();
            probe.tick(client.level, client.player.getEyePosition());
        }
    }

}
