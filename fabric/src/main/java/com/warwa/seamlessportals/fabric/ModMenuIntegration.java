package com.warwa.seamlessportals.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.warwa.seamlessportals.EntityPortalsFlag;
import com.warwa.seamlessportals.client.SeamlessConfigScreen; // NF-PARITY W19: moved to :common
import qouteall.imm_ptl.core.platform_specific.IPConfigGUI;

/**
 * Mod Menu entrypoint ("modmenu" in fabric.mod.json). Loaded only when Mod Menu is present.
 *
 * <p><b>D3 flag-switch (S19-E).</b> The config button routes on the entity-portal master
 * switch ({@link EntityPortalsFlag}):
 * <ul>
 *   <li>flag-ON → IP's real Cloth Config screen ({@link IPConfigGUI#createClothConfigScreen},
 *       now backed by the real Cloth Config 26.2.155 dependency — the F21 AutoConfig no-op was
 *       retired this stage, so {@code AutoConfigClient.getConfigScreen(IPConfig.class, parent)}
 *       builds a live screen; 26.2.155 split the screen entry out of {@code AutoConfig} into
 *       {@code AutoConfigClient}, the sole API drift of the swap);</li>
 *   <li>flag-OFF → the block-era pure-vanilla {@link SeamlessConfigScreen} (editing
 *       {@code SeamlessPortalsConfig}).</li>
 * </ul>
 *
 * <p>The flag-OFF branch is load-safe: {@code EntityPortalsFlag.isOn()} is a plain boolean read,
 * and the conditional only evaluates the SELECTED branch — flag-OFF the IPConfigGUI
 * ({@code @Environment(CLIENT)}) lambda is never formed, and even flag-ON that class carries no
 * static-init assuming flag-ON (it is a stateless static-method holder). Flag-ON, cloth's
 * {@code getConfigScreen} reads the same static holder {@code AutoConfig.register} populated in
 * IPModMain.loadConfig (which runs flag-ON only) — so no unregistered-config throw.
 *
 * <p>This collapses to {@link qouteall.imm_ptl.core.platform_specific.IPModMenuConfigEntry}
 * verbatim at S20 when the flag dies. IPModMenuConfigEntry stays the 1:1 IP class, deliberately
 * unwired until then (Port-note S19 §4).
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return EntityPortalsFlag.isOn()
            ? (parent -> IPConfigGUI.createClothConfigScreen(parent))
            : SeamlessConfigScreen::new;
    }
}
