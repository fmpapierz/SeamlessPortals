package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.ducks.IEEntityRenderState;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * S12-A (Slice C) — R3 mixin 2 of 4: the clip-context tag SETTER (S11-R3-clip-bracketing.md §3.2). Wraps the
 * {@code this.extractEntity(entity, partialTick)} call inside {@code LevelExtractor.extractVisibleEntities}
 * ({@code 26.2:.../extract/LevelExtractor.java:243}) and tags the freshly extracted
 * {@code EntityRenderState} with the colliding entity+portal when the entity is colliding a portal — the
 * CASE-1 trigger (IP {@code CrossPortalEntityRenderer :115/:126}).
 *
 * <p><b>Trigger parity.</b> {@code ((IEEntity) entity).ip_isCollidingWithPortal()} is the EXACT predicate that
 * populates {@code CrossPortalEntityRenderer.collidedEntities} ({@code onEntityTickClient :126} puts an entity
 * iff {@code ip_isCollidingWithPortal()}, {@code onClientTick :117} prunes it when the predicate goes false).
 * Using the entity's own authoritative collision state needs no access to that private render-side set.
 *
 * <p><b>Set-or-CLEAR every extraction.</b> The tag is written on EVERY extractEntity — {@code (entity, portal)}
 * when colliding, {@code (null, null)} otherwise — so a reused/pooled render state can never carry a stale
 * clip context into the submit pass (design §3.2 "no retention concern"). This realizes the extract-vs-render
 * phase assignment (CUTOVER_SPEC §4.2): the clip DECISION is captured at extract; the GL effect happens at
 * draw. No per-frame LOGGER (render-thread-logging discipline).
 *
 * <p>Uses {@code @WrapOperation} (not a raw {@code @Inject}) because it must capture BOTH the {@code entity}
 * argument and the {@code EntityRenderState} return of a single call site deterministically — the return value
 * is on the stack (not yet a local) at the extractEntity {@code INVOKE:AFTER} point, so a local-capture
 * {@code @Inject} could not bind the state. WrapOperation is IP-consistent (IP's own MixinLevelRenderer wraps
 * the per-entity render call).
 *
 * <p>Held/UNREGISTERED; registered into the S12 client-mixin set, activated flag-ON at S13.
 */
@Mixin(LevelExtractor.class)
public class MixinLevelExtractor {

    @WrapOperation(
        method = "extractVisibleEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;extractEntity"
                + "(Lnet/minecraft/world/entity/Entity;F)"
                + "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"
        )
    )
    @SuppressWarnings("deprecation") // ip_getCollidingPortal() is informational; submit path re-derives last-wins
    private EntityRenderState seamlessportals$tagClipContext(
        LevelExtractor instance, Entity entity, float partialTick, Operation<EntityRenderState> original
    ) {
        EntityRenderState state = original.call(instance, entity, partialTick);

        if (state instanceof IEEntityRenderState clipState) {
            if (((IEEntity) entity).ip_isCollidingWithPortal()) {
                Portal collidingPortal = ((IEEntity) entity).ip_getCollidingPortal();
                clipState.ip_setClipContext(entity, collidingPortal);
            }
            else {
                clipState.ip_setClipContext(null, null);
            }
        }

        return state;
    }
}
