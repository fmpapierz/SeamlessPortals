package com.warwa.seamlessportals.render;

import com.warwa.seamlessportals.mixin.client.LevelExtractorBEInvokerForge;
import com.warwa.seamlessportals.mixin.client.LevelExtractorBEInvokerVanilla;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * 26.3 — the one call path to vanilla's private block-entity extract, across its two loader shapes.
 *
 * <p>Vanilla/Fabric and NeoForge carry {@code extractVisibleBlockEntities(Camera, float, LevelRenderState)};
 * MinecraftForge 26.3-66.0.2 carries ONLY {@code (Camera, float, LevelRenderState, Frustum)} with a mandatory
 * (non-null) frustum. Exactly one of the two invoker mixins is applied per loader (the mixin plugin's
 * {@code NON_FORGE_MIXINS} / {@code FORGE_ONLY_MIXINS}).
 *
 * <p><b>The branch must be decided WITHOUT touching the other interface.</b> An accessor-mixin interface is only
 * loadable by ordinary code when Mixin has registered it as pass-through, and it registers only mixins that still
 * have a target after the plugin's veto — sponge-mixin 0.17.4 {@code MixinConfig.prepareMixins}: the
 * {@code IListener.onPrepare} call (which feeds {@code MixinCoprocessorPassthrough.loadable}) sits inside
 * {@code if (mixin.getTargetClasses().size() > 0)} (javap -c: {@code ifle} @355 jumps past the {@code onPrepare} loop
 * @469). A vetoed interface therefore throws {@code IllegalClassLoadError} ("in a defined mixin package ... cannot be
 * referenced directly") the moment anything resolves it — and an {@code instanceof} resolves it. So the loader is
 * probed first, by the SAME test the plugin uses ({@code SeamlessMixinConfigPlugin.isForgeRuntime}: presence of
 * {@code net.minecraftforge.fml.loading.FMLLoader}), and each branch names only the interface that is applied there.
 *
 * <p>{@code passFrustum} is the frustum the caller hands {@code extractVisibleEntities} for the same pass. On the
 * vanilla shape it is unused (that method culls nothing by frustum — the 26.2 behaviour, unchanged). On Forge it is
 * what Forge's own {@code LevelExtractor.extract()} does: one frustum local feeds both the entity extract and the
 * block-entity extract (javap -c on the Forge jar: slot 6 at offsets 629 and 651).
 */
public final class BlockEntityExtractInvoke {

    private BlockEntityExtractInvoke() {}

    /** Twin of {@code SeamlessMixinConfigPlugin.isForgeRuntime()} — same probe, so the two can never disagree. */
    private static final boolean FORGE_SHAPE = probeForge();

    private static boolean probeForge() {
        try {
            Class.forName("net.minecraftforge.fml.loading.FMLLoader", false,
                BlockEntityExtractInvoke.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void invoke(
        LevelExtractor extractor, Camera camera, float deltaPartialTick, LevelRenderState output, Frustum passFrustum
    ) {
        if (FORGE_SHAPE) {
            ((LevelExtractorBEInvokerForge) (Object) extractor)
                .seamlessportals$invokeExtractVisibleBlockEntitiesForge(camera, deltaPartialTick, output, passFrustum);
        } else {
            ((LevelExtractorBEInvokerVanilla) (Object) extractor)
                .seamlessportals$invokeExtractVisibleBlockEntities(camera, deltaPartialTick, output);
        }
    }
}
