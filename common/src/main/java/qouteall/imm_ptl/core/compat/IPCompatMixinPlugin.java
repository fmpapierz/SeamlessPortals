package qouteall.imm_ptl.core.compat;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * The COMPOSED mixin-config plugin for {@code seamlessportals-ip-compat.mixins.json}
 * (C2 governing design {@code migration/C2_DESIGN.md} §6.1, stage C2-0 deliverable 1).
 *
 * <p>It weaves a compat mixin only when BOTH gates pass:
 *
 * <ol>
 *   <li><b>Gate 1 — IP's ORDER-SENSITIVE substring presence test</b> (ported 1:1 from
 *       {@code qouteall.imm_ptl.core.compat.IPCompatMixinPlugin} in the upstream IP source; the
 *       Flywheel / CardinalComponents arms are dropped — out of the C2 mandate). The order is
 *       load-bearing: {@code IrisSodium} must be tested BEFORE {@code Iris} and {@code Sodium},
 *       because an {@code IrisSodium}-named class contains all three substrings and must require
 *       BOTH mods, not just the first arm that matches. Any class that matches NONE of the arms
 *       defaults to {@code false} (default-off) — a compat mixin that forgets to name its target
 *       mod is silently skipped, never accidentally woven.</li>
 *   <li><b>Gate 2 — OURS: FabricLoader must be present</b>, applied to EVERY class. This is the
 *       composition that IP's plugin alone cannot express. Until S20 it read
 *       {@code isFabricLoaderPresent() && EntityPortalsFlag.isOn()}; the flag is deleted and the
 *       loader half is what always mattered — on NeoForge gate 2 skips EVERY compat class, which
 *       is required, not merely benign: no invoker install exists there (design §6.3) and the
 *       ported engine is Fabric-only until C7.</li>
 * </ol>
 *
 * <h2>PRESERVED FOOTGUN — read before adding any class to the compat config</h2>
 * Gate 1 keys purely on the mixin's fully-qualified class name via {@link String#contains}. So
 * <b>every class registered in this config MUST keep {@code Sodium} and/or {@code Iris} (matching
 * capitalisation) in its SIMPLE name, or gate 1 falls through to {@code return false} and the
 * class is SILENTLY DROPPED</b> — no boot error, no log line, it simply never applies. This is
 * IP's own design and is why the accessor is {@code IESodiumWorldRenderer} and the probes are
 * {@code MixinSodiumProbe_*} / {@code MixinSodiumChunkRenderList_Probe}: the substring is the
 * mod-presence key, not decoration. (The {@code sodium} package segment is lower-case and would
 * NOT match {@code contains("Sodium")}; only the simple name is load-bearing.)
 *
 * <h2>Why a NEW plugin, not {@link com.warwa.seamlessportals.mixin.SeamlessMixinConfigPlugin}</h2>
 * Another workflow is editing {@code SeamlessMixinConfigPlugin}; and its {@code qouteall.*} flag
 * gate is all-or-nothing — it has no per-mod substring logic, so a sodium-absent plain run would
 * hard-fail every compat mixin at {@code defaultRequire = 1}. This plugin composes the two gates
 * cleanly and leaves {@code SeamlessMixinConfigPlugin} untouched.
 *
 * <h2>require semantics</h2>
 * The config sets {@code injectors.defaultRequire = 1}: every BEHAVIORAL compat mixin (shipped in
 * later C2 stages) is a loud boot crash on a 0.9.1 / 1.11.2 bind failure — deliberate honesty, so
 * a silent no-op with the invoker reporting "present" can never happen. The C2-0 PROBE mixins are
 * the exception: each probe {@code @Inject} is {@code require = 0} and lever-gated behind
 * {@code -Dseamlessportals.compatProbe=true} (they are diagnostics, must never gate the boot).
 *
 * <h2>The Iris arm — populated at IS3 (D9 amendment)</h2>
 * At C2-4 this arm matched ZERO registered classes (the invoker
 * {@code IrisInterface.OnIrisPresent} is a pure facade — reflection + public Iris API, NO mixin).
 * IS3 (port-note {@code IS-iris-shaders-on.md} §4.2/§4.6) registers the FIRST live iris-targeting
 * mixin: {@code iris.MixinIrisSodiumTransformPatcher_ClipInject} — the shaders-ON terrain-clip GLSL
 * injector. It carries {@code "IrisSodium"} in its simple name so gate-1's ORDER-SENSITIVE test
 * (IrisSodium BEFORE Iris BEFORE Sodium) routes it to {@code isSodiumPresent() && isIrisPresent()}
 * — correct, since it patches Patch.SODIUM terrain GLSL and is meaningless without BOTH mods. It
 * is NOT one of IP's three iris mixins (MixinIrisRenderingPipeline / MixinIrisClearPass /
 * MixinIrisFinalPassRenderer — those target the rendering pipeline and stay dead); it is a NEW
 * mixin with no IP precedent. {@code @Pseudo} + {@code require = 1}: a drift in iris's
 * {@code TransformPatcher.transformInternal} descriptor is a LOUD boot crash (D7 honesty), the
 * gate ensures iris-present, and gate-2 forces it off on NeoForge.
 *
 * <h2>Loader safety</h2>
 * Config plugins load VERY early (before the mod initializers). Mod detection here is pure
 * reflection over {@code FabricLoader} / NeoForge {@code ModList} — the same dual-path shape
 * {@code SeamlessMixinConfigPlugin.detect()} uses — so it touches nothing on the C7 landmine list
 * (no fabric EventFactory, no Minecraft class). Results are cached (presence is fixed for the JVM
 * session). {@code embeddium} is deliberately NOT treated as {@code sodium} here: it is a NeoForge
 * Sodium fork whose internals are NOT Sodium 0.9.1, so it must route to warn+force-none and never
 * enter the 0.9.1-specific compat weave (design §6.3); on NeoForge gate 2 already skips everything,
 * making the point moot today but keeping the plugin forward-honest.
 */
public class IPCompatMixinPlugin implements IMixinConfigPlugin {

    private static volatile Boolean sodiumPresent = null;
    private static volatile Boolean irisPresent = null;

    @Override
    public void onLoad(String mixinPackage) {
        // No-op.
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * COMPOSED gate. Gate 1 (IP order-sensitive substring presence) AND gate 2
     * ({@link #isFabricLoaderPresent()}). A {@code null} mixin class name defaults off.
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName == null) {
            return false;
        }
        // Gate 1: IP's order-sensitive substring presence test (1:1 shape, Flywheel/Cardinal arms
        // dropped — out of C2 scope). IrisSodium BEFORE Iris BEFORE Sodium is load-bearing.
        boolean gate1;
        if (mixinClassName.contains("IrisSodium")) {
            gate1 = isSodiumPresent() && isIrisPresent();
        }
        else if (mixinClassName.contains("Iris")) {
            gate1 = isIrisPresent();
        }
        else if (mixinClassName.contains("Sodium")) {
            gate1 = isSodiumPresent();
        }
        else {
            // Default-off: a compat class that names no target mod (the footgun) is never woven.
            gate1 = false;
        }
        if (!gate1) {
            return false;
        }
        // Gate 2 (ours): FABRIC ONLY. This is the SECOND weave gate in the tree — the migration
        // notes named only SeamlessMixinConfigPlugin — and it is the sole reason the 15-mixin
        // Sodium/Iris compat set stays unwoven on plain NeoForge (neoforge.mods.toml:47-53 states
        // exactly that in prose, and ip-compat is a SEVENTH qouteall config the "six IP blocks"
        // framing misses).
        //
        // S20 INCREMENT 4 — `&& EntityPortalsFlag.isOn()` deleted here, and NEVER replaced by
        // `return true`. The flag's own off-Fabric force-false was what this gate actually relied
        // on; increment 1 hoisted the loader half out so this collapse would be a one-term
        // deletion. See port-note S20-block-era-deletion.md §E.2/§G.12 and
        // SeamlessMixinConfigPlugin.isFabricLoaderPresent's javadoc for the ledgered non-blockers.
        return isFabricLoaderPresent();
    }

    /**
     * Whether FabricLoader is on the runtime classpath. Second copy of the predicate (the original
     * was {@code private static} on the deleted {@code EntityPortalsFlag}; the sibling copy lives
     * on {@code SeamlessMixinConfigPlugin}, which carries the full rationale). Duplicated rather
     * than shared because a mixin config plugin runs at bootstrap, before any shared utility class
     * of ours is safe to touch, and because the two plugins must not be able to drift apart by one
     * of them losing a dependency.
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
        // No-op.
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
        // No-op.
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
        // No-op.
    }

    private static boolean isSodiumPresent() {
        Boolean cached = sodiumPresent;
        if (cached != null) {
            return cached;
        }
        boolean detected = detectMod("sodium");
        sodiumPresent = detected;
        return detected;
    }

    private static boolean isIrisPresent() {
        Boolean cached = irisPresent;
        if (cached != null) {
            return cached;
        }
        boolean detected = detectMod("iris");
        irisPresent = detected;
        return detected;
    }

    /**
     * Reflective dual-path mod detection — the same shape
     * {@code SeamlessMixinConfigPlugin.detect()} uses (a config plugin runs before the loader is
     * fully wired, so a hard {@code FabricLoader} dependency is unwise). Tries the FabricLoader
     * path first, then the NeoForge {@code ModList} path. Every failure resolves {@code false}.
     * NOTE: embeddium is intentionally NOT matched — see the class javadoc (design §6.3).
     */
    private static boolean detectMod(String modId) {
        // FabricLoader path.
        try {
            Class<?> loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = loaderClass.getMethod("getInstance").invoke(null);
            Object result = loaderClass
                .getMethod("isModLoaded", String.class)
                .invoke(instance, modId);
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        } catch (Throwable ignored) {
            // not Fabric, or loader not ready — fall through to the NeoForge path
        }
        // NeoForge path.
        try {
            Class<?> modListClass = Class.forName("net.neoforged.fml.ModList");
            Object instance = modListClass.getMethod("get").invoke(null);
            Object result = modListClass
                .getMethod("isLoaded", String.class)
                .invoke(instance, modId);
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        } catch (Throwable ignored) {
            // not NeoForge or the id is absent
        }
        return false;
    }
}
