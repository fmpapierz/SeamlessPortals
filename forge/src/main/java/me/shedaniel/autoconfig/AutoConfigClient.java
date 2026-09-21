// F21 Cloth-Config / AutoConfig — FORGE-MODULE functional no-op (see ConfigData.java header). 26.3 addition to the
// recovered F21 set: real Cloth Config 26.x moved getConfigScreen from AutoConfig to AutoConfigClient, and
// qouteall.imm_ptl.core.platform_specific.IPConfigGUI calls it there
// (`AutoConfigClient.getConfigScreen(IPConfig.class, parent).get()`).
// FORGE 26.3: no longer a no-op — getConfigScreen now supplies a real screen, me.shedaniel.autoconfig.gui
// .NativeConfigScreen. This class is the ONLY thing outside that package that names it, and only client code reaches
// this class (IPConfigGUI), so the GUI classes can never be asked for on a dedicated server; AutoConfig /
// ConfigManager / ConfigHolder / ConfigData, which a server does load, reference none of them.
package me.shedaniel.autoconfig;

import me.shedaniel.autoconfig.gui.NativeConfigScreen;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Supplier;

/**
 * Client half of the static entry point. Cloth Config has NO MinecraftForge build for any 26.x version
 * (maven.shedaniel.me: cloth-config-forge ends at 17.0.144 / MC 1.21.3; Modrinth lists no forge file for 26.x), so
 * on Forge there is no Cloth GUI to open. The F21 {@link AutoConfig#getConfigScreen} returned {@code null}, and this
 * class at first returned a supplier of the PARENT screen — which made the Forge mod list's "Config" button open the
 * screen the player was already on, i.e. do nothing (and the {@code /imm_ptl_client_debug config} command, whose
 * parent is {@code null}, open no screen at all).
 *
 * <p>FORGE 26.3: it now supplies {@link NativeConfigScreen}, a config screen built only from vanilla 26.3 GUI classes
 * and driven by reflection over the config class exactly as AutoConfig's GUI is driven by its annotations: the
 * {@code @Config} name picks the {@code text.autoconfig.<name>.*} lang keys, {@code @ConfigEntry.Category} makes the
 * pages, {@code @Gui.Excluded} hides a field, {@code @Gui.Tooltip} adds the tooltip, {@code @BoundedDiscrete} turns a
 * whole number into a slider, and the field type picks the widget (Yes/No toggle, enum cycle button, typed number or
 * text box). Edits are staged; Done writes them into the holder's live config and calls {@link ConfigHolder#save()}
 * — whose save listeners are what apply the config to the running game — and Cancel / Esc discard them.
 * Config load/save itself is still {@link AutoConfig#register} + {@link ConfigManager}; the one thing that moved
 * there is the order inside {@code save()} (listeners, then the file — Cloth's order; see the note in that method).
 */
public class AutoConfigClient {
    public static <T extends ConfigData> Supplier<Screen> getConfigScreen(Class<T> configClass, Screen parent) {
        // FORGE 26.3: as in real AutoConfig, the holder is resolved NOW (an unregistered class throws here, not inside
        // the supplier) and every get() builds a fresh screen from the config's values at that moment. A lambda, not
        // NativeConfigScreen::new — a constructor reference would link the GUI class when this method runs rather than
        // when a screen is actually wanted.
        ConfigHolder<T> holder = AutoConfig.getConfigHolder(configClass);
        return () -> new NativeConfigScreen(configClass, holder, parent);
    }
}
