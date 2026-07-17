package qouteall.imm_ptl.core.mixin.client.accessor;

import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * S14-A FIX-6 (audit link fog, MAJOR M6 — the CUTOVER_SPEC §3-item-3 FogEnvironment bracket):
 * accessor to the private static {@code FogRenderer.FOG_ENVIRONMENTS} list (26.2
 * {@code FogRenderer.java:39-46} — a static list of SHARED environment instances). The only
 * mutable per-frame state in it is {@code AtmosphericFogEnvironment.rainFogMultiplier}, which the
 * dest fog probe ({@code SecondaryWorldRenderCore} Step 6 {@code setupFog}) lerps against the DEST
 * level — a cross-dimension shared-static leak exactly shaped like IP's 1.21.3 fog statics, which
 * IP swapped per-dim via {@code StaticFieldsSwappingManager} (IP {@code FogRendererContext:84-105},
 * API_RISKS R9 "same-shaped cross-dimension leak, new home").
 */
@Mixin(FogRenderer.class)
public interface IEFogRenderer_Environments {

    @Accessor("FOG_ENVIRONMENTS")
    static List<FogEnvironment> ip_getFogEnvironments() {
        throw new AssertionError();
    }
}
