package qouteall.imm_ptl.core.platform_specific;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * S19-B 1:1 port of IP:core/platform_specific/IPModMenuConfigEntry.java — the ModMenu
 * config-screen entrypoint. Deliberately NOT wired into fabric.mod.json.
 *
 * <p>S19-E UPDATE: the S19-B cloth-config blocker is RESOLVED — the real Cloth Config
 * 26.2.155 dep replaced the F21 AutoConfig no-op, and the screen chain
 * ({@code IPConfigGUI.createClothConfigScreen} → {@code AutoConfigClient.getConfigScreen})
 * is LIVE flag-ON via the D3 flag-switch in the fabric-side {@code ModMenuIntegration}
 * (which calls the identical chain). This class stays the 1:1 IP shape and stays UNWIRED
 * until S20: the fabric.mod.json "modmenu" entrypoint swaps to THIS class when the flag
 * dies and the block-era screen is deleted. Compiles against the fabricStubs ModMenu shells
 * on :common/:neoforge; :fabric resolves the REAL ModMenu API. Port-note S19 §4 + §8.
 */
public class IPModMenuConfigEntry implements ModMenuApi {

    public IPModMenuConfigEntry() {}

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return IPConfigGUI::createClothConfigScreen;
    }

}
