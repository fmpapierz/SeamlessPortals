package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessors for {@code Minecraft} fields needed by portal context-switch
 * rendering.
 *
 * <p>Target fields (verified from Minecraft bytecode in 26.1.2):
 * <ul>
 *   <li>{@code public final LevelRenderer levelRenderer} — swapped to
 *       destination dim's renderer per portal-render frame.</li>
 *   <li>{@code private final RenderBuffers renderBuffers} — swapped to a
 *       pooled {@link com.warwa.seamlessportals.render.PortalRenderBuffersPool}
 *       buffer per portal-render frame so the main render's buffers stay
 *       untouched. Required to safely use the dormant vanilla
 *       {@code mc.levelRenderer} as a portal-view secondary (its own
 *       {@code renderBuffers} field is the same instance as
 *       {@code mc.renderBuffers}).</li>
 * </ul>
 *
 * <p>{@code @Mutable} removes the {@code final} modifier so the setter
 * can write the field.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessorMixin {

    @Accessor("levelRenderer")
    @Mutable
    void seamlessportals$setLevelRenderer(LevelRenderer levelRenderer);

    @Accessor("levelRenderer")
    LevelRenderer seamlessportals$getLevelRenderer();

    @Accessor("renderBuffers")
    RenderBuffers seamlessportals$getRenderBuffers();

    @Accessor("renderBuffers")
    @Mutable
    void seamlessportals$setRenderBuffers(RenderBuffers renderBuffers);
}
