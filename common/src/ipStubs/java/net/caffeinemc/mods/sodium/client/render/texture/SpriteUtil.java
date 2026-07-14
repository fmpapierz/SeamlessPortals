// F21 compileOnly stub (entity-portal migration; S00-U0-decisions F21).
// Empty third-party shell — NOT IP source, NOT shipped. S04-compat.md §3 SODIUM S6:
// SodiumInterface calls the static SpriteUtil.markSpriteActive(sprite). Matched to IP's
// call site (static), not to real Sodium's possible instance form.
package net.caffeinemc.mods.sodium.client.render.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public class SpriteUtil {
    public static void markSpriteActive(TextureAtlasSprite sprite) {
    }
}
