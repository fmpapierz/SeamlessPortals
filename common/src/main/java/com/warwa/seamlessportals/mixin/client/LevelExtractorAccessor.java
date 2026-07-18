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

    /** S14.42 (render-chain probe): read the driven renderer for identity-coherence checks. */
    @Accessor("levelRenderer")
    LevelRenderer seamlessportals$getLevelRenderer();

    @Accessor("level")
    ClientLevel seamlessportals$getLevel();

    @Accessor("level")
    void seamlessportals$setLevel(ClientLevel level);

    @Accessor("sectionUpdateTracker")
    SectionUpdateTracker seamlessportals$getSectionUpdateTracker();

    @Accessor("sectionUpdateTracker")
    void seamlessportals$setSectionUpdateTracker(SectionUpdateTracker tracker);

    /** The render distance the extractor last reconfigured for. extract() compares
     *  it against {@code options.getEffectiveRenderDistance()} every frame and, on a
     *  mismatch, calls {@code allChanged()} → {@code invalidateCompiledGeometry} (wipes
     *  ALL meshes). A seamless promote must sync this to the current effective RD or
     *  the next extract wipes the very meshes the promotion preserves. */
    @Accessor("lastViewDistance")
    void seamlessportals$setLastViewDistance(int lastViewDistance);

    /** The LevelRenderState this extractor's {@code extract()} populates (final,
     *  bound at construction). Must equal the renderer's current levelRenderState
     *  for render() to see the extracted entities/clouds/particles. */
    @Accessor("levelRenderState")
    net.minecraft.client.renderer.state.level.LevelRenderState seamlessportals$getLevelRenderState();

    /** S14.7 (cold-promote fix): the one-shot "rebuild dispatcher/viewArea/graph at next
     *  extract" flag ({@code LevelExtractor.java:84}, consumed at {@code :122-124} →
     *  {@code invalidateCompiledGeometry}, which CREATES them when null). Setting it directly —
     *  paired with a fresh tracker — is the manual equivalent of {@code allChanged()} minus its
     *  side effects (the S14.5 reload-cascade mixin TAIL-fires on allChanged; a cold promote must
     *  not trigger a cross-dim reload sweep mid-crossing). */
    @Accessor("shouldInvalidateCompiledGeometry")
    void seamlessportals$setShouldInvalidateCompiledGeometry(boolean value);

    /** S14.42 verify-fold: the "reset level render data at next extract" one-shot (armed by
     *  setLevel at world creation; consumed at the FIRST extract → resetLevelRenderData →
     *  SOG.waitAndReset(null) → loadedChunks.clear()). While armed, the delta pump must NOT
     *  drain the dim's window — the first Step-5 feed's WHOLESALE window re-seeds loadedChunks
     *  after that clear, and draining beforehand would permanently under-include every
     *  pre-first-view chunk (verify BLOCKER, wf_723f7b39-bf4). */
    @Accessor("shouldResetLevelRenderData")
    boolean seamlessportals$getShouldResetLevelRenderData();

    /** S15 (recursive-view entities): the ISOLATED entity-only extract — vanilla's private
     *  {@code extractVisibleEntities(Camera, Frustum, DeltaTracker, LevelRenderState)}
     *  (LevelExtractor.java:221) writes {@code output.entityRenderStates} +
     *  {@code lastEntityRenderStateCount}, plus two idempotent re-writes of values the main
     *  extract already set this frame (the static {@code Entity.setViewScale}, options-derived;
     *  {@code xOld/yOld/zOld} on tickCount==0 entities — current pos); it touches NO one-shot
     *  dirty trackers, no particles, no light — safe to re-run mid-frame against a scratch
     *  output LRS. Used by
     *  {@code SecondaryWorldRenderCore.renderPortalEntitiesSameDim} to give loop-back
     *  (same-dim / sharedState) portal passes a real portal-camera entity extract: the main
     *  pass consumed+cleared the main {@code entityRenderStates}, and those states were
     *  extracted against the MAIN camera anyway. */
    @org.spongepowered.asm.mixin.gen.Invoker("extractVisibleEntities")
    void seamlessportals$invokeExtractVisibleEntities(
        net.minecraft.client.Camera camera,
        net.minecraft.client.renderer.culling.Frustum frustum,
        net.minecraft.client.DeltaTracker deltaTracker,
        net.minecraft.client.renderer.state.level.LevelRenderState output);
}
