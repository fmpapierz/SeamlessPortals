package qouteall.imm_ptl.peripheral.platform_specific;

import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.platform.Platform;
import org.slf4j.Logger;

public class IPFeatureControl {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static boolean isProvidedByJarInJar() {
        // SELF-IDENTITY RE-HOST (S13 first-light fix): IP looks up its own mod container by
        // its mod id "iportal"; this port ships as "seamlessportals" (same semantics — is OUR
        // jar nested inside another mod). Registry/asset namespaces stay verbatim; loader
        // self-lookups must use the host mod id or they throw at init.
        // NF-PARITY W8: relaxation — the old code threw when the container was missing; isModNested returns false.
        return Platform.get().isModNested("seamlessportals");
    }
    
    public static boolean enableVanillaBehaviorChangingByDefault() {
        return !isProvidedByJarInJar();
    }
}
