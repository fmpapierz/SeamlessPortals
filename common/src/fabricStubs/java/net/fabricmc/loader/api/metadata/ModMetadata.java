// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. Members touched by the held tree via ModContainer.getMetadata():
// getVersion()/getName()/getId()/getIconPath(int) (O_O), getVersion() (IPFeatureControl chain).
// :common compile classpath only. Removed at S20.
package net.fabricmc.loader.api.metadata;

import net.fabricmc.loader.api.Version;

import java.util.Optional;

public interface ModMetadata {
    String getId();

    String getName();

    Version getVersion();

    Optional<String> getIconPath(int size);
}
