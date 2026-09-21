// F21 Cloth-Config / AutoConfig — FORGE-MODULE functional no-op (see ConfigData.java header). 26.3 addition to the
// recovered F21 set: real Cloth Config 26.x moved getConfigScreen from AutoConfig to AutoConfigClient, and
// qouteall.imm_ptl.core.platform_specific.IPConfigGUI calls it there
// (`AutoConfigClient.getConfigScreen(IPConfig.class, parent).get()`).
package me.shedaniel.autoconfig;

import net.minecraft.client.gui.screens.Screen;

import java.util.function.Supplier;

/**
 * Client half of the static entry point. Cloth Config has NO MinecraftForge build for any 26.x version
 * (maven.shedaniel.me: cloth-config-forge ends at 17.0.144 / MC 1.21.3; Modrinth lists no forge file for 26.x), so
 * on Forge there is no config GUI to open. The F21 {@link AutoConfig#getConfigScreen} returned {@code null}; the
 * caller here dereferences the supplier immediately, so this returns a supplier of the PARENT screen instead —
 * opening "the config screen" simply leaves the player where they were. Config load/save is unaffected: that is
 * {@link AutoConfig#register} + {@link ConfigManager}, fully functional.
 */
public class AutoConfigClient {
    public static <T extends ConfigData> Supplier<Screen> getConfigScreen(Class<T> configClass, Screen parent) {
        return () -> parent;
    }
}