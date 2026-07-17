package qouteall.imm_ptl.core.mixin.client.accessor;

import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * S14-A FIX-6 companion of {@link IEFogRenderer_Environments}: get/set the one mutable field on
 * the shared {@code AtmosphericFogEnvironment} instance ({@code private float rainFogMultiplier},
 * 26.2 {@code AtmosphericFogEnvironment.java:26}, lerped toward the queried level's rain-fog
 * target by {@code updateRainFogState:89-98}). The dest render pass brackets it per-dim (the IP
 * {@code StaticFieldsSwappingManager} pattern applied to the field's 26.2 home) so a dest-world
 * rain-fog value never bleeds into the main world's next pass and vice versa.
 */
@Mixin(AtmosphericFogEnvironment.class)
public interface IEAtmosphericFogEnvironment {

    @Accessor("rainFogMultiplier")
    float ip_getRainFogMultiplier();

    @Accessor("rainFogMultiplier")
    void ip_setRainFogMultiplier(float value);
}
