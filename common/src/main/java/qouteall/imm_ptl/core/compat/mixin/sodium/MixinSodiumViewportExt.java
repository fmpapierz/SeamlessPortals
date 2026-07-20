package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumViewport;
import qouteall.q_misc_util.my_util.BoxPredicateF;

/**
 * C2-3 D2 — the snapshot-carrier half of the culling redesign: {@code @Unique} fields merged
 * onto Sodium 0.9.1's {@code Viewport} (javap: {@code public final class
 * net.caffeinemc.mods.sodium.client.render.viewport.Viewport} — final is mixin-legal; the
 * class has no subclasses to break). See {@link IESodiumViewport} for the full D2 contract,
 * the thread model, and the deliberately-unhooked-path map.
 *
 * <p>Lifecycle: a fresh {@code Viewport} is created per pass
 * ({@code ViewportProvider.sodium$createViewport()} — sodium's main {@code cullTerrain} and
 * our dest drive {@code SodiumInterface.OnSodiumPresent.ip_driveDestTerrainSetup} both do
 * this), so all three fields start null every pass; the producer
 * ({@code MixinSodiumWorldRenderer_CullSnapshot}, setupTerrain HEAD, render thread) stamps
 * them exactly once before any consumer runs. Immutable-after-publish: nothing writes them
 * after the producer returns.
 *
 * <p>Registered in {@code seamlessportals-ip-compat.mixins.json}; the class name carries
 * {@code Sodium} for the plugin's substring gate.
 */
@Mixin(value = Viewport.class, remap = false)
public abstract class MixinSodiumViewportExt implements IESodiumViewport {

    @Unique
    private @Nullable BoxPredicateF ip_portalCullPredicate;

    @Unique
    private @Nullable Boolean ip_useOcclusionCullingOverride;

    /**
     * D2b DORMANT — seeded null, no consumer; the ledgered re-entry surface for IP file #4's
     * box-portal iteration-origin retarget (see {@link IESodiumViewport}).
     */
    @Unique
    private @Nullable SectionPos ip_modifiedIterationOrigin;

    @Override
    public @Nullable BoxPredicateF ip_getPortalCullPredicate() {
        return ip_portalCullPredicate;
    }

    @Override
    public void ip_setPortalCullPredicate(@Nullable BoxPredicateF predicate) {
        ip_portalCullPredicate = predicate;
    }

    @Override
    public @Nullable Boolean ip_getUseOcclusionCullingOverride() {
        return ip_useOcclusionCullingOverride;
    }

    @Override
    public void ip_setUseOcclusionCullingOverride(@Nullable Boolean override) {
        ip_useOcclusionCullingOverride = override;
    }

    @Override
    public @Nullable SectionPos ip_getModifiedIterationOrigin() {
        return ip_modifiedIterationOrigin;
    }

    @Override
    public void ip_setModifiedIterationOrigin(@Nullable SectionPos origin) {
        ip_modifiedIterationOrigin = origin;
    }
}
