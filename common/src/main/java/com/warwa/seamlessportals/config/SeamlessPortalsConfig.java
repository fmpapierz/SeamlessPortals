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

    public int getPortalRenderDistance() { return portalRenderDistance; }
    public void setPortalRenderDistance(int distance) { this.portalRenderDistance = Math.max(1, Math.min(16, distance)); }

    public boolean isEnablePortalRendering() { return enablePortalRendering; }
    public void setEnablePortalRendering(boolean enable) { this.enablePortalRendering = enable; }

    public int getPortalFramebufferScale() { return portalFramebufferScale; }
    public void setPortalFramebufferScale(int scale) { this.portalFramebufferScale = Math.max(25, Math.min(100, scale)); }

    public boolean isEnableChunkCaching() { return enableChunkCaching; }
    public int getMaxRemoteChunksPerPortal() { return maxRemoteChunksPerPortal; }

    public boolean isSeamlessTeleportation() { return seamlessTeleportation; }
    public boolean isProjectilePassThrough() { return projectilePassThrough; }
    public int getCameraSmoothingTicks() { return cameraSmoothingTicks; }
}
