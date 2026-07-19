// S10-A-family loader-seam compileOnly stub (entity-portal migration; port-note
// S19-peripheral-tail.md). NOT IP source, NOT shipped. Faithful shell of fabric-api
// 0.152.1+26.2's fabric-creative-tab-api-v1 FabricCreativeModeTab as far as the ported tree
// consumes it: the static builder() returning the VANILLA CreativeModeTab.Builder (the chained
// .icon/.title/.displayItems/.build calls resolve against vanilla). This module is the 26.2
// successor of fabric-item-group-api-v1 (FabricItemGroup.builder() — IP's original import,
// GONE on 26.2); first consumer: PeripheralModMain.TAB (S19-A creative tab). :common compile
// classpath only; the REAL FabricCreativeModeTab resolves on :fabric. Removed at S20.
package net.fabricmc.fabric.api.creativetab.v1;

import net.minecraft.world.item.CreativeModeTab;

public final class FabricCreativeModeTab {
    private FabricCreativeModeTab() {}

    public static CreativeModeTab.Builder builder() {
        throw new AssertionError("compileOnly stub — never on the runtime classpath");
    }
}
