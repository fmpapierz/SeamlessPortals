// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. O_O.getImmPtlVersion does `version instanceof SemanticVersionImpl`
// then getVersionComponentCount()/getVersionComponent(int). Implements Version so the instanceof
// + downcast compile. :common compile classpath only. Removed at S20.
package net.fabricmc.loader.impl.util.version;

import net.fabricmc.loader.api.Version;

public class SemanticVersionImpl implements Version {
    @Override
    public String getFriendlyString() {
        return null;
    }

    @Override
    public int compareTo(Version other) {
        return 0;
    }

    public int getVersionComponentCount() {
        return 0;
    }

    public int getVersionComponent(int pos) {
        return 0;
    }
}
