package qouteall.imm_ptl.core.platform_specific;

import me.shedaniel.autoconfig.AutoConfigClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screens.Screen;

@Environment(EnvType.CLIENT)
public class IPConfigGUI {
    public static Screen createClothConfigScreen(Screen parent) {
        // S19-E version-forced deviation from the verbatim IP 1.21.3 source (which calls
        // AutoConfig.getConfigScreen). In real Cloth Config 26.2.155 the client GUI entry point
        // was split out of the common `AutoConfig` class into `AutoConfigClient` — javap
        // (cloth-config-26.2.155.jar):
        //   AutoConfig            -> register / getConfigHolder only (no getConfigScreen)
        //   AutoConfigClient      -> Supplier<Screen> getConfigScreen(Class<T>, Screen)
        // Same method name / params / Supplier<Screen> return, only the owning class changed, so
        // `.get()` is preserved 1:1. Client-only reference — this class is @Environment(CLIENT).
        return AutoConfigClient.getConfigScreen(IPConfig.class, parent).get();
    }
}
