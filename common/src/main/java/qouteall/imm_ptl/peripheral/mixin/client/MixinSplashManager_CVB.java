package qouteall.imm_ptl.peripheral.mixin.client;

import net.minecraft.client.resources.SplashManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// S19 commons tail — port of IP's MixinSplashManager_CVB. It rewrites two of VANILLA's own splash
// lines (portal in-joke) — no lang/asset dependency; "Euclidian!" and "Slow acting portals!" ship in
// Minecraft's texts/splashes.txt.
//
// 26.2 DELTA (whole-mixin rework, target verified against SplashManager.java):
//   1.21.3: field was `@Shadow @Final private List<String> splashes` and the applied list was
//           MUTABLE, so IP called splashes.remove(String)/add(String) in place.
//   26.2:   the field is `private List<Component> splashes = List.of()` (:31) — NON-final,
//           element type Component (not String) — and apply() reassigns it to an IMMUTABLE
//           `List.copyOf(preparations)` (:51). Consequences, all forced:
//             (a) drop @Final (field is no longer final);
//             (b) compare/build Component, not String — matched via Component#getString();
//             (c) cannot mutate the immutable list in place → build a fresh mutable copy, apply
//                 IP's exact edits, then reassign the shadowed field with List.copyOf(...).
// The apply() descriptor (Ljava/util/List;Lnet/minecraft/server/packs/resources/ResourceManager;
// Lnet/minecraft/util/profiling/ProfilerFiller;)V is unchanged (List erases identically), so the
// @Inject(RETURN) anchor is byte-for-byte the same as IP's.
@Mixin(SplashManager.class)
public class MixinSplashManager_CVB {
    // 26.2 DELTA: List<Component>, non-final (see class note).
    @Shadow
    private List<Component> splashes;

    // 26.2 DELTA: reuse vanilla's own private factory so the added lines carry the identical
    // DEFAULT_STYLE (yellow) as every other splash — IP added plain Strings that vanilla styled
    // uniformly; shadowing literalSplash reproduces that styling faithfully instead of re-hardcoding
    // the private DEFAULT_STYLE constant.
    @Shadow
    private static Component literalSplash(String text) {
        throw new AssertionError();
    }

    @Inject(method = "apply(Ljava/util/List;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("RETURN"))
    private void onApply(
        List<Component> list, ResourceManager resourceManager, ProfilerFiller profiler, CallbackInfo ci
    ) {
        // 26.2 DELTA: mutable working copy of the immutable applied list.
        List<Component> mutableSplashes = new ArrayList<>(splashes);
        boolean changed = false;

        // IP's edits, preserved 1:1 (ip_removeSplash reproduces List#remove(Object)'s
        // first-match + boolean-return semantics, but over Component#getString()).
        if (ip_removeSplash(mutableSplashes, "Euclidian!")) {
            mutableSplashes.add(literalSplash("Non-Euclidian!"));
            changed = true;
        }
        if (ip_removeSplash(mutableSplashes, "Slow acting portals!")) {
            mutableSplashes.add(literalSplash("Fast acting portals!"));
            mutableSplashes.add(literalSplash("Immersive Portals!"));
            changed = true;
        }

        // 26.2 DELTA: reassign the shadowed field (the applied list is immutable — cannot mutate in
        // place). Keep the immutability invariant vanilla expects by copying to an immutable list.
        if (changed) {
            splashes = List.copyOf(mutableSplashes);
        }
    }

    @Unique
    private static boolean ip_removeSplash(List<Component> splashes, String text) {
        for (Iterator<Component> iterator = splashes.iterator(); iterator.hasNext(); ) {
            if (iterator.next().getString().equals(text)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }
}
