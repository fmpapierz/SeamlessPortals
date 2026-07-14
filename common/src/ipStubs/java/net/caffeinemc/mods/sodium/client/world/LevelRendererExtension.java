// F21 compileOnly stub (entity-portal migration; S00-U0-decisions F21).
// Empty third-party shell — NOT IP source, NOT shipped. S04-compat.md §3 SODIUM S7:
// SodiumInterface casts Minecraft.getInstance().levelRenderer to this interface and calls
// sodium$getWorldRenderer(). Kind = interface (cast target on a vanilla LevelRenderer).
package net.caffeinemc.mods.sodium.client.world;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;

public interface LevelRendererExtension {
    SodiumWorldRenderer sodium$getWorldRenderer();
}
