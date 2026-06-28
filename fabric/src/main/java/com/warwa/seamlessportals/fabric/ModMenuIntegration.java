package com.warwa.seamlessportals.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu entrypoint ("modmenu" in fabric.mod.json). Returns the factory that
 * builds {@link SeamlessConfigScreen} when the user clicks the config button on
 * Seamless Portals' Mod Menu entry. Loaded only when Mod Menu is present.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SeamlessConfigScreen::new;
    }
}
