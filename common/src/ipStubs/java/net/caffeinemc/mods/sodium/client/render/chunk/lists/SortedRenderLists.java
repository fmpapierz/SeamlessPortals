// F21 compileOnly stub (entity-portal migration; S00-U0-decisions F21).
// Empty third-party shell — NOT IP source, NOT shipped. S04-compat.md §3 SODIUM S8:
// SodiumRenderingContext holds a SortedRenderLists field, initialized from the static
// SortedRenderLists.empty().
package net.caffeinemc.mods.sodium.client.render.chunk.lists;

public class SortedRenderLists {
    public static SortedRenderLists empty() {
        return null;
    }
}
