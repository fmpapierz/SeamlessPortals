package com.warwa.seamlessportals.mixin;

import com.warwa.seamlessportals.EntityPortalsFlag;
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
     * WEAVE-LEVEL EXCLUSIVITY (D3 extension, S13 first-light attempt 3): block-era mixins that
     * COLLIDE at the bytecode level with a registered IP mixin on the same injection site
     * (two {@code @Redirect}s on one instruction = Mixin skips the second and its
     * {@code require} check kills the boot). Runtime {@code !entityPortals} gates cannot help
     * here — the collision happens at transform time. These are skipped when the flag is ON;
     * their function is superseded by the IP counterpart (each entry documents by what).
     */
    private static final Set<String> ENTITY_PORTALS_SUPERSEDED_MIXINS = Set.of(
        // superseded by qouteall...client.sync.MixinClientPacketListener redirectGetEntityById
        // (IP resolves entities across per-dim client worlds — strict superset of the
        // local-player fallback; see the block-era mixin's own IP-parity javadoc note)
        "com.warwa.seamlessportals.mixin.client.ClientPacketListenerLocalPlayerFallbackMixin"
    );

    /**
     * S19-D D3 CARVE-OUT (see the shouldApplyMixin comment): the alt-dim worldgen ACCESSOR
     * mixins that must weave in BOTH flag states so a flag-ON-created alternate-dimension
     * world reopens flag-OFF (level.dat → unconditional codec seam → these accessors at
     * generation time). Pure additive accessors/invokers — no injections, no behavior.
     */
    private static final Set<String> D3_UNCONDITIONAL_WORLDGEN_ACCESSORS = Set.of(
        "qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IEChunkAccess_AlternateDim",
        "qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IEChunkGenerator_AlternateDim",
        "qouteall.imm_ptl.peripheral.mixin.common.alternate_dimension.IENoiseRouterData"
    );

    /**
     * S19 COMMONS-TAIL D3 CARVE-OUT (port-note S19 §1.1 lineage): the legacy-item DATAFIX mixin must
     * weave in BOTH flag states. {@code ItemStackComponentizationFix} only fires when loading a
     * PRE-1.20.5 (pre-componentization) save; IP's addition moves a legacy
     * {@code immersive_portals:command_stick} / {@code portal_wand} stack's {@code tag} data into the
     * {@code iportal:command_stick_data} / {@code iportal:portal_wand_data} components. Those items —
     * and their DataComponentTypes — are registered UNCONDITIONALLY on Fabric (D3 save-parity,
     * port-note §1.1), so a flag-OFF world can carry them. The flag has defaulted ON since the S17
     * cutover, but EXPLICIT {@code entityPortals=false} remains a supported two-way switch until S20 —
     * gating this datafix flag-ON would mean a flag-OFF user opening a legacy pre-1.20.5 IP world gets
     * the stored command/mode silently swept into {@code minecraft:custom_data} (permanent item-data
     * loss if the flag is later flipped ON). NOTE the carve-out also weaves on NeoForge (where the flag
     * is force-false and the peripheral items are C7-deferred) — audited benign: the handler references
     * only DFU/guava/log4j, and converting the on-disk NBT there preserves the data for a later world
     * move to Fabric (S19 verify, recorded decision). The handler is a pure additive
     * {@code @Inject(RETURN)} guarded by {@code is("immersive_portals:...")} — byte-neutral for every
     * other item, zero engine dependency — so weaving it flag-OFF is harmless AND required for D3
     * item-data parity. Exactly parallel to {@link #D3_UNCONDITIONAL_WORLDGEN_ACCESSORS} (an
     * unconditional serialize seam demands an unconditional load path).
     */
    private static final Set<String> D3_UNCONDITIONAL_ITEM_DATAFIX = Set.of(
        "qouteall.imm_ptl.peripheral.mixin.common.dfu.MixinItemStackComponentizationFix"
    );

    /**
     * NF-PARITY W3/B1 (2026-08-25): LOADER-SHAPE VARIANT sets. Where NeoForge PATCHES a vanilla
     * call site so that one {@code @WrapOperation}/{@code @Inject} descriptor cannot match both
     * loaders (e.g. {@code LevelExtractor.extract}'s {@code extractVisibleBlockEntities} call —
     * 3-arg on vanilla/Fabric, 4-arg with a trailing {@code @Nullable Frustum} on NeoForge), the
     * mixin exists in TWO shape variants and exactly ONE is applied per loader. This keeps
     * {@code defaultRequire = 1} strictness within each loader — the selected variant hard-fails
     * on a zero-match instead of silently no-opping (the repo's scoped-suppression lesson).
     * Loader detection = {@link #isNeoForgeRuntime()}, a class-presence probe safe at
     * mixin-bootstrap time.
     */
    private static final Set<String> NEOFORGE_ONLY_MIXINS = Set.of(
        "qouteall.imm_ptl.core.mixin.client.render.MixinLevelExtractor_DestSubLevers_BEShapeNeoForge",
        "com.warwa.seamlessportals.mixin.client.HandleRespawnLoadScreenShapeNeoForge",
        // W21: the clip-bracket main-pass draw site — Fabric drives it via
        // BEFORE_TRANSLUCENT_TERRAIN; NeoForge has no event in that gap.
        "qouteall.imm_ptl.core.mixin.client.render.MixinLevelRenderer_ClipBracketMainPassNeoForge",
        // Cross-portal break fix: targets the NF-patched-in ServerPlayerGameMode.removeBlock
        // helper (absent on vanilla/Fabric) that escaped the parent mixin's level redirect.
        "qouteall.imm_ptl.core.mixin.common.interaction.MixinServerPlayerGameMode_RemoveBlockNeoForge"
    );

    private static final Set<String> NON_NEOFORGE_MIXINS = Set.of(
        "qouteall.imm_ptl.core.mixin.client.render.MixinLevelExtractor_DestSubLevers_BEShapeVanilla",
        "com.warwa.seamlessportals.mixin.client.HandleRespawnLoadScreenShapeVanilla"
    );

    private static volatile Boolean neoForgeRuntime = null;

    /**
     * Whether we are running under NeoForge's FML. {@code net.neoforged.fml.loading.FMLLoader}
     * is loaded long before mixin config plugins are instantiated (FMLLoader bootstraps the
     * mixin service itself), so a class-presence probe is safe and stable here. On Fabric the
     * class is absent → false.
     */
    private static boolean isNeoForgeRuntime() {
        Boolean cached = neoForgeRuntime;
        if (cached != null) return cached;
        boolean present;
        try {
            Class.forName("net.neoforged.fml.loading.FMLLoader", false,
                SeamlessMixinConfigPlugin.class.getClassLoader());
            present = true;
        } catch (Throwable ignored) {
            present = false;
        }
        neoForgeRuntime = present;
        return present;
    }

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
        // NeoForge path. NF-PARITY W8 fix (2026-08-25, recon-confirmed latent defect): the old
        // ModList.get() probe was a SILENT NO-OP here — ModList.INSTANCE is only assigned in
        // ModLoader.gatherAndInitializeMods (ModLoader.java:78-99), long AFTER mixin config
        // plugins run, so the reflective call NPE'd into the catch and Sodium detection never
        // fired on NeoForge. The correct early API is LoadingModList (populated at
        // FMLLoader.java:346, well before mixin plugin instantiation).
        try {
            Class<?> lmlClass = Class.forName("net.neoforged.fml.loading.LoadingModList");
            Object lml = lmlClass.getMethod("get").invoke(null);
            java.lang.reflect.Method byId = lmlClass.getMethod("getModFileById", String.class);
            if (byId.invoke(lml, "sodium") != null) return true;
            if (byId.invoke(lml, "embeddium") != null) return true;
        } catch (Throwable ignored) {
            // not NeoForge, or LoadingModList unavailable
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
        // NF-PARITY W3/B1: loader-shape variant selection — exactly one variant of a
        // shape-split mixin applies per loader (see the sets' javadoc). Checked FIRST so a
        // variant never leaks through the flag gates below on the wrong loader.
        if (NEOFORGE_ONLY_MIXINS.contains(mixinClassName) && !isNeoForgeRuntime()) {
            return false;
        }
        if (NON_NEOFORGE_MIXINS.contains(mixinClassName) && isNeoForgeRuntime()) {
            return false;
        }
        // D3 EXCLUSIVITY GATE (entity-portal migration, migration/EXCLUSIVITY_LEDGER.md §4):
        // the ported Immersive-Portals mixin set lives in the qouteall.* packages. It is woven
        // ONLY when the entity-portal engine is ON. Flag OFF (the explicit opt-out; the default
        // has been ON since the S17 cutover) → every IP
        // mixin is skipped here, so the block-era com.warwa mixins are the only portal driver set
        // applied. This is the load-time half of the one-driver-per-session contract; the runtime
        // half is the `!entityPortals` gates in the block-era mod driver code.
        if (mixinClassName != null && mixinClassName.startsWith("qouteall.")) {
            // S19-D D3 CARVE-OUT (port-note S19 §6/§8): the three alt-dim WORLDGEN ACCESSOR
            // mixins weave in BOTH flag states. Rationale = save-parity symmetry with the
            // UNCONDITIONAL chunk-generator/biome-source codec seam: a flag-ON-created world
            // containing an alternate dimension persists its generator in level.dat; a
            // flag-OFF reopen deserializes it through those codecs and GENERATES through
            // these accessors — gating them flag-OFF would crash the reopen (the exact D3
            // scenario the unconditional seams exist to protect). All three are pure
            // additive @Accessor/@Invoker on vanilla classes: zero behavior, zero injection,
            // byte-neutral for a world that never references the generators.
            //
            // S19 COMMONS-TAIL carve-out: D3_UNCONDITIONAL_ITEM_DATAFIX joins the same both-states
            // rule — the wand/command-stick legacy-item datafix is the load-time counterpart of the
            // UNCONDITIONALLY-registered item DataComponentTypes. The flag defaults ON since S17,
            // but explicit flag-OFF stays a supported two-way switch until S20, and a flag-OFF
            // legacy-world load must still convert the item data (see that set's javadoc).
            if (!D3_UNCONDITIONAL_WORLDGEN_ACCESSORS.contains(mixinClassName)
                && !D3_UNCONDITIONAL_ITEM_DATAFIX.contains(mixinClassName)
                && !EntityPortalsFlag.isOn()) {
                return false;
            }
        }
        // The mirror half: flag ON suppresses block-era mixins whose injection sites collide
        // with a registered IP mixin (weave-level exclusivity; see the set's javadoc).
        if (EntityPortalsFlag.isOn() && ENTITY_PORTALS_SUPERSEDED_MIXINS.contains(mixinClassName)) {
            System.out.println("[SEAMLESS EXCLUSIVITY] Skipping block-era mixin " + mixinClassName
                + " (superseded by the IP set while entityPortals is ON)");
            return false;
        }
        if (isSodiumPresent() && SODIUM_INCOMPATIBLE_MIXINS.contains(mixinClassName)) {
            System.out.println("[SEAMLESS COMPAT] Skipping mixin " + mixinClassName
                + " (incompatible with Sodium)");
            return false;
        }
        return true;
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
