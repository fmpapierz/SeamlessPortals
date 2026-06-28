package com.warwa.seamlessportals.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/**
 * Guards vanilla {@code LevelRenderer.compileSections} against section-update
 * states whose section node is NOT in the current {@link ViewArea}.
 *
 * <p>Vanilla {@code compileSections} does
 * {@code section = this.viewArea.getRenderSection(state.sectionNode())} then
 * immediately {@code section.wasPreviouslyEmpty()} with NO null check
 * (verified LevelRenderer.java:609/625) — its own flow guarantees every node in
 * {@code levelRenderState.sectionUpdateRenderStates} resolves in range.
 *
 * <p>The mod breaks that guarantee during portal teleport: when it promotes /
 * swaps a secondary {@link LevelRenderer} to be the main renderer, the
 * {@code SectionOcclusionGraph}-derived {@code visibleSections} transiently hold
 * nodes outside the new {@code viewArea}. {@code LevelExtractor.extract} feeds
 * those into {@code sectionUpdateRenderStates}, and {@code compileSections} then
 * NPEs on the null section (the "crash on teleport",
 * {@code NullPointerException ... RenderSection.wasPreviouslyEmpty() because
 * section is null}).
 *
 * <p>A section not present in the current viewArea is out of range and must not
 * be compiled, so we drop those entries before the loop iterates them; they
 * recompile normally once the section is back in range. This only makes explicit
 * the validity the vanilla flow already assumes, for the mod's non-vanilla
 * multi-renderer juggling. We {@code @Redirect} the single
 * {@code sectionUpdateRenderStates} field-get (LevelRenderer.java:614) and return
 * a filtered view (fast-path returns the original list untouched when nothing is
 * out of range, which is every normal frame).
 */
@Mixin(LevelRenderer.class)
public class LevelRendererCompileSectionsMixin {

    @Redirect(
        method = "compileSections",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/state/level/LevelRenderState;sectionUpdateRenderStates:Ljava/util/List;",
            opcode = Opcodes.GETFIELD))
    private List<SectionUpdateRenderState> seamlessportals$skipOutOfRangeSections(LevelRenderState levelRenderState) {
        List<SectionUpdateRenderState> original = levelRenderState.sectionUpdateRenderStates;
        ViewArea viewArea =
            ((LevelRendererAccessorMixin) (Object) this).seamlessportals$getViewArea();
        if (viewArea == null || original.isEmpty()) {
            return original;
        }
        ViewAreaInvokerMixin va = (ViewAreaInvokerMixin) (Object) viewArea;

        List<SectionUpdateRenderState> filtered = null;
        for (int i = 0; i < original.size(); i++) {
            SectionUpdateRenderState state = original.get(i);
            if (va.seamlessportals$invokeGetRenderSection(state.sectionNode()) == null) {
                // Out-of-viewArea node — would NPE vanilla compileSections at
                // section.wasPreviouslyEmpty(). Drop it (not in range this frame).
                if (filtered == null) {
                    filtered = new ArrayList<>(original.subList(0, i));
                }
            } else if (filtered != null) {
                filtered.add(state);
            }
        }
        return filtered == null ? original : filtered;
    }
}
