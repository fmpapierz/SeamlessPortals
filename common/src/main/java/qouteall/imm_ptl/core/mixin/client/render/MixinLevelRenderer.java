package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.render.ImmPtlViewArea;

/**
 * S12-A (Slice C) — the R4 {@link ImmPtlViewArea} INSTALL redirect (CUTOVER_SPEC §1; S11B-render-drivers.md
 * §2.4). This is the 26.2 re-expression of IP 1.21.3's {@code MixinLevelRenderer.redirectConstructingBuildChunkStorage}
 * ({@code IP:imm_ptl/core/mixin/client/render/MixinLevelRenderer.java:322-345}) — the {@code @Redirect} of the
 * {@code new ViewArea(...)} construction that swaps in the mod-owned unbounded coord-pinned store when the
 * driver flag is on.
 *
 * <p><b>Construction site moved (SPIKE-R4 CONFIRMED).</b> IP redirected the {@code new ViewArea} inside
 * 1.21.3 {@code LevelRenderer.allChanged}; on 26.2 the construction moved to
 * {@code LevelRenderer.invalidateCompiledGeometry(ClientLevel, Options, Camera, BlockColors)}
 * ({@code 26.2:LevelRenderer.java:796,819-827}), invoked from {@code LevelExtractor.extract} when
 * {@code shouldInvalidateCompiledGeometry} is consumed (set by {@code allChanged()} on first extract,
 * {@code setLevel}, and F3+A). The ctor also lost its 1.21.3 {@code Level}/{@code LevelRenderer} params (now
 * 7-arg {@code (dispatcher, minY, maxY, minSectionY, maxSectionY, renderDistance, occlusionGraph)}); the
 * {@link ImmPtlViewArea} subclass needs the {@code Level}, so this handler appends the enclosing method's
 * first param ({@code ClientLevel level}) as the 8th ctor arg — exactly how the subclass sources the level
 * the 26.2 super ctor dropped.
 *
 * <p><b>Gate: the GLOBAL flag, NEVER {@code mc.levelRenderer} identity.</b> Verbatim IP gate
 * {@code IPCGlobal.useHackedChunkRenderDispatcher} — a global static boolean, the driver-swap toggle the S13
 * exclusivity wiring binds to {@code entityPortals}. SPIKE-R4 §4-S1 proved an identity check
 * ({@code Minecraft.levelRenderer == this}) is UNRELIABLE here: the mod swaps {@code Minecraft.levelRenderer}
 * per portal-render frame, so a secondary renderer's ctor self-identifies as "MAIN". Flag-OFF → the redirect
 * is inert and vanilla {@code new ViewArea} runs unchanged (block-era render untouched); flag-ON → the
 * subclass installs for main + every secondary renderer (SPIKE-R4 CONFIRMED for both).
 *
 * <p><b>Held/UNREGISTERED.</b> Not listed in {@code seamlessportals-ip-client.mixins.json "client":[]}; it
 * compiles as ordinary annotated Java in the probe and is registered flag-ON at S13. IP upstream ships a
 * plain {@code @Redirect} (which claims the {@code NEW} instruction exclusively); ported as-is for fidelity.
 * If a third-party mod redirecting the same {@code new ViewArea} conflicts post-cutover, a {@code @WrapOperation}
 * variant is the documented one-line S12 fallback (CUTOVER_SPEC §1.3; not adopted here).
 *
 * <p><b>{@code ImmPtlViewArea.init()}</b> (the client-init wiring of the chunk-unload signal + tick/purge
 * events, {@code ImmPtlViewArea.java:103-129}) is registered at the same client-init seam as the other render
 * {@code init()} calls (S13 wiring) — NOT in this mixin.
 *
 * <p><b>Scope.</b> IP's monolithic {@code MixinLevelRenderer} also carries the {@code renderEntity}
 * {@code @WrapOperation}, the weather-pass hook, and the occlusion {@code rawGet} casts. On 26.2 the
 * per-entity clip hooks re-express onto {@link MixinLevelRenderer_CrossPortalEntity} (the submit-side
 * anchors, R3); the remaining renderer hooks ride the S12 concrete-renderer slices. This file carries ONLY
 * the R4 ViewArea install (Slice C's install handoff).
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
    @Redirect(
        method = "invalidateCompiledGeometry",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;IIIII"
                + "Lnet/minecraft/client/renderer/SectionOcclusionGraph;)"
                + "Lnet/minecraft/client/renderer/ViewArea;"
        )
    )
    private ViewArea seamlessportals$installImmPtlViewArea(
        SectionRenderDispatcher dispatcher,
        int minY,
        int maxY,
        int minSectionY,
        int maxSectionY,
        int renderDistance,
        SectionOcclusionGraph occlusionGraph,
        // trailing captured method param of invalidateCompiledGeometry(ClientLevel, Options, Camera, BlockColors)
        ClientLevel level
    ) {
        if (!IPCGlobal.useHackedChunkRenderDispatcher) {
            return new ViewArea(
                dispatcher, minY, maxY, minSectionY, maxSectionY, renderDistance, occlusionGraph
            );
        }
        return new ImmPtlViewArea(
            dispatcher, minY, maxY, minSectionY, maxSectionY, renderDistance, occlusionGraph, level
        );
    }
}
