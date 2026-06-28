package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for {@link LevelExtractor}'s renderer/level/tracker bindings.
 *
 * <p>26.2 split extraction off {@code LevelRenderer} onto a single
 * {@code mc.levelExtractor} ({@code public final}, bound ONCE to the original
 * {@code mc.levelRenderer} at {@code Minecraft.java:649}). The mod's portal
 * teleport swaps {@code mc.levelRenderer} to a promoted cached renderer but the
 * single {@code mc.levelExtractor} keeps driving the ORIGINAL renderer — so
 * {@code GameRenderer.extract()} populates the wrong renderer's
 * {@code visibleSections} (proven: log DIAG #1 oldRenderer=1225, promoted=0) →
 * blank terrain after teleport.
 *
 * <p>These accessors let {@code PortalWorldManager.promoteToMain} re-point
 * {@code mc.levelExtractor} onto the promoted renderer + dest level + the
 * promoted renderer's {@code SectionUpdateTracker} (copied from the mod's
 * per-dimension {@code destExtractor}, which is already correctly set up). We do
 * this by direct field copy rather than {@code LevelExtractor.setLevel(...)}
 * because {@code setLevel} calls {@code allChanged()} →
 * {@code invalidateCompiledGeometry} which would wipe the cached meshes the
 * cached-renderer promotion exists to preserve.
 *
 * <p>{@code @Mutable} on the {@code levelRenderer} setter removes its
 * {@code final} modifier (the other two fields are already non-final).
 */
@Mixin(LevelExtractor.class)
public interface LevelExtractorAccessor {

    @Accessor("levelRenderer")
    @Mutable
    void seamlessportals$setLevelRenderer(LevelRenderer levelRenderer);

    @Accessor("level")
    ClientLevel seamlessportals$getLevel();

    @Accessor("level")
    void seamlessportals$setLevel(ClientLevel level);

    @Accessor("sectionUpdateTracker")
    SectionUpdateTracker seamlessportals$getSectionUpdateTracker();

    @Accessor("sectionUpdateTracker")
    void seamlessportals$setSectionUpdateTracker(SectionUpdateTracker tracker);

    /** The LevelRenderState this extractor's {@code extract()} populates (final,
     *  bound at construction). Must equal the renderer's current levelRenderState
     *  for render() to see the extracted entities/clouds/particles. */
    @Accessor("levelRenderState")
    net.minecraft.client.renderer.state.level.LevelRenderState seamlessportals$getLevelRenderState();
}
