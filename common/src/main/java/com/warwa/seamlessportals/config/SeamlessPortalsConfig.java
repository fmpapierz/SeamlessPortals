package com.warwa.seamlessportals.config;

import com.warwa.seamlessportals.portal.PortalType;

import java.util.EnumMap;
import java.util.Map;

public class SeamlessPortalsConfig {
    private static final SeamlessPortalsConfig INSTANCE = new SeamlessPortalsConfig();

    private final Map<PortalType, PortalTypeConfig> portalConfigs = new EnumMap<>(PortalType.class);

    private int maxPortalRenderDepth = 1;
    private int portalRenderDistance = 8;
    private boolean enablePortalRendering = true;

    private int portalFramebufferScale = 100;
    private boolean enableChunkCaching = true;
    // T3 (experimental, off by default): give portal SECONDARY levels an unbounded chunk
    // store so destination chunks are never dropped at the radius cap → no far-ring reload
    // (re-decode + re-mesh) on crossing. Memory cost; see SeamlessClientChunkMap.
    private boolean unboundedClientChunkStore = false;
    private int maxRemoteChunksPerPortal = 64;

    private boolean seamlessTeleportation = true;
    private boolean projectilePassThrough = true;
    private int cameraSmoothingTicks = 5;

    private SeamlessPortalsConfig() {
        portalConfigs.put(PortalType.NETHER, new PortalTypeConfig(true));
        portalConfigs.put(PortalType.END, new PortalTypeConfig(true));
        portalConfigs.put(PortalType.CUSTOM, new PortalTypeConfig(true));
    }

    public static SeamlessPortalsConfig get() {
        return INSTANCE;
    }

    /**
     * Load the configurable knob(s) from {@code <configDir>/seamlessportals.properties}
     * (creating the file with current defaults if absent), then re-write it so it
     * always reflects the live values. Called once at mod init. The headline knob is
     * {@code portalRenderDistance} — the IP-style dest loading/mesh depth.
     */
    public static void loadFrom(java.nio.file.Path configDir) {
        java.nio.file.Path file = configDir.resolve("seamlessportals.properties");
        if (java.nio.file.Files.exists(file)) {
            java.util.Properties props = new java.util.Properties();
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
                props.load(in);
                String d = props.getProperty("portalRenderDistance");
                if (d != null) INSTANCE.setPortalRenderDistance(Integer.parseInt(d.trim()));
                String depth = props.getProperty("maxPortalRenderDepth");
                if (depth != null) INSTANCE.setMaxPortalRenderDepth(Integer.parseInt(depth.trim()));
                String en = props.getProperty("enablePortalRendering");
                if (en != null) INSTANCE.setEnablePortalRendering(Boolean.parseBoolean(en.trim()));
                String unb = props.getProperty("unboundedClientChunkStore");
                if (unb != null) INSTANCE.unboundedClientChunkStore = Boolean.parseBoolean(unb.trim());
            } catch (Exception e) {
                com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.warn(
                    "[SEAMLESS] Failed to read config, using defaults: {}", e.toString());
            }
        }
        saveTo(configDir);
    }

    /** Persist the current knob values to {@code seamlessportals.properties}. */
    public static void saveTo(java.nio.file.Path configDir) {
        try {
            java.nio.file.Files.createDirectories(configDir);
            java.nio.file.Path file = configDir.resolve("seamlessportals.properties");
            java.util.Properties props = new java.util.Properties();
            props.setProperty("portalRenderDistance", String.valueOf(INSTANCE.portalRenderDistance));
            props.setProperty("maxPortalRenderDepth", String.valueOf(INSTANCE.maxPortalRenderDepth));
            props.setProperty("enablePortalRendering", String.valueOf(INSTANCE.enablePortalRendering));
            props.setProperty("unboundedClientChunkStore", String.valueOf(INSTANCE.unboundedClientChunkStore));
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(file)) {
                props.store(out,
                    " Seamless Portals config\n"
                    + "# portalRenderDistance: how many chunks deep the portal DESTINATION is kept\n"
                    + "#   loaded + meshed (clamped 1..32; default 8 = Immersive Portals' default).\n"
                    + "#   Higher = more seamless crossing (less far-chunk reload) but MORE memory\n"
                    + "#   (~depth^2 chunks held + meshed per portal) and longer prep. At very high\n"
                    + "#   render distances large values can run you out of memory.\n"
                    + "#   Tip: look at the portal until the view fills in, then cross. Edit this\n"
                    + "#   value and restart to change it.");
            }
        } catch (Exception e) {
            com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.warn(
                "[SEAMLESS] Failed to write config: {}", e.toString());
        }
    }

    public static boolean isImmersive(PortalType type) {
        PortalTypeConfig config = INSTANCE.portalConfigs.get(type);
        return config != null && config.isImmersive();
    }

    public static boolean shouldRenderThrough(PortalType type) {
        if (!INSTANCE.enablePortalRendering) return false;
        PortalTypeConfig config = INSTANCE.portalConfigs.get(type);
        return config != null && config.isRenderThrough();
    }

    public static boolean shouldSeamlessTeleport(PortalType type) {
        if (!INSTANCE.seamlessTeleportation) return false;
        PortalTypeConfig config = INSTANCE.portalConfigs.get(type);
        return config != null && config.isSeamlessTeleport();
    }

    public static boolean shouldProjectilePassThrough(PortalType type) {
        if (!INSTANCE.projectilePassThrough) return false;
        PortalTypeConfig config = INSTANCE.portalConfigs.get(type);
        return config != null && config.isProjectilePassThrough();
    }

    public PortalTypeConfig getPortalConfig(PortalType type) {
        return portalConfigs.get(type);
    }

    public int getMaxPortalRenderDepth() { return maxPortalRenderDepth; }
    public void setMaxPortalRenderDepth(int depth) { this.maxPortalRenderDepth = Math.max(0, Math.min(3, depth)); }

    /**
     * The destination loading/mesh DEPTH cap (chunks) — the analogue of IP's
     * {@code indirectLoadingRadiusCap}. The dest is kept loaded + meshed up to this
     * deep around the portal (graduated by distance, IP-style), so on crossing those
     * chunks don't reload. Default 8 = exactly IP's default. Raise toward your render
     * distance for a more seamless (but heavier — ~depth² chunks held + meshed per
     * portal) crossing; IP clamps the same knob to 1..32.
     */
    public int getPortalRenderDistance() { return portalRenderDistance; }
    public void setPortalRenderDistance(int distance) { this.portalRenderDistance = Math.max(1, Math.min(32, distance)); }

    public boolean isEnablePortalRendering() { return enablePortalRendering; }
    public void setEnablePortalRendering(boolean enable) { this.enablePortalRendering = enable; }

    /** T3 experimental knob: unbounded secondary chunk store (no far-ring reload on crossing). */
    public boolean isUnboundedClientChunkStore() { return unboundedClientChunkStore; }
    public void setUnboundedClientChunkStore(boolean enable) { this.unboundedClientChunkStore = enable; }

    public int getPortalFramebufferScale() { return portalFramebufferScale; }
    public void setPortalFramebufferScale(int scale) { this.portalFramebufferScale = Math.max(25, Math.min(100, scale)); }

    public boolean isEnableChunkCaching() { return enableChunkCaching; }
    public int getMaxRemoteChunksPerPortal() { return maxRemoteChunksPerPortal; }

    public boolean isSeamlessTeleportation() { return seamlessTeleportation; }
    public boolean isProjectilePassThrough() { return projectilePassThrough; }
    public int getCameraSmoothingTicks() { return cameraSmoothingTicks; }
}
