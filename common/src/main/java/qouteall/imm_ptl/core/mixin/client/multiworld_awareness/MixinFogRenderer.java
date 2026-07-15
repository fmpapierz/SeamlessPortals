package qouteall.imm_ptl.core.mixin.client.multiworld_awareness;

import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.render.context_management.FogRendererContext;

/**
 * S12-B port disposition: TARGET-GONE — <b>re-expressed onto the R9 fog design</b> (mixin-client.md §5;
 * API_RISKS R9 / CUTOVER_SPEC §3; the S11-A {@code FogRendererContext} re-expression).
 *
 * <p><b>IP 1.21.3 role:</b> a static-initializer wired three {@code FogRendererContext} hooks that
 * read/write vanilla's SIX private fog statics ({@code fogRed/fogGreen/fogBlue/targetBiomeFog/
 * previousBiomeFog/biomeChangedTime}) for per-dimension fog save/restore, then called
 * {@code FogRendererContext.init()}. The class-load of {@code FogRenderer} triggered the merged
 * static-init (hence the whole context machinery was armed early).
 *
 * <p><b>26.2 reality:</b> the old {@code net.minecraft.client.renderer.FogRenderer} is gone; the reborn
 * {@code net.minecraft.client.renderer.fog.FogRenderer} is a fully-rewritten INSTANCE class with <b>NO
 * color/biome-interpolation statics</b> (R9). Per-dim smoothing state now lives in
 * {@code Camera.attributeProbe()}, and {@code FogRendererContext} (S11-A) already records that the three
 * hooks "degrade to no-ops" and {@code getCurrentFogColor} is "superseded by reading {@code FogData.color}
 * directly in {@code getFogColorOf}". So this mixin's ONLY remaining 26.2 job is to preserve IP's LIFECYCLE
 * contract: install the (now no-op) hooks and call {@code FogRendererContext.init()} at fog-renderer
 * class-load, so {@code swappingManager} is armed before {@code update()}/{@code getFogColorOf()}/
 * {@code onPlayerTeleport()} run. There are no statics to {@code @Shadow} and no injections — the
 * static initializer is the entire mixin (the same static-init-merge mechanism IP relied on).
 *
 * <p>Priority 1100 kept (IP parity). Held/unregistered until S13. {@code FogRendererContext.getFogColorOf}
 * carries the live per-dim fog color-probe (S11-A); the LIVE per-layer fog UBO ownership is the driver-core
 * concern (S13, CUTOVER_SPEC §3.2/§3.3), not this context installer.
 */
@Mixin(value = FogRenderer.class, priority = 1100)
public class MixinFogRenderer {

    static {
        // 26.2 (R9): no fog statics remain to mirror, so the copy-from/copy-to hooks are no-ops (the swap
        // machinery calls them but there is nothing to swap for fog); getCurrentFogColor is superseded by
        // FogRendererContext.getFogColorOf reading FogData.color directly. Hooks kept non-null for the
        // StaticFieldsSwappingManager contract. The load-bearing action is FogRendererContext.init().
        FogRendererContext.copyContextFromObject = context -> {};
        FogRendererContext.copyContextToObject = context -> {};
        FogRendererContext.getCurrentFogColor = () -> Vec3.ZERO;

        FogRendererContext.init();
    }
}
