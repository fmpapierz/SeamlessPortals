package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;

// S11-C (Slice A) — the entity-visibility gate, a SIGNATURE-IDENTICAL 1:1 port (render-core S35; the
// former G24 "GONE" misclassification is withdrawn). IP HEAD-injects (cancellable) into
// EntityRenderDispatcher.shouldRender(Entity, Frustum, DDD)Z and forces `false` when
// CrossPortalEntityRenderer.shouldRenderEntityNow returns false. 26.2's target is signature-identical
// (`<E extends Entity> boolean shouldRender(E, Frustum, double, double, double)`,
// 26.2:EntityRenderDispatcher.java:127-130; the erased descriptor Entity/Frustum/DDD matches IP's), and
// vanilla's own extraction path calls it (LevelExtractor.isEntityVisible :254) — so the gate now
// suppresses EXTRACTION (the render state is never created), strictly upstream of IP's draw suppression,
// with the SAME visibility outcome. shouldRenderEntityNow's inputs (PortalRendering.isRendering() + the
// rendering portal) must be valid when the portal pass's extract runs — guaranteed by G1's per-dimension
// extract choreography (verified at S13).
//
// HELD/UNREGISTERED: this class is authored at the verbatim IP mixin path but is NOT listed in any loaded
// mixins.json (seamlessportals-ip-client.mixins.json "client":[] stays empty). It compiles as ordinary
// annotated Java in the probe; it is wired into the S12 client-mixin set and activated flag-ON at S13.
@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {
    @Inject(
        method = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onShouldRenderEntity(
        Entity entity_1,
        Frustum frustum_1,
        double double_1,
        double double_2,
        double double_3,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!CrossPortalEntityRenderer.shouldRenderEntityNow(entity_1)) {
            // §2b probe: count the cross-portal hide vetoing entities during a DEST extract
            // (hid in the [ENT-PROBE] line — over-hiding here is a culprit candidate).
            if (qouteall.imm_ptl.core.render.EntityVisibilityProbe.ENABLED
                && qouteall.imm_ptl.core.render.SecondaryWorldRenderCore.isDestExtracting) {
                qouteall.imm_ptl.core.render.EntityVisibilityProbe.hiddenByGate++;
            }
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

}
