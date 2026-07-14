// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. Members touched by the held tree: getMetadata() (O_O, IPFeatureControl),
// getContainingMod() (IPFeatureControl.isProvidedByJarInJar). :common compile classpath only.
// Removed at S20.
package net.fabricmc.loader.api;

import net.fabricmc.loader.api.metadata.ModMetadata;

import java.util.Optional;

public interface ModContainer {
    ModMetadata getMetadata();

    Optional<ModContainer> getContainingMod();
}
