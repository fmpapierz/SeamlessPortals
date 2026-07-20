package qouteall.imm_ptl.core.compat;

import com.warwa.seamlessportals.EntityPortalsFlag;
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
 *   <li><b>Gate 2 — OUR {@link EntityPortalsFlag#isOn()} entity-portal master switch</b>, applied
 *       to EVERY class. This is the composition that IP's plugin alone cannot express: without it,
 *       IP's substring gate would weave the whole compat set flag-OFF (block-era), violating the
 *       one-driver-per-session weave contract. Off Fabric the flag is force-{@code false}
 *       (S13-B P4), so on NeoForge gate 2 skips EVERY compat class — benign, no invoker install
 *       exists there anyway (design §6.3).</li>
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
 * <h2>The Iris arm is DELIBERATELY UNPOPULATED at C2-4 (D9)</h2>
 * The {@code contains("Iris")} / {@code contains("IrisSodium")} arms are live code but match
 * ZERO registered classes: C2-4 ships the iris invoker ({@code IrisInterface.OnIrisPresent} —
 * pure facade, reflection + public Iris API, NO mixin) with shaders-OFF parity and honest
 * shaders-ON dummy routing, and registers NO iris compat mixin (design §5 D9). IP's iris mixin
 * set (FILES 1/2/3: MixinIrisRenderingPipeline / MixinIrisClearPass / MixinIrisFinalPassRenderer)
 * is javap-CONFIRMED alive on Iris 1.11.2+26.2 — a cheap revival, ledgered — but every live body
 * is Experimental-renderer-gated and that renderer is DEFER-DORMANT until the shaders-ON
 * re-expression (C2-5 user checkpoint); registering dead weaves at {@code defaultRequire = 1}
 * would only add boot surface.
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
     * ({@link EntityPortalsFlag#isOn()}). A {@code null} mixin class name defaults off.
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
        // Gate 2 (ours): the entity-portal master switch. Flag-OFF (or any non-Fabric loader, where
        // the flag is force-false) skips every compat class — today's block-era behavior is intact.
        return EntityPortalsFlag.isOn();
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
