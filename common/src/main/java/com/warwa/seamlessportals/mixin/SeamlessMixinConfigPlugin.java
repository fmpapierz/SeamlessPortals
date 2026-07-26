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
            //
            // S20 INCREMENT 1 — THE LOADER GATE IS NOW EXPLICIT HERE (do not remove it with the
            // flag). Today `EntityPortalsFlag.isOn()` force-falses off Fabric at TWO internal sites
            // (EntityPortalsFlag:98-100 in readFromDisk, :89 in seedIfUnset), and that force-false
            // is the ONLY thing keeping the whole IP mixin set unwoven on plain NeoForge. S20
            // deletes the flag, which collapses this term to always-true — so the loader half is
            // hoisted out to its own predicate BEFORE the flag dies. Adding it is a NO-OP today
            // (`isFabricLoaderPresent() && isOn()` == `isOn()`, since isOn() already implies it)
            // and becomes the load-bearing guard the moment `isOn()` is replaced by `true`.
            //
            // WHY IT MATTERS (S20 adversarial audit; port-note S20-block-era-deletion.md §E.2 +
            // §G): without it the collapse weaves MixinPlayerChunkSender on NeoForge, which
            // @Overwrites vanilla chunk sending and reroutes it to ImmPtlChunkTracking — a driver
            // whose init() is only ever reached from IPModMain.init, never called on NeoForge. The
            // result is a world that sends ZERO chunks with no exception and no log line. A green
            // NeoForge boot does NOT detect it (weave and boot both succeed; the failures are all
            // first-use), so this guard cannot be validated away by testing.
            if (!D3_UNCONDITIONAL_WORLDGEN_ACCESSORS.contains(mixinClassName)
                && !D3_UNCONDITIONAL_ITEM_DATAFIX.contains(mixinClassName)
                && !(isFabricLoaderPresent() && EntityPortalsFlag.isOn())) {
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

    /**
     * Whether FabricLoader is on the runtime classpath — i.e. we are running under Fabric (or a
     * Fabric-API-bridging environment like Sinytra Connector, where the {@code net.fabricmc.*} types
     * the IP set needs ARE actually present). {@code false} on plain NeoForge, where those types
     * exist only as compileOnly shells (see {@code common/build.gradle}'s {@code fabricStubs} set).
     *
     * <p><b>S20 INCREMENT 1.</b> Body copied verbatim from {@code EntityPortalsFlag}'s private
     * method of the same name (it is {@code private static} there — {@code EntityPortalsFlag:147} —
     * so it could not be called across, and the class itself dies at S20). Copied rather than
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
