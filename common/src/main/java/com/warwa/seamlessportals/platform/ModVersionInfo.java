package com.warwa.seamlessportals.platform;

/**
 * NF-PARITY W8 (2026-08-25): loader-neutral view of a mod's version, replacing the
 * {@code net.fabricmc.loader.api.Version} / {@code SemanticVersionImpl} surface in
 * {@code :common}.
 *
 * <p>{@code isRegularSemantic} is true when the version has exactly the plain
 * {@code major.minor.patch} shape IP's network handshake serializes
 * ({@code ImmPtlNetworkConfig.ModVersion}):
 * <ul>
 *   <li>Fabric: the version is a {@code SemanticVersionImpl} with
 *       {@code getVersionComponentCount() == 3} (and no prerelease/build suffix);</li>
 *   <li>NeoForge: {@code DefaultArtifactVersion} with {@code getBuildNumber() == 0} and
 *       {@code getQualifier() == null}.</li>
 * </ul>
 * When false (e.g. the literal {@code ${version}} placeholder in a Fabric dev env),
 * callers fall back to {@code ModVersion.OTHER} exactly as before.
 *
 * @param major   major component (0 when not regular)
 * @param minor   minor component (0 when not regular)
 * @param patch   patch component (0 when not regular)
 * @param isRegularSemantic whether the three components above are meaningful
 * @param raw     the loader's own {@code toString()} of the version, for display
 */
public record ModVersionInfo(
    int major, int minor, int patch, boolean isRegularSemantic, String raw
) {
    @Override
    public String toString() {
        return raw;
    }
}
