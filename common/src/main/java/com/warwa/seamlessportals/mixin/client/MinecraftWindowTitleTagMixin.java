package com.warwa.seamlessportals.mixin.client;

import com.warwa.seamlessportals.platform.Platform;
import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.3 MULTI-LOADER DEV AID — DEFAULT OFF ({@code -Dseamlessportals.windowTitleTag=true}; every dev run config passes it,
 * a shipped jar never does): append {@code [<loader> + Sodium + Iris]} to the window title, so four clients open side by
 * side can be told apart at a glance.
 *
 * <p><b>Why it is needed.</b> javap, {@code Minecraft.createTitle()}: the NeoForge-patched jar inserts {@code " NeoForge"}
 * and the Forge jar {@code " Forge"} into the title; Fabric and Quilt leave vanilla's {@code "Minecraft* 26.3"}, so those
 * two windows (and a Fabric window with or without Sodium/Iris) are indistinguishable.
 *
 * <p><b>The tag is what the RUNNING game reports, not what the launcher believes it started.</b> The loader name is
 * vanilla's own client brand, {@code ClientBrandRetriever.getClientModName()} — the string the F3 version line prints
 * ({@code DebugEntryVersion}) and the brand payload sends — which every loader patches for itself: Fabric Loader's
 * branding hook -&gt; {@code fabric}, Quilt Loader's {@code Hooks.insertBranding} -&gt; {@code quilt} (javap,
 * quilt-loader-0.31.0-beta.4), NeoForge {@code BrandingControl.getClientBranding()} -&gt; {@code neoforge}, Forge
 * {@code BrandingControl.getBranding()} -&gt; {@code forge}. That is what makes it right on Quilt, which runs the FABRIC
 * jar: nothing in this mod knows it is on Quilt. Sodium / Iris come from the loader's mod list.
 *
 * <p>Same {@code private String createTitle()} with a single {@code areturn} in the Fabric merged, NeoForge-patched and
 * Forge jars (javap). {@code require = 0}: a cosmetic aid must never stop a boot. The first call is early in the
 * {@code Minecraft} constructor; anything that throws there degrades to the untagged title, and vanilla recomputes the
 * title on every {@code updateTitle()} (world join / leave).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftWindowTitleTagMixin {

    @Unique
    private static final boolean SEAMLESSPORTALS$ENABLED = Boolean.getBoolean("seamlessportals.windowTitleTag");

    @Inject(method = "createTitle()Ljava/lang/String;", at = @At("RETURN"), cancellable = true, require = 0)
    private void seamlessportals$tagWindowTitle(CallbackInfoReturnable<String> cir) {
        if (!SEAMLESSPORTALS$ENABLED) {
            return;
        }
        try {
            String brand = ClientBrandRetriever.getClientModName();
            String loader;
            if (brand == null || brand.isEmpty()) {
                loader = "unknown loader";
            } else {
                switch (brand) {
                    case "fabric" -> loader = "Fabric";
                    case "quilt" -> loader = "Quilt";
                    case "neoforge" -> loader = "NeoForge";
                    case "forge" -> loader = "Forge";
                    default -> loader = brand;
                }
            }
            StringBuilder tag = new StringBuilder(loader);
            Platform platform = Platform.get();
            if (platform.isModLoaded("sodium")) {
                tag.append(" + Sodium");
            }
            if (platform.isModLoaded("iris")) {
                tag.append(" + Iris");
            }
            cir.setReturnValue(cir.getReturnValue() + " [" + tag + "]");
        } catch (Throwable ignored) {
            // cosmetic: keep the title the game computed
        }
    }
}
