package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import qouteall.imm_ptl.core.ducks.IEEntityRenderState;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * S12-A (Slice C) — R3 mixin 1 of 4: the {@code EntityRenderState} clip-context tag HOLDER
 * (S11-R3-clip-bracketing.md §3.2). Adds the two frame-scoped fields the {@link IEEntityRenderState} duck
 * exposes. The SET happens at extraction ({@link MixinLevelExtractor}); the submit-side WrapOperation
 * ({@link MixinLevelRenderer_CrossPortalEntity}) reads them.
 *
 * <p>Frame-scoped: {@code EntityRenderState}s live in {@code levelRenderState.entityRenderStates}, cleared
 * right after submit ({@code 26.2:LevelRenderer.java:282}); the extract inject sets-or-clears the context on
 * EVERY extraction, so a reused/pooled state never carries a stale tag (no retention concern).
 *
 * <p>Held/UNREGISTERED (not in {@code seamlessportals-ip-client.mixins.json "client":[]}); registered into the
 * S12 client-mixin set, activated flag-ON at S13.
 */
@Mixin(EntityRenderState.class)
public class MixinEntityRenderState implements IEEntityRenderState {

    @Unique
    @Nullable
    private Entity seamlessportals$clipEntity;

    @Unique
    @Nullable
    private Portal seamlessportals$clipPortal;

    @Override
    public void ip_setClipContext(@Nullable Entity collidedEntity, @Nullable Portal collidingPortal) {
        this.seamlessportals$clipEntity = collidedEntity;
        this.seamlessportals$clipPortal = collidingPortal;
    }

    @Override
    @Nullable
    public Entity ip_getClipContextEntity() {
        return this.seamlessportals$clipEntity;
    }

    @Override
    @Nullable
    public Portal ip_getClipContextPortal() {
        return this.seamlessportals$clipPortal;
    }
}
