// S10-A-family loader-seam compileOnly stub (entity-portal migration; S19-B, port-note
// S19-peripheral-tail §4). NOT IP source, NOT shipped. See ModMenuApi.java's header for the
// fabricStubs (real-on-:fabric, shell-on-:common/:neoforge) placement rationale. Faithful
// shell of ModMenu's ConfigScreenFactory as consumed by IPModMenuConfigEntry (the functional
// create(parent) shape; IP binds IPConfigGUI::createClothConfigScreen to it). Removed at S20.
package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screens.Screen;

@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {
    S create(Screen parent);
}
