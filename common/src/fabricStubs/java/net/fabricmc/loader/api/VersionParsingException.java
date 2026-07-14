// S10-A loader-seam compileOnly stub (entity-portal migration; port-note S10A-loader-seam.md).
// NOT IP source, NOT shipped. Checked exception thrown by Version.parse and caught in O_O.
// :common compile classpath only. Removed at S20.
package net.fabricmc.loader.api;

public class VersionParsingException extends Exception {
    public VersionParsingException(String message) {
        super(message);
    }
}
