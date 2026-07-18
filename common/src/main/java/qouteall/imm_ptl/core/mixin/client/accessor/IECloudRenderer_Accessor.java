package qouteall.imm_ptl.core.mixin.client.accessor;

import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// S18.3 port disposition: NEW ADDITIVE accessor for the dest-clouds isolation (port-note S18 §3).
// The mod's per-dest-dim CloudRenderer instances are NOT resource-reload listeners, so their
// `texture` (an immutable public record, 26.2:CloudRenderer.java:56/:340) stays null and render()
// no-ops — the SAME reason the secondary LevelRenderers' own cloudRenderers never drew. The
// isolation mirrors the reload-registered MAIN instance's texture into the mod instances once per
// frame (SecondaryWorldRenderCore.endCloudFrames): get on the main, set on ours.
@Mixin(CloudRenderer.class)
public interface IECloudRenderer_Accessor {

    @Accessor("texture")
    CloudRenderer.TextureData ip_getTexture();

    @Accessor("texture")
    void ip_setTexture(CloudRenderer.TextureData texture);
}
