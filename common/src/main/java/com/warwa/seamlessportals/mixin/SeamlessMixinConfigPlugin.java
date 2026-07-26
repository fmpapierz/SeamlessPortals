package com.warwa.seamlessportals.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for compile-time mixin gating.
 *
 * <p>Some of our mixins target vanilla methods that Sodium overwrites at
 * the same priority (e.g. {@code LevelRenderer.cullTerrain}). Mixin
 * raises an {@code InvalidInjectionException} on these conflicts before
 * {@code require = 0} can take effect, so we have to filter them out at
 * the config-plugin level — Mixin asks {@link #shouldApplyMixin(String, String)}
 * for each candidate mixin and skips the ones we return {@code false} for.
 *
 * <p>The runtime detection here is hand-rolled (not {@link com.warwa.seamlessportals.compat.SodiumCompat})
 * because the config plugin loads VERY early — before FabricLoader is
 * fully wired in some bootstrap orderings. We use a try/reflective
 * lookup with FabricLoader and ModList as fallbacks, identical in spirit
 * to {@code SodiumCompat#detectSodium}.
 *
 * <p>The set in {@link #SODIUM_INCOMPATIBLE_MIXINS} lists fully-qualified
 * class names of mixins to omit when Sodium is present. Add to it as new
 * conflicts are discovered.
 *
 * <p><b>S20 INCREMENT 4 — this class now holds the ENTIRE cross-loader contract for the mod's
 * mixin set.</b> The {@code entityPortals} flag is deleted; what used to gate the ported
 * Immersive-Portals weave was {@code EntityPortalsFlag.isOn()}, whose internal off-Fabric
 * force-false was doing the real work. Two rules replace it, both keyed on
 * {@link #isFabricLoaderPresent()}: the {@code qouteall.*} weave gate in
 * {@link #shouldApplyMixin} (minus the two D3 carve-outs, which weave on BOTH loaders by
 * design) and {@link #FABRIC_ONLY_IP_DRIVERS} for the mod-owned mixins that drive the IP
 * engine. Read both before changing either — port-note
 * {@code migration/port-notes/S20-block-era-deletion.md} §E.2/§G.1/§G.12 records why every
 * simplification that suggests itself here is wrong, and why no gate in this repo can catch it.
 */
public class SeamlessMixinConfigPlugin implements IMixinConfigPlugin {

    /**
     * Mixin class names (matching the {@code package + "." + classname}
     * Mixin uses in {@link #shouldApplyMixin}) that conflict with Sodium
     * and must be skipped when Sodium is present.
     *
     * <p>The package prefix here is set by {@code "package"} in the
     * mixin config file. For {@code seamlessportals-common.mixins.json}
     * that's {@code com.warwa.seamlessportals.mixin}, so the entries
     * here are listed WITHOUT that prefix.
     */
    private static final Set<String> SODIUM_INCOMPATIBLE_MIXINS = Set.of(
        "com.warwa.seamlessportals.mixin.client.LevelRendererCullTerrainMixin"
    );

    /**
     * <b>S20 INCREMENT 4 — THE LOADER GATE FOR MOD-OWNED IP DRIVERS.</b> These are
     * {@code com.warwa} mixins whose bodies unconditionally drive the ported IP engine. They do
     * NOT start with {@code "qouteall."}, so the weave gate in {@link #shouldApplyMixin} never saw
     * them: they fall through to {@code return true} at the end of that method and ARE woven on
     * plain NeoForge (which loads {@code seamlessportals-common.mixins.json} via
     * {@code neoforge.mods.toml}).
     *
     * <p>Until S20 that was harmless because every one of their bodies sat behind
     * {@code entityPortals}, which {@code EntityPortalsFlag} force-falsed off Fabric. The flag is
     * gone, so the loader half has to be stated here or the first NeoForge client frame reaches
     * IP's {@code IPGlobal} static initialiser — a hard {@code NoClassDefFoundError} on Fabric's
     * {@code EventFactory} → {@code ExceptionInInitializerError} on the render thread → a client
     * that no longer boots. NOTHING catches this: {@code compileJava} is green either way and the
     * 8-leg gametest suite is Fabric-only (port-note S20-block-era-deletion.md §G.12).
     *
     * <p>Skipping them on NeoForge costs that platform nothing — there is no IP engine there to
     * drive (§G.1: NeoForge has no portal behaviour until C7), so every one of these bodies would
     * be pure crash surface. On Fabric the set is inert (the predicate is true, nothing is
     * skipped) and the woven result is byte-identical to today.
     *
     * <p>NOTE {@code GameRendererMixin}: its IP frame-end chain was collapsed to unconditional at
     * increment 3, before this mechanism existed, which left exactly this hazard live on NeoForge
     * (four IP calls per frame — {@code MyGameRenderer.endFramePooled},
     * {@code SecondaryWorldRenderCore.closeFrameTransientUbos}, {@code DrawCallTrace.onFrameEnd},
     * {@code TeleportFlashProbe.onFrameEnd}). It is listed here for that reason, not because
     * increment 4 changed it.
     *
     * <p>NOT exhaustive of NeoForge exposure, and deliberately so: several ungated
     * {@code com.warwa} client mixins have READ qouteall state on NeoForge since long before S20
     * ({@code SkyRendererTargetMixin}, {@code LevelRendererEntityVisibilityMixin},
     * {@code LevelRendererBlockOutlineMixin}). That is pre-existing and unchanged by S20; it
     * belongs to the C7 NeoForge round, and widening this set to cover it would be an untested
     * behaviour change on a platform no gate here exercises.
     */
    private static final Set<String> FABRIC_ONLY_IP_DRIVERS = Set.of(
        "com.warwa.seamlessportals.mixin.client.MinecraftFramePumpMixin",
        "com.warwa.seamlessportals.mixin.client.GameRendererMixin",
        "com.warwa.seamlessportals.mixin.client.LevelExtractorWindowHardeningMixin"
    );

    /**
     * S19-D D3 CARVE-OUT (see the shouldApplyMixin comment): the alt-dim worldgen ACCESSOR
     * mixins that must weave on BOTH LOADERS so an alternate-dimension world created on Fabric
     * reopens on NeoForge (level.dat → unconditional codec seam → these accessors at generation
     * time). Pure additive accessors/invokers — no injections, no behavior.
     *
     * <p><b>S20 INCREMENT 4:</b> the carve-out's original job was both-FLAG-STATES parity, and
     * the flag is gone — but the set is NOT redundant, it has simply changed axis. On NeoForge the
     * collapsed weave gate is FALSE, so this set is now the only reason these three weave there,
     * and they weave there today. See the gate body in {@link #shouldApplyMixin}.
     */
    private static final Set<String> D3_UNCONDITIONAL_WORLDGEN_ACCESSORS = Set.of(
        "qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IEChunkAccess_AlternateDim",
        "qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IEChunkGenerator_AlternateDim",
        "qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IENoiseRouterData"
    );

    /**
     * S19 COMMONS-TAIL D3 CARVE-OUT (port-note S19 §1.1 lineage): the legacy-item DATAFIX mixin must
     * weave on BOTH LOADERS. {@code ItemStackComponentizationFix} only fires when loading a
     * PRE-1.20.5 (pre-componentization) save; IP's addition moves a legacy
     * {@code immersive_portals:command_stick} / {@code portal_wand} stack's {@code tag} data into the
     * {@code iportal:command_stick_data} / {@code iportal:portal_wand_data} components. Those items —
     * and their DataComponentTypes — are registered UNCONDITIONALLY on Fabric (D3 save-parity,
     * port-note §1.1), so any Fabric world can carry them.
     *
     * <p><b>S20 INCREMENT 4 — the reason to keep this INVERTED but did not weaken.</b> It was
     * written for both-flag-states parity; with the flag gone, what it now buys is NeoForge. The
     * collapsed weave gate is false there, so this set is the ONLY reason the datafix weaves on
     * NeoForge — and without it a NeoForge open of a legacy pre-1.20.5 world sweeps the stored
     * command/mode into {@code minecraft:custom_data}: permanent item-data loss, realised later
     * when the world moves to Fabric. Weaving it there was audited benign at S19 (the handler
     * references only DFU/guava/log4j and is a pure additive {@code @Inject(RETURN)} guarded by
     * {@code is("immersive_portals:...")} — byte-neutral for every other item, zero engine
     * dependency). Exactly parallel to {@link #D3_UNCONDITIONAL_WORLDGEN_ACCESSORS} (an
     * unconditional serialize seam demands an unconditional load path).
     */
    private static final Set<String> D3_UNCONDITIONAL_ITEM_DATAFIX = Set.of(
        "qouteall.imm_ptl.peripheral.mixin.common.dfu.MixinItemStackComponentizationFix"
    );

    private static volatile Boolean sodiumLoaded = null;

    private static boolean isSodiumPresent() {
        Boolean cached = sodiumLoaded;
        if (cached != null) return cached;
        boolean detected = detect();
        sodiumLoaded = detected;
        return detected;
    }

    private static boolean detect() {
        // FabricLoader path
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loaderClass.getMethod("getInstance").invoke(null);
            Object result = loaderClass
                .getMethod("isModLoaded", String.class)
                .invoke(instance, "sodium");
            if (result instanceof Boolean && (Boolean) result) return true;
        } catch (Throwable ignored) {
            // not Fabric or class missing
        }
        // NeoForge path
        try {
            Class<?> modListClass = Class.forName("net.neoforged.fml.ModList");
            Object instance = modListClass.getMethod("get").invoke(null);
            Object result = modListClass
                .getMethod("isLoaded", String.class)
                .invoke(instance, "sodium");
            if (result instanceof Boolean && (Boolean) result) return true;
            result = modListClass
                .getMethod("isLoaded", String.class)
                .invoke(instance, "embeddium");
            if (result instanceof Boolean && (Boolean) result) return true;
        } catch (Throwable ignored) {
            // not NeoForge or no Sodium fork present
        }
        return false;
    }

    @Override
    public void onLoad(String mixinPackage) {
        // No-op — we read from the static SODIUM_INCOMPATIBLE_MIXINS set.
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // THE LOADER GATE (S20 increment 4; was the D3 exclusivity gate,
        // migration/EXCLUSIVITY_LEDGER.md §4 — now ARCHIVED with the flag). The ported
        // Immersive-Portals mixin set lives in the qouteall.* packages and is woven on FABRIC
        // ONLY. It used to read `isFabricLoaderPresent() && EntityPortalsFlag.isOn()`; increment 1
        // hoisted the loader half out precisely so the flag's death would be a one-term deletion
        // here rather than a re-derivation.
        if (mixinClassName != null && mixinClassName.startsWith("qouteall.")) {
            // DO NOT collapse this to `true`. `isOn()` carried an off-Fabric force-false, so the
            // flag term was never "just the flag" — it was the ONLY thing keeping the whole IP
            // mixin set unwoven on plain NeoForge. Removing the loader term weaves
            // MixinPlayerChunkSender there, which @Overwrites vanilla chunk sending and reroutes
            // it to ImmPtlChunkTracking — a driver whose init() is only ever reached from
            // IPModMain.init, never called on NeoForge. The result is a world that sends ZERO
            // chunks, with no exception and no log line. A green NeoForge boot does NOT detect it
            // (weave and boot both succeed; every failure is first-use), so this guard cannot be
            // validated away by testing. Port-note S20-block-era-deletion.md §E.2 + §G.
            //
            // KEEP BOTH CARVE-OUT TERMS (the alt-dim worldgen accessors, S19-D §6/§8; the
            // legacy-item datafix, S19 commons-tail). Post-flag they look redundant and are not:
            // the tempting reading ("on Fabric the gate is now always true, so a carve-out
            // short-circuits a branch never taken") holds on Fabric and is exactly backwards on
            // NeoForge, where the gate is FALSE and these two sets are the ONLY reason those four
            // classes weave — and they weave there TODAY. Dropping them would sweep legacy
            // command_stick/portal_wand item data into minecraft:custom_data on a NeoForge open of
            // a pre-1.20.5 world (permanent loss on a later move to Fabric) and cost an alt-dim
            // world its generation accessors. Invisible to compile, suite and a green boot alike
            // (port-note §G.12). Each set's javadoc carries the full argument.
            if (!D3_UNCONDITIONAL_WORLDGEN_ACCESSORS.contains(mixinClassName)
                && !D3_UNCONDITIONAL_ITEM_DATAFIX.contains(mixinClassName)
                && !isFabricLoaderPresent()) {
                return false;
            }
        }
        // S20 INCREMENT 4: the same loader rule for the mod-owned mixins that DRIVE the IP engine.
        // They are not "qouteall." prefixed, so the gate above never sees them — see the set's
        // javadoc for why an unguarded weave here is a NeoForge boot crash rather than a
        // regression.
        else if (mixinClassName != null
            && FABRIC_ONLY_IP_DRIVERS.contains(mixinClassName)
            && !isFabricLoaderPresent()) {
            System.out.println("[SEAMLESS EXCLUSIVITY] Skipping IP-driver mixin " + mixinClassName
                + " (FabricLoader absent — the ported IP engine is Fabric-only until C7)");
            return false;
        }
        if (isSodiumPresent() && SODIUM_INCOMPATIBLE_MIXINS.contains(mixinClassName)) {
            System.out.println("[SEAMLESS COMPAT] Skipping mixin " + mixinClassName
                + " (incompatible with Sodium)");
            return false;
        }
        return true;
    }

    /**
     * Whether FabricLoader is on the runtime classpath — i.e. we are running under Fabric (or a
     * Fabric-API-bridging environment like Sinytra Connector, where the {@code net.fabricmc.*} types
     * the IP set needs ARE actually present). {@code false} on plain NeoForge, where those types
     * exist only as compileOnly shells (see {@code common/build.gradle}'s {@code fabricStubs} set).
     *
     * <p><b>S20 INCREMENT 1/4 — this is now the load-bearing cross-loader predicate.</b> Body
     * copied verbatim from the deleted {@code EntityPortalsFlag}'s private method of the same name
     * (it was {@code private static} there, so it could not be called across). Copied rather than
     * re-invented because this exact reflective shape is already PROVEN to work at mixin-bootstrap
     * time, which is when this plugin runs; anything cleverer here risks the whole Fabric weave.
     *
     * <p>Ledgered non-blockers, both behaviour-preserving versus today (S20 audit §E.2):
     * (1) this is a loader PROXY, not a capability test — under Sinytra Connector the
     * {@code Class.forName} succeeds and the IP set weaves, which is correct there and is exactly
     * what happens today; (2) after the flag dies nothing couples the WEAVE to
     * {@code IPModMain.init} having actually run — today the flag couples them incidentally. A
     * future loader that satisfies this predicate WITHOUT running the Fabric {@code main}
     * entrypoint would reproduce the landmine, so if one ever appears, tighten this to a
     * capability test (e.g. {@code net.fabricmc.fabric.api.event.EventFactory}, whose absence is
     * the actual cause of the {@code IPGlobal.<clinit>} failure).
     */
    private static boolean isFabricLoaderPresent() {
        try {
            Class.forName("net.fabricmc.loader.api.FabricLoader");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // No-op
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
        // No-op
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
        // No-op
    }
}
