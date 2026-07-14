// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. Members touched by the held tree: static parse(String) (O_O),
// compareTo(Version) (O_O), toString() (O_O, inherited from Object). Extends Comparable<Version>
// so version.compareTo(other) resolves. :common compile classpath only. Removed at S20.
package net.fabricmc.loader.api;

public interface Version extends Comparable<Version> {
    String getFriendlyString();

    static Version parse(String string) throws VersionParsingException {
        return null;
    }
}
