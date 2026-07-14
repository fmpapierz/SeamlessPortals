// S10-A loader-seam compileOnly stub (entity-portal migration; migration/EXECUTION_PLAN.md
// §3 S10; port-note S10A-loader-seam.md). NOT IP source, NOT shipped. Faithful shell of the
// FabricLoader surface the HELD IP tree calls (O_O, MiscHelper, IPFeatureControl, IPMixinPlugin,
// IPPortingLibCompat, IPFlywheelCompat, RequiemCompat). DECISION (port-note §2): the FabricLoader
// family is resolved by this compileOnly stub, NOT by a PlatformHelper loader-info seam — O_O
// uses the full FabricLoader/ModContainer/Version/SemanticVersion surface (version parsing, mod
// metadata, game dir, icon paths), far beyond isModLoaded, so a minimal seam cannot cover it and
// full coverage would be a large held-file rewrite; the IP code runs only on Fabric, where the
// REAL FabricLoader is present at runtime (this stub is compileOnly, never shipped). :common
// compile classpath only. Removed at S20.
package net.fabricmc.loader.api;

import net.fabricmc.api.EnvType;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

public interface FabricLoader {
    static FabricLoader getInstance() {
        return null;
    }

    Optional<ModContainer> getModContainer(String id);

    Collection<ModContainer> getAllMods();

    boolean isModLoaded(String id);

    boolean isDevelopmentEnvironment();

    EnvType getEnvironmentType();

    Path getGameDir();
}
