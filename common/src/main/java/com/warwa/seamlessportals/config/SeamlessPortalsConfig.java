package com.warwa.seamlessportals.config;

import com.warwa.seamlessportals.portal.PortalType;

import java.util.EnumMap;
import java.util.Map;

public class SeamlessPortalsConfig {
    private static final SeamlessPortalsConfig INSTANCE = new SeamlessPortalsConfig();

    private final Map<PortalType, PortalTypeConfig> portalConfigs = new EnumMap<>(PortalType.class);

    private int maxPortalRenderDepth = 1;
    private int portalRenderDistance = 8;
    /** "auto" = IP-style graduated dest depth by portal distance; false = fixed full {@link #portalRenderDistance}. */
    private boolean autoRenderDistance = true;
    /** Dest ENTITY streaming radius (chunks). -1 = max (== {@link #getPortalRenderDistance()}). */
    private int entityLoadDistanceChunks = -1;
    private boolean enablePortalRendering = true;
    /**
     * Speculative pre-warming: when a valid UNLIT obsidian frame exists near a player, pre-load +
     * pre-stream + pre-mesh its expected destination region BEFORE ignition — lighting the portal
     * reveals an already-prepared view. Costs speculative server work for frames never lit.
     */
    private boolean speculativePrewarm = true;

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

    // ===== IS5-REC — recursive portal views WITH A SHADERPACK ON =============================
    /**
     * How many portal layers deep a portal-inside-a-portal renders when an iris shaderpack is
     * ACTIVE. Default 5, matching the engine's shaders-OFF bound, user-decided 2026-08-02 after the
     * depth-5 leg came back mechanically clean (maxPortalDepth=5, deferredPeak=4, guards in step,
     * zero budget cuts, no anomalies).
     *
     * <p>SEPARATE knob from the shaders-OFF depth on purpose: layer <i>n</i> shaders-ON is a full
     * pack-shaded world render — gbuffer, shadow pass, the whole composite chain — so it is a
     * categorically heavier unit than a stencil-family layer. 1 = one layer, i.e. the behaviour
     * before this feature (a portal seen inside a portal is flat pass-through).
     */
    private int irisRecursionDepth = 5;

    /**
     * OFF by default (user-decided 2026-08-02). When ON, the shaders-ON recursion depth is reduced
     * automatically while the frame rate is low.
     *
     * <p>This exists because the engine's own mirror-room lag protection CANNOT cover this case:
     * {@code RenderStates.updateIsLaggy} only looks at the frame rate once more than 10 dest renders
     * happened in the previous frame, and a deep single chain produces about one render per layer —
     * five or six, never eleven. So a depth-5 chain can make frames arbitrarily expensive without
     * that guard ever arming. MEASURED: the depth-5 leg reported {@code isLaggy=false} on all 166
     * rows, which proves only that the gate could not fire, not that the frame rate was fine.
     */
    private boolean irisRecursionLagGuard = false;

    private SeamlessPortalsConfig() {
        portalConfigs.put(PortalType.NETHER, new PortalTypeConfig(true));
        portalConfigs.put(PortalType.END, new PortalTypeConfig(true));
        portalConfigs.put(PortalType.CUSTOM, new PortalTypeConfig(true));
    }

    public static SeamlessPortalsConfig get() {
        return INSTANCE;
    }

    /**
     * The entity-portal migration MASTER SWITCH (D3). {@code true} (<b>the default since the
     * S17 cutover flip, 2026-07-18</b>) = the ported Immersive-Portals entity-portal driver set
     * runs; {@code false} = the block-era driver set runs, byte-for-byte unchanged (two-way
     * switch until S20). Load-time, read ONCE (see {@link com.warwa.seamlessportals.EntityPortalsFlag}
     * for the mechanism and the mixin-plugin/runtime-gate consistency contract). Every
     * {@code !entityPortals} gate in mod-owned driver code, and the IP-mixin gating in
     * {@code SeamlessMixinConfigPlugin}, read this same value.
     */
    public static boolean isEntityPortals() {
        return com.warwa.seamlessportals.EntityPortalsFlag.isOn();
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
                if (d != null) {
                    String dt = d.trim();
                    if (dt.equalsIgnoreCase("auto")) {
                        // "auto" → IP-style GRADUATED depth (full within 5 blocks of the portal,
                        // ⅔ within 15, ⅓ beyond), capped at the max so it reaches your render
                        // distance up close. A NUMBER overrides the graduation: fixed full depth.
                        INSTANCE.autoRenderDistance = true;
                        INSTANCE.setPortalRenderDistance(32);
                    } else {
                        try {
                            INSTANCE.setPortalRenderDistance(Integer.parseInt(dt));
                            INSTANCE.autoRenderDistance = false; // explicit value = fixed, no graduation
                        } catch (NumberFormatException nfe) {
                            INSTANCE.autoRenderDistance = true; // unparseable → auto
                        }
                    }
                }
                String eld = props.getProperty("entityLoadDistance");
                if (eld != null) {
                    String et = eld.trim();
                    if (et.equalsIgnoreCase("max")) {
                        INSTANCE.entityLoadDistanceChunks = -1; // -1 = max (== render distance)
                    } else {
                        try { INSTANCE.entityLoadDistanceChunks = Integer.parseInt(et); }
                        catch (NumberFormatException nfe) { /* keep default */ }
                    }
                }
                String depth = props.getProperty("maxPortalRenderDepth");
                if (depth != null) INSTANCE.setMaxPortalRenderDepth(Integer.parseInt(depth.trim()));
                String en = props.getProperty("enablePortalRendering");
                if (en != null) INSTANCE.setEnablePortalRendering(Boolean.parseBoolean(en.trim()));
                String unb = props.getProperty("unboundedClientChunkStore");
                if (unb != null) INSTANCE.unboundedClientChunkStore = Boolean.parseBoolean(unb.trim());
                String spec = props.getProperty("speculativePrewarm");
                if (spec != null) INSTANCE.speculativePrewarm = Boolean.parseBoolean(spec.trim());
                String ird = props.getProperty("irisRecursionDepth");
                if (ird != null) {
                    try { INSTANCE.setIrisRecursionDepth(Integer.parseInt(ird.trim())); }
                    catch (NumberFormatException nfe) { /* keep default */ }
                }
                String irlg = props.getProperty("irisRecursionLagGuard");
                if (irlg != null) INSTANCE.irisRecursionLagGuard = Boolean.parseBoolean(irlg.trim());
                // Entity-portal migration master switch (D3). Seed the load-time flag from the same
                // file the mixin plugin reads, so the two never disagree within a session. seedIfUnset
                // is a no-op if the plugin already resolved it (read-once semantics).
                String ep = props.getProperty("entityPortals");
                if (ep != null) {
                    com.warwa.seamlessportals.EntityPortalsFlag.seedIfUnset(Boolean.parseBoolean(ep.trim()));
                }
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
            props.setProperty("portalRenderDistance",
                INSTANCE.autoRenderDistance ? "auto" : String.valueOf(INSTANCE.portalRenderDistance));
            props.setProperty("entityLoadDistance",
                INSTANCE.entityLoadDistanceChunks < 0 ? "max" : String.valueOf(INSTANCE.entityLoadDistanceChunks));
            props.setProperty("maxPortalRenderDepth", String.valueOf(INSTANCE.maxPortalRenderDepth));
            props.setProperty("enablePortalRendering", String.valueOf(INSTANCE.enablePortalRendering));
            props.setProperty("unboundedClientChunkStore", String.valueOf(INSTANCE.unboundedClientChunkStore));
            props.setProperty("speculativePrewarm", String.valueOf(INSTANCE.speculativePrewarm));
            props.setProperty("entityPortals", String.valueOf(isEntityPortals()));
            props.setProperty("irisRecursionDepth", String.valueOf(INSTANCE.irisRecursionDepth));
            props.setProperty("irisRecursionLagGuard", String.valueOf(INSTANCE.irisRecursionLagGuard));
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(file)) {
                props.store(out,
                    " Seamless Portals config\n"
                    + "# entityPortals: MASTER SWITCH. true (the default) = the Immersive-Portals\n"
                    + "#   entity-portal engine — seamless see-through portals, entity crossings,\n"
                    + "#   recursion, obsidian-frame generation. false = the classic block-portal\n"
                    + "#   system (returns everything to the pre-engine behavior). LOAD-TIME: edit\n"
                    + "#   and RESTART the game to change it.\n"
                    + "# portalRenderDistance: how many chunks deep the portal DESTINATION is kept\n"
                    + "#   loaded + meshed. Set to \"auto\" for IP-style GRADUATED depth (full near the\n"
                    + "#   portal, less as you back away — cheaper, the default), OR a number 1..32 to\n"
                    + "#   FIX the depth (the full value is shown in the portal window regardless of\n"
                    + "#   distance — heavier, ~depth^2 chunks held + meshed per portal). 8 = IP default.\n"
                    + "# entityLoadDistance: chunks around the dest portal within which destination\n"
                    + "#   entities are streamed so they show + move in the portal view. \"max\" (default)\n"
                    + "#   = the render distance; a smaller number limits it (fewer entities/packets).\n"
                    + "#   Edit and restart to change.\n"
                    + "# irisRecursionDepth: how many portal layers deep a portal-inside-a-portal\n"
                    + "#   renders WHEN A SHADERPACK IS ON (1..5, default 5). Each extra layer is a\n"
                    + "#   FULL pack-shaded world render (gbuffer + shadow pass + composite chain), so\n"
                    + "#   this is much more expensive per layer than the shaders-off equivalent.\n"
                    + "#   Set to 1 for the old behaviour: a portal seen inside a portal is flat\n"
                    + "#   pass-through. Applies live; no restart needed.\n"
                    + "# irisRecursionLagGuard: false (default) = the depth above is always used.\n"
                    + "#   true = drop to fewer layers automatically while the frame rate is low. The\n"
                    + "#   engine's own mirror-room lag protection CANNOT cover deep recursion (it only\n"
                    + "#   checks the frame rate after >10 destination renders in a frame, and a deep\n"
                    + "#   single chain makes about one per layer), so this is the only automatic\n"
                    + "#   protection for this case.");
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

    public int getIrisRecursionDepth() { return irisRecursionDepth; }

    public void setIrisRecursionDepth(int depth) {
        this.irisRecursionDepth = Math.max(1, Math.min(5, depth));
    }

    public boolean isIrisRecursionLagGuard() { return irisRecursionLagGuard; }

    public void setIrisRecursionLagGuard(boolean on) { this.irisRecursionLagGuard = on; }

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

    /**
     * When true ("auto"), the dest loading/mesh depth is GRADUATED by the player's distance to the
     * portal (full RD within 5 blocks, ⅔ within 15, ⅓ beyond — IP-faithful, cheaper). When false (a
     * numeric portalRenderDistance), the depth is FIXED at {@link #getPortalRenderDistance()} always
     * — the full configured render distance is shown in the portal window regardless of distance
     * (heavier).
     */
    public boolean isAutoRenderDistance() { return autoRenderDistance; }
    public void setAutoRenderDistance(boolean auto) { this.autoRenderDistance = auto; }

    /**
     * Dest ENTITY streaming radius in chunks. Entities in the destination dimension within this
     * many chunks of the dest portal are mirrored to the client so they show (and move) in the
     * portal view. Defaults to {@code max} (== the render distance) so entities load as far as the
     * terrain does; set a smaller number to limit it.
     */
    public int getEntityLoadDistanceChunks() {
        return entityLoadDistanceChunks < 0 ? getPortalRenderDistance() : entityLoadDistanceChunks;
    }

    public boolean isEnablePortalRendering() { return enablePortalRendering; }
    public void setEnablePortalRendering(boolean enable) { this.enablePortalRendering = enable; }

    /** Speculative pre-warming of unlit valid frames' expected destinations (see field doc). */
    public boolean isSpeculativePrewarm() { return speculativePrewarm; }
    public void setSpeculativePrewarm(boolean enable) { this.speculativePrewarm = enable; }

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
