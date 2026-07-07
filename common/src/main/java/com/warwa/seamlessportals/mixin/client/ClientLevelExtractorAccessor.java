package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mutable accessor for {@code ClientLevel}'s {@code private final LevelExtractor
 * levelExtractor} (ClientLevel.java:152) — the construction-time field EVERY
 * visual block-change notification routes through ({@code setBlocksDirty} →
 * {@code levelExtractor.setBlockDirty}, {@code sendBlockUpdated} →
 * {@code levelExtractor.blockChanged}).
 *
 * <p>THE NETHER BLOCK-FREEZE ROOT CAUSE (2026-07-06): {@code demoteFromMain}
 * builds a brand-new LevelExtractor + SectionUpdateTracker per demote, and the
 * next promote wires {@code mc.levelExtractor} to consume THAT tracker — while
 * the mod-created level's final field kept writing dirty-marks into the
 * original construction extractor's tracker, which nothing reads after the
 * first demote. Block break/place/server-updates then mutate chunk data
 * correctly but never trigger a remesh (frozen visuals from the second entry
 * onward). The demote path uses this setter to re-point the level at its
 * current extractor, keeping the writer and the promoted reader on one tracker
 * for the whole session.
 */
@Mixin(ClientLevel.class)
public interface ClientLevelExtractorAccessor {

    @Accessor("levelExtractor")
    @Mutable
    void seamlessportals$setLevelExtractor(LevelExtractor levelExtractor);
}
