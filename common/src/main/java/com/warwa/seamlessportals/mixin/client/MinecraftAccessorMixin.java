package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Makes Minecraft.levelRenderer mutable for context-switch rendering.
 *
 * Following the Immersive Portals architecture, we need to swap
 * Minecraft.levelRenderer to the destination dimension's renderer
 * during portal rendering, then swap it back.
 *
 * Target field (verified from Minecraft.java line 293):
 *   public final LevelRenderer levelRenderer;
 *
 * @Mutable removes the 'final' modifier.
 * @Accessor generates getter/setter.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessorMixin {

    // Target: public final LevelRenderer levelRenderer (line 293)
    @Accessor("levelRenderer")
    @Mutable
    void seamlessportals$setLevelRenderer(LevelRenderer levelRenderer);

    @Accessor("levelRenderer")
    LevelRenderer seamlessportals$getLevelRenderer();
}
