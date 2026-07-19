package qouteall.imm_ptl.core.platform_specific;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * S19-B 1:1 port of IP:core/platform_specific/IPModMenuConfigEntry.java — the ModMenu
 * config-screen entrypoint. COMPILE SHAPE ONLY, deliberately NOT wired into fabric.mod.json.
 * Ground truth (corrected at S19-B): ModMenu itself EXISTS for 26.2 (modmenu:20.0.0-beta.4,
 * a real :fabric dep — the block-era {@code ModMenuIntegration} → {@code
 * SeamlessConfigScreen} is the live "modmenu" entrypoint in BOTH flag states, the D3
 * baseline). The blocker is CLOTH-CONFIG only: this class's screen chain is
 * {@code IPConfigGUI.createClothConfigScreen} → {@code AutoConfig.getConfigScreen(...).get()}
 * and the shipped F21 AutoConfig surface returns NULL from getConfigScreen (cloth-config's
 * GUI has no MC 26.2 build — see the AutoConfig no-op header), so wiring THIS entrypoint
 * would hand the user a guaranteed NPE on the config button. C2 re-entry: real cloth-config
 * dep replaces the AutoConfig no-op → swap this class into the fabric.mod.json "modmenu"
 * list (the block-era screen retires with the S20 block-era deletion). Compiles against the
 * fabricStubs ModMenu shells on :common/:neoforge; :fabric resolves the REAL ModMenu API.
 * Port-note S19 §4.
 */
public class IPModMenuConfigEntry implements ModMenuApi {

    public IPModMenuConfigEntry() {}

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return IPConfigGUI::createClothConfigScreen;
    }

}
