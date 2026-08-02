package qouteall.imm_ptl.core.platform_specific;

import com.google.gson.JsonObject;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.peripheral.platform_specific.IPFeatureControl;
import qouteall.q_misc_util.Helper;

import java.util.HashSet;

@Config(name = "immersive_portals")
public class IPConfig implements ConfigData {
    // json does not allow comments...
    @ConfigEntry.Gui.Excluded
    public String check_the_wiki_for_more_information = "https://qouteall.fun/immptl/wiki/Config-Options";
    
    // client visible configs
    
    // IS5-REC (2026-08-02): @ConfigEntry.BoundedDiscrete(min = 0, max = 10) REMOVED. Cloth renders a
    // bounded int as a SLIDER and an unbounded one as a TYPED FIELD, and the bound was the only
    // thing preventing values like 20 or 100 — which the user explicitly asked for. Free-typed here
    // and in config/immersive_portals.json.
    //
    // THIS IS THE ENGINE BOUND and the single source of truth for it. A parallel
    // seamlessportals.properties knob was briefly added and is now DELETED: onConfigChanged below
    // writes IPGlobal.maxPortalLayer from IPModMain.init, which runs AFTER
    // SeamlessPortalsConfig.loadFrom, so the other knob was overwritten every boot while its file
    // kept reporting the value the user set. Two writers, one static, no arbitration — do not
    // reintroduce one.
    @ConfigEntry.Category("client")
    @ConfigEntry.Gui.Tooltip
    public int maxPortalLayer = 5;

    /**
     * IS5-REC — recursion depth WHEN AN IRIS SHADERPACK IS ON. Separate from
     * {@link #maxPortalLayer} because a shaders-ON layer is a FULL pack-shaded world render
     * (gbuffer + shadow pass + composite chain), a categorically heavier unit than a stencil layer.
     * 1 = the pre-feature behaviour (a portal seen inside a portal is flat pass-through).
     *
     * <p>CAPPED BY {@link #maxPortalLayer}: the engine refuses to render portal content past that
     * ({@code PortalRenderer.renderPortalContent}), so raising this alone does nothing — raise both.
     *
     * <p>VRAM: each layer a scene ACTUALLY REACHES allocates its own full-screen colour+depth target
     * (~16.6 MB at 1920x1080, ~66 MB at 3840x2160). Allocation is lazy, so a high value costs
     * nothing until such a scene exists; a genuinely 100-deep one would hold ~1.7 GB at 1080p.
     */
    @ConfigEntry.Category("client")
    @ConfigEntry.Gui.Tooltip
    public int irisRecursionDepth = 5;

    /**
     * IS5-REC — OFF by default (user-decided 2026-08-02). Reduces the shaders-ON recursion depth
     * automatically while the frame rate is low.
     *
     * <p>Exists because the engine's own mirror-room protection CANNOT cover deep recursion:
     * {@code RenderStates.updateIsLaggy} only consults the frame rate once >10 dest renders happened
     * in the previous frame, and a deep SINGLE chain makes about one render per layer — five or six,
     * never eleven. MEASURED: the depth-5 leg reported {@code isLaggy=false} on all 166 rows, which
     * proves only that the gate could not fire, not that the frame rate was fine.
     */
    @ConfigEntry.Category("client")
    @ConfigEntry.Gui.Tooltip
    public boolean irisRecursionLagGuard = false;

    /**
     * How far from the player a portal will still render its WINDOW, in chunks. {@code 0} = follow
     * the vanilla render distance, which is the behaviour this mod has always had and what the
     * per-entry reset button restores.
     *
     * <p>Consumed by {@code PortalRenderer.getRenderRange}, whose sole consumer is
     * {@code shouldSkipRenderingPortal}: a portal further than this from the camera is culled and
     * its window is not drawn. Raising it means distant portals keep showing their destination;
     * lowering it culls them sooner and is the cheapest way to claw back frames in a portal-dense
     * build.
     *
     * <p>DISTINCT from {@code portalRenderDistance} in seamlessportals.properties, which controls how
     * many chunks DEEP the destination is loaded and meshed. This one is how far AWAY you can stand
     * and still see the window at all. Setting this high while that stays low gives you distant
     * windows onto a shallow destination.
     *
     * <p>Unbounded in the GUI so it is a typed field rather than a slider, but clamped to 0..32 on
     * apply — 32 is the vanilla render-distance maximum, past which the source chunks the culling is
     * measured against do not exist anyway. The downstream deep-layer divide and the large-scale
     * portal multiplier in getRenderRange still apply on top, unchanged.
     */
    @ConfigEntry.Category("client")
    @ConfigEntry.Gui.Tooltip
    public int portalWindowRenderDistance = 0;
    @ConfigEntry.Category("client")
    @ConfigEntry.Gui.Tooltip
    public boolean lagAttackProof = true;
    @ConfigEntry.Category("client")
    public boolean enableCrossPortalSound = true;
    @ConfigEntry.Category("client")
    @ConfigEntry.Gui.Tooltip
    public boolean pureMirror = false;
    @ConfigEntry.Category("client")
    public boolean renderYourselfInPortal = true;
    @ConfigEntry.Category("client")
    @ConfigEntry.Gui.Tooltip
    public boolean correctCrossPortalEntityRendering = true;
    // S12-A (Slice C) — the R3 cross-portal entity clip DELIVERY mechanism, the C4-rider A/B switch persisted
    // to the in-game config screen (closes the S11-C-deferred IPConfig wiring; S11-R3 §5, S11C §1.2 / §7.2 V1
    // P3). Mirrors the correctCrossPortalEntityRendering template above (client category + Tooltip) with an
    // EnumHandler for the enum, exactly like netherPortalMode/endPortalMode below. The FQN mirrors IPGlobal's
    // server-safe reference style: naming the nested PerEntityClipBracket.Mechanism constant never force-loads
    // the client-only PerEntityClipBracket render class (the enum class file carries no client dependency in
    // its <clinit>) — the same discipline IPGlobal.crossPortalEntityClipMechanism already ships.
    @ConfigEntry.Category("client")
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
    @ConfigEntry.Gui.Tooltip
    public qouteall.imm_ptl.core.render.PerEntityClipBracket.Mechanism crossPortalEntityClipMechanism =
        qouteall.imm_ptl.core.render.PerEntityClipBracket.Mechanism.SUBMIT_ORDER_UNIFORM;
    @ConfigEntry.Category("client")
    public boolean reducedPortalRendering = false;
    @ConfigEntry.Category("client")
    public boolean netherPortalOverlay = false;
    @ConfigEntry.Category("client")
    public boolean enableNetherPortalEffect = true;
    @ConfigEntry.Category("client")
    @ConfigEntry.Gui.Tooltip
    public boolean enableClientPerformanceAdjustment = true;
    @ConfigEntry.Category("client")
    public boolean clientTolerantVersionMismatchWithServer = false;
    @ConfigEntry.Category("client")
    @ConfigEntry.Gui.Tooltip
    public boolean compatibilityRenderMode = false;
    
    // client invisible configs
    
    @ConfigEntry.Gui.Excluded
    public boolean checkModInfoFromInternet = true;
    @ConfigEntry.Gui.Excluded
    public boolean enableUpdateNotification = true;
    @ConfigEntry.Gui.Excluded
    public boolean sharedBlockMeshBufferOptimization = true;
    @ConfigEntry.Gui.Excluded
    public boolean enableClippingMechanism = true;
    @ConfigEntry.Gui.Excluded
    public boolean visibilityPrediction = true;
    @ConfigEntry.Gui.Excluded
    public boolean useDepthClampForPortalRendering = true;
    @ConfigEntry.Gui.Excluded
    public boolean enableCrossPortalView = true;
    @ConfigEntry.Gui.Excluded
    public int portalRenderLimit = 200;
    @ConfigEntry.Gui.Excluded
    public boolean doCheckGlError = false;
    @ConfigEntry.Gui.Excluded
    public boolean shaderpackWarning = true;
    @ConfigEntry.Gui.Excluded
    public int portalWandCursorAlignment = 2; // zero for no align
    @ConfigEntry.Gui.Excluded
    public boolean saveMemoryInBufferPack = false;
    @ConfigEntry.Gui.Excluded
    public boolean initialScreenShown = false;
    
    // common visible configs
    
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
    public IPGlobal.NetherPortalMode netherPortalMode =
        IPFeatureControl.enableVanillaBehaviorChangingByDefault() ?
            IPGlobal.NetherPortalMode.adaptive : IPGlobal.NetherPortalMode.vanilla;
    
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
    public IPGlobal.EndPortalMode endPortalMode =
        IPFeatureControl.enableVanillaBehaviorChangingByDefault() ?
            IPGlobal.EndPortalMode.normal : IPGlobal.EndPortalMode.vanilla;
    
    public boolean enableMirrorCreation =
        IPFeatureControl.enableVanillaBehaviorChangingByDefault();
    
    public boolean enableWarning = true;
    public boolean lightVanillaNetherPortalWhenCrouching = true;
    @ConfigEntry.Gui.Tooltip
    public boolean enableServerPerformanceAdjustment = true;
    public boolean enableDatapackPortalGen = true;
    @ConfigEntry.BoundedDiscrete(min = 1, max = 32)
    @ConfigEntry.Gui.Tooltip
    public int indirectLoadingRadiusCap = 8;
    @ConfigEntry.BoundedDiscrete(min = 3, max = 64)
    public int regularPortalLengthLimit = 64;
    @ConfigEntry.BoundedDiscrete(min = 4, max = 128)
    public int scaleLimit = 30;
    @ConfigEntry.Gui.Tooltip
    public boolean easeCreativePermission = true;
    @ConfigEntry.Gui.Tooltip
    public boolean easeCommandStickPermission = false;
    public boolean portalsChangeGravityByDefault = false;
    public boolean portalWandUsableOnSurvivalMode = false;
    
    // common invisible configs
    
    @ConfigEntry.Gui.Excluded
    public int portalSearchingRange = 128;
    @ConfigEntry.Gui.Excluded
    public boolean serverSideNormalChunkLoading = true;
    @ConfigEntry.Gui.Excluded
    public boolean teleportationDebug = false;
    @ConfigEntry.Gui.Excluded
    public boolean looseMovementCheck = false;
    @ConfigEntry.Gui.Excluded
    public boolean chunkPacketDebug = false;
    @ConfigEntry.Gui.Excluded
    public boolean enableImmPtlChunkLoading = true;
    @ConfigEntry.Gui.Excluded
    public boolean serverTolerantVersionMismatchWithClient = false;
    @ConfigEntry.Gui.Excluded
    public boolean serverRejectClientWithoutImmPtl = true;
    @ConfigEntry.Gui.Excluded
    public boolean serverTeleportLogging = false;
    @ConfigEntry.Gui.Excluded
    public boolean enableCrossPortalInteraction = true;
    @ConfigEntry.Gui.Excluded
    public HashSet<String> disabledWarnings = new HashSet<>();
    
    @ConfigEntry.Gui.Excluded
    @Nullable
    public JsonObject dimStackPreset = null;
    
    public static IPConfig getConfig() {
        return IPGlobal.configHolder.getConfig();
    }
    
    public void saveConfigFile() {
        IPGlobal.configHolder.setConfig(this);
        IPGlobal.configHolder.save();
    }
    
    public void onConfigChanged() {
        indirectLoadingRadiusCap = Mth.clamp(indirectLoadingRadiusCap, 1, 32);
        regularPortalLengthLimit = Mth.clamp(regularPortalLengthLimit, 3, 64);
        scaleLimit = Mth.clamp(scaleLimit, 8, 128);
        if (netherPortalMode == null) {
            netherPortalMode = IPGlobal.NetherPortalMode.adaptive;
        }
        if (endPortalMode == null) {
            endPortalMode = IPGlobal.EndPortalMode.normal;
        }
        if (crossPortalEntityClipMechanism == null) {
            crossPortalEntityClipMechanism =
                qouteall.imm_ptl.core.render.PerEntityClipBracket.Mechanism.SUBMIT_ORDER_UNIFORM;
        }

        IPGlobal.renderMode = compatibilityRenderMode ? IPGlobal.RenderMode.compatibility : IPGlobal.RenderMode.normal;
        // S19-E increment 3 — NAMED DEVIATION guard (removed at C2; see ExperimentalCompatGate).
        // When the fabric client detected Sodium/Iris flag-ON while the compat gate is off, portal
        // views were force-disabled for the session. onConfigChanged re-derives renderMode from the
        // config above and can re-fire at runtime (an in-game config save), which would resurrect
        // portal rendering against the un-C2-verified compat path. Re-apply the session force here
        // so the disable survives config reloads. Session-scoped only (never persisted) and
        // false-by-default, so this is a no-op on dedicated servers and in the flag-OFF baseline.
        if (qouteall.imm_ptl.core.compat.ExperimentalCompatGate.forcePortalRenderingOffThisSession) {
            IPGlobal.renderMode = IPGlobal.RenderMode.none;
        }
        IPGlobal.enableWarning = enableWarning;
        IPGlobal.enableMirrorCreation = enableMirrorCreation;
        IPGlobal.doCheckGlError = doCheckGlError;
        IPGlobal.maxPortalLayer = maxPortalLayer;
        // IS5-REC. The -P dev lever PINS the depth for A/B legs, so the config must not overwrite it
        // — a lever that stops governing halfway through a run is how three legs of this project got
        // voided. Clamped to a resource ceiling, not a taste one: see the field's VRAM note.
        if (!IPGlobal.IRIS_MAX_LAYER_PINNED_BY_LEVER) {
            IPGlobal.irisMaxPortalLayer =
                Math.max(1, Math.min(IPGlobal.IRIS_RECURSION_DEPTH_CEILING, irisRecursionDepth));
        }
        IPGlobal.irisRecursionLagGuard = irisRecursionLagGuard;
        // Clamp on APPLY rather than in the GUI: leaving the field unbounded is what makes Cloth
        // render a typed box instead of a slider, so the clamp has to live here. 0 = follow the
        // vanilla render distance (the shipped default, and what the reset button restores).
        IPGlobal.portalWindowRenderDistance = Math.max(0, Math.min(32, portalWindowRenderDistance));
        IPGlobal.warnIfDeepRecursion(maxPortalLayer, IPGlobal.irisMaxPortalLayer);
        IPGlobal.lagAttackProof = lagAttackProof;
        IPGlobal.portalRenderLimit = portalRenderLimit;
        IPGlobal.netherPortalFindingRadius = portalSearchingRange;
        IPGlobal.renderYourselfInPortal = renderYourselfInPortal;
        IPGlobal.activeLoading = serverSideNormalChunkLoading;
        IPGlobal.teleportationDebugEnabled = teleportationDebug;
        IPGlobal.correctCrossPortalEntityRendering = correctCrossPortalEntityRendering;
        IPGlobal.crossPortalEntityClipMechanism = crossPortalEntityClipMechanism;
        // S18 C4-round fold: log the active mechanism once per config load — the first A/B round's
        // Test-2 engagement was not provable from logs (nothing recorded which mechanism ran);
        // this line makes every future A/B session self-documenting. S20-removal rides with the
        // loser-code cleanup (post-C4).
        qouteall.q_misc_util.Helper.log(
            "crossPortalEntityClipMechanism = " + crossPortalEntityClipMechanism);
        IPGlobal.looseMovementCheck = looseMovementCheck;
        IPGlobal.pureMirror = pureMirror;
        IPGlobal.indirectLoadingRadiusCap = indirectLoadingRadiusCap;
        IPGlobal.netherPortalMode = netherPortalMode;
        IPGlobal.endPortalMode = endPortalMode;
        IPGlobal.reducedPortalRendering = reducedPortalRendering;
        IPGlobal.offsetOcclusionQuery = visibilityPrediction;
        IPGlobal.netherPortalOverlay = netherPortalOverlay;
        IPGlobal.scaleLimit = scaleLimit;
        IPGlobal.easeCreativePermission = easeCreativePermission;
        IPGlobal.easeCommandStickPermission = easeCommandStickPermission;
        IPGlobal.enableSharedBlockMeshBuffers = sharedBlockMeshBufferOptimization;
        IPGlobal.enableDatapackPortalGen = enableDatapackPortalGen;
        IPGlobal.enableCrossPortalView = enableCrossPortalView;
        IPGlobal.enableClippingMechanism = enableClippingMechanism;
        IPGlobal.lightVanillaNetherPortalWhenCrouching = lightVanillaNetherPortalWhenCrouching;
        IPGlobal.enableNetherPortalEffect = enableNetherPortalEffect;
        IPGlobal.enableClientPerformanceAdjustment = enableClientPerformanceAdjustment;
        IPGlobal.enableServerPerformanceAdjustment = enableServerPerformanceAdjustment;
        IPGlobal.enableCrossPortalSound = enableCrossPortalSound;
        IPGlobal.checkModInfoFromInternet = checkModInfoFromInternet;
        IPGlobal.enableUpdateNotification = enableUpdateNotification;
        IPGlobal.enableDepthClampForPortalRendering = useDepthClampForPortalRendering;
        BlockPortalShape.defaultLengthLimit = regularPortalLengthLimit;
        IPGlobal.maxNormalPortalRadius = Math.max(regularPortalLengthLimit / 2, 16);
        IPGlobal.chunkPacketDebug = chunkPacketDebug;
        IPGlobal.saveMemoryInBufferPack = saveMemoryInBufferPack;
        
        Helper.LOGGER.info("iPortal Config Applied");
    }
    
    public boolean shouldDisplayWarning(String warningKey) {
        return enableWarning && !disabledWarnings.contains(warningKey);
    }
    
}
