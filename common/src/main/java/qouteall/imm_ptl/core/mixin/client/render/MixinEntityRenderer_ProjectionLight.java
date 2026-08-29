package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;

/**
 * ★ PROJECTION LIGHT (2026-08-24) — sample a projected image's light AT THE IMAGE, in the
 * projection's target level.
 *
 * <p>Vanilla {@code getPackedLightCoords} samples {@code entity.level().getBrightness} at the
 * entity's real position; a seam projection is drawn in the OTHER level at the transformed
 * position, and the drawing pass interprets the coords through ITS dimension's lightmap.
 * Cross-dim the mismatch is stark — a nether-sampled (sky=0, block=0) coord under the overworld
 * night lightmap renders near-black, the user's "source side of the cart becomes much darker
 * [than night]" the moment the flip moves the entity into the nether. While
 * {@link CrossPortalEntityRenderer#projectionLightWorld} is set — only across the projection's
 * own {@code extractEntity} call, cleared in a {@code finally} — answer with the target level's
 * brightness at the drawn position. The on-fire full-bright special case is preserved.
 *
 * <p>javap-verified against the loom deobf jar (house rule):
 * {@code public final int getPackedLightCoords(T, float)} — erased first parameter
 * {@code Entity}; the overridable per-renderer {@code getBlockLightLevel}/{@code
 * getSkyLightLevel} pair is intentionally bypassed while the override is active (both just read
 * {@code entity.level().getBrightness}, which is the very read being retargeted).
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRenderer_ProjectionLight {
    @Inject(
        method = "getPackedLightCoords(Lnet/minecraft/world/entity/Entity;F)I",
        at = @At("HEAD"),
        cancellable = true
    )
    private void seamlessportals$projectionLightAtImage(
        Entity entity, float partialTickTime, CallbackInfoReturnable<Integer> cir
    ) {
        ClientLevel world = CrossPortalEntityRenderer.projectionLightWorld;
        if (world != null) {
            BlockPos pos = BlockPos.containing(CrossPortalEntityRenderer.projectionLightPos);
            int blockLight = entity.isOnFire() ? 15 : world.getBrightness(LightLayer.BLOCK, pos);
            int skyLight = world.getBrightness(LightLayer.SKY, pos);
            cir.setReturnValue(LightCoordsUtil.pack(blockLight, skyLight));
        }
    }
}
