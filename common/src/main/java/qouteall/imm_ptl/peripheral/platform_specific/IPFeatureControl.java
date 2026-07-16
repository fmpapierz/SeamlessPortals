package qouteall.imm_ptl.peripheral.platform_specific;

import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;

public class IPFeatureControl {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static boolean isProvidedByJarInJar() {
        // SELF-IDENTITY RE-HOST (S13 first-light fix): IP looks up its own mod container by
        // its mod id "iportal"; this port ships as "seamlessportals" (same semantics — is OUR
        // jar nested inside another mod). Registry/asset namespaces stay verbatim; loader
        // self-lookups must use the host mod id or they throw at init.
        ModContainer modContainer = FabricLoader.getInstance()
            .getModContainer("seamlessportals")
            .orElseThrow(() -> new RuntimeException("seamlessportals mod not found"));
        
        return modContainer.getContainingMod().isPresent();
    }
    
    public static boolean enableVanillaBehaviorChangingByDefault() {
        return !isProvidedByJarInJar();
    }
}
