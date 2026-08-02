package com.warwa.seamlessportals.config;

import com.warwa.seamlessportals.portal.PortalType;

import java.util.EnumMap;
import java.util.Map;

public class SeamlessPortalsConfig {
    private static final SeamlessPortalsConfig INSTANCE = new SeamlessPortalsConfig();

    private final Map<PortalType, PortalTypeConfig> portalConfigs = new EnumMap<>(PortalType.class);

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
     * How many portal layers deep a portal-inside-a-portal renders with NO shaderpack (the stencil
     * renderer family). Feeds {@link qouteall.imm_ptl.core.IPGlobal#maxPortalLayer}, which is the
     * ENGINE bound: {@code PortalRenderer.renderPortalContent} refuses to render content past it, so
     * it also caps {@link #irisRecursionDepth} — raising the shader depth alone does nothing.
     *
     * <p>REPLACES the former {@code maxPortalRenderDepth}, which was a live slider in the config
     * screen with NO consumer anywhere in the tree: nothing outside its own getter/setter ever read
     * it, so moving it changed nothing. This one is wired.
     */
    private int vanillaRecursionDepth = qouteall.imm_ptl.core.IPGlobal.maxPortalLayer;

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
                String vrd = props.getProperty("vanillaRecursionDepth");
                if (vrd != null) {
                    try { INSTANCE.setVanillaRecursionDepth(Integer.parseInt(vrd.trim())); }
                    catch (NumberFormatException nfe) { /* keep default */ }
                }
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
            props.setProperty("vanillaRecursionDepth", String.valueOf(INSTANCE.vanillaRecursionDepth));
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
                    + "# vanillaRecursionDepth: how many portal layers deep a portal-inside-a-portal\n"
                    + "#   renders with NO shaderpack (0.." + MAX_RECURSION_DEPTH + ", default 5).\n"
                    + "#   This is the ENGINE bound and it also CAPS irisRecursionDepth below —\n"
                    + "#   raising the shader depth alone does nothing, because the engine refuses to\n"
                    + "#   render portal content past this value. Type any number you like.\n"
                    + "# irisRecursionDepth: the same thing WHEN A SHADERPACK IS ON (1.."
                    + MAX_RECURSION_DEPTH + ", default 5).\n"
                    + "#   Each extra layer is a FULL pack-shaded world render (gbuffer + shadow pass\n"
                    + "#   + composite chain), so it is far more expensive per layer than the\n"
                    + "#   shaders-off equivalent. Set to 1 for the old behaviour: a portal seen\n"
                    + "#   inside a portal is flat pass-through. Applies live; no restart needed.\n"
                    + "#   VRAM NOTE: with shaders on, each layer a scene ACTUALLY REACHES allocates\n"
                    + "#   its own full-screen colour+depth target (~16.6 MB at 1920x1080, ~66 MB at\n"
                    + "#   3840x2160). Allocation is lazy, so a high setting costs nothing until such\n"
                    + "#   a scene exists — but a genuinely 100-deep scene would hold ~1.7 GB at 1080p.\n"
                    + "#   Depths above " + DEEP_RECURSION_WARN_AT + " log a warning with the arithmetic.\n"
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

    /**
     * The hard ceiling on either recursion depth. Not a taste limit — a resource one.
     *
     * <p>The iris compat renderer allocates ONE full-screen colour+depth target PER LAYER
     * ({@code IrisCompatOn262Renderer.deferredFor}). At 1920x1080 that is roughly 16.6 MB a layer, so
     * depth 128 is about 2.1 GB of VRAM, and about 8.5 GB at 3840x2160. The array grows LAZILY, so a
     * high setting costs nothing until a scene actually recurses that deep — but if one does, it is
     * allocated for real. 128 is chosen as "absurdly high but not instantly fatal on a 24 GB card";
     * {@link #DEEP_RECURSION_WARN_AT} is where the log starts saying so.
     */
    public static final int MAX_RECURSION_DEPTH = 128;

    /** Above this, {@link #warnIfDeep} logs the VRAM arithmetic once per changed value. */
    public static final int DEEP_RECURSION_WARN_AT = 8;

    public int getIrisRecursionDepth() { return irisRecursionDepth; }

    public void setIrisRecursionDepth(int depth) {
        this.irisRecursionDepth = Math.max(1, Math.min(MAX_RECURSION_DEPTH, depth));
        warnIfDeep("irisRecursionDepth (shaderpack ON)", this.irisRecursionDepth);
    }

    public int getVanillaRecursionDepth() { return vanillaRecursionDepth; }

    /**
     * Sets the no-shaderpack depth AND pushes it into {@link qouteall.imm_ptl.core.IPGlobal#maxPortalLayer},
     * which is where the engine actually reads it. Writing only the config field would recreate
     * exactly the dead-knob defect this setting replaces.
     */
    public void setVanillaRecursionDepth(int depth) {
        this.vanillaRecursionDepth = Math.max(0, Math.min(MAX_RECURSION_DEPTH, depth));
        qouteall.imm_ptl.core.IPGlobal.maxPortalLayer = this.vanillaRecursionDepth;
        warnIfDeep("vanillaRecursionDepth (no shaderpack)", this.vanillaRecursionDepth);
    }

    private static int lastWarnedDepth = -1;

    /**
     * One line per changed value — loud enough to explain a VRAM cliff after the fact, quiet enough
     * not to spam a player who set 40 on purpose. Deliberately does NOT clamp: the value is the
     * user's decision, this only makes its cost legible.
     */
    private static void warnIfDeep(String which, int depth) {
        if (depth <= DEEP_RECURSION_WARN_AT || depth == lastWarnedDepth) {
            return;
        }
        lastWarnedDepth = depth;
        com.warwa.seamlessportals.SeamlessPortalsConstants.LOGGER.warn(
            "[SEAMLESS] {} set to {}. Each layer a scene actually reaches allocates its own"
                + " full-screen colour+depth target (~16.6 MB at 1920x1080, ~66 MB at 3840x2160),"
                + " so a scene that truly recurses {} deep would hold ~{} MB of them at 1080p."
                + " Buffers are allocated lazily, so this costs nothing until such a scene exists —"
                + " and every layer is also a full pack-shaded world render when shaders are on.",
            which, depth, depth, depth * 17);
    }

    public boolean isIrisRecursionLagGuard() { return irisRecursionLagGuard; }

    public void setIrisRecursionLagGuard(boolean on) { this.irisRecursionLagGuard = on; }


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
