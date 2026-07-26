package qouteall.imm_ptl.core.platform_specific;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * S19-B 1:1 port of IP:core/platform_specific/IPModMenuConfigEntry.java — the ModMenu
 * config-screen entrypoint.
 *
 * <p><b>S20 INCREMENT 4 — THIS IS NOW THE WIRED ENTRYPOINT</b> ({@code fabric.mod.json}
 * {@code "modmenu"}). It replaces the block-era {@code ModMenuIntegration}, whose D3 flag-switch
 * chose between IP's Cloth screen (flag-ON) and the pure-vanilla {@code SeamlessConfigScreen}
 * (flag-OFF); both of those classes were deleted in the same commit. The swap is
 * behaviour-preserving on the surviving arm: {@code ModMenuIntegration}'s flag-ON branch returned
 * {@code parent -> IPConfigGUI.createClothConfigScreen(parent)}, byte-equivalent to the method
 * reference below.
 *
 * <p>The swap had to be ATOMIC with the flag's death, never landed early: IP's Cloth config is
 * registered by {@code AutoConfig.register(IPConfig.class, …)} at {@code IPModMain:146} ←
 * {@code IPModMain.init()}, which ran flag-ON only — pointing ModMenu here sooner would have
 * pointed it at an unregistered config (port-note §G.11).
 *
 * <p>The screen chain ({@code IPConfigGUI.createClothConfigScreen} →
 * {@code AutoConfigClient.getConfigScreen}) is backed by the real Cloth Config 26.2.155 dependency
 * since S19-E. Compiles against the fabricStubs ModMenu shells on {@code :common}/{@code :neoforge};
 * {@code :fabric} resolves the REAL ModMenu API. Port-note S19 §4 + §8.
 */
public class IPModMenuConfigEntry implements ModMenuApi {

    public IPModMenuConfigEntry() {}

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return IPConfigGUI::createClothConfigScreen;
    }

}
