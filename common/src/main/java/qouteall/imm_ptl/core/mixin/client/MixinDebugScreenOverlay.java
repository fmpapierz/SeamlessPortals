package qouteall.imm_ptl.core.mixin.client;

import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;

/**
 * S12-B port disposition: TARGET-GONE — <b>UNKNOWN-NEEDS-DESIGN, deferred</b> (mixin-client.md §1).
 *
 * <p>IP 1.21.3 appended {@code RenderStates.collectDebugText()} to the F3 right column via
 * {@code DebugScreenOverlay.getSystemInformation()} @RETURN (top or bottom per
 * {@code IPGlobal.moveDebugTextToTop}).
 *
 * <p><b>26.2 reality:</b> {@code getSystemInformation()} is GONE. The F3 text is assembled from a
 * registry of {@code DebugScreenEntry} values ({@code DebugScreenEntries.ENTRIES_BY_ID},
 * {@code 26.2:gui/components/debug/DebugScreenEntries.java:12}); <b>both {@code register} overloads are
 * private</b> ({@code :60,:64}) and an entry only renders if additionally enabled in the active profile
 * ({@code Minecraft.debugEntries} / {@code DebugScreenEntryList}). Re-expressing IP's append therefore
 * requires registering a custom {@code DebugScreenEntry} (via an invoker/AT on the private
 * {@code register}, or direct insertion into {@code ENTRIES_BY_ID}) AND enabling it in the profile list
 * — a design task, not a mechanical retarget.
 *
 * <p><b>Disposition:</b> this is a COSMETIC F3-overlay debug feature, not load-bearing for the cutover.
 * The IP injection is deferred (the custom-{@code DebugScreenEntry} design is an S13+/post-cutover item);
 * the mixin is retained as an empty inert body so the file/mixin set stays complete. Held/unregistered
 * until S13. ({@code RenderStates.collectDebugText()} remains available for the eventual entry.)
 */
@Mixin(DebugScreenOverlay.class)
public class MixinDebugScreenOverlay {
    // 26.2: getSystemInformation() removed; F3 text = DebugScreenEntries registry (private register).
    // The RenderStates.collectDebugText() append needs a custom DebugScreenEntry — DEFERRED (design).
    // Intentionally empty (non-load-bearing debug feature).
}
